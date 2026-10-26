package com.massivex.android.transfer

import com.massivex.android.wallet.*
import com.massivex.massa.*
import java.io.*
import java.util.UUID

internal enum class TransferState { SIGNED, SUBMIT_ATTEMPT, PENDING, UNKNOWN, VERIFIED_SUCCESS, VERIFIED_FAILURE;
    val terminal get() = this == VERIFIED_SUCCESS || this == VERIFIED_FAILURE
}
internal enum class TransferNote { PREPARED, ATTEMPT_RECORDED, ACCEPTED_NOT_FINAL, REJECTED_UNVERIFIED, RESPONSE_UNKNOWN, OBSERVED_PENDING, MISSING_OR_PRUNED, INVALID_EVIDENCE, CONFLICT, TRUSTED_PROVIDER, UNAVAILABLE }
internal data class VerifiedNodeContext(val chain: Long, val version: String, val checkedAt: Long) {
    fun validate(now: Long) { demand(chain == CHAIN && version in SUPPORTED_VERSIONS && age(now, checkedAt), SendError.EVIDENCE) }
}
internal data class FinalReport(val context: VerifiedNodeContext, val observedAt: Long, val endpoint: String, val operationId: String,
    val sourceAddress: String, val publicKey: String, val signature: String, val contentHex: String, val signedBinding: String, val success: Boolean) {
    val claim get() = if (success) 1 else 2
    fun validate(signed: SignedTransfer) {
        signed.validate(); context.validate(observedAt)
        val d = signed.draft
        demand(endpoint == RPC_ENDPOINT && operationId == d.operationId && sourceAddress == d.source.address && publicKey == d.source.publicKey &&
            signature == signed.signature && contentHex == d.content.hex() && signedBinding == signed.binding, SendError.EVIDENCE)
    }
}
internal data class TrustedResolution(val policy: String, val acceptedAt: Long, val first: FinalReport, val second: FinalReport, val finalContext: VerifiedNodeContext) {
    val success get() = first.success
    fun validate(signed: SignedTransfer, now: Long? = null) {
        first.validate(signed); second.validate(signed); finalContext.validate(acceptedAt)
        demand(policy == TRUST_POLICY && first.success == second.success && first.context.version == second.context.version &&
            finalContext.version == second.context.version && second.observedAt >= first.observedAt && second.observedAt - first.observedAt >= 1000 &&
            age(acceptedAt, first.context.checkedAt) && age(acceptedAt, first.observedAt) && age(acceptedAt, second.observedAt) &&
            finalContext.checkedAt >= second.observedAt, SendError.EVIDENCE)
        if (now != null) demand(age(now, acceptedAt), SendError.STALE)
    }
}
internal data class OperationObservation(val state: TransferState, val report: FinalReport? = null) {
    init { demand(state in setOf(TransferState.PENDING, TransferState.UNKNOWN) || state.terminal, SendError.EVIDENCE) }
}
internal interface FinalityReader {
    suspend fun observe(signed: SignedTransfer): OperationObservation
    suspend fun checkContext(): VerifiedNodeContext
}
internal data class TransferRecord(val signed: SignedTransfer, val state: TransferState, val updatedAt: Long,
    val note: TransferNote, val claims: Int = 0, val reports: List<FinalReport> = emptyList(), val resolution: TrustedResolution? = null) {
    val operationId get() = signed.draft.operationId
    fun validate() {
        signed.validate()
        demand(updatedAt >= signed.draft.reviewedAt && claims in 0..3 && reports.size <= 16, SendError.JOURNAL)
        reports.forEach { it.validate(signed); demand(claims and it.claim != 0, SendError.JOURNAL) }
        if (state.terminal) {
            val r = resolution ?: throw SendFailure(SendError.JOURNAL); r.validate(signed)
            demand(claims == if (r.success) 1 else 2, SendError.JOURNAL)
            demand(state == if (r.success) TransferState.VERIFIED_SUCCESS else TransferState.VERIFIED_FAILURE, SendError.JOURNAL)
            demand(reports.takeLast(2) == listOf(r.first, r.second) && note == TransferNote.TRUSTED_PROVIDER && updatedAt >= r.acceptedAt, SendError.JOURNAL)
        } else demand(resolution == null, SendError.JOURNAL)
        if (claims == 3) demand(state == TransferState.UNKNOWN && note == TransferNote.CONFLICT, SendError.JOURNAL)
    }
    override fun toString() = "TransferRecord(<protected>)"
}
internal data class JournalFile(val installationId: String, val revision: Long = 0, val records: List<TransferRecord> = emptyList()) {
    fun validate() {
        demand(UUID.fromString(installationId).toString() == installationId && revision >= 0 && records.size <= JournalCodec.MAX_RECORDS &&
            records.map { it.operationId }.toSet().size == records.size, SendError.JOURNAL)
        records.forEach { it.validate() }
        demand(records.filter { !it.state.terminal }.groupBy { it.signed.draft.source.identity }.values.all { it.size == 1 }, SendError.JOURNAL)
    }
}
internal interface JournalPersistence { fun read(): JournalFile; fun write(file: JournalFile) }

