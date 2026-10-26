@file:Suppress("DEPRECATION")
package com.massivex.android

import android.content.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
import com.massivex.android.network.*
import com.massivex.android.presentation.localizedDecimal
import com.massivex.android.receive.ReceiveQr
import com.massivex.android.wallet.*
import com.massivex.massa.MASAmount
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.*
import java.util.UUID

/** Emulator-only via guarded runner. Public fixtures; no key access, destructive device policy, or secret capture. */
class ReadOnlyRuntimeTest : InstrumentationTestCase() {
    private val a="AU1rqxSMewKgJU2m5orM2HFuMg7ioEFiECuhX1sSken92VxUBfQy"
    private val b="AU12MBEEKVAFZhxYzeyNYpAAcKEu667KKjHBos9i65htLwPkHyQG4"
    private lateinit var repository:WalletRepository
    private var activity:MainActivity?=null
    private var original:String?=null
    private val created=mutableListOf<String>()
    override fun setUp(){
        super.setUp()
        val target=instrumentation.targetContext
        val noSecrets=object:OwnerAuthentication{
            override suspend fun approve(action:Action):Approval=throw AssertionError("Read-only test requested secret authorization")
            override fun cancel(){}
        }
        repository=WalletRepository(AtomicCatalog(target),AndroidSecrets(target),Randomness{throw AssertionError("No seeds in read tests")},noSecrets)
        original=repository.load().selectedId
        val existing=repository.load().wallets.firstOrNull{it.address==a}
        val id=existing?.id?:repository.addWatch(a,"Public SDK fixture").selectedId!!.also{created+=it}
        repository.select(id)
    }
    override fun tearDown(){
        activity?.let{instrumentation.runOnMainSync{it.finish()}}
        runBlocking{for(id in created){repository.select(id);repository.remove(id,true)}}
        original?.let{id->if(repository.load().wallets.any{it.id==id})repository.select(id)}
        super.tearDown()
    }
    private fun launch():MainActivity{
        val target=instrumentation.targetContext
        return (instrumentation.startActivitySync(Intent(target,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity).also{activity=it;instrumentation.runOnMainSync{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
    }
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null)emptyList()else listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
    private fun shell(command:String){instrumentation.uiAutomation.executeShellCommand(command).use{fd->java.io.FileInputStream(fd.fileDescriptor).use{it.readBytes()}}}
    private fun click(label:String){
        repeat(14){ attempt ->
            val found=nodes(instrumentation.uiAutomation.rootInActiveWindow).lastOrNull{(it.text?.toString()==label||it.contentDescription?.toString()==label)&&it.isVisibleToUser&&it.isEnabled}
            if(found!=null){var n:AccessibilityNodeInfo=found;n.refresh();while(!n.isClickable){n=n.parent?:break;n.refresh()};n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);Thread.sleep(150);if(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(300);return}}
            shell(if(attempt<7) "input swipe 540 1850 540 650 300" else "input swipe 540 650 540 1850 300")
        };fail("Expected public UI action unavailable")
    }
    private fun awaitSelected(label:String){
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end){
            nodes(instrumentation.uiAutomation.rootInActiveWindow).filter{it.isVisibleToUser&&(it.text?.toString()==label||it.contentDescription?.toString()==label)}.forEach{candidate->
                var n:AccessibilityNodeInfo?=candidate
                repeat(5){val current=n;if(current!=null){current.refresh();if(current.isSelected)return;n=current.parent}}
            }
            Thread.sleep(100)
        }
        fail("Native selected state did not render")
    }
    private fun awaitText(text:String){
        val deadline=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<deadline){if(nodes(instrumentation.uiAutomation.rootInActiveWindow).any{it.text?.toString()==text||it.contentDescription?.toString()==text})return;Thread.sleep(150)}
        fail("Expected public display unavailable: $text")
    }
    private fun awaitScrollableText(text:String){
        repeat(6){
            if(nodes(instrumentation.uiAutomation.rootInActiveWindow).any{it.text?.toString()==text||it.contentDescription?.toString()==text})return
            shell("input swipe 540 1800 540 700 350");Thread.sleep(300)
        };awaitText(text)
    }
    private fun awaitPackage(allowed:Set<String>){
        val deadline=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<deadline){
            if(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in allowed)return
            Thread.sleep(100)
        }
        fail("Expected native window was not active")
    }
    fun testNativeReceiveCopyAndShare(){
        val app=launch()
        val reads=ViewModelProvider(app)[ReadModel::class.java].reads
        val deadline=System.currentTimeMillis()+45000
        while(System.currentTimeMillis()<deadline && (reads.state.value.balanceLoading || reads.state.value.address!=a)) Thread.sleep(200)
        val live=reads.state.value
        assertEquals(a,live.address)
        if(live.balance!=null){assertEquals(a,live.balance!!.address);click(app.getString(R.string.ui_balance_info));awaitText(app.getString(R.string.confirmed_exact,live.balance!!.confirmed.text));awaitText(app.getString(R.string.candidate_value,live.balance!!.candidate.text));click(app.getString(R.string.ui_done))}
        else{assertNotNull(live.balanceError);awaitText(app.getString(R.string.read_unavailable))}
        android.util.Log.i("MXA003LiveNative","balance="+if(live.balance!=null)"PASS" else "UNAVAILABLE ${live.balanceError}")
        click(app.getString(R.string.receive));awaitText(a)
        assertTrue(nodes(instrumentation.uiAutomation.rootInActiveWindow).any{it.contentDescription?.toString()==app.getString(R.string.qr_description)})
        val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(256,256,ReceiveQr.pixels(a)))));assertEquals(a,decoded.text)
        click(app.getString(R.string.copy_address));awaitText(app.getString(R.string.address_copied))
        var ownClip:String?=null
        instrumentation.runOnMainSync{val board=app.getSystemService(ClipboardManager::class.java);if(board.primaryClipDescription?.label?.toString()==app.getString(R.string.public_data))ownClip=board.primaryClip?.getItemAt(0)?.text?.toString()}
        assertEquals(a,ownClip)
        val intent=publicAddressShare(a);assertEquals(Intent.ACTION_SEND,intent.action);assertEquals("text/plain",intent.type);assertEquals(a,intent.getStringExtra(Intent.EXTRA_TEXT));assertEquals(setOf(Intent.EXTRA_TEXT),intent.extras!!.keySet())
        click(app.getString(R.string.share_address))
        try{
            awaitPackage(setOf("android","com.android.intentresolver"))
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in setOf("android","com.android.intentresolver"))
        }finally{
            shell("input keyevent 4")
            awaitPackage(setOf(instrumentation.targetContext.packageName))
        }
        click(app.getString(R.string.back));click(app.getString(R.string.activity));awaitSelected(app.getString(R.string.activity))
        click(app.getString(R.string.filter_received));assertEquals(ActivityFilter.RECEIVED,reads.state.value.filter);awaitSelected(app.getString(R.string.filter_received))
        click(app.getString(R.string.filter_asc));assertEquals(ActivityFilter.ASC,reads.state.value.filter);awaitSelected(app.getString(R.string.filter_asc))
        click(app.getString(R.string.activity_info));awaitText(app.getString(R.string.ui_activity_details));assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any{it.text?.toString()==app.getString(R.string.ui_balance_info)})
        val h=reads.state.value
        android.util.Log.i("MXA003LiveNative","history="+if(h.historyLoaded)"PASS partial=${h.partial}" else "UNAVAILABLE ${h.historyError}")
        click(app.getString(R.string.ui_done));click(app.getString(R.string.settings));click(app.getString(R.string.currency_title))
        awaitText(app.getString(R.string.currency_title))
        val codes=reads.state.value.currencies
        if(codes.isNotEmpty()){
            val wanted=if(reads.state.value.currency!="EUR"&&"EUR" in codes)"EUR" else "USD"
            var field:AccessibilityNodeInfo?=null
            val until=System.currentTimeMillis()+10000
            while(field==null&&System.currentTimeMillis()<until){
                field=nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull{it.isEditable||it.className?.toString()=="android.widget.EditText"}
                if(field==null)Thread.sleep(100)
            }
            assertNotNull("Currency search field did not become available",field)
            assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,wanted)}))
            var option:AccessibilityNodeInfo?=null
            val optionDeadline=System.currentTimeMillis()+10000
            while(option==null&&System.currentTimeMillis()<optionDeadline){
                option=nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull{it.text?.toString()?.startsWith("$wanted ·")==true}
                if(option==null)Thread.sleep(100)
            }
            assertNotNull("Requested supported currency was not displayed",option);click(option!!.text.toString())
            assertEquals(wanted,app.getSharedPreferences("public-display-v1",Context.MODE_PRIVATE).getString("currency",null))
            click(app.getString(R.string.back))
            if(!reads.state.value.fiat){
                val balance=reads.state.value.balance!!
                click(localizedDecimal(java.math.BigDecimal(balance.confirmed.nano.toString()).movePointLeft(9).setScale(2,java.math.RoundingMode.HALF_EVEN).toPlainString()+" MAS",app.resources.configuration.locales[0]))
            }
            assertTrue(app.getSharedPreferences("public-display-v1",Context.MODE_PRIVATE).getBoolean("fiat",false))
            val quoteDeadline=System.currentTimeMillis()+35000
            while(reads.state.value.quoteLoading&&System.currentTimeMillis()<quoteDeadline)Thread.sleep(150)
            assertFalse("Foreground quote request did not settle",reads.state.value.quoteLoading)
            // Return to the headline after scrolling down to the fiat toggle; keep the exact UI-value assertion.
            repeat(2){shell("input swipe 540 650 540 1850 400");Thread.sleep(200)}
            val current=reads.state.value;val quote=current.quoteUsable
            if(quote!=null&&current.balance!=null)awaitText(localizedDecimal(quote.value(current.balance!!.confirmed.nano)+" "+wanted,app.resources.configuration.locales[0]))
            else if(current.balance!=null){assertNull(current.quoteUsable);assertTrue(current.fiat)}
        }else awaitText(app.getString(R.string.currencies_unavailable))
        val q=reads.state.value
        android.util.Log.i("MXA003LiveNative","quote="+if(q.quoteUsable!=null)"PASS currency=${q.currency}" else "UNAVAILABLE ${q.quoteError}")
    }
    fun testNativeSelectionNeverShowsPreviousWalletBalance(){
        val app=launch();val pending=CompletableDeferred<BalanceRead>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val first=repository.load().selected!!
        val second=first.copy(id=UUID.randomUUID().toString(),name="Second public fixture",address=b)
        val selected=androidx.compose.runtime.mutableStateOf(first)
        val prefs=object:DisplayPreferences{override var currency="USD";override var fiat=false;override var filter=ActivityFilter.ALL}
        fun balance(address:String,value:ULong)=BalanceRead(address,MASAmount(value),MASAmount(value),NodeStatus("MAIN.5.1",1),System.currentTimeMillis())
        val reads=ReadCoordinator(scope,object:BalanceReader{override suspend fun balance(address:String)=if(address==a)balance(address,1230000000uL)else pending.await()},object:HistoryReader{override suspend fun page(address:String,cursor:HistoryCursor?)=HistoryPage(emptyList(),null)},object:MarketReader{override suspend fun currencies(now:Long)=emptyList<String>();override suspend fun quote(currency:String,now:Long):MarketQuote=throw ReadFailure(ReadError.UNSUPPORTED)},prefs)
        try{
            instrumentation.runOnMainSync{
                ViewModelProvider(app)[ReadModel::class.java].reads.stop()
                reads.select(first.id,a);reads.resume()
                app.setContent{MaterialTheme{val state=reads.state.collectAsState();ColumnForReadTest{ReadSummary(selected.value,state.value,reads,{})}}}
            }
            awaitText(localizedDecimal("1.23 MAS",app.resources.configuration.locales[0]))
            instrumentation.runOnMainSync{selected.value=second} // Deliberately render BEFORE the selection effect.
            awaitText("Second public fixture")
            assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any{it.text?.toString()==localizedDecimal("1.23 MAS",app.resources.configuration.locales[0])})
            Thread.sleep(2100)
            instrumentation.runOnMainSync{reads.select(second.id,b)}
            pending.complete(balance(b,2000000000uL));awaitText(localizedDecimal("2.00 MAS",app.resources.configuration.locales[0]))
            assertEquals(b,reads.state.value.balance!!.address)
        }finally{instrumentation.runOnMainSync{reads.stop();scope.cancel()}}
    }
    fun testActualPublicProvidersReadOnly(){
        val real=PlatformReadTransport();var calls=0
        val bounded=ReadTransport{request->calls++;if(calls>10)throw AssertionError("Live read budget exceeded");real.execute(request)}
        fun report(label:String,value:String){android.util.Log.i("MXA003Live","$label=$value")}
        runBlocking{
            try{val value=MainnetReads(bounded).balance(a);assertEquals(a,value.address);report("balance","PASS version=${value.node.version} final=${value.confirmed.text} candidate=${value.candidate.text}")}
            catch(e:ReadFailure){report("balance","UNAVAILABLE ${e.reason}")}
            try{val page=ExplorerReads(bounded).page(a,null);report("history","PASS count=${page.operations.size} partial=${page.partial}")}
            catch(e:ReadFailure){report("history","UNAVAILABLE ${e.reason}")}
            try{val quote=PublicMarketReads(bounded).quote("USD",System.currentTimeMillis());assertTrue(quote.usable(System.currentTimeMillis()));report("quote","PASS source=${quote.source}")}
            catch(e:ReadFailure){report("quote","UNAVAILABLE ${e.reason}")}
        }
        assertTrue(calls in 1..10);report("requestCount",calls.toString())
    }
}
@androidx.compose.runtime.Composable
private fun ColumnForReadTest(content:@androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.()->Unit){
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        androidx.compose.material3.Text("MOCK UI TEST — synthetic public fixtures")
        content()
    }
}
