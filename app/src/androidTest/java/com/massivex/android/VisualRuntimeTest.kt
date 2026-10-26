@file:Suppress("DEPRECATION")
package com.massivex.android

import android.content.Intent
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModelProvider
import com.massivex.android.network.*
import com.massivex.android.presentation.*
import com.massivex.android.wallet.*
import com.massivex.massa.*
import kotlinx.coroutines.*
import java.util.UUID

/** Public never-fund fixtures only. Works on the explicitly consented dev phone or guarded task AVD. No screenshots. */
class VisualRuntimeTest:InstrumentationTestCase(){
    private val ctx get()=instrumentation.targetContext
    private lateinit var repo:WalletRepository
    private var original:String?=null
    private val created=mutableListOf<String>()
    private var app:MainActivity?=null
    private val a="AU1rqxSMewKgJU2m5orM2HFuMg7ioEFiECuhX1sSken92VxUBfQy"
    override fun setUp(){super.setUp();assertEquals("com.massivex.android.dev",ctx.packageName)
        repo=WalletRepository(AtomicCatalog(ctx),AndroidSecrets(ctx),Randomness{throw AssertionError("No secret creation")},object:OwnerAuthentication{override suspend fun approve(action:Action):Approval=throw AssertionError("No secret authorization");override fun cancel(){}})
        original=repo.load().selectedId
        val existing=repo.load().wallets.firstOrNull{it.address==a};if(existing!=null){assertTrue(existing.watchOnly);repo.select(existing.id)}else{val w=repo.addWatch(a,"PUBLIC visual fixture").selected!!;created+=w.id}
    }
    override fun tearDown(){app?.let{instrumentation.runOnMainSync{it.finish()}};runBlocking{created.forEach{repo.select(it);repo.remove(it,true)}};original?.let{id->if(repo.load().wallets.any{it.id==id})repo.select(id)};super.tearDown()}
    private fun launch():MainActivity=(instrumentation.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))as MainActivity).also{app=it;instrumentation.runOnMainSync{it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)};Thread.sleep(900)}
    private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList()else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
    private fun shell(command:String){instrumentation.uiAutomation.executeShellCommand(command).use{fd->java.io.FileInputStream(fd.fileDescriptor).use{it.readBytes()}}}
    private fun visible(label:String)=nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull{it.isVisibleToUser&&(it.text?.toString()==label||it.contentDescription?.toString()==label)}
    private fun click(label:String){repeat(16){i->val n=visible(label);

if(n!=null){var target:AccessibilityNodeInfo=n;target.refresh();while(!target.isClickable){target=target.parent?:break;target.refresh()};target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id);Thread.sleep(150)
            if(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){instrumentation.waitForIdleSync();Thread.sleep(350);return}}
        shell(if(i<8)"input swipe 540 1800 540 900 300"else"input swipe 540 900 540 1800 300")};fail("Public visual action unavailable: $label")}
    private fun await(label:String):AccessibilityNodeInfo{val end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){visible(label)?.let{return it};Thread.sleep(100)};throw AssertionError("Public visual element unavailable: $label")}
    private fun awaitSelected(label:String){
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end){
            nodes(instrumentation.uiAutomation.rootInActiveWindow).filter{it.isVisibleToUser&&(it.text?.toString()==label||it.contentDescription?.toString()==label)}.forEach{candidate->
                var n:AccessibilityNodeInfo?=candidate
                repeat(5){val current=n;if(current!=null){current.refresh();if(current.isSelected)return;n=current.parent}}
            }
            Thread.sleep(100)
        }
        fail("Native selected state did not render: $label")
    }
    fun testDashboardTabHierarchyAndRelocatedManagement(){
        val activity=launch();val density=activity.resources.displayMetrics.density
        val balance=await(activity.getString(R.string.ui_total_balance));val assets=await(activity.getString(R.string.ui_assets));val recent=await(activity.getString(R.string.recent_activity))
        fun top(n:AccessibilityNodeInfo)=Rect().also{n.getBoundsInScreen(it)}.top
        assertTrue(top(balance)<top(assets));assertTrue(top(assets)<top(recent));assertTrue(top(recent)<activity.resources.displayMetrics.heightPixels-64*density)
        assertNull(visible(activity.getString(R.string.back)));assertNull(visible(activity.getString(R.string.remove)))
        click(activity.getString(R.string.activity));awaitSelected(activity.getString(R.string.activity));await(activity.getString(R.string.filter_all));assertNull(visible(activity.getString(R.string.back)));assertNull(visible(activity.getString(R.string.app_name)))
        val reads=ViewModelProvider(activity)[ReadModel::class.java].reads
        click(activity.getString(R.string.filter_sent));assertEquals(ActivityFilter.SENT,reads.state.value.filter);awaitSelected(activity.getString(R.string.filter_sent))
        click(activity.getString(R.string.filter_received));assertEquals(ActivityFilter.RECEIVED,reads.state.value.filter);awaitSelected(activity.getString(R.string.filter_received))
        click(activity.getString(R.string.filter_asc));assertEquals(ActivityFilter.ASC,reads.state.value.filter);awaitSelected(activity.getString(R.string.filter_asc))
        click(activity.getString(R.string.settings));await(activity.getString(R.string.ui_manage));assertNull(visible(activity.getString(R.string.back)))
        click(activity.getString(R.string.ui_manage));await(activity.getString(R.string.rename));await(activity.getString(R.string.remove));click(activity.getString(R.string.ui_done))
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE==0)
    }
    fun testNativeReceiveLayoutAndPublicOnlyShare(){
        val activity=launch();click(activity.getString(R.string.receive));val qr=await(activity.getString(R.string.qr_description));val r=Rect();qr.getBoundsInScreen(r)
        assertTrue(r.width()>160*activity.resources.displayMetrics.density);assertTrue(kotlin.math.abs(r.width()-r.height())<4)
        await(a);await(activity.getString(R.string.ui_mainnet));click(activity.getString(R.string.copy_address));await(activity.getString(R.string.address_copied))
        val intent=publicAddressShare(a);assertEquals(setOf(Intent.EXTRA_TEXT),intent.extras!!.keySet());assertEquals(a,intent.getStringExtra(Intent.EXTRA_TEXT));assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE==0)
    }
    fun testSendPanelInputsAndDisabledPreview(){
        val activity=launch();var callbacks=0
        val public=OfflineAccount.fromSeed(ByteArray(32){42}).use{it.publicAccount()}
        val wallet=Wallet(UUID.randomUUID().toString(),"PUBLIC NEVER FUND",public.address,public.publicKey,null)
        val source=com.massivex.android.transfer.TransferSource.capture(wallet)
        instrumentation.runOnMainSync{ViewModelProvider(activity)[ReadModel::class.java].reads.stop();ViewModelProvider(activity)[SendModel::class.java].background();activity.setContent{WalletTheme(Presentation(accent=Accent.MIDNIGHT)){androidx.compose.material3.Surface{Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)){SendScreen(wallet,com.massivex.android.transfer.SendUi(source=source),{_,_->callbacks++},{callbacks++},{callbacks++},{},{callbacks++})}}}}}
        await(activity.getString(R.string.continue_action));await(activity.getString(R.string.ui_recipient));await(activity.getString(R.string.ui_amount))
        assertEquals(2,nodes(instrumentation.uiAutomation.rootInActiveWindow).count{it.isEditable})
        assertEquals(0,callbacks);assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE==0)
    }
    fun testSixLocalesTwoFontScalesWithPopulatedPublicPresentation(){
        val activity=launch();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val prefs=object:DisplayPreferences{override var currency="USD";override var fiat=false;override var filter=ActivityFilter.ALL}
        val reads=ReadCoordinator(scope,object:BalanceReader{override suspend fun balance(address:String):BalanceRead=throw AssertionError("No fixture network")},object:HistoryReader{override suspend fun page(address:String,cursor:HistoryCursor?):HistoryPage=throw AssertionError("No fixture network")},object:MarketReader{override suspend fun currencies(now:Long)=emptyList<String>();override suspend fun quote(currency:String,now:Long):MarketQuote=throw AssertionError("No fixture network")},prefs)
        val wallet=repo.load().selected!!;val now=System.currentTimeMillis()
        val id=MassaCodec.encode(ByteArray(32){7},"O")
        val state=ReadUi(walletId=wallet.id,address=a,balance=BalanceRead(a,MASAmount(1230000000uL),MASAmount(1230000000uL),NodeStatus("MAIN.5.1",1),now),historyLoaded=true,operations=(0..7).map{PublicOperation(HistoryIdentity(id+"_"+it,id,false),"Transaction",a,a,MASAmount(1000000uL),MASAmount(10000uL),now,"Final",true)},now=now)
        try{instrumentation.runOnMainSync{ViewModelProvider(activity)[ReadModel::class.java].reads.stop();ViewModelProvider(activity)[SendModel::class.java].background()}
            for(tag in PresentationPolicy.languages)for(scale in listOf(1f,1.6f)){
                val localized=ctx.createConfigurationContext(android.content.res.Configuration(ctx.resources.configuration).apply{setLocales(android.os.LocaleList.forLanguageTags(tag))})
                instrumentation.runOnMainSync{activity.setContent{CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides localized.resources.configuration,LocalDensity provides Density(activity.resources.displayMetrics.density,scale)){
                    WalletTheme(Presentation(accent=Accent.MIDNIGHT)){androidx.compose.material3.Surface{Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)){ReadSummary(wallet,state,reads,{});RecentActivity(state,reads,{})}}}
                }}}
                instrumentation.waitForIdleSync();Thread.sleep(100)
                await(localized.getString(R.string.ui_total_balance))
                val value=localizedDecimal("1.23 MAS",java.util.Locale.forLanguageTag(tag))
                val balanceNode=await(value)
                assertTrue("Balance accessibility description must retain the visible value",balanceNode.contentDescription.isNullOrEmpty()||balanceNode.contentDescription.toString().contains(value))
                val root=instrumentation.uiAutomation.rootInActiveWindow;assertNotNull(root)
                nodes(root).filter{it.isVisibleToUser&&it.isClickable}.forEach{val r=Rect();it.getBoundsInScreen(r);assertTrue("Control wider than viewport",r.width()<=activity.resources.displayMetrics.widthPixels)}
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE==0)
            }
        }finally{scope.cancel()}
    }
}
