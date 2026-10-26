@file:Suppress("DEPRECATION")
package com.massivex.android

import android.os.Process
import com.massivex.android.transfer.*
import com.massivex.android.wallet.*
import kotlinx.coroutines.*
import java.io.File

/** Three separately launched processes, only under the guarded task-AVD runner. All receipts are FAKE. */
internal class TransferRestartRuntimeTest:SendRuntimeBase(){
    private val fixture="instrumentation-mxa004-restart"
    fun testPrepareDurableUnknown(){
        storage(fixture);preserve=true
        // A surviving fixture is evidence, never implicitly cleared/rebroadcast by this phase.
        assertTrue(ledger.records().isEmpty());assertTrue(repo.load().wallets.isEmpty())
        val wallet=authorized{repo.create("PUBLIC SYNTHETIC RESTART NEVER FUND")}.selected!!;var calls=0
        val lost=object:BroadcastTransport{override val enabled=true;override suspend fun submit(signed:SignedTransfer):BroadcastReply{calls++;assertEquals(TransferState.SUBMIT_ATTEMPT,ledger.records().single().state);throw java.io.IOException("PUBLIC fake lost response after acceptance")}}
        val c=coordinator(lost);prepare(c,wallet);authorized{c.confirm(c.state.value.review,ProtectedWalletSigner(repo,auth))}
        assertEquals(1,calls);assertEquals(TransferState.UNKNOWN,ledger.records().single().state)
        File(folder,"test-process-marker").writeText("${Process.myPid()}:1") // Public PID/phase only; no key or payload.
    }
    fun testResumeAfterForceStopWithoutSigningOrResubmission(){
        storage(fixture);preserve=true
        val marker=File(folder,"test-process-marker");val parts=marker.readText().split(':');assertEquals("1",parts[1]);assertTrue(parts[0].toInt()!=Process.myPid())
        Thread.sleep(11000) // Public reconciliation remains available after the seed-key auth window expires.
        try{secrets.decrypt(repo.load().selected!!).fill(0);fail("Expired seed authorization accepted")}catch(e:WalletException){assertEquals(WalletError.AUTH,e.reason)}
        val stored=ledger.records().single();assertEquals(TransferState.UNKNOWN,stored.state);assertNotNull(ledger.pending(stored.signed.draft.source))
        val cannotSubmit=object:BroadcastTransport{override val enabled=true;override suspend fun submit(signed:SignedTransfer):BroadcastReply=throw AssertionError("Restart attempted transmission")}
        val c=coordinator(cannotSubmit,finality());instrumentation.runOnMainSync{c.select(stored.signed.draft.source);c.resume()}
        runBlocking{c.reload();c.reconcile(stored.operationId)} // No seed decryption, authentication or write transport.
        assertEquals(TransferState.VERIFIED_SUCCESS,ledger.records().single().state);assertNull(ledger.pending(stored.signed.draft.source))
        marker.writeText("${Process.myPid()}:2")
    }
    fun testTerminalSurvivesSecondForceStopAndOnlyFixtureIsCleaned(){
        storage(fixture)
        val marker=File(folder,"test-process-marker").readText().split(':');assertEquals("2",marker[1]);assertTrue(marker[0].toInt()!=Process.myPid())
        val r=ledger.records().single();assertEquals(TransferState.VERIFIED_SUCCESS,r.state);r.resolution!!.validate(r.signed);assertNull(ledger.pending(r.signed.draft.source))
        assertEquals(1,repo.load().wallets.size)
        // Base teardown removes only this known synthetic namespace after the terminal persistence check.
    }
}
