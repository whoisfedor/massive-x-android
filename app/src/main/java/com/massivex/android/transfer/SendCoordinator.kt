package com.massivex.android.transfer

import com.massivex.android.wallet.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

internal enum class SendPhase { FORM, REVIEW, AUTHORIZING, RECORDING, SUBMITTING, STATUS }
internal data class SendUi(val source:TransferSource?=null,val review:ReviewedTransfer?=null,val phase:SendPhase=SendPhase.FORM,val busy:Boolean=false,
    val error:SendError?=null,val records:List<TransferRecord> = emptyList(),val maximum:String?=null,val preview:SendSnapshot?=null,val previewRecipient:String?=null) {
    val pending get()=records.firstOrNull { !it.state.terminal && it.signed.draft.source.identity==source?.identity }
    val latest get()=records.lastOrNull { it.signed.draft.source.identity==source?.identity }
    fun forSource(current:TransferSource?):SendUi = if(source==current)this else SendUi(source=current,records=records)
}
internal class ProtectedWalletSigner(private val repository:WalletRepository,private val authentication:OwnerAuthentication,private val clock:()->Long=System::currentTimeMillis):TransferSigner {
    override suspend fun sign(draft:ReviewedTransfer):SignedTransfer { SendIdentityPolicy.production(draft.source,draft.recipient);return repository.signReviewed(draft,clock) }
    override fun cancel()=authentication.cancel()
}
/** No code here retries a payment. A transport call is reachable only after two durable journal states. */
internal class SendCoordinator(private val preflight:PreflightReader,private val ledger:TransferLedger,private val finality:FinalityReader,
    private val broadcast:BroadcastTransport=NoBroadcastTransport,private val clock:()->Long=System::currentTimeMillis,
    private val pause:suspend(Long)->Unit={delay(it)}) {
    private val mutable=MutableStateFlow(SendUi());val state=mutable.asStateFlow()
    val submissionEnabled get()=broadcast.enabled
    private val mutex=Mutex();private val generation=AtomicLong()
    @Volatile private var active=false
    @Volatile private var signer:TransferSigner?=null
    fun select(source:TransferSource?) {
        if(state.value.source==source)return
        invalidate();mutable.update { it.copy(source=source,error=null,maximum=null,preview=null) }
    }
    fun resume(){active=true}
    fun invalidate(keepPreview:Boolean=false){generation.incrementAndGet();signer?.cancel();mutable.update { it.copy(review=null,phase=SendPhase.FORM,error=null,maximum=null,preview=if(keepPreview&&it.phase==SendPhase.FORM)it.preview else null,previewRecipient=if(keepPreview&&it.phase==SendPhase.FORM)it.previewRecipient else null) }}
    fun stop(){active=false;invalidate()}
    private fun current(source:TransferSource,token:Long){demand(active && generation.get()==token && state.value.source==source,SendError.CHANGED)}
    private suspend fun disk(block:()->Unit)=withContext(NonCancellable+Dispatchers.IO){block()}
    suspend fun reload() {
        try { val records=withContext(Dispatchers.IO){ledger.records()};mutable.update { it.copy(records=records) } }
        catch(e:CancellationException){throw e}
        catch(_:Exception){mutable.update { it.copy(error=SendError.JOURNAL) }}
    }
    suspend fun maximum(recipient:String) = formSnapshot(recipient,true)
    suspend fun preview(recipient:String) = formSnapshot(recipient,false)
    private suspend fun formSnapshot(recipient:String,useMaximum:Boolean) {
        if(!mutex.tryLock())return
        val source=state.value.source;val token=generation.get();mutable.update { it.copy(busy=true,error=null,maximum=null,review=null,phase=SendPhase.FORM) }
        try { demand(active,SendError.CHANGED);demand(source!=null,SendError.WALLET);val s=preflight.snapshot(source!!,SendInput.recipient(recipient));current(source,token)
            s.validate(clock());val maximum=if(useMaximum)SendInput.maximum(s,clock()).text else null;mutable.update { it.copy(maximum=maximum,preview=s,previewRecipient=SendInput.recipient(recipient)) }
        }catch(e:CancellationException){throw e}catch(e:Exception){if(generation.get()==token)mutable.update { it.copy(error=reason(e)) }}finally{mutable.update { it.copy(busy=false) };mutex.unlock()}
    }
    suspend fun prepare(recipient:String,amount:String) {
        if(!mutex.tryLock())return
        val source=state.value.source;val token=generation.get();mutable.update { it.copy(busy=true,error=null,review=null,phase=SendPhase.FORM) }
        try {
            demand(source!=null,SendError.WALLET);current(source!!,token)
            demand(ledger.pending(source)==null,SendError.LOCKED)
            val recipientValue=SendInput.recipient(recipient);SendInput.amount(amount)
            val snapshot=preflight.snapshot(source,recipientValue);current(source,token)
            val draft=ReviewedTransfer.make(source,recipientValue,amount,snapshot,clock())
            val records=withContext(Dispatchers.IO){ledger.records()}
            demand(records.size<JournalCodec.MAX_RECORDS,SendError.CAPACITY);demand(records.none { it.operationId==draft.operationId },SendError.DUPLICATE)
            current(source,token);mutable.update { it.copy(review=draft,phase=SendPhase.REVIEW,records=records) }
        }catch(e:CancellationException){throw e}catch(e:Exception){if(generation.get()==token)mutable.update { it.copy(error=reason(e)) }}
        finally{mutable.update { it.copy(busy=false) };mutex.unlock()}
    }
    suspend fun confirm(approved:ReviewedTransfer?,protectedSigner:TransferSigner) {
        if(!mutex.tryLock())return
        val d=state.value.review;val token=generation.get();var lease:AutoCloseable?=null;var prepared=false;var signerEngaged=false
        mutable.update { it.copy(busy=true,error=null) }
        try {
            demand(d!=null && approved?.commitment==d.commitment && state.value.phase==SendPhase.REVIEW,SendError.CHANGED)
            current(d!!.source,token);d.validate()
            demand(broadcast.enabled,SendError.DISABLED) // Disabled injected adapters stop before authorization or intent persistence.
            withContext(Dispatchers.IO){lease=ledger.acquire(d.source)}
            demand(age(clock(),d.reviewedAt),SendError.STALE)
            d.validateFresh(preflight.snapshot(d.source,d.recipient),clock());current(d.source,token)
            mutable.update { it.copy(phase=SendPhase.AUTHORIZING) }
            signer=protectedSigner;signerEngaged=true
            val signed=protectedSigner.sign(d);current(d.source,token)
            demand(signed.draft.commitment==d.commitment,SendError.SIGNATURE);signed.validate()
            d.validateFresh(preflight.snapshot(d.source,d.recipient),clock());current(d.source,token)
            mutable.update { it.copy(phase=SendPhase.RECORDING) }
            disk { ledger.prepared(signed,clock());prepared=true }
            current(d.source,token);currentCoroutineContext().ensureActive();demand(age(clock(),d.reviewedAt),SendError.STALE)
            val permit=withContext(NonCancellable+Dispatchers.IO){ledger.beginSubmission(signed,clock())}
            current(d.source,token);currentCoroutineContext().ensureActive();demand(age(clock(),d.reviewedAt),SendError.STALE)
            mutable.update { it.copy(phase=SendPhase.SUBMITTING) }
            val reply=broadcast.submit(signed,permit)
            val accepted=reply is BroadcastReply.Accepted && reply.operationId==d.operationId
            disk { ledger.transition(d.operationId,if(accepted)TransferState.PENDING else TransferState.UNKNOWN,clock(),
                if(accepted)TransferNote.ACCEPTED_NOT_FINAL else if(reply==BroadcastReply.Rejected)TransferNote.REJECTED_UNVERIFIED else TransferNote.RESPONSE_UNKNOWN) }
            if(generation.get()==token)mutable.update { it.copy(phase=SendPhase.STATUS,review=null) }
        }catch(e:Exception){
            if(prepared && d!=null){try{disk{ledger.transition(d.operationId,TransferState.UNKNOWN,clock(),TransferNote.RESPONSE_UNKNOWN)}}catch(_:Exception){mutable.update{it.copy(error=SendError.JOURNAL)}}}
            if(generation.get()==token)mutable.update { it.copy(error=reason(e),phase=if(prepared)SendPhase.STATUS else if(reason(e) in setOf(SendError.CHANGED,SendError.STALE))SendPhase.FORM else SendPhase.REVIEW,
                review=if(prepared || reason(e) in setOf(SendError.CHANGED,SendError.STALE))null else it.review) }
            if(e is CancellationException)throw e
        }finally{if(signerEngaged)protectedSigner.cancel();signer=null;lease?.close();withContext(NonCancellable){reload()};mutable.update { it.copy(busy=false) };mutex.unlock()}
    }
    suspend fun reconcile(operationId:String) {
        if(!mutex.tryLock())return
        val token=generation.get();mutable.update { it.copy(busy=true,error=null) }
        try {
            demand(active,SendError.CHANGED)
            val r=withContext(Dispatchers.IO){ledger.records().singleOrNull { it.operationId==operationId }} ?: throw SendFailure(SendError.EVIDENCE)
            if(r.state.terminal)return
            val first=finality.observe(r.signed);disk{ledger.observation(operationId,first,clock())}
            demand(active && token==generation.get(),SendError.CHANGED);pause(1000);currentCoroutineContext().ensureActive()
            val second=finality.observe(r.signed);disk{ledger.observation(operationId,second,clock())}
            demand(active && token==generation.get(),SendError.CHANGED)
            if(first.report!=null && second.report!=null && first.report.success==second.report.success){
                val context=finality.checkContext();demand(active && token==generation.get(),SendError.CHANGED)
                val evidence=TrustedResolution(TRUST_POLICY,clock(),first.report,second.report,context)
                evidence.validate(r.signed,clock());disk{ledger.resolve(operationId,evidence,clock())}
                if(active&&token==generation.get()&&state.value.source?.identity==r.signed.draft.source.identity)
                    mutable.update{it.copy(phase=SendPhase.STATUS,review=null)}
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){if(generation.get()==token)mutable.update{it.copy(error=reason(e))}}
        finally{withContext(NonCancellable){reload()};mutable.update{it.copy(busy=false)};mutex.unlock()}
    }
    /** Scheduling only: four foreground checks, no signing/submission and no background retry loop. */
    suspend fun pollSelected() {
        val token=generation.get()
        repeat(4){attempt->
            if(!active || generation.get()!=token)return
            reload();val record=state.value.pending ?: return
            reconcile(record.operationId)
            if(state.value.pending==null || attempt==3 || generation.get()!=token)return
            pause(15_000)
        }
    }
    private fun reason(e:Exception):SendError = when(e){
        is SendFailure->e.reason
        is WalletException->when(e.reason){WalletError.CANCELLED->SendError.CANCELLED;WalletError.EXPIRED->SendError.STALE;WalletError.CHANGED->SendError.CHANGED;else->SendError.AUTH}
        is CancellationException->SendError.CANCELLED
        else->SendError.NETWORK
    }
}
