@file:Suppress("DEPRECATION")
package com.massivex.android

import android.content.*
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.massivex.android.transfer.*
import com.massivex.android.wallet.*
import com.massivex.massa.*
import kotlinx.coroutines.*
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** PUBLIC SYNTHETIC OFFLINE FIXTURES ONLY. Guarded task AVD; no real transport or owner catalog. */
internal abstract class SendRuntimeBase:InstrumentationTestCase(){
    protected lateinit var activity:MainActivity
    protected val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    protected lateinit var auth:AndroidAuthentication
    protected lateinit var secrets:AndroidSecrets
    protected lateinit var repo:WalletRepository
    protected lateinit var disk:AndroidTransferJournal
    protected lateinit var ledger:TransferLedger
    protected lateinit var folder:File
    protected var namespace=""
    protected val recipient=OfflineAccount.fromSeed(ByteArray(32){43}).use{it.publicAccount().address}
    protected var preserve=false
    override fun setUp(){
        super.setUp();assertEquals("com.massivex.android.dev",instrumentation.targetContext.packageName)
        assertEquals("1",shell("getprop ro.kernel.qemu").trim())
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.runOnMainSync{ViewModelProvider(activity)[ReadModel::class.java].reads.stop();ViewModelProvider(activity)[SendModel::class.java].background()}
    }
    protected fun storage(name:String="instrumentation-${UUID.randomUUID()}"){
        namespace=name;folder=File(activity.noBackupFilesDir,"send-test-$name").apply{mkdirs()}
        val context=object:ContextWrapper(activity){override fun getNoBackupFilesDir()=folder}
        disk=AndroidTransferJournal(context,name);ledger=TransferLedger(disk)
        secrets=AndroidSecrets(activity,"massivex.instrumentation.transfer.$name.")
        auth=AndroidAuthentication(activity,secrets)
        repo=WalletRepository(AtomicCatalog(context),secrets,Randomness{ByteArray(32){42}},auth,removalGuard=ledger)
    }
    protected fun snapshot()=SendSnapshot(System.currentTimeMillis(),CHAIN,"MAIN.5.1",100uL,10uL,MASAmount(10_000_000uL),MASAmount(10_000_000_000uL),MASAmount(10_000_000_000uL),MASAmount(2_000_000uL),MASAmount(2_000_000uL))
    protected fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList()else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
    protected fun shell(command:String):String=instrumentation.uiAutomation.executeShellCommand(command).use{fd->java.io.FileInputStream(fd.fileDescriptor).bufferedReader().use{it.readText()}}
    protected fun await(text:String){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(nodes(instrumentation.uiAutomation.rootInActiveWindow).any{it.text?.toString()==text})return;Thread.sleep(100)};fail("Expected safe native Send result unavailable")}
    protected fun click(text:String){
        repeat(32){i->
            val node=nodes(instrumentation.uiAutomation.rootInActiveWindow).lastOrNull{it.text?.toString()==text&&it.isVisibleToUser}
            if(node!=null){
                var n:AccessibilityNodeInfo=node;n.refresh()
                while(!n.isClickable&&n.parent!=null){n=n.parent;n.refresh()}
                if(n.isEnabled&&n.isClickable){n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);Thread.sleep(100);n.refresh();if(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(300);return}}
                Thread.sleep(150)
            }else shell(if(i<16)"input swipe 540 1900 540 650 300"else"input swipe 540 650 540 1900 300")
        };fail("Native Send action unavailable: $text")
    }

    protected fun enter(index:Int,text:String){val end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){val fields=nodes(instrumentation.uiAutomation.rootInActiveWindow).filter{it.isEditable||it.className?.toString()=="android.widget.EditText"};if(fields.size>index){assertTrue(fields[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)}));Thread.sleep(300);return};Thread.sleep(100)};fail("Native public entry unavailable")}
    protected fun credential(cancel:Boolean=false){
        val end=System.currentTimeMillis()+30000
        while(System.currentTimeMillis()<end){
            val all=nodes(instrumentation.uiAutomation.rootInActiveWindow)
            all.firstOrNull{it.text?.toString()?.contains("Use PIN",true)==true}?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val pin=all.firstOrNull{it.packageName?.toString() in setOf("com.android.systemui","com.android.settings")&&(it.viewIdResourceName?.contains("pinEntry")==true||it.isEditable)}
            if(pin!=null){Thread.sleep(300);if(cancel){repeat(4){if(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in setOf("com.android.systemui","com.android.settings")){shell("input keyevent 4");Thread.sleep(300)}}}else{pin.performAction(AccessibilityNodeInfo.ACTION_FOCUS);shell("input text 246810");shell("input keyevent 66")};return}
            Thread.sleep(150)
        }
        fail("Disposable device credential window unavailable")
    }
    protected fun <T> authorized(block:suspend()->T):T{
        val future=scope.async{block()};credential();val result=runBlocking{withTimeout(45000){future.await()}}
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end){if(activity.hasWindowFocus()&&instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==activity.packageName)break;Thread.sleep(100)}
        Thread.sleep(600);return result
    }
    protected fun coordinator(transport:BroadcastTransport,finality:FinalityReader=object:FinalityReader{
        override suspend fun observe(signed:SignedTransfer)=OperationObservation(TransferState.UNKNOWN)
        override suspend fun checkContext()=VerifiedNodeContext(CHAIN,"MAIN.5.1",System.currentTimeMillis())
    })=SendCoordinator(PreflightReader{_,_->snapshot()},ledger,finality,transport)
    protected fun finality()=object:FinalityReader{
        override suspend fun observe(signed:SignedTransfer):OperationObservation{val now=System.currentTimeMillis();val d=signed.draft;return OperationObservation(TransferState.VERIFIED_SUCCESS,FinalReport(VerifiedNodeContext(CHAIN,"MAIN.5.1",now),now,RPC_ENDPOINT,d.operationId,d.source.address,d.source.publicKey,signed.signature,d.content.hex(),signed.binding,true))}
        override suspend fun checkContext()=VerifiedNodeContext(CHAIN,"MAIN.5.1",System.currentTimeMillis())
    }
    protected fun prepare(c:SendCoordinator,wallet:Wallet){instrumentation.runOnMainSync{c.select(TransferSource.capture(wallet));c.resume()};runBlocking{c.prepare(recipient,"1")};assertNotNull(c.state.value.review)}
    override fun tearDown(){
        if(::auth.isInitialized)auth.cancel();scope.cancel()
        if(!preserve&&::folder.isInitialized){secrets.walletIds().forEach{secrets.delete(it)};KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(disk.alias)};folder.deleteRecursively()}
        if(::activity.isInitialized)instrumentation.runOnMainSync{activity.finish()}
        super.tearDown()
    }
}
internal class SafeSendRuntimeTest:SendRuntimeBase(){
    fun testNativeFormReviewAndSlideCannotBroadcast(){
        storage();val public=OfflineAccount.fromSeed(ByteArray(32){42}).use{it.publicAccount()};val wallet=Wallet(UUID.randomUUID().toString(),"PUBLIC SYNTHETIC TEST — NEVER FUND",public.address,public.publicKey,null)
        val c=coordinator(NoBroadcastTransport);var confirmations=0
        val impossible=object:TransferSigner{override suspend fun sign(draft:ReviewedTransfer):SignedTransfer=throw AssertionError("Preview requested a key");override fun cancel(){}}
        instrumentation.runOnMainSync{
            c.select(TransferSource.capture(wallet));c.resume()
            activity.setContent{MaterialTheme{val state=c.state.collectAsState();Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("PUBLIC SYNTHETIC TEST — NEVER FUND")
                SendScreen(wallet,state.value,{r,a->scope.launch{c.prepare(r,a)}},{r->scope.launch{c.maximum(r)}},{r->scope.launch{c.preview(r)}},{c.invalidate(keepPreview=true)},confirm={d->confirmations++;scope.launch{c.confirm(d,impossible)}})
            }}}
        }
        await(activity.getString(R.string.ui_recipient));enter(0,recipient);await(activity.getString(R.string.send_available,"10"))
        click(activity.getString(R.string.send_max));await("9.99");enter(1,"1,25");click(activity.getString(R.string.continue_action))
        await("1.26 MAS");await(shortPublic(recipient)+" ⌄")
        val slider=nodes(instrumentation.uiAutomation.rootInActiveWindow).first{it.contentDescription?.toString()==activity.getString(R.string.send_slide)}
        val bounds=Rect();slider.getBoundsInScreen(bounds);val y=bounds.centerY()
        shell("input tap ${bounds.right-8} $y");Thread.sleep(300);assertEquals(0,confirmations)
        shell("input swipe ${bounds.left+40} $y ${bounds.right-8} $y 900")
        val end=System.currentTimeMillis()+10000;while(c.state.value.error!=SendError.DISABLED&&System.currentTimeMillis()<end)Thread.sleep(100)
        assertEquals(1,confirmations);assertEquals(SendError.DISABLED,c.state.value.error);assertTrue(ledger.records().isEmpty());assertFalse(c.submissionEnabled)
        instrumentation.runOnMainSync{c.stop()}
    }
    fun testRealAuthorizationFakeSubmissionEncryptedJournalAndTamperBoundaries(){
        storage();val wallet=authorized{repo.create("PUBLIC SYNTHETIC NEVER FUND")}.selected!!;var calls=0
        val fake=object:BroadcastTransport{override val enabled=true;override suspend fun submit(signed:SignedTransfer):BroadcastReply{calls++;val durable=TransferLedger(AndroidTransferJournal(object:ContextWrapper(activity){override fun getNoBackupFilesDir()=folder},namespace)).records().single();assertEquals(TransferState.SUBMIT_ATTEMPT,durable.state);assertEquals(signed.binding,durable.signed.binding);return BroadcastReply.Accepted(signed.draft.operationId)}}
        val c=coordinator(fake,finality());prepare(c,wallet);authorized{c.confirm(c.state.value.review,ProtectedWalletSigner(repo,auth))}
        assertEquals(1,calls);assertEquals(TransferState.PENDING,ledger.records().single().state);assertNotNull(TransferLedger(disk).pending(TransferSource.capture(wallet)))
        val seedInfo=secrets.keyInfo(wallet.id);assertTrue(seedInfo.isUserAuthenticationRequired);assertEquals(5,seedInfo.userAuthenticationValidityDurationSeconds)
        val original=disk.path.readBytes();val string=original.toString(Charsets.ISO_8859_1);assertFalse(string.contains(wallet.address));assertFalse(string.contains(ledger.records().single().signed.signature))
        val tampered=original.copyOf();tampered[tampered.lastIndex]=(tampered.last().toInt() xor 1).toByte();disk.path.writeBytes(tampered)
        try{disk.read();fail("Tampered journal accepted")}catch(_:SendFailure){}
        disk.path.writeBytes(original)
        // Header installation identity is authenticated too, not merely plaintext routing metadata.
        val wrongAad=original.copyOf();wrongAad[10]=if(wrongAad[10]=='a'.code.toByte())'b'.code.toByte()else'a'.code.toByte();disk.path.writeBytes(wrongAad)
        try{disk.read();fail("Substituted journal identity accepted")}catch(_:SendFailure){}
        val badVersion=original.copyOf();badVersion[7]=2;disk.path.writeBytes(badVersion);try{disk.read();fail("Unsupported journal version accepted")}catch(_:SendFailure){}
        disk.path.writeBytes(original);runBlocking{c.reconcile(ledger.records().single().operationId)}
        assertEquals(TransferState.VERIFIED_SUCCESS,TransferLedger(disk).records().single().state);assertNull(ledger.pending(TransferSource.capture(wallet)))
        val completed=disk.path.readBytes();assertFalse(original.contentEquals(completed));assertFalse(original.copyOfRange(46,58).contentEquals(completed.copyOfRange(46,58)))
        assertNull(KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey(disk.alias,null).encoded)
        disk.path.delete();try{disk.read();fail("Missing initialized journal became empty")}catch(_:SendFailure){};disk.path.writeBytes(completed)
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(disk.alias)}
        try{disk.read();fail("Missing journal key silently replaced")}catch(_:SendFailure){}
    }
    fun testActualCredentialCancellationLeavesNoIntent(){
        storage();val wallet=authorized{repo.create("PUBLIC SYNTHETIC NEVER FUND")}.selected!!;var calls=0
        val fake=object:BroadcastTransport{override val enabled=true;override suspend fun submit(signed:SignedTransfer):BroadcastReply{calls++;return BroadcastReply.Missing}}
        val c=coordinator(fake);prepare(c,wallet)
        val future=scope.async{c.confirm(c.state.value.review,ProtectedWalletSigner(repo,auth))};credential(true);runBlocking{withTimeout(30000){future.await()}}
        assertEquals(0,calls);assertTrue(ledger.records().isEmpty());assertEquals(SendError.CANCELLED,c.state.value.error)
    }
}
