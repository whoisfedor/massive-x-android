package com.massivex.android
import com.massivex.android.transfer.*
import org.junit.Assert.*
import org.junit.Test
class SendStatePresentationTest {
    @Test fun durableAttemptWithoutAcknowledgmentRemainsUnknownAcrossRestart(){
        val s=signed();val disk=MemoryJournal();val ledger=TransferLedger(disk)
        ledger.acquire(s.draft.source).use{ledger.prepared(s,TEST_TIME);ledger.beginSubmission(s,TEST_TIME)}
        val restored=TransferLedger(disk).records().single()
        assertEquals(TransferDisplayState.UNKNOWN,restored.displayState());assertNotNull(TransferLedger(disk).pending(s.draft.source))
        ledger.transition(s.draft.operationId,TransferState.PENDING,TEST_TIME+1,TransferNote.ACCEPTED_NOT_FINAL)
        assertEquals(TransferDisplayState.PENDING,TransferLedger(disk).records().single().displayState())
    }
    @Test fun greenCompletionRequiresStoredSuccessfulResolution(){
        val s=signed()
        assertEquals(TransferDisplayState.UNKNOWN,TransferRecord(s,TransferState.VERIFIED_SUCCESS,TEST_TIME,TransferNote.TRUSTED_PROVIDER).displayState())
        for(success in listOf(true,false)){
            val e=resolution(s,success);val record=TransferRecord(s,if(success)TransferState.VERIFIED_SUCCESS else TransferState.VERIFIED_FAILURE,e.acceptedAt,TransferNote.TRUSTED_PROVIDER,if(success)1 else 2,listOf(e.first,e.second),e)
            record.validate();assertEquals(if(success)TransferDisplayState.COMPLETED else TransferDisplayState.FAILED,record.displayState())
        }
    }
}
