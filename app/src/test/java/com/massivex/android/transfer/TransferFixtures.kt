package com.massivex.android.transfer

import com.massivex.android.wallet.*
import com.massivex.android.wallet.WalletRepositoryTest
import com.massivex.massa.*
import kotlinx.coroutines.*
import java.io.IOException
import java.util.UUID

/** PUBLIC, KNOWN-INSECURE SYNTHETIC KEYS AND BALANCES. NEVER FUND. No fixture is a live receipt. */
internal const val TEST_TIME=1_700_000_000_000L
internal fun identity(seed:Int)=OfflineAccount.fromSeed(ByteArray(32){seed.toByte()}).use{it.publicAccount()}
internal val recipient get()=identity(43).address
internal fun source(seed:Int=42)=TransferSource(UUID.nameUUIDFromBytes("fixture-$seed".toByteArray()).toString(),identity(seed).address,identity(seed).publicKey)
internal fun snapshot(now:Long=TEST_TIME)=SendSnapshot(now,CHAIN,"MAIN.5.1",100uL,10uL,MASAmount(1_000_000uL),MASAmount(10_000_000_000uL),MASAmount(9_000_000_000uL),MASAmount(2_000_000uL),MASAmount(2_000_000uL))
internal fun review(seed:Int=42,now:Long=TEST_TIME,amount:String="1",snap:SendSnapshot=snapshot(now))=ReviewedTransfer.make(source(seed),recipient,amount,snap,now)
internal fun signed(d:ReviewedTransfer=review(),seed:Int=42)=OfflineAccount.fromSeed(ByteArray(32){seed.toByte()}).use{SignedTransfer(d,it.signOperation(d.content,PublicAccount(d.source.publicKey,d.source.address))).also{it.validate()}}
internal fun report(s:SignedTransfer,at:Long=TEST_TIME+1000,success:Boolean=true)=FinalReport(VerifiedNodeContext(CHAIN,"MAIN.5.1",at),at,RPC_ENDPOINT,s.draft.operationId,s.draft.source.address,s.draft.source.publicKey,s.signature,s.draft.content.hex(),s.binding,success)
internal fun resolution(s:SignedTransfer,success:Boolean=true):TrustedResolution {
    val a=report(s,TEST_TIME+1000,success);val b=report(s,TEST_TIME+2000,success)
    return TrustedResolution(TRUST_POLICY,TEST_TIME+2000,a,b,VerifiedNodeContext(CHAIN,"MAIN.5.1",TEST_TIME+2000))
}
internal class MemoryJournal:JournalPersistence {
    var bytes=JournalCodec.encode(JournalFile(UUID.randomUUID().toString()))
    var writes=0;var failWrite:Int?=null;var commitThenFail:Int?=null;var broken=false
    val states=mutableListOf<TransferState>()
    override fun read():JournalFile {if(broken)throw IOException("synthetic storage failure");return JournalCodec.decode(bytes.copyOf())}
    override fun write(file:JournalFile){writes++;if(writes==failWrite)throw IOException("synthetic precommit failure")
        bytes=JournalCodec.encode(file).copyOf();file.records.lastOrNull()?.let{states+=it.state}
        if(writes==commitThenFail)throw IOException("synthetic ambiguous commit")
    }
}
internal class TestAuth:OwnerAuthentication {
    var epoch=0;var time=0L;var denied:WalletError?=null;var intercept:(suspend(Action)->Unit)?=null
    val actions=mutableListOf<Action>();var wrongBinding=false
    override suspend fun approve(action:Action):Approval {
        actions+=action;denied?.let{throw WalletException(it)};val generation=epoch
        intercept?.invoke(action)
        return Approval(if(wrongBinding)action.copy(commitment="different")else action,Clock{time},time){generation==epoch}
    }
    override fun cancel(){epoch++}
}
internal class FakeFinality(private val clock:()->Long):FinalityReader {
    var observeCalls=0;var contextCalls=0
    var observation:(suspend(SignedTransfer,Int)->OperationObservation)?=null
    var context:(suspend()->VerifiedNodeContext)?=null
    override suspend fun observe(signed:SignedTransfer):OperationObservation{observeCalls++;return observation?.invoke(signed,observeCalls)?:OperationObservation(TransferState.UNKNOWN)}
    override suspend fun checkContext():VerifiedNodeContext{contextCalls++;return context?.invoke()?:VerifiedNodeContext(CHAIN,"MAIN.5.1",clock())}
    fun final(success:Boolean=true){observation={s,_->OperationObservation(if(success)TransferState.VERIFIED_SUCCESS else TransferState.VERIFIED_FAILURE,report(s,clock(),success))}}
}
internal class RecordingTransport:BroadcastTransport {
    override val enabled=true
    var calls=0;var callback:suspend(SignedTransfer)->BroadcastReply={BroadcastReply.Accepted(it.draft.operationId)}
    override suspend fun submit(signed:SignedTransfer):BroadcastReply{calls++;return callback(signed)}
}
internal class TransferHarness(val disk:MemoryJournal=MemoryJournal(),val broadcast:BroadcastTransport=RecordingTransport()) {
    var now=TEST_TIME;var snapshotValue=snapshot();var preflightCalls=0
    var preflightHook:(suspend(Int)->SendSnapshot)?=null
    val auth=TestAuth();val secrets=WalletRepositoryTest.Secrets();val catalog=WalletRepositoryTest.Disk();val ledger=TransferLedger(disk)
    val repository=WalletRepository(catalog,secrets,Randomness{ByteArray(32){42}},auth,removalGuard=ledger)
    lateinit var wallet:Wallet
    val finality=FakeFinality{now}
    val reader=PreflightReader{_,_->preflightCalls++;preflightHook?.invoke(preflightCalls)?:snapshotValue.copy(fetchedAt=now)}
    val coordinator=SendCoordinator(reader,ledger,finality,broadcast,{now},{now+=it})
    val signer=ProtectedWalletSigner(repository,auth){now}
    suspend fun start():TransferHarness{wallet=repository.create("PUBLIC NEVER FUND").selected!!;auth.actions.clear();coordinator.select(TransferSource.capture(wallet));coordinator.resume();coordinator.reload();return this}
    suspend fun prepare(){coordinator.prepare(recipient,"1");check(coordinator.state.value.review!=null){"Synthetic review did not prepare"}}
    suspend fun send(){prepare();coordinator.confirm(coordinator.state.value.review,signer)}
    fun record()=ledger.records().single()
}
