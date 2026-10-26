package com.massivex.massa

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Strict bounded Version 1 import only. Not a general YAML object loader or an export API. */
object MassaYAML {
    class Document internal constructor(
        internal val salt: ByteArray, internal val nonce: ByteArray, internal val ciphertext: ByteArray,
        internal val publicKey: ByteArray, internal val address: String?
    ) {
        override fun toString(): String = "MassaYAML.Document(<redacted>)"
    }

    fun parse(data: ByteArray): Document {
        enforce(data.isNotEmpty() && data.size <= 16_384, Failure.YAML)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        } catch (_: Exception) { throw MassaException(Failure.YAML) }
        enforce('\u0000' !in text, Failure.YAML)
        val allowed = setOf("Version", "Nickname", "Address", "Salt", "Nonce", "CipheredData", "PublicKey")
        val arrays = setOf("Salt", "Nonce", "CipheredData", "PublicKey")
        val fields = linkedMapOf<String, String>()
        var current: String? = null
        for (raw in text.split(Regex("\\r\\n|[\\n\\r\\u0085\\u2028\\u2029]"))) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val key = current
            if (line.startsWith("- ") && key in arrays) {
                val byte = line.substring(2).trim()
                enforce(byte.matches(Regex("[0-9]+")) && byte.toIntOrNull() in 0..255, Failure.YAML)
                fields[key!!] = fields.getValue(key) + byte + ","
                continue
            }
            if (key != null && fields.getValue(key).startsWith('[') && !fields.getValue(key).endsWith(']')) {
                fields[key] = fields.getValue(key) + line
                continue
            }
            val colon = line.indexOf(':')
            enforce(colon >= 0, Failure.YAML)
            val next = line.substring(0, colon)
            enforce(next in allowed && next !in fields, Failure.YAML)
            fields[next] = line.substring(colon + 1).trim(); current = next
        }
        enforce("Version" in fields, Failure.YAML)
        enforce(fields["Version"] == "1", Failure.VERSION)
        fun scalar(key: String): String? {
            val raw = fields[key]?.takeIf { it.isNotEmpty() } ?: return null
            if (raw.startsWith('"')) {
                try {
                    JsonReader(StringReader(raw)).use { reader ->
                        reader.strictness = Strictness.STRICT
                        enforce(reader.peek() == JsonToken.STRING, Failure.YAML)
                        val value = reader.nextString()
                        enforce(reader.peek() == JsonToken.END_DOCUMENT, Failure.YAML)
                        // Foundation rejects unpaired UTF-16 surrogate escapes.
                        var index = 0
                        while (index < value.length) {
                            val char = value[index++]
                            if (char.isHighSurrogate()) { enforce(index < value.length && value[index].isLowSurrogate(), Failure.YAML); index++ }
                            else enforce(!char.isLowSurrogate(), Failure.YAML)
                        }
                        return value
                    }
                } catch (_: Exception) { throw MassaException(Failure.YAML) }
            }
            if (raw.startsWith('\'')) {
                enforce(raw.length >= 2 && raw.endsWith('\''), Failure.YAML)
                return raw.substring(1, raw.length - 1).replace("''", "'")
            }
            enforce(raw.none { it in "[]{}&*!|>#%" }, Failure.YAML)
            return raw
        }
        scalar("Nickname")
        fun bytes(key: String, count: Int): ByteArray {
            var raw = fields[key] ?: throw MassaException(Failure.YAML)
            if (raw.startsWith('[')) {
                enforce(raw.endsWith(']'), Failure.YAML); raw = raw.substring(1, raw.length - 1)
            } else { enforce(raw.endsWith(','), Failure.YAML); raw = raw.dropLast(1) }
            val values = raw.split(',').map { it.trim() }
            enforce(values.size == count, Failure.YAML)
            return values.map {
                enforce(it.matches(Regex("[0-9]+")), Failure.YAML)
                val value = it.toIntOrNull() ?: throw MassaException(Failure.YAML)
                enforce(value in 0..255, Failure.YAML); value.toByte()
            }.toByteArray()
        }
        val public = bytes("PublicKey", 33)
        enforce(public[0] == 0.toByte(), Failure.VERSION)
        val address = scalar("Address")
        if (address != null) try { MassaCodec.validateAddress(address, userOnly = true) } catch (_: MassaException) { throw MassaException(Failure.IDENTITY) }
        return Document(bytes("Salt", 16), bytes("Nonce", 12), bytes("CipheredData", 49), public, address)
    }

    fun decrypt(document: Document, password: String): OfflineAccount {
        val encoded = password.toByteArray(Charsets.UTF_8)
        var derived: ByteArray? = null
        var clear: ByteArray? = null
        try {
            enforce(encoded.isNotEmpty() && encoded.size <= 1024, Failure.AUTHENTICATION)
            // Explicit UTF-8 bytes match Swift even for non-ASCII passwords; no provider-specific PBE char conversion.
            val generator = PKCS5S2ParametersGenerator(SHA256Digest())
            generator.init(encoded, document.salt, 600_000)
            derived = (generator.generateDerivedParameters(256) as KeyParameter).key
            clear = try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, document.nonce))
                    doFinal(document.ciphertext) // 33 ciphertext bytes followed by the 16-byte tag; no AAD.
                }
            } catch (_: Exception) { throw MassaException(Failure.AUTHENTICATION) }
            enforce(clear.size == 33 && clear[0] == 0.toByte(), Failure.VERSION)
            val seed = clear.copyOfRange(1, clear.size)
            val account = try { OfflineAccount.fromSeed(seed) } finally { seed.fill(0) }
            try {
                val identity = account.publicAccount()
                enforce((byteArrayOf(0) + MassaCodec.decode(identity.publicKey, "P")).contentEquals(document.publicKey) &&
                    (document.address == null || identity.address == document.address), Failure.IDENTITY)
                return account
            } catch (error: Exception) { account.close(); throw error }
        } finally { encoded.fill(0); derived?.fill(0); clear?.fill(0) }
    }
}
