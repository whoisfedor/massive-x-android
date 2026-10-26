package com.massivex.android.transfer

import com.massivex.android.wallet.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TransferJournalTest {
    private fun prepared(disk:MemoryJournal=MemoryJournal()):Pair<TransferLedger,SignedTransfer>{val ledger=TransferLedger(disk);val s=signed();ledger.acquire(s.draft.source).use{ledger.prepared(s,TEST_TIME)};return ledger to s}
    @Test fun emptyJournalRoundTrips(){val f=JournalFile(UUID.randomUUID().toString());assertEquals(f,JournalCodec.decode(JournalCodec.encode(f)))}
    @Test fun signedIntentRoundTripsAndBindsAllBytes(){val (l,s)=prepared();val d=l.records().single();assertEquals(s.binding,d.signed.binding);assertEquals(TransferState.SIGNED,d.state);d.validate()}
    @Test fun truncatedAndTrailingJournalRejected(){val (_,s)=prepared();val bytes=JournalCodec.encode(JournalFile(UUID.randomUUID().toString(),1,listOf(TransferRecord(s,TransferState.SIGNED,TEST_TIME,TransferNote.PREPARED))));listOf(bytes.copyOf(bytes.size-1),bytes+byteArrayOf(0),bytes.copyOf(7)).forEach{rejects{JournalCodec.decode(it)}}}
    @Test fun unsupportedVersionRejected(){val bytes=JournalCodec.encode(JournalFile(UUID.randomUUID().toString()));bytes[7]=2;rejects{JournalCodec.decode(bytes)}}
    @Test fun wrongMagicAndOversizeRejected(){rejects{JournalCodec.decode(ByteArray(20))};rejects{JournalCodec.decode(ByteArray(JournalCodec.MAX_BYTES+1))}}
    @Test fun duplicateOperationIdsRejected(){val (_,s)=prepared();val r=TransferRecord(s,TransferState.SIGNED,TEST_TIME,TransferNote.PREPARED);rejects{JournalFile(UUID.randomUUID().toString(),1,listOf(r,r)).validate()}}
    @Test fun twoPendingIntentsForSameSourceRejected(){val (_,s)=prepared();val b=signed(review(amount="2"));rejects{JournalFile(UUID.randomUUID().toString(),1,listOf(TransferRecord(s,TransferState.SIGNED,TEST_TIME,TransferNote.PREPARED),TransferRecord(b,TransferState.SIGNED,TEST_TIME,TransferNote.PREPARED))).validate()}}
    @Test fun malformedSignatureNeverPersists(){val d=MemoryJournal();val l=TransferLedger(d);l.acquire(source()).use{rejects(SendError.SIGNATURE){l.prepared(SignedTransfer(review(),"bad"),TEST_TIME)}};assertTrue(l.records().isEmpty())}
    @Test fun signedWithoutSourceLeaseRejected(){rejects{TransferLedger(MemoryJournal()).prepared(signed(),TEST_TIME)}}
    @Test fun sourceLeaseRejectsConcurrentLeaseButNotOtherSource(){val l=TransferLedger(MemoryJournal());l.acquire(source()).use{rejects(SendError.LOCKED){l.acquire(source())};l.acquire(source(44)).close()};l.acquire(source()).close()}
    @Test fun leaseReleasedAfterFailure(){val l=TransferLedger(MemoryJournal());try{l.acquire(source()).use{throw IllegalStateException()}}catch(_:IllegalStateException){};l.acquire(source()).close()}
    @Test fun pendingSourceLocksAfterFreshLedgerRestart(){val disk=MemoryJournal();val (_,s)=prepared(disk);assertNotNull(TransferLedger(disk).pending(s.draft.source));rejects(SendError.LOCKED){TransferLedger(disk).acquire(s.draft.source)}}
    @Test fun reimportUuidCannotEscapeLock(){val (l,s)=prepared();rejects(SendError.LOCKED){l.acquire(s.draft.source.copy(walletId=UUID.randomUUID().toString()))}}
    @Test fun preparedCannotJumpToPendingOrTerminal(){val (l,s)=prepared();rejects{l.transition(s.draft.operationId,TransferState.PENDING,TEST_TIME,TransferNote.ACCEPTED_NOT_FINAL)};rejects{l.transition(s.draft.operationId,TransferState.VERIFIED_SUCCESS,TEST_TIME,TransferNote.TRUSTED_PROVIDER)}}
    @Test fun attemptCannotBeRepeated(){val (l,s)=prepared();l.transition(s.draft.operationId,TransferState.SUBMIT_ATTEMPT,TEST_TIME,TransferNote.ATTEMPT_RECORDED);rejects{l.transition(s.draft.operationId,TransferState.SUBMIT_ATTEMPT,TEST_TIME,TransferNote.ATTEMPT_RECORDED)}}
    @Test fun bareTerminalFlagsCannotUnlock(){val (l,s)=prepared();rejects{l.observation(s.draft.operationId,OperationObservation(TransferState.VERIFIED_SUCCESS),TEST_TIME)};assertNotNull(l.pending(s.draft.source))}
    @Test fun oneMatchedReportRemainsLocked(){val (l,s)=prepared();val r=report(s);l.observation(s.draft.operationId,OperationObservation(TransferState.VERIFIED_SUCCESS,r),r.observedAt);assertNotNull(l.pending(s.draft.source));assertEquals(1,l.records().single().claims)}
    @Test fun terminalSuccessMustPersistBeforeUnlock(){val (l,s)=prepared();val e=resolution(s);l.observation(s.draft.operationId,OperationObservation(TransferState.VERIFIED_SUCCESS,e.first),e.first.observedAt);l.observation(s.draft.operationId,OperationObservation(TransferState.VERIFIED_SUCCESS,e.second),e.second.observedAt);assertNotNull(l.pending(s.draft.source));l.resolve(s.draft.operationId,e,e.acceptedAt);assertNull(l.pending(s.draft.source));assertEquals(TransferState.VERIFIED_SUCCESS,l.records().single().state)}
    @Test fun terminalFailureDistinctAndDurable(){val disk=MemoryJournal();val (l,s)=prepared(disk);val e=resolution(s,false);listOf(e.first,e.second).forEach{l.observation(s.draft.operationId,OperationObservation(TransferState.VERIFIED_FAILURE,it),it.observedAt)};l.resolve(s.draft.operationId,e,e.acceptedAt);assertEquals(TransferState.VERIFIED_FAILURE,TransferLedger(disk).records().single().state);assertNull(TransferLedger(disk).pending(s.draft.source))}
    @Test fun terminalWithoutResolutionIsCorrupt(){val s=signed();rejects{TransferRecord(s,TransferState.VERIFIED_SUCCESS,TEST_TIME,TransferNote.TRUSTED_PROVIDER).validate()}}
    @Test fun oppositeTerminalClaimsStayLockedAcrossRestartAndLaterAgreement(){val disk=MemoryJournal();val (l,s)=prepared(disk);val a=report(s,TEST_TIME+1000,true);val b=report(s,TEST_TIME+2000,false);l.observation(s.draft.operationId,OperationObservation(TransferState.VERIFIED_SUCCESS,a),a.observedAt);l.observation(s.draft.operationId,OperationObservation(TransferState.VERIFIED_FAILURE,b),b.observedAt);val reopened=TransferLedger(disk);val e=resolution(s);listOf(e.first,e.second).forEach{reopened.observation(s.draft.operationId,OperationObservation(TransferState.VERIFIED_SUCCESS,it),it.observedAt)};rejects{reopened.resolve(s.draft.operationId,e,e.acceptedAt)};assertNotNull(reopened.pending(s.draft.source));assertEquals(3,reopened.records().single().claims)}
    @Test fun wrongSourceReceiptCannotUnlock(){val (l,s)=prepared();rejects(SendError.EVIDENCE){l.observation(s.draft.operationId,OperationObservation(TransferState.VERIFIED_SUCCESS,report(s).copy(sourceAddress=recipient)),TEST_TIME+1000)}}
    @Test fun reportsBoundedButConflictsNeverForgotten(){val (l,s)=prepared();repeat(24){i->val r=report(s,TEST_TIME+1000+i,! (i==0));l.observation(s.draft.operationId,OperationObservation(if(r.success)TransferState.VERIFIED_SUCCESS else TransferState.VERIFIED_FAILURE,r),r.observedAt)};assertEquals(16,l.records().single().reports.size);assertEquals(3,l.records().single().claims)}
    @Test fun terminalEvidenceCannotResolveDifferentIntent(){val (l,s)=prepared();rejects{l.resolve(s.draft.operationId,resolution(signed(review(amount="2"))),TEST_TIME+2000)};assertNotNull(l.pending(s.draft.source))}
    @Test fun failedPrecommitLatchesWithoutTransportPermission(){val d=MemoryJournal();val l=TransferLedger(d);d.failWrite=1;l.acquire(source()).use{rejects{l.prepared(signed(),TEST_TIME)}};rejects(SendError.JOURNAL){l.records()};l.reload();assertTrue(l.records().isEmpty())}
    @Test fun ambiguousCommitRetainsSourceLock(){val d=MemoryJournal();val l=TransferLedger(d);d.commitThenFail=1;l.acquire(source()).use{rejects{l.prepared(signed(),TEST_TIME)}};rejects{l.records()};l.reload();assertNotNull(l.pending(source()))}
    @Test fun terminalWriteFailureNeverUnlocksInMemory(){val d=MemoryJournal();val (l,s)=prepared(d);val e=resolution(s);listOf(e.first,e.second).forEach{l.observation(s.draft.operationId,OperationObservation(TransferState.VERIFIED_SUCCESS,it),it.observedAt)};d.failWrite=d.writes+1;rejects{l.resolve(s.draft.operationId,e,e.acceptedAt)};rejects{l.pending(source())};assertNotNull(TransferLedger(d).pending(source()))}
    @Test fun missingOrCorruptPersistenceNeverDefaultsEmpty(){val d=MemoryJournal();val (l,_)=prepared(d);d.bytes=byteArrayOf();rejects{l.records()};rejects{l.acquire(source(44))}}
    @Test fun installationSubstitutionAndRevisionRollbackDetected(){val d=MemoryJournal();val old=d.bytes.copyOf();val (l,_)=prepared(d);d.bytes=old;rejects{l.records()}}
    @Test fun pendingDeletionGuardPreservesWalletAndAlias()=runBlocking{val h=TransferHarness().start();h.send();try{h.repository.remove(h.wallet.id,true);fail("Pending wallet deleted")}catch(e:WalletException){assertEquals(WalletError.TRANSFER_LOCKED,e.reason)};assertTrue(h.secrets.walletIds().contains(h.wallet.id));assertNotNull(h.ledger.pending(TransferSource.capture(h.wallet)))}
    @Test fun watchOnlyDeletionUnaffectedByPendingOtherSource()=runBlocking{val h=TransferHarness().start();h.send();val w=h.repository.addWatch(identity(45).address,"Other public watch").selected!!;h.repository.remove(w.id,true);assertTrue(h.repository.load().wallets.any{it.id==h.wallet.id})}
    @Test fun signingLeasePreventsDeletionBeforeJournal()=runBlocking{val h=TransferHarness().start();h.ledger.acquire(TransferSource.capture(h.wallet)).use{try{h.repository.remove(h.wallet.id,true);fail("Active signer deleted")}catch(e:WalletException){assertEquals(WalletError.TRANSFER_LOCKED,e.reason)}}}
    @Test fun staleDoubleCloseCannotReleaseNewLease(){val l=TransferLedger(MemoryJournal());val a=l.acquire(source());a.close();val b=l.acquire(source());a.close();rejects(SendError.LOCKED){l.acquire(source())};b.close();l.acquire(source()).close()}
    @Test fun interruptedRemovalWithoutPendingCanResume()=runBlocking{val h=TransferHarness().start();h.secrets.deleteFailure=true;try{h.repository.remove(h.wallet.id,true);fail("Expected simulated alias failure")}catch(e:WalletException){assertEquals(WalletError.INCOMPLETE,e.reason)};assertTrue(h.repository.load().selected!!.removing);h.secrets.deleteFailure=false;assertTrue(h.repository.remove(h.wallet.id,true).wallets.isEmpty())}
    @Test fun interruptedRemovalCannotBypassPendingLock()=runBlocking{val h=TransferHarness().start();h.send();val c=h.repository.load();h.catalog.write(CatalogCodec.encode(c.copy(wallets=c.wallets.map{it.copy(removing=true)})));try{h.repository.remove(h.wallet.id,true);fail("Pending deletion resumed")}catch(e:WalletException){assertEquals(WalletError.TRANSFER_LOCKED,e.reason)};assertTrue(h.secrets.walletIds().contains(h.wallet.id))}

    @Test fun signedWithoutAttemptCannotBecomeLocallyPendingFromAnUnverifiedPoolObservation(){
        val s=signed();val ledger=TransferLedger(MemoryJournal())
        ledger.acquire(s.draft.source).use{ledger.prepared(s,TEST_TIME)}
        ledger.observation(s.draft.operationId,OperationObservation(TransferState.PENDING),TEST_TIME+1)
        assertEquals(TransferState.UNKNOWN,ledger.records().single().state)
        assertNotNull(ledger.pending(s.draft.source))
    }

}
