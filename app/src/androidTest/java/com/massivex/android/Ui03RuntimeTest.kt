@file:Suppress("DEPRECATION")
package com.massivex.android

import android.content.*
import android.test.InstrumentationTestCase
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
import com.massivex.android.network.*
import com.massivex.android.transfer.*
import com.massivex.android.wallet.Wallet
import com.massivex.massa.*
import java.util.UUID

/** Isolated dev namespace, public fixtures only. Never authorizes, signs or broadcasts. */
class Ui03RuntimeTest:InstrumentationTestCase(){
    private lateinit var app:MainActivity
    private val ctx get()=instrumentation.targetContext
    private val account=OfflineAccount.fromSeed(ByteArray(32){42}).use{it.publicAccount()}
    private val recipient=OfflineAccount.fromSeed(ByteArray(32){43}).use{it.publicAccount().address}
    private val wallet=Wallet(UUID.randomUUID().toString(),"PUBLIC UI-03 — NEVER FUND",account.address,account.publicKey,null)
    override fun setUp(){super.setUp();assertEquals("com.massivex.android.dev",ctx.packageName)
        app=instrumentation.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.runOnMainSync{ViewModelProvider(app)[ReadModel::class.java].reads.stop();ViewModelProvider(app)[SendModel::class.java].background();app.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        Thread.sleep(600)
    }
    override fun tearDown(){instrumentation.runOnMainSync{app.clearOwnedClip();app.finish()};super.tearDown()}
    private fun secure()=app.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0
    private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList()else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
    private fun click(label:String){repeat(20){
        var n=nodes(instrumentation.uiAutomation.rootInActiveWindow).lastOrNull{it.isVisibleToUser&&(it.text?.toString()==label||it.contentDescription?.toString()==label)}
        if(n!=null){n!!.refresh();while(!n!!.isClickable&&n!!.parent!=null){n=n!!.parent;n!!.refresh()};if(n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(350);return}}
        instrumentation.uiAutomation.executeShellCommand("input swipe 540 1800 540 700 250").close();Thread.sleep(350)
    };fail("Missing public UI action: $label")}
    private fun fixture(content:@Composable ()->Unit){instrumentation.runOnMainSync{app.capturePolicy.sensitive(false);app.setContent{WalletTheme(Presentation(appearance=Appearance.DARK,accent=Accent.MIDNIGHT)){Surface(color=MaterialTheme.colorScheme.background){Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(MX.sectionGap)){Text("PUBLIC UI-03 — NEVER FUND",style=MaterialTheme.typography.labelSmall);content()}}}}};Thread.sleep(600)}
    private fun screenshot(name:String){assertFalse("Never capture a secure window",secure());val bitmap=instrumentation.uiAutomation.takeScreenshot();assertNotNull(bitmap);java.io.File(ctx.cacheDir,"ui03-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    fun testPublicSecretEntryAndNavigationCaptureTransitions(){
        val model=ViewModelProvider(app)[WalletModel::class.java]
        assertFalse(secure())
        for(screen in listOf(Screen.PRIVATE,Screen.YAML)){
            instrumentation.runOnMainSync{model.navigate(screen)};Thread.sleep(500);assertFalse("Empty forms are public",secure())
            val label=ctx.getString(if(screen==Screen.PRIVATE)R.string.import_private else R.string.import_yaml)
            val deadline=System.currentTimeMillis()+10_000
            var edited=false
            while(System.currentTimeMillis()<deadline&&!edited){
                val all=nodes(instrumentation.uiAutomation.rootInActiveWindow)
                val fields=all.filter{it.isEditable}
                if(fields.size==2){
                    fields[1].performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    fields[1].refresh()
                    edited=fields[1].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"PUBLIC not a key or password")})
                }
                if(!edited)Thread.sleep(150)
            }
            assertTrue("Set synthetic input on rendered $screen",edited)
            Thread.sleep(300);assertTrue("Input must protect before it renders",secure())
            instrumentation.runOnMainSync{model.navigate(Screen.HOME)};Thread.sleep(500);assertFalse(secure())
            instrumentation.runOnMainSync{model.navigate(screen)};Thread.sleep(500);assertFalse(secure())
            assertTrue(nodes(instrumentation.uiAutomation.rootInActiveWindow).filter{it.isEditable}.all{it.text.isNullOrEmpty()})
        }
    }
    fun testSecureRevealSurrogateBlocksCaptureAndReleasesAfterHide(){
        // NON-SECRET colored marker: prove actual compositor blocking without risking a key screenshot.
        fixture{Text("PUBLIC capture marker")}
        val clear=instrumentation.uiAutomation.takeScreenshot();assertNotNull(clear)
        instrumentation.runOnMainSync{app.capturePolicy.sensitive(true)};Thread.sleep(300);assertTrue(secure())
        val protected=instrumentation.uiAutomation.takeScreenshot()
        if(protected!=null){
            var differences=0
            for(y in protected.height/4 until protected.height*3/4 step 17)for(x in 0 until protected.width step 17){if(protected.getPixel(x,y)!=clear.getPixel(x,y))differences++}
            assertTrue("Secure content must be replaced by the compositor",differences>20);protected.recycle()
        }
        clear.recycle()
        instrumentation.runOnMainSync{app.capturePolicy.sensitive(false);app.capturePolicy.sensitive(true)};Thread.sleep(300);assertTrue("Pending release cannot beat new protection",secure())
        instrumentation.runOnMainSync{app.capturePolicy.sensitive(false)};Thread.sleep(300);assertFalse(secure())
    }
    fun testSensitiveClipboardSurvivesNavigationBackgroundAndOldTimer(){
        val synthetic=OfflineAccount.fromSeed(ByteArray(32){44}).use{it.privateKeyEncoding()}
        fixture{BackupPanel(false,{},null,false,false,{}, {app.copySecret(synthetic)}, {})}
        click(ctx.getString(R.string.copy_private))
        instrumentation.runOnMainSync{val b=app.getSystemService(ClipboardManager::class.java);assertTrue(b.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"));assertTrue(app.hasOwnedClip())}
        fixture{Text("PUBLIC paste destination")}
        instrumentation.uiAutomation.executeShellCommand("input keyevent 3").close();Thread.sleep(31_500)
        ctx.startActivity(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP));Thread.sleep(800)
        val pasted=mutableStateOf("")
        fixture{TextField(pasted.value,{pasted.value=it},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),label={Text("DISPOSABLE protected paste input")})}
        instrumentation.runOnMainSync{app.capturePolicy.sensitive(true)}
        assertTrue(secure())
        val paste=nodes(instrumentation.uiAutomation.rootInActiveWindow).first{it.isEditable}
        paste.performAction(AccessibilityNodeInfo.ACTION_FOCUS);paste.refresh()
        assertTrue(paste.performAction(AccessibilityNodeInfo.ACTION_PASTE));Thread.sleep(200)
        val pasteDeadline=System.currentTimeMillis()+5000
        while(pasted.value!=synthetic&&System.currentTimeMillis()<pasteDeadline)Thread.sleep(100)
        assertTrue("Disposable key pasted exactly",pasted.value==synthetic)
        // Read only the clip that this test itself created, while foreground.
        instrumentation.runOnMainSync{assertTrue(app.hasOwnedClip());assertTrue("Owned disposable key remains available",synthetic==app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString());app.clearOwnedClip();assertFalse(app.hasOwnedClip())}
    }
    fun testManualClearNeverClearsNewerClipboard(){
        instrumentation.runOnMainSync{
            val b=app.getSystemService(ClipboardManager::class.java)
            app.copySecret("PUBLIC disposable fixture")
            b.setPrimaryClip(ClipData.newPlainText("UI03 public newer clip","PUBLIC newer value"))
            assertFalse(app.hasOwnedClip());app.clearOwnedClip()
            assertEquals("UI03 public newer clip",b.primaryClipDescription!!.label.toString());assertEquals("PUBLIC newer value",b.primaryClip!!.getItemAt(0).text.toString())
            b.clearPrimaryClip()
        }
    }
    fun testSliderAtTwoDisplayAndFontScalesNeverSubmitsOnTap(){
        var submissions=0
        for(scale in listOf(1f,1.3f))for(font in listOf(1f,1.6f)){
            fixture{androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(app.resources.displayMetrics.density*scale,font)){SlideConfirmation("public-$scale-$font",true){submissions++}}}
            val track=nodes(instrumentation.uiAutomation.rootInActiveWindow).first{it.contentDescription?.toString()==ctx.getString(R.string.send_slide)}
            val bounds=android.graphics.Rect();track.getBoundsInScreen(bounds)
            val now=android.os.SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)){
                val event=android.view.MotionEvent.obtain(now,now+30,action,bounds.exactCenterX(),bounds.exactCenterY(),0)
                instrumentation.sendPointerSync(event);event.recycle()
            }
            Thread.sleep(150);assertEquals(0,submissions)
            screenshot("slider-$scale-$font")
        }
    }
    fun testPublicNativeEvidenceAndModalDismissal(){
        Thread.sleep(3000) // Let the preceding PUBLIC clipboard overlay dismiss before evidence.
        val model=ViewModelProvider(app)[WalletModel::class.java]
        for((screen,name)in listOf(Screen.CREATE to "create",Screen.PRIVATE to "private-empty",Screen.YAML to "yaml-empty",Screen.WATCH to "watch")){
            instrumentation.runOnMainSync{model.navigate(screen)};Thread.sleep(600);screenshot(name)
        }
        fixture{Text(ctx.getString(R.string.backup),style=MaterialTheme.typography.titleLarge);BackupPanel(false,{},null,true,false,{},{},{})};screenshot("backup-masked")
        fixture{ReceiveScreen(recipient,false,{})};screenshot("receive")
        val now=System.currentTimeMillis();val snapshot=SendSnapshot(now,CHAIN,"MAIN.5.1",100uL,10uL,MASAmount(10_000_000uL),MASAmount(1_000_000_000uL),MASAmount(1_000_000_000uL),MASAmount(1uL),MASAmount(1uL))
        val review=ReviewedTransfer.make(TransferSource.capture(wallet),recipient,"0.1",snapshot,now)
        fixture{SendScreen(wallet,SendUi(source=review.source,review=review,phase=SendPhase.REVIEW),{_,_->},{},{},{},{})};screenshot("review")
        val op=PublicOperation(HistoryIdentity(MassaCodec.encode(ByteArray(32){7},"O"),MassaCodec.encode(ByteArray(32){7},"O"),false),"Transaction",account.address,recipient,MASAmount(100000000uL),MASAmount(10000000uL),now,"Final",true)
        fixture{OperationScreen(ReadUi(operations=listOf(op),detailId=op.id),{})};screenshot("operation")
        val publicState=ReadUi(walletId=wallet.id,address=wallet.address,historyLoaded=true,operations=listOf(op),now=now)
        val reads=ViewModelProvider(app)[ReadModel::class.java].reads
        fixture{ReadSummary(wallet,publicState,reads,{},{});RecentActivity(publicState,reads,{})};screenshot("home")
        fixture{ActivityScreen(publicState,reads,{})};screenshot("activity")
        val store=PresentationStore(app)
        fixture{val p=store.state.collectAsState();SettingsScreen(p.value,store,{},{},{})};screenshot("settings")
        click(ctx.getString(R.string.accent_color));screenshot("accent-modal")
        instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close();Thread.sleep(400)
        assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any{it.text?.toString()==ctx.getString(R.string.ui_done)})
        click(ctx.getString(R.string.appearance));screenshot("appearance-modal")
        instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close();Thread.sleep(400)
        click(ctx.getString(R.string.about_roadmap))
        repeat(4){instrumentation.uiAutomation.executeShellCommand("input swipe 540 650 540 1850 350").use{fd->java.io.FileInputStream(fd.fileDescriptor).use{it.readBytes()}};Thread.sleep(100)}
        screenshot("about-top")
        repeat(2){instrumentation.uiAutomation.executeShellCommand("input swipe 540 1800 540 700 400").close();Thread.sleep(600)};screenshot("about-continuation")
    }
}
