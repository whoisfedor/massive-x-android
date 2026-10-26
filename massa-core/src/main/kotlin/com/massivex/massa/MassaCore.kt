package com.massivex.massa

import org.bitcoinj.base.Base58
import org.bouncycastle.crypto.digests.Blake3Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer

enum class Failure { ENCODING, LENGTH, AMOUNT, OVERFLOW, ZERO, INSUFFICIENT, YAML, VERSION, AUTHENTICATION, IDENTITY, CLOSED }
class MassaException(val failure: Failure) : IllegalArgumentException(failure.name)
internal fun enforce(condition: Boolean, failure: Failure) { if (!condition) throw MassaException(failure) }

object MassaCodec {
    fun hash(bytes: ByteArray): ByteArray {
        val digest = Blake3Digest(256)
        digest.update(bytes, 0, bytes.size)
        return ByteArray(32).also { digest.doFinal(it, 0) }
    }

    fun encode(bytes: ByteArray, prefix: String): String {
        enforce(bytes.size == 32 || bytes.size == 64, Failure.LENGTH)
        return prefix + Base58.encodeChecked(0, bytes)
    }

    fun decode(text: String, prefix: String, length: Int = 32): ByteArray {
        enforce(length == 32 || length == 64, Failure.LENGTH)
        enforce(text.length <= 102 && text.startsWith(prefix) && text.all { it.code in 1..127 }, Failure.ENCODING)
        val body = text.substring(prefix.length)
        enforce(body.length <= 100, Failure.ENCODING)
        val checked = try { Base58.decodeChecked(body) } catch (_: IllegalArgumentException) {
            throw MassaException(Failure.ENCODING)
        }
        try {
            enforce(checked.size == length + 1 && checked[0] == 0.toByte(), Failure.ENCODING)
            val result = checked.copyOfRange(1, checked.size)
            // Reject noncanonical representations as well as bad checksums/versions.
            enforce(Base58.encodeChecked(0, result) == body, Failure.ENCODING)
            return result
        } finally { checked.fill(0) }
    }

    fun validateAddress(address: String, userOnly: Boolean = false) {
        val prefix = when {
            address.startsWith("AU") -> "AU"
            !userOnly && address.startsWith("AS") -> "AS"
            else -> throw MassaException(Failure.ENCODING)
        }
        decode(address, prefix)
    }
}

data class PublicAccount(val publicKey: String, val address: String) {
    companion object {
        fun fromRaw(bytes: ByteArray): PublicAccount {
            enforce(bytes.size == 32, Failure.LENGTH)
            return PublicAccount(MassaCodec.encode(bytes, "P"), MassaCodec.encode(MassaCodec.hash(byteArrayOf(0) + bytes), "AU"))
        }
    }

    fun verify(message: ByteArray, signature: String): Boolean {
        val public = MassaCodec.decode(publicKey, "P")
        val rawSignature = MassaCodec.decode(signature, "", 64)
        val digest = MassaCodec.hash(message)
        val verifier = Ed25519Signer() // Pure Ed25519, not Ed25519ph.
        verifier.init(false, Ed25519PublicKeyParameters(public, 0))
        verifier.update(digest, 0, digest.size)
        return verifier.verifySignature(rawSignature)
    }
}

/** Offline key material only. No persistence, network, UI or hardware-protection claim. */
class OfflineAccount private constructor(private var seed: ByteArray?) : AutoCloseable {
    companion object {
        fun fromSeed(seed: ByteArray): OfflineAccount {
            enforce(seed.size == 32, Failure.LENGTH)
            return OfflineAccount(seed.copyOf())
        }
        fun import(privateKey: String): OfflineAccount {
            val bytes = MassaCodec.decode(privateKey, "S")
            return try { fromSeed(bytes) } finally { bytes.fill(0) }
        }
    }

