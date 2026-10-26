@file:Suppress("DEPRECATION")
package com.massivex.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.test.InstrumentationTestCase
import android.view.Surface
import android.view.WindowManager
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
import com.massivex.android.presentation.*
import com.massivex.android.transfer.*
import com.massivex.android.wallet.Wallet
import com.massivex.massa.*
import java.util.UUID

/** In-memory PUBLIC never-fund UI only; no signing, HTTP transport, authorization or notification posting. */
class Ui02RuntimeTest:InstrumentationTestCase(){
    private lateinit var activity:MainActivity
    private val ctx get()=instrumentation.targetContext
    private val public=OfflineAccount.fromSeed(ByteArray(32){42}).use{it.publicAccount()}
    private val recipient=OfflineAccount.fromSeed(ByteArray(32){43}).use{it.publicAccount().address}
    private val wallet=Wallet(UUID.randomUUID().toString(),"PUBLIC TEST — NEVER FUND",public.address,public.publicKey,null)
    override fun setUp(){super.setUp();assertEquals("com.massivex.android.dev",ctx.packageName)
        activity=instrumentation.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))as MainActivity
        instrumentation.runOnMainSync{ViewModelProvider(activity)[ReadModel::class.java].reads.stop();ViewModelProvider(activity)[SendModel::class.java].background();activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    }
    override fun tearDown(){instrumentation.runOnMainSync{activity.finish()};super.tearDown()}
    private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList()else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
    private fun await(label:String):AccessibilityNodeInfo{val end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull{it.isVisibleToUser&&(it.text?.toString()==label||it.text?.toString()==label+" ✓"||it.contentDescription?.toString()==label)}?.let{return it};Thread.sleep(100)};throw AssertionError("Missing public UI: $label")}
    private fun edit(index:Int,text:String){val node=nodes(instrumentation.uiAutomation.rootInActiveWindow).filter{it.isEditable}[index];assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)}));Thread.sleep(200)}
    private fun click(label:String){
        repeat(20){i->
            var node=nodes(instrumentation.uiAutomation.rootInActiveWindow).lastOrNull{it.isVisibleToUser&&(it.text?.toString()==label||it.text?.toString()==label+" ✓"||it.contentDescription?.toString()==label)}
            if(node!=null){node.refresh();while(!node!!.isClickable&&node!!.parent!=null){node=node!!.parent;node!!.refresh()};if(node!!.isClickable&&node!!.isEnabled&&node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(250);return}}
            instrumentation.uiAutomation.executeShellCommand(if(i<10)"input swipe 540 1800 540 700 300"else"input swipe 540 700 540 1800 300").use{fd->java.io.FileInputStream(fd.fileDescriptor).use{it.readBytes()}}
        };fail("Public UI action unavailable: $label")
    }
    private fun fixture(content:@Composable ()->Unit){instrumentation.runOnMainSync{activity.setContent{WalletTheme(Presentation(accent=Accent.MIDNIGHT)){Surface(color=MaterialTheme.colorScheme.background){Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){Text("PUBLIC SYNTHETIC PREVIEW — NEVER FUND");content()}}}}};instrumentation.waitForIdleSync()}
    fun testAutomaticPreflightCommaContinueAndUnavailableForm(){
        var previews=0;var continued:String?=null
        fixture{
            var state by remember{mutableStateOf(SendUi(source=TransferSource.capture(wallet)))}
            SendScreen(wallet,state,{_,a->continued=a},{},{r->previews++;val now=System.currentTimeMillis();state=state.copy(previewRecipient=r,preview=SendSnapshot(now,CHAIN,"MAIN.5.1",100uL,10uL,MASAmount(10_000_000uL),MASAmount(1_000_000_000uL),MASAmount(1_000_000_000uL),MASAmount(1uL),MASAmount(1uL)))},{},{},balance="1")
        }
        await(ctx.getString(R.string.continue_action));assertEquals(0,previews)
        edit(0,recipient);await(ctx.getString(R.string.send_available,"1"));Thread.sleep(600);assertEquals(1,previews)
        edit(1,"0,1");await("0.1");click(ctx.getString(R.string.continue_action));assertEquals("0.1",continued)
        edit(1,"0,0000000001");continued=null
        var disabled=false
        repeat(20){var node:AccessibilityNodeInfo?=await(ctx.getString(R.string.continue_action));repeat(5){val current=node;if(current!=null){current.refresh();if(!current.isEnabled)disabled=true;node=current.parent}};if(!disabled)Thread.sleep(100)}
        assertTrue("Malformed money must disable the rendered Continue control",disabled);assertNull(continued)
    }
    fun testPublicCopyRestoresAfterTwoSeconds(){var copied:String?=null;fixture{PublicCopy(recipient,{copied=it})};click(recipient);await(ctx.getString(R.string.copied_public));assertEquals(recipient,copied);Thread.sleep(2200);await(recipient)}
    fun testPortraitSensorRequestPreservesForm(){
        fixture{SendScreen(wallet,SendUi(source=TransferSource.capture(wallet)),{_,_->},{},{},{},{})}
        await(ctx.getString(R.string.continue_action));edit(1,"0,1")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,activity.requestedOrientation)
        if(android.os.Build.HARDWARE!="ranchu"&&android.os.Build.HARDWARE!="goldfish")return // Never change personal-phone display settings.
        try{instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);Thread.sleep(800);assertEquals(Configuration.ORIENTATION_PORTRAIT,activity.resources.configuration.orientation);await("0.1");assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE==0)}finally{instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)}
    }
    fun testProtectedPeriodicJobConfigurationAndDefaultOff(){
        val component=android.content.ComponentName(ctx,com.massivex.android.notifications.BalanceCheckJob::class.java)
        val job=android.app.job.JobInfo.Builder(1234,component).setPeriodic(15*60*1000L).setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build()
        assertEquals(900_000L,job.intervalMillis);assertTrue(job.isPersisted);assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY,job.networkType)
        val service=ctx.packageManager.getServiceInfo(component,0);assertEquals("android.permission.BIND_JOB_SERVICE",service.permission)
        assertFalse(ctx.getSharedPreferences("background-balances-v1",0).getBoolean("enabled",false))
        assertNull(ctx.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(com.massivex.android.notifications.BalanceNotifications.JOB))
    }
    fun testRenderPublicComposeFixtureWithoutChangingCaptureProtection(){
        if(android.os.Build.HARDWARE!="ranchu")return
        val now=System.currentTimeMillis()
        val snapshot=SendSnapshot(now,CHAIN,"MAIN.5.1",100uL,10uL,MASAmount(10_000_000uL),MASAmount(1_000_000_000uL),MASAmount(1_000_000_000uL),MASAmount(1uL),MASAmount(1uL))
        val review=ReviewedTransfer.make(TransferSource.capture(wallet),recipient,"0.1",snapshot,now)
        for((name,state)in listOf("send" to SendUi(source=review.source),"review" to SendUi(source=review.source,review=review,phase=SendPhase.REVIEW))){
            fixture{SendScreen(wallet,state,{_,_->},{},{},{},{},balance="1")}
            await("PUBLIC SYNTHETIC PREVIEW — NEVER FUND");Thread.sleep(400)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE==0)
            val bitmap=instrumentation.uiAutomation.takeScreenshot()
            assertNotNull("Public app screenshots must be available",bitmap)
            java.io.File(ctx.cacheDir,"ui02-public-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }

    fun testSettingsMenusAndSecondaryNativeCards(){
        val store=PresentationStore(activity);val original=store.state.value
        try{
            fixture{val p=store.state.collectAsState();SettingsScreen(p.value,store,{},{},{},"USD")}
            await(ctx.getString(R.string.settings));click(ctx.getString(R.string.accent_color));await(ctx.getString(R.string.accent_midnight));click(ctx.getString(R.string.accent_midnight))
            assertEquals(Accent.MIDNIGHT,store.state.value.accent)
            assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any{it.isVisibleToUser&&it.text?.toString()==ctx.getString(R.string.ui_done)})
            click(ctx.getString(R.string.appearance));click(ctx.getString(R.string.appearance_light));assertEquals(Appearance.LIGHT,store.state.value.appearance)
            assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any{it.isVisibleToUser&&it.text?.toString()==ctx.getString(R.string.ui_done)})
            click(ctx.getString(R.string.ecosystem));await(ctx.getString(R.string.link_explorer));await(ctx.getString(R.string.link_dusa));click(ctx.getString(R.string.ui_done))
            click(ctx.getString(R.string.buy_mas));await(ctx.getString(R.string.link_bitget));await(ctx.getString(R.string.link_mexc));click(ctx.getString(R.string.ui_done))
            click(ctx.getString(R.string.ui_connection));await("https://mainnet.massa.net/api/v2");await(ctx.getString(R.string.ui_only_network));click(ctx.getString(R.string.ui_done))
        }finally{instrumentation.runOnMainSync{store.accent(original.accent);store.appearance(original.appearance)}}
    }

    fun testYamlDocumentReturnKeepsOnlyNewFileAndClearsPassword(){
        if(android.os.Build.HARDWARE!="ranchu")return
        // The runner stages one PUBLIC encrypted interoperability fixture, never an owner document.
        click(ctx.getString(R.string.settings));click(ctx.getString(R.string.ui_manage));click(ctx.getString(R.string.add_wallet));click(ctx.getString(R.string.import_yaml))
        await(ctx.getString(R.string.ui_choose_file));edit(1,"PUBLIC not a credential")
        click(ctx.getString(R.string.ui_choose_file))
        val filename="mxa-ui02-public-wallet.yaml"
        var found=false
        repeat(20){
            val all=nodes(instrumentation.uiAutomation.rootInActiveWindow)
            if(all.any{n->n.isVisibleToUser&&n.text?.toString()==filename})found=true
            if(!found){all.firstOrNull{n->n.contentDescription?.toString()=="Show roots"}?.performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(200)
                nodes(instrumentation.uiAutomation.rootInActiveWindow).lastOrNull{n->n.text?.toString()=="Downloads"}?.let{n->var target=n;while(!target.isClickable&&target.parent!=null)target=target.parent;target.performAction(AccessibilityNodeInfo.ACTION_CLICK)}}
            Thread.sleep(200)
        }
        assertTrue("Staged public YAML must be offered by DocumentsUI",found);click(filename)
        val end=System.currentTimeMillis()+10_000;var imported=false
        while(System.currentTimeMillis()<end){
            val fields=nodes(instrumentation.uiAutomation.rootInActiveWindow).filter{it.isEditable}
            if(fields.size==2&&nodes(instrumentation.uiAutomation.rootInActiveWindow).any{it.text?.toString()==ctx.getString(R.string.ui_file_selected)}){assertTrue(fields[1].text.isNullOrEmpty());imported=true;break};Thread.sleep(100)
        }
        assertTrue("New encrypted document should survive the picker return; old password must not",imported)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
    }

}