/** Stable bounded binary schema inside authenticated encryption. Never Java serialization or plaintext disk JSON. */
internal object JournalCodec {
    const val MAX_RECORDS = 512
    const val MAX_BYTES = 4_194_304
    fun encode(file: JournalFile): ByteArray {
        file.validate()
        val bytes = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { d ->
            d.writeInt(0x4d584134); d.writeInt(1); d.writeUTF(file.installationId); d.writeLong(file.revision); d.writeInt(file.records.size)
            file.records.forEach { r ->
                val v = r.signed.draft; d.writeUTF(v.reviewId); d.writeUTF(v.source.walletId); d.writeUTF(v.source.address); d.writeUTF(v.source.publicKey)
                d.writeUTF(v.recipient); d.writeUTF(v.amount.nano.toString()); d.writeLong(v.reviewedAt); d.writeUTF(v.expiry.toString())
                val s = v.snapshot; d.writeLong(s.fetchedAt); d.writeLong(s.chain); d.writeUTF(s.version); d.writeUTF(s.period.toString()); d.writeUTF(s.validity.toString())
                listOf(s.fee, s.finalBalance, s.candidateBalance, s.recipientFinal, s.recipientCandidate).forEach { d.writeUTF(it.nano.toString()) }
                d.writeUTF(v.content.hex()); d.writeUTF(v.digest.hex()); d.writeUTF(v.operationId); d.writeUTF(v.commitment); d.writeUTF(r.signed.signature)
                d.writeUTF(r.state.name); d.writeLong(r.updatedAt); d.writeUTF(r.note.name); d.writeInt(r.claims); d.writeInt(r.reports.size)
                r.reports.forEach { report(d, it) }; d.writeBoolean(r.resolution != null)
                r.resolution?.let { e -> d.writeUTF(e.policy); d.writeLong(e.acceptedAt); report(d, e.first); report(d, e.second); context(d, e.finalContext) }
            }
        } }.toByteArray()
        demand(bytes.size <= MAX_BYTES, SendError.CAPACITY); return bytes
    }
    private fun context(d: DataOutputStream, c: VerifiedNodeContext) { d.writeLong(c.chain); d.writeUTF(c.version); d.writeLong(c.checkedAt) }
    private fun report(d: DataOutputStream, r: FinalReport) {
        context(d, r.context); d.writeLong(r.observedAt)
        listOf(r.endpoint,r.operationId,r.sourceAddress,r.publicKey,r.signature,r.contentHex,r.signedBinding).forEach(d::writeUTF)
        d.writeBoolean(r.success)
    }
    fun decode(bytes: ByteArray): JournalFile = try {
        demand(bytes.size <= MAX_BYTES, SendError.JOURNAL)
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            fun text(limit: Int = 128): String = d.readUTF().also { demand(it.length <= limit, SendError.JOURNAL) }
            fun ulong(): ULong { val value = text(20); return value.toULongOrNull()?.also { demand(it.toString() == value, SendError.JOURNAL) } ?: throw SendFailure(SendError.JOURNAL) }
            fun amount() = MASAmount(ulong())
            fun context() = VerifiedNodeContext(d.readLong(), text(32), d.readLong())
            fun report(): FinalReport = FinalReport(context(),d.readLong(),text(),text(),text(),text(),text(),text(1024),text(64),d.readBoolean())
            demand(d.readInt() == 0x4d584134 && d.readInt() == 1, SendError.JOURNAL)
            val id = text(36); val rev = d.readLong(); val count = d.readInt(); demand(count in 0..MAX_RECORDS, SendError.JOURNAL)
            val records = List(count) {
                val reviewId = text(36); val source = TransferSource(text(36),text(),text()); val recipient = text(); val value = amount(); val at = d.readLong(); val expiry = ulong()
                val snapshot = SendSnapshot(d.readLong(),d.readLong(),text(32),ulong(),ulong(),amount(),amount(),amount(),amount(),amount())
                val v = ReviewedTransfer.restore(reviewId,source,recipient,value,snapshot,expiry,at)
                demand(text(1024) == v.content.hex() && text(64) == v.digest.hex() && text() == v.operationId && text(64) == v.commitment, SendError.JOURNAL)
                val signed = SignedTransfer(v,text()); val state = TransferState.valueOf(text(32)); val updated = d.readLong(); val note = TransferNote.valueOf(text(32)); val claims = d.readInt()
                val size = d.readInt(); demand(size in 0..16, SendError.JOURNAL); val reports = List(size) { report() }
                val resolution = if (d.readBoolean()) TrustedResolution(text(64),d.readLong(),report(),report(),context()) else null
                TransferRecord(signed,state,updated,note,claims,reports,resolution)
            }
            demand(d.available() == 0, SendError.JOURNAL); JournalFile(id,rev,records).also { it.validate() }
        }
    } catch (e: SendFailure) { throw e } catch (_: Exception) { throw SendFailure(SendError.JOURNAL) }
}

