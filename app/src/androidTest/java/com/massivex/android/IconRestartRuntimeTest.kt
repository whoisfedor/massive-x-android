@file:Suppress("DEPRECATION")
package com.massivex.android

import android.content.Intent
import android.os.Process
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import com.massivex.android.presentation.*

/** Five parameterized phases, with a real force-stop between phases; only this dev package is changed. */
class IconRestartRuntimeTest:InstrumentationTestCase(){
    fun testIconSelectionAcrossColdProcess(){
        val ctx=instrumentation.targetContext;assertEquals("com.massivex.android.dev",ctx.packageName)
        val phase=(instrumentation as InstrumentationTestRunner).arguments.getString("phase")!!.toInt();require(phase in 0..4)
        val store=PresentationStore(ctx);val marker=ctx.getSharedPreferences("icon-acceptance-v1",0)
        val choices=LauncherIcon.entries
        if(phase==0){if(!marker.contains("original"))marker.edit().putString("original",store.state.value.icon.name).commit()}
        else{
            assertTrue(marker.getInt("pid",-1)!=Process.myPid());assertEquals(phase-1,marker.getInt("phase",-1))
            assertEquals(choices[phase-1],store.state.value.icon)
            val active=ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(ctx.packageName),0)
            assertEquals(1,active.size);assertTrue(active.single().activityInfo.name.endsWith(choices[phase-1].alias))
        }
        if(phase<4){store.icon(choices[phase]);marker.edit().putInt("pid",Process.myPid()).putInt("phase",phase).commit()}
        else{store.icon(LauncherIcon.valueOf(marker.getString("original","CLASSIC")!!));marker.edit().remove("pid").remove("phase").remove("original").commit()}
    }
}
