@file:Suppress("DEPRECATION")
package com.massivex.android

import android.test.InstrumentationTestCase
import android.util.Log
import com.massivex.android.network.*
import com.massivex.android.transfer.*
import com.massivex.massa.*
import kotlinx.coroutines.*
import java.util.UUID

/** Bounded public READS only, no Activity/owner catalog, locally signed never-broadcast synthetic operation. */
internal class SendReadContractRuntimeTest:InstrumentationTestCase(){
    fun testOfficialPreflightAndMissingOperationStayLocked(){
        assertEquals("com.massivex.android.dev",instrumentation.targetContext.packageName)
        val context=instrumentation.targetContext;val name="instrumentation-live-${UUID.randomUUID()}";val disk=AndroidTransferJournal(context,name)
        val ledger=TransferLedger(disk);val account=OfflineAccount.fromSeed(ByteArray(32){42})
        try{
            val public=account.publicAccount();val source=TransferSource(UUID.randomUUID().toString(),public.address,public.publicKey)
            val recipient=OfflineAccount.fromSeed(ByteArray(32){43}).use{it.publicAccount().address}
            val now=System.currentTimeMillis();val synthetic=SendSnapshot(now,CHAIN,"MAIN.5.1",100uL,10uL,MASAmount(10_000_000uL),MASAmount(10_000_000_000uL),MASAmount(10_000_000_000uL),MASAmount(0uL),MASAmount(0uL))
            val draft=ReviewedTransfer.make(source,recipient,"1",synthetic,now);val signed=SignedTransfer(draft,account.signOperation(draft.content,public));signed.validate()
            ledger.acquire(source).use{ledger.prepared(signed,now)}
            var calls=0;val real=PlatformReadTransport();val bounded=ReadTransport{request->calls++;assertTrue("Read budget exceeded",calls<=8);real.execute(request)}
            val reads=MainnetSendReads(bounded)
            runBlocking{
                try{val current=reads.snapshot(source,recipient);current.validate(System.currentTimeMillis());Log.i("MXA004Live","preflight=PASS version=${current.version} fee=${current.fee.text} validity=${current.validity}")}
                catch(e:Exception){assertTrue(e is ReadFailure||e is SendFailure);Log.i("MXA004Live","preflight=UNAVAILABLE")}
                val c=SendCoordinator(reads,ledger,reads);c.select(source);c.resume();c.reconcile(draft.operationId)
                assertFalse(ledger.records().single().state.terminal);assertNotNull(ledger.pending(source));assertFalse(c.submissionEnabled)
                Log.i("MXA004Live","original-operation-read="+if(c.state.value.error==null)"PASS remains-locked"else"UNAVAILABLE remains-locked")
                Log.i("MXA004Live","readCount=$calls")
            }
        }finally{
            account.close()
            // The signed fixture was never submitted; remove only its known test-only journal/anchor.
            java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(disk.alias)}
            disk.path.delete()
        }
    }
}