/** One process-scoped ledger shared by Send and wallet deletion. Durable storage is the source of truth. */
internal class TransferLedger(private val disk: JournalPersistence) : WalletRemovalGuard {
    private val leases = mutableMapOf<String,Any>()
    private var last: JournalFile? = null
    private var fault = false
    @Synchronized fun records(): List<TransferRecord> = load().records.toList()
    private fun load(): JournalFile {
        demand(!fault, SendError.JOURNAL)
        try {
            val file = disk.read().also { it.validate() }
            last?.let { previous ->
                demand(file.installationId == previous.installationId && file.revision >= previous.revision && previous.records.all { old -> file.records.any { next ->
                    next.operationId == old.operationId && next.signed.binding == old.signed.binding && next.claims and old.claims == old.claims &&
                        (!old.state.terminal || (next.state == old.state && next.resolution == old.resolution))
                } }, SendError.JOURNAL)
                if(file.revision == previous.revision)demand(JournalCodec.encode(file).contentEquals(JournalCodec.encode(previous)),SendError.JOURNAL)
            }
            last = file; return file
        } catch (_: Exception) { fault = true; throw SendFailure(SendError.JOURNAL) }
    }
    @Synchronized fun reload() { fault = false; load() }
    @Synchronized fun pending(source: TransferSource): TransferRecord? = load().records.firstOrNull { it.signed.draft.source.identity == source.identity && !it.state.terminal }
    @Synchronized fun acquire(source: TransferSource): AutoCloseable {
        source.validate(); demand(pending(source) == null && source.identity !in leases, SendError.LOCKED)
        val token=Any();leases[source.identity]=token
        return AutoCloseable { synchronized(this) { if(leases[source.identity]===token)leases.remove(source.identity) };Unit }
    }
    override fun begin(wallet: Wallet): AutoCloseable {
        if (wallet.watchOnly) return AutoCloseable {}
        return try { acquire(TransferSource(wallet.id,wallet.address,wallet.publicKey!!).also{it.validate()}) } catch (_: Exception) { throw WalletException(WalletError.TRANSFER_LOCKED) }
    }
    @Synchronized fun prepared(signed: SignedTransfer, now: Long) {
        signed.validate(); val f = load()
        demand(signed.draft.source.identity in leases && f.records.none { it.operationId == signed.draft.operationId }, SendError.DUPLICATE)
        demand(f.records.none { !it.state.terminal && it.signed.draft.source.identity == signed.draft.source.identity }, SendError.LOCKED)
        demand(f.records.size < JournalCodec.MAX_RECORDS, SendError.CAPACITY)
        save(f, f.records + TransferRecord(signed,TransferState.SIGNED,now,TransferNote.PREPARED))
    }
    @Synchronized fun transition(id: String, state: TransferState, now: Long, note: TransferNote) {
        val f = load(); val r = f.records.singleOrNull { it.operationId == id } ?: throw SendFailure(SendError.JOURNAL)
        demand(!r.state.terminal && !state.terminal, SendError.JOURNAL)
        val allowed = when (state) {
            TransferState.SUBMIT_ATTEMPT -> r.state == TransferState.SIGNED
            TransferState.PENDING -> r.state in setOf(TransferState.SUBMIT_ATTEMPT,TransferState.PENDING,TransferState.UNKNOWN)
            TransferState.UNKNOWN -> true
            else -> false
        }
        demand(allowed, SendError.JOURNAL)
        replace(f,r.copy(state=if(r.claims==3)TransferState.UNKNOWN else state,updatedAt=maxOf(now,r.updatedAt),note=if(r.claims==3)TransferNote.CONFLICT else note))
    }
    /** Mint authority only while advancing SIGNED to a durably committed attempt, under its live lease.
     * Recovered attempts cannot mint another permit. The binary journal format is unchanged. */
    @Synchronized fun beginSubmission(signed: SignedTransfer, now: Long): SubmissionPermit {
        val record = load().records.singleOrNull { it.operationId == signed.draft.operationId }
        demand(record?.signed?.binding == signed.binding && signed.draft.source.identity in leases, SendError.JOURNAL)
        transition(signed.draft.operationId, TransferState.SUBMIT_ATTEMPT, now, TransferNote.ATTEMPT_RECORDED)
        // Verify durable read-back before network authority escapes this ledger.
        demand(load().records.single { it.operationId == signed.draft.operationId }.state == TransferState.SUBMIT_ATTEMPT, SendError.JOURNAL)
        return SubmissionPermit(signed.binding)
    }
    @Synchronized fun observation(id: String, observation: OperationObservation, now: Long) {
        val f = load(); val r = f.records.singleOrNull { it.operationId == id } ?: throw SendFailure(SendError.JOURNAL)
        demand(!r.state.terminal, SendError.EVIDENCE)
        val report = observation.report
        if (observation.state.terminal) demand(report != null && report.success == (observation.state == TransferState.VERIFIED_SUCCESS), SendError.EVIDENCE)
        else demand(report == null, SendError.EVIDENCE)
        report?.validate(r.signed)
        if (report != null) demand(age(now,report.observedAt),SendError.EVIDENCE)
        val claims = r.claims or (report?.claim ?: 0)
        val state = if(claims==3 || observation.state.terminal || (r.state==TransferState.SIGNED && observation.state==TransferState.PENDING))TransferState.UNKNOWN else observation.state
        replace(f,r.copy(state=state,updatedAt=maxOf(now,r.updatedAt),note=when { claims==3 -> TransferNote.CONFLICT; report!=null -> TransferNote.INVALID_EVIDENCE; state==TransferState.PENDING -> TransferNote.OBSERVED_PENDING; else -> TransferNote.MISSING_OR_PRUNED },claims=claims,reports=(r.reports+listOfNotNull(report)).takeLast(16)))
    }
    @Synchronized fun resolve(id: String, resolution: TrustedResolution, now: Long) {
        val f = load(); val r = f.records.singleOrNull { it.operationId == id } ?: throw SendFailure(SendError.JOURNAL)
        demand(!r.state.terminal, SendError.EVIDENCE); resolution.validate(r.signed,now)
        demand(r.claims == if(resolution.success)1 else 2, SendError.EVIDENCE)
        demand(r.reports.takeLast(2)==listOf(resolution.first,resolution.second),SendError.EVIDENCE)
        replace(f,r.copy(state=if(resolution.success)TransferState.VERIFIED_SUCCESS else TransferState.VERIFIED_FAILURE,updatedAt=maxOf(now,r.updatedAt),note=TransferNote.TRUSTED_PROVIDER,resolution=resolution))
    }
    private fun replace(f: JournalFile, r: TransferRecord) = save(f,f.records.map { if(it.operationId==r.operationId)r else it })
    private fun save(f: JournalFile, records: List<TransferRecord>) {
        demand(f.revision < Long.MAX_VALUE,SendError.JOURNAL)
        val next=f.copy(revision=f.revision+1,records=records)
        try { next.validate(); disk.write(next); last=next }
        catch (_: Exception) { fault=true; throw SendFailure(SendError.JOURNAL) } // Never unlock based on an ambiguous commit.
    }
}
