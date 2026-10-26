@file:Suppress("DEPRECATION")
package com.massivex.android

import android.app.LocaleManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import android.test.InstrumentationTestCase
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.massivex.android.presentation.*
import com.massivex.android.transfer.*
import com.massivex.android.wallet.Wallet
import com.massivex.massa.*
import kotlinx.coroutines.*
import java.util.UUID

/** Public UI/capability assertions only. Safe on the explicitly authorized fresh dev install. */
class ParityRuntimeTest:InstrumentationTestCase(){
    private val ctx get()=instrumentation.targetContext
    private var activity:MainActivity?=null
    private fun launch():MainActivity=(instrumentation.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))as MainActivity).also{activity=it;Thread.sleep(500)}
    override fun setUp(){super.setUp();assertEquals("com.massivex.android.dev",ctx.packageName)}
    override fun tearDown(){activity?.let{instrumentation.runOnMainSync{it.finish()}};super.tearDown()}
    fun testSixLocalesResolveAndActuallyRecreateApp(){
        if(Build.VERSION.SDK_INT<33)return
        val manager=ctx.getSystemService(LocaleManager::class.java);val original=manager.applicationLocales
        val system=manager.systemLocales.toLanguageTags()
        try{for(tag in PresentationPolicy.languages){
            manager.applicationLocales=LocaleList.forLanguageTags(tag);Thread.sleep(500)
            val app=launch();val end=System.currentTimeMillis()+10000
            while(app.resources.configuration.locales[0].language!=java.util.Locale.forLanguageTag(tag).language&&System.currentTimeMillis()<end)Thread.sleep(100)
            assertEquals(java.util.Locale.forLanguageTag(tag).language,app.resources.configuration.locales[0].language)
            val expected=ctx.createConfigurationContext(android.content.res.Configuration(ctx.resources.configuration).apply{setLocales(LocaleList.forLanguageTags(tag))})
            assertEquals(expected.getString(R.string.send_disabled),app.getString(R.string.send_disabled));assertEquals(expected.getString(R.string.backup_warning),app.getString(R.string.backup_warning))
            assertTrue(app.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE ==0)
            instrumentation.runOnMainSync{app.finish()};activity=null;Thread.sleep(200)
        }}finally{manager.applicationLocales=original}
        assertEquals(system,manager.systemLocales.toLanguageTags())
    }
    fun testAppearanceAndPalettesPersistWithoutRecreatingOrRebindingReview(){
        val app=launch();val store=PresentationStore(app);val original=store.state.value
        val public=OfflineAccount.fromSeed(ByteArray(32){42}).use{it.publicAccount()};val recipient=OfflineAccount.fromSeed(ByteArray(32){43}).use{it.publicAccount().address}
        val source=TransferSource(UUID.randomUUID().toString(),public.address,public.publicKey);val now=System.currentTimeMillis()
        val snap=SendSnapshot(now,CHAIN,"MAIN.5.1",100uL,10uL,MASAmount(10_000_000uL),MASAmount(10_000_000_000uL),MASAmount(10_000_000_000uL),MASAmount(0uL),MASAmount(0uL))
        val review=ReviewedTransfer.make(source,recipient,"1",snap,now);val wallet=Wallet(source.walletId,"PUBLIC NEVER FUND",source.address,source.publicKey,null)
        val namespace="instrumentation-theme-${UUID.randomUUID()}"
        val disk=AndroidTransferJournal(app,namespace);val ledger=TransferLedger(disk)
        val reader=object:FinalityReader{override suspend fun observe(signed:SignedTransfer)=OperationObservation(TransferState.UNKNOWN);override suspend fun checkContext()=VerifiedNodeContext(CHAIN,"MAIN.5.1",System.currentTimeMillis())}
        val coordinator=SendCoordinator(PreflightReader{_,_->snap},ledger,reader)
        coordinator.select(source);coordinator.resume();runBlocking{coordinator.prepare(recipient,"1")}
        val commitment=coordinator.state.value.review!!.commitment
        var rendered=0
        try{
            instrumentation.runOnMainSync{ViewModelProvider(app)[ReadModel::class.java].reads.stop();ViewModelProvider(app)[SendModel::class.java].background()
                app.setContent{val p=store.state.collectAsState();val current=coordinator.state.collectAsState();WalletTheme(p.value){SideEffect{rendered++};Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)){
                    Text("PUBLIC SYNTHETIC REVIEW — NEVER FUND")
                    SendScreen(wallet,current.value,{_,_->},{},{},{},{throw AssertionError("No UI test submission")})
                }}}
            }
            for(mode in Appearance.entries)for(accent in Accent.entries){instrumentation.runOnMainSync{store.appearance(mode);store.accent(accent)};Thread.sleep(100);assertEquals(mode,PresentationStore(app).state.value.appearance);assertEquals(accent,PresentationStore(app).state.value.accent);assertEquals(commitment,coordinator.state.value.review?.commitment);assertFalse(app.isDestroyed)}
            assertTrue(rendered>0);assertTrue(app.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE==0)
        }finally{instrumentation.runOnMainSync{store.appearance(original.appearance);store.accent(original.accent)};coordinator.stop();java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(disk.alias)};disk.path.delete()}
    }
    fun testFourIconSelectionsHaveOneLauncherAndSurviveStoreReload(){
        val store=PresentationStore(ctx);val original=store.state.value.icon;val pid=android.os.Process.myPid()
        val namespace="instrumentation-icon-default-${UUID.randomUUID()}"
        val fresh=object:android.content.ContextWrapper(ctx){override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(if(name=="presentation-v1")namespace else name,mode)}
        try{assertEquals(LauncherIcon.CLASSIC,PresentationStore(fresh).state.value.icon)}finally{ctx.deleteSharedPreferences(namespace)}
        val archive=ctx.packageManager.getPackageArchiveInfo(ctx.applicationInfo.sourceDir,PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS)!!
        val defaults=archive.activities!!.filter{it.name.contains(".launcher.")&&it.enabled}
        assertEquals(1,defaults.size);assertTrue(defaults.single().name.endsWith(".Classic"))
        try{for(icon in LauncherIcon.entries){
            val image=android.graphics.BitmapFactory.decodeResource(ctx.resources,iconImage(icon),android.graphics.BitmapFactory.Options().apply{inScaled=false})
            assertEquals(1254,image.width);assertEquals(1254,image.height);image.recycle()
            store.icon(icon);val pm=ctx.packageManager
            val launchers=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(ctx.packageName),0)
            assertEquals(1,launchers.size);assertTrue(launchers.single().activityInfo.name.endsWith(icon.alias));assertEquals(icon,PresentationStore(ctx).state.value.icon)
            val drawable=launchers.single().loadIcon(pm);assertTrue(drawable.intrinsicWidth>0)
            if(Build.VERSION.SDK_INT>=33){assertTrue(drawable is android.graphics.drawable.AdaptiveIconDrawable);assertNotNull((drawable as android.graphics.drawable.AdaptiveIconDrawable).monochrome)}
            assertEquals(pid,android.os.Process.myPid())
        }}finally{store.icon(original)}
    }
    fun testLargeFontSixLocaleSettingsAndSafeIntents(){
        val app=launch();val store=PresentationStore(app)
        for(tag in PresentationPolicy.languages){
            val localized=ctx.createConfigurationContext(android.content.res.Configuration(ctx.resources.configuration).apply{setLocales(LocaleList.forLanguageTags(tag))})
            instrumentation.runOnMainSync{app.setContent{CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides app,androidx.compose.ui.platform.LocalContext provides localized,LocalDensity provides Density(app.resources.displayMetrics.density,1.6f)){
                val settings=store.state.collectAsState();WalletTheme(settings.value){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)){SettingsScreen(settings.value,store,{}, {assertTrue(ExternalDestinations.valid(it))},{})}}
            }}}
            instrumentation.waitForIdleSync()
            var root=instrumentation.uiAutomation.rootInActiveWindow
            val ready=System.currentTimeMillis()+10000
            while((root==null||root.packageName?.toString()!=ctx.packageName)&&System.currentTimeMillis()<ready){Thread.sleep(100);root=instrumentation.uiAutomation.rootInActiveWindow}
            assertNotNull("Native public window did not become ready",root)
            assertEquals(ctx.packageName,root!!.packageName.toString())
            fun inspect(n:android.view.accessibility.AccessibilityNodeInfo?){if(n==null)return
                if(n.isVisibleToUser&&n.isClickable){val b=android.graphics.Rect();n.getBoundsInScreen(b);assertTrue("Public control width overflow",b.width()<=app.resources.displayMetrics.widthPixels)}
                for(i in 0 until n.childCount)inspect(n.getChild(i))
            };inspect(root)
        }
        for(url in ExternalDestinations.fixed.values){val intent=externalIntent(url);assertNull(intent.extras);assertFalse(intent.hasExtra(Intent.EXTRA_TEXT));assertTrue(intent.action in setOf(Intent.ACTION_VIEW,Intent.ACTION_SENDTO))}
    }
}