    private fun live(): ByteArray = seed ?: throw MassaException(Failure.CLOSED)
    fun publicAccount(): PublicAccount = PublicAccount.fromRaw(Ed25519PrivateKeyParameters(live(), 0).generatePublicKey().encoded)
    fun privateKeyEncoding(): String = MassaCodec.encode(live(), "S")
    fun signOperation(content: ByteArray, expected: PublicAccount): String {
        enforce(publicAccount() == expected, Failure.IDENTITY)
        val digest = MassaCodec.hash(MassaOperation.canonical(content, expected.publicKey))
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(live(), 0))
        signer.update(digest, 0, digest.size)
        return MassaCodec.encode(signer.generateSignature(), "")
    }
    override fun close() { seed?.fill(0); seed = null }
    override fun toString(): String = "OfflineAccount(<redacted>)"
}

data class MASAmount(val nano: ULong) : Comparable<MASAmount> {
    companion object {
        private val canonical = Regex("^(0|[1-9][0-9]*)(\\.[0-9]{1,9})?$")
        fun parse(text: String): MASAmount {
            enforce(text.length <= 30 && canonical.matches(text), Failure.AMOUNT)
            val parts = text.split('.')
            val whole = parts[0].toULongOrNull() ?: throw MassaException(Failure.OVERFLOW)
            enforce(whole <= ULong.MAX_VALUE / 1_000_000_000uL, Failure.OVERFLOW)
            val scaled = whole * 1_000_000_000uL
            val fraction = if (parts.size == 2) parts[1].padEnd(9, '0').toULong() else 0uL
            enforce(fraction <= ULong.MAX_VALUE - scaled, Failure.OVERFLOW)
            return MASAmount(scaled + fraction)
        }
        fun validateTransfer(recipient: String, amount: String, fee: String, available: String): MASAmount {
            MassaCodec.validateAddress(recipient)
            val value = parse(amount).nano; val cost = parse(fee).nano; val balance = parse(available).nano
            enforce(value > 0uL, Failure.ZERO)
            enforce(cost <= ULong.MAX_VALUE - value, Failure.INSUFFICIENT)
            enforce(value + cost <= balance, Failure.INSUFFICIENT)
            return MASAmount(value + cost)
        }
    }
    val text: String get() {
        val whole = nano / 1_000_000_000uL; val fraction = nano % 1_000_000_000uL
        return if (fraction == 0uL) whole.toString() else "$whole.${fraction.toString().padStart(9, '0').trimEnd('0')}"
    }
    override fun compareTo(other: MASAmount): Int = nano.compareTo(other.nano)
}

/** Protocol serialization only; never a Send coordinator or a network submission interface. */
object MassaOperation {
    const val MAINNET_CHAIN_ID: Long = 77_658_377
    fun varint(value: ULong): ByteArray {
        var remaining = value
        val result = ArrayList<Byte>(10)
        do {
            var byte = (remaining and 127uL).toInt(); remaining = remaining shr 7
            if (remaining != 0uL) byte = byte or 128
            result.add(byte.toByte())
        } while (remaining != 0uL)
        return result.toByteArray()
    }
    fun serialized(fee: MASAmount, amount: MASAmount, expiry: ULong, recipient: String): ByteArray {
        MassaCodec.validateAddress(recipient)
        val type = if (recipient.startsWith("AU")) 0 else 1
        return varint(fee.nano) + varint(expiry) + byteArrayOf(0, type.toByte(), 0) +
            MassaCodec.decode(recipient, if (type == 0) "AU" else "AS") + varint(amount.nano)
    }
    fun canonical(content: ByteArray, publicKey: String): ByteArray =
        ByteBuffer.allocate(8).putLong(MAINNET_CHAIN_ID).array() + byteArrayOf(0) + MassaCodec.decode(publicKey, "P") + content
    fun operationID(content: ByteArray, publicKey: String): String = MassaCodec.encode(MassaCodec.hash(canonical(content, publicKey)), "O")
}
