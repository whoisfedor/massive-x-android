package com.massivex.android.notifications

import android.Manifest
import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.massivex.android.MainActivity
import com.massivex.android.R
import com.massivex.android.network.*
import com.massivex.android.transfer.MainnetSendReads
import com.massivex.android.wallet.*
import kotlinx.coroutines.*
import java.util.UUID

/** Public data only. Stable final balances at increasing final cursors; candidate balances never participate. */
internal data class ConfirmedSample(val identity:String,val amount:String,val period:Long,val version:String,val at:Long)
internal object BalanceChangePolicy {
    fun changed(old:ConfirmedSample?,first:ConfirmedSample,second:ConfirmedSample,now:Long):Boolean {
        require(first.identity==second.identity && first.amount==second.amount && first.version==second.version &&
            second.period>=first.period && first.at in (now-20_000)..now && second.at in first.at..now && second.at-first.at>=1000)
        return old!=null && old.identity==second.identity && old.version==second.version && first.period>=old.period && second.period>old.period && old.amount!=second.amount
    }
}
/** Official periodic JobScheduler, unique persisted ID, >=15 min, network constraint, no expedited/retry loop. */
internal object BalanceNotifications {
    const val JOB=0x4d5802
    private const val CHANNEL="confirmed-balances"
    private val lock=Any()
    private fun prefs(c:Context)=c.getSharedPreferences("background-balances-v1",Context.MODE_PRIVATE)
    fun permitted(c:Context)= (Build.VERSION.SDK_INT<33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) && c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    fun enabled(c:Context)=prefs(c).getBoolean("enabled",false) && permitted(c)
    private fun selected(c:Context)=AtomicCatalog(c).read()?.let(CatalogCodec::decode)?.selected?.takeUnless{it.removing}
    fun setEnabled(c:Context,value:Boolean) = synchronized(lock) {
        check(prefs(c).edit().clear().putBoolean("enabled",value&&permitted(c)).putString("generation",UUID.randomUUID().toString()).commit())
        c.getSystemService(NotificationManager::class.java).cancel(JOB)
        sync(c)
    }
    fun sync(c:Context)=synchronized(lock) {
        val scheduler=c.getSystemService(JobScheduler::class.java)
        val wallet=runCatching{selected(c)}.getOrNull()
        if(!enabled(c)||wallet==null){scheduler.cancel(JOB);prefs(c).edit().remove("identity").remove("amount").remove("period").remove("version").commit();return@synchronized}
        val identity=wallet.id+":"+wallet.address
        if(prefs(c).getString("identity",null)!=identity){check(prefs(c).edit().remove("amount").remove("period").remove("version").putString("identity",identity).putString("generation",UUID.randomUUID().toString()).commit())}
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,c.getString(R.string.balance_alerts),NotificationManager.IMPORTANCE_DEFAULT).apply{lockscreenVisibility=Notification.VISIBILITY_PRIVATE})
        if(scheduler.getPendingJob(JOB)==null)check(scheduler.schedule(JobInfo.Builder(JOB,ComponentName(c,BalanceCheckJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build())==JobScheduler.RESULT_SUCCESS)
    }
    suspend fun check(c:Context) {
        val wallet=synchronized(lock){if(!enabled(c))return;selected(c)} ?: return
        val identity=wallet.id+":"+wallet.address
        val generation=prefs(c).getString("generation",null)?:return
        val transport=PlatformReadTransport()
        suspend fun rpc(request:(String)->ReadRequest):com.google.gson.JsonElement {
            val id=UUID.randomUUID().toString();val req=request(id)
            return MainnetReads.envelope(ReadJson.parse(transport.execute(req),req.limit),id)
        }
        suspend fun sample():ConfirmedSample {
            val start=System.currentTimeMillis()
            val status=rpc{ReadRequest.rpc(RpcRead.STATUS,it)}
            val context=MainnetSendReads.decodeStatus(status,start,System.currentTimeMillis())
            // Missing/pruned/backwards final cursor cannot establish a new balance event.
            val period=status.obj().required("execution_stats").obj().required("final_cursor").obj().required("period").integer()
            val amount=MainnetReads.decodeBalance(rpc{ReadRequest.rpc(RpcRead.ADDRESSES,it,wallet.address)},wallet.address).first
            return ConfirmedSample(identity,amount.text,period,context.context.version,start)
        }
        val first=sample();delay(1000);val second=sample()
        synchronized(lock) {
            if(!enabled(c)||selected(c)?.let{it.id+":"+it.address}!=identity)return
            val p=prefs(c)
            if(p.getString("identity",null)!=identity||p.getString("generation",null)!=generation)return
            val old=p.getString("amount",null)?.let{ConfirmedSample(identity,it,p.getLong("period",-1),p.getString("version","").orEmpty(),0)}
            val changed=BalanceChangePolicy.changed(old,first,second,System.currentTimeMillis())
            // Reject cursor regression even for baselines; never replace trusted state with stale evidence.
            if(old!=null && (first.period<old.period || second.period<old.period || (second.period==old.period&&second.amount!=old.amount)))return
            check(p.edit().putString("amount",second.amount).putLong("period",second.period).putString("version",second.version).commit())
            if(changed) {
                val intent=PendingIntent.getActivity(c,JOB,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                c.getSystemService(NotificationManager::class.java).notify(JOB,Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.notification_wallet).setContentTitle(c.getString(R.string.app_name)).setContentText(c.getString(R.string.balance_changed)).setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(intent).setAutoCancel(true).build())
            }
        }
    }
}
/** OS-protected entry point: no signing, authorization, seed storage or transfer adapter dependency. */
class BalanceCheckJob:JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var task:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        task=scope.launch{try{withTimeout(60_000){BalanceNotifications.check(com.massivex.android.presentation.PresentationStore.localized(applicationContext))}}catch(_:Exception){/* Quiet bounded read failure; never log wallet data. */}finally{jobFinished(params,false)}}
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {task?.cancel();return false}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
