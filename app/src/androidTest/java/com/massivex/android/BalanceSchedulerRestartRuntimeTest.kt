@file:Suppress("DEPRECATION")
package com.massivex.android

import android.app.job.JobScheduler
import android.test.InstrumentationTestCase
import com.massivex.android.notifications.BalanceNotifications
import com.massivex.android.wallet.*
import kotlinx.coroutines.runBlocking

/** Dedicated task AVD ONLY. Public watch address, no keys or transfers, no fabricated balance event. */
class BalanceSchedulerRestartRuntimeTest:InstrumentationTestCase(){
    private val ctx get()=instrumentation.targetContext
    private val prefs get()=ctx.getSharedPreferences("instrumentation-notification-restart",0)
    private val address="AU1rqxSMewKgJU2m5orM2HFuMg7ioEFiECuhX1sSken92VxUBfQy"
    private fun repository()=WalletRepository(AtomicCatalog(ctx),AndroidSecrets(ctx),Randomness{throw AssertionError("No seed creation")},object:OwnerAuthentication{override suspend fun approve(action:Action):Approval=throw AssertionError("No authorization");override fun cancel(){}})
    override fun setUp(){super.setUp();assertEquals("ranchu",android.os.Build.HARDWARE);assertEquals("com.massivex.android.dev",ctx.packageName)}
    fun testPreparePersistedJobAndPermissionOptIn(){
        if(prefs.getBoolean("prepared",false)){
            BalanceNotifications.setEnabled(ctx,false)
            val previous=repository()
            prefs.getString("created",null)?.let{id->previous.load().wallets.firstOrNull{it.id==id}?.let{w->assertTrue(w.watchOnly&&w.address==address);previous.select(id);runBlocking{previous.remove(id,true)}}}
            prefs.getString("original",null)?.let{id->if(previous.load().wallets.any{it.id==id})previous.select(id)}
            check(prefs.edit().clear().commit())
        }
        val repo=repository();val old=repo.load();val existing=old.wallets.firstOrNull{it.address==address}
        if(existing!=null)assertTrue(existing.watchOnly)
        val chosen=existing?:repo.addWatch(address,"PUBLIC notification test").selected!!
        check(prefs.edit().putString("original",old.selectedId).putString("created",if(existing==null)chosen.id else null).putBoolean("prepared",true).commit())
        repo.select(chosen.id)
        assertTrue(BalanceNotifications.permitted(ctx));assertFalse(BalanceNotifications.enabled(ctx))
        BalanceNotifications.setEnabled(ctx,true)
        val job=ctx.getSystemService(JobScheduler::class.java).getPendingJob(BalanceNotifications.JOB)!!
        assertTrue(job.isPersisted);assertEquals(900_000L,job.intervalMillis)
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY,job.networkType)
    }
    fun testResumeAfterProcessDeathAndRebootThenOptOut(){
        assertTrue(prefs.getBoolean("prepared",false));assertTrue(BalanceNotifications.enabled(ctx))
        // The runner inspected the restored OS queue before am instrument restarts this package.
        // Instrumentation itself force-stops targets; foreground sync must restore scheduling.
        val scheduler=ctx.getSystemService(JobScheduler::class.java)
        BalanceNotifications.sync(ctx)
        assertNotNull(scheduler.getPendingJob(BalanceNotifications.JOB))
        BalanceNotifications.setEnabled(ctx,false)
        assertFalse(BalanceNotifications.enabled(ctx));assertNull(scheduler.getPendingJob(BalanceNotifications.JOB))
        val repo=repository()
        prefs.getString("created",null)?.let{id->repo.select(id);runBlocking{repo.remove(id,true)}}
        prefs.getString("original",null)?.let{id->if(repo.load().wallets.any{it.id==id})repo.select(id)}
        check(prefs.edit().clear().commit())
    }
}
