package com.massivex.android.transfer

import com.massivex.android.wallet.Wallet
import com.massivex.massa.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

internal const val CHAIN = 77_658_377L
internal const val TRUST_POLICY = "massa-labs-mainnet-rpc-v1"
internal const val RPC_ENDPOINT = "https://mainnet.massa.net/api/v2"
internal const val REVIEW_LIFETIME = 20_000L
internal val SUPPORTED_VERSIONS = setOf("MAIN.5.0", "MAIN.5.1")
internal enum class SendError { WALLET, RECIPIENT, AMOUNT, FUNDS, OVERFLOW, CREATION_MINIMUM, NETWORK, STALE, CHANGED, AUTH, CANCELLED, BUSY, LOCKED, JOURNAL, CAPACITY, DUPLICATE, DISABLED, SIGNATURE, EVIDENCE }
internal class SendFailure(val reason: SendError) : Exception(reason.name)
internal fun demand(ok: Boolean, reason: SendError) { if (!ok) throw SendFailure(reason) }
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun unhex(value: String, max: Int = 512): ByteArray {
    demand(value.length % 2 == 0 && value.length <= max * 2 && value.matches(Regex("[0-9a-f]*")), SendError.JOURNAL)
    return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
internal fun age(now: Long, then: Long, limit: Long = REVIEW_LIFETIME): Boolean = then >= 0 && now >= then && now - then <= limit
internal fun sum(a: ULong, b: ULong): ULong { demand(b <= ULong.MAX_VALUE - a, SendError.OVERFLOW); return a + b }
internal fun validAU(value: String) { try { MassaCodec.validateAddress(value, true) } catch (_: Exception) { throw SendFailure(SendError.RECIPIENT) } }
internal object SendInput {
    // Same editable decimal normalization as MASUserInput; persisted/RPC values remain canonical dot decimals.
    fun amount(text: String): MASAmount {
        val dot = text.replace(',', '.')
        val normalized = if (dot.startsWith('.')) "0$dot" else dot
        val value = try { MASAmount.parse(normalized) } catch (_: Exception) { throw SendFailure(SendError.AMOUNT) }
        demand(value.nano > 0uL, SendError.AMOUNT); return value
    }
    fun recipient(text: String): String = text.trim().also { validAU(it) }
    fun maximum(snapshot: SendSnapshot, now: Long): MASAmount {
        snapshot.validate(now)
        demand(snapshot.spendable.nano > snapshot.fee.nano, SendError.FUNDS)
        return MASAmount(snapshot.spendable.nano - snapshot.fee.nano).also { RecipientCredit.make(it, snapshot) }
    }
}
internal object SendIdentityPolicy {
    // Exact current iOS KnownUnsafeWallets. Fixtures remain usable through injected test providers/signers,
    // but the shipping Send entry never offers these publicly compromised identities as safe recipients.
    val unsafe = setOf("AU1rqxSMewKgJU2m5orM2HFuMg7ioEFiECuhX1sSken92VxUBfQy", "AU12MBEEKVAFZhxYzeyNYpAAcKEu667KKjHBos9i65htLwPkHyQG4", "AU12b9KwUq6tSJ1hG2EdLrysd9LWEhshwNp514hAJ2RskT4kc7voi", "AU1FgfkVQ5nVCHdw9n4Rt6Lq9EULgicGPTWLNzM5NxD6g8TrJVyi")
    fun production(source: TransferSource, recipient: String) { demand(source.address !in unsafe, SendError.WALLET); demand(recipient !in unsafe, SendError.RECIPIENT) }
}
internal data class TransferSource(val walletId: String, val address: String, val publicKey: String) {
    val identity: String get() = "$CHAIN:$publicKey:$address"
    fun validate() {
        demand(runCatching { UUID.fromString(walletId).toString() == walletId }.getOrDefault(false), SendError.WALLET)
        demand(runCatching { PublicAccount.fromRaw(MassaCodec.decode(publicKey, "P")).address == address }.getOrDefault(false), SendError.WALLET)
    }
    companion object {
        fun capture(wallet: Wallet): TransferSource {
            demand(!wallet.watchOnly && !wallet.removing, SendError.WALLET)
            return TransferSource(wallet.id, wallet.address, wallet.publicKey!!).also { it.validate() }
        }
    }
}
internal data class SendSnapshot(val fetchedAt: Long, val chain: Long, val version: String, val period: ULong, val validity: ULong,
    val fee: MASAmount, val finalBalance: MASAmount, val candidateBalance: MASAmount, val recipientFinal: MASAmount, val recipientCandidate: MASAmount) {
    val spendable get() = minOf(finalBalance, candidateBalance)
    fun validate(now: Long) {
        demand(chain == CHAIN && version in SUPPORTED_VERSIONS && age(now, fetchedAt) && validity >= 2uL && period <= ULong.MAX_VALUE - 9uL, SendError.STALE)
    }
    fun sameReviewContext(other: SendSnapshot): Boolean = chain == other.chain && version == other.version && validity == other.validity && fee == other.fee &&
        finalBalance == other.finalBalance && candidateBalance == other.candidateBalance && recipientFinal == other.recipientFinal && recipientCandidate == other.recipientCandidate
}
internal data class RecipientCredit(val creationPossible: Boolean, val minimum: MASAmount, val maximum: MASAmount) {
    companion object {
        // MAIN.5.0/5.1 massa-models constants.rs LEDGER_ENTRY_BASE_COST; deducted by transfer_coins.
        const val CREATION_NANO: ULong = 1_000_000uL
        fun make(amount: MASAmount, snapshot: SendSnapshot): RecipientCredit = make(amount, snapshot.recipientFinal.nano == 0uL || snapshot.recipientCandidate.nano == 0uL)
        fun make(amount: MASAmount, uncertain: Boolean): RecipientCredit {
            demand(!uncertain || amount.nano >= CREATION_NANO, SendError.CREATION_MINIMUM)
            return RecipientCredit(uncertain, MASAmount(amount.nano - if (uncertain) CREATION_NANO else 0uL), amount)
        }
    }
}
/** Public immutable review; all byte arrays are computed copies, never caller-owned mutable buffers. */
internal class ReviewedTransfer private constructor(val reviewId: String, val source: TransferSource, val recipient: String, val amount: MASAmount,
    val snapshot: SendSnapshot, val expiry: ULong, val reviewedAt: Long) {
    val fee get() = snapshot.fee
    val total = MASAmount(sum(amount.nano, fee.nano))
    val credit = RecipientCredit.make(amount, snapshot)
    val content: ByteArray get() = MassaOperation.serialized(fee, amount, expiry, recipient)
    val canonical: ByteArray get() = MassaOperation.canonical(content, source.publicKey)
    val digest: ByteArray get() = MassaCodec.hash(canonical)
    val operationId: String get() = MassaOperation.operationID(content, source.publicKey)
    val commitment: String get() {
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { d ->
            d.writeUTF("MassiveX/review/v1"); d.writeUTF(reviewId); d.writeUTF(source.walletId); d.writeUTF(source.address); d.writeUTF(source.publicKey)
            d.writeUTF(digest.hex()); d.writeLong(reviewedAt); d.writeLong(snapshot.fetchedAt); d.writeUTF(snapshot.version); d.writeLong(snapshot.chain)
            d.writeUTF(snapshot.period.toString()); d.writeUTF(snapshot.validity.toString())
            listOf(snapshot.finalBalance, snapshot.candidateBalance, snapshot.recipientFinal, snapshot.recipientCandidate).forEach { d.writeUTF(it.nano.toString()) }
        } }.toByteArray()
        return MassaCodec.hash(bytes).hex()
    }
    fun validate() {
        source.validate(); validAU(recipient)
        demand(UUID.fromString(reviewId).toString() == reviewId && amount.nano > 0uL && reviewedAt >= 0 && age(reviewedAt, snapshot.fetchedAt), SendError.JOURNAL)
        snapshot.validate(reviewedAt)
        demand(expiry == snapshot.period + minOf(9uL, snapshot.validity - 1uL) && total <= snapshot.spendable, SendError.JOURNAL)
        sum(snapshot.recipientFinal.nano, amount.nano); sum(snapshot.recipientCandidate.nano, amount.nano)
    }
    fun validateFresh(fresh: SendSnapshot, now: Long) {
        validate(); fresh.validate(now)
        demand(age(now, reviewedAt) && snapshot.sameReviewContext(fresh) && fresh.period >= snapshot.period && fresh.period < expiry &&
            RecipientCredit.make(amount, fresh) == credit, SendError.CHANGED)
    }
    override fun toString() = "ReviewedTransfer(<public-intent-redacted>)"
    companion object {
        fun make(source: TransferSource, recipient: String, text: String, snapshot: SendSnapshot, now: Long): ReviewedTransfer {
            snapshot.validate(now); source.validate(); validAU(recipient)
            val value=SendInput.amount(text)
            demand(sum(value.nano,snapshot.fee.nano)<=snapshot.spendable.nano,SendError.FUNDS)
            return ReviewedTransfer(UUID.randomUUID().toString(), source, recipient, value, snapshot,
                snapshot.period + minOf(9uL, snapshot.validity - 1uL), now).also { it.validate() }
        }
        fun restore(reviewId: String, source: TransferSource, recipient: String, amount: MASAmount, snapshot: SendSnapshot, expiry: ULong, at: Long): ReviewedTransfer =
            ReviewedTransfer(reviewId, source, recipient, amount, snapshot, expiry, at).also { it.validate() }
    }
}
internal class SignedTransfer(val draft: ReviewedTransfer, val signature: String) {
    fun validate() {
        draft.validate()
        demand(runCatching { PublicAccount(draft.source.publicKey, draft.source.address).verify(draft.canonical, signature) }.getOrDefault(false), SendError.SIGNATURE)
    }
    val binding get() = MassaCodec.hash(("MassiveX/signed/v1\u0000" + draft.commitment + "\u0000" + signature).toByteArray()).hex()
    override fun toString() = "SignedTransfer(<redacted>)"
}
internal fun interface PreflightReader { suspend fun snapshot(source: TransferSource, recipient: String): SendSnapshot }
internal interface TransferSigner { suspend fun sign(draft: ReviewedTransfer): SignedTransfer; fun cancel() }
internal sealed interface BroadcastReply {
    data class Accepted(val operationId: String) : BroadcastReply
    data object Rejected : BroadcastReply
    data object Missing : BroadcastReply
}
internal interface BroadcastTransport {
    val enabled: Boolean
    suspend fun submit(signed: SignedTransfer): BroadcastReply
    suspend fun submit(signed: SignedTransfer, permit: SubmissionPermit): BroadcastReply = submit(signed)
}
/** Process-only, single-use authority. Never serialized or recovered after a crash. */
internal class SubmissionPermit internal constructor(private val binding: String) {
    private val consumed = java.util.concurrent.atomic.AtomicBoolean()
    fun consume(signed: SignedTransfer) {
        demand(binding == signed.binding && consumed.compareAndSet(false, true), SendError.JOURNAL)
    }
}
/** Explicit disabled transport for isolated negative tests; production injects MainnetBroadcastTransport. */
internal object NoBroadcastTransport : BroadcastTransport {
    override val enabled = false
    override suspend fun submit(signed: SignedTransfer): Nothing = throw SendFailure(SendError.DISABLED)
}
