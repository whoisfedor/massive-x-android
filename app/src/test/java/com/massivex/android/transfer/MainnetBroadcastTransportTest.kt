package com.massivex.android.transfer

import com.massivex.android.network.*
import com.google.gson.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** Every HTTP connection is injected in memory. These tests can never contact Mainnet. */
class MainnetBroadcastTransportTest {
    private val fixture=signed()
    private inner class Wire:HttpsURLConnection(URL(RPC_ENDPOINT)) {
        var status=200;var media="application/json";var declared=-1L;var disconnected=false
        var response:(JsonObject)->String={r->"""{"jsonrpc":"2.0","id":"${r["id"].asString}","result":["${fixture.draft.operationId}"]}"""}
        var lost=false
        var beforeOutput:()->Unit={}
        var onDisconnect:()->Unit={}
        val bytes=ByteArrayOutputStream()
        override fun connect(){};override fun disconnect(){disconnected=true;onDisconnect()};override fun usingProxy()=false
        override fun getCipherSuite()="FAKE_NO_NETWORK";override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun getResponseCode():Int {if(lost)throw java.net.SocketTimeoutException();return status}
        override fun getContentType()=media;override fun getContentLengthLong()=declared
        override fun getOutputStream():OutputStream{beforeOutput();return bytes}
        override fun getInputStream():InputStream=response(JsonParser.parseString(bytes.toString()).asJsonObject).byteInputStream()
    }
    private fun permit(s:SignedTransfer=fixture):SubmissionPermit {val l=TransferLedger(MemoryJournal());l.acquire(s.draft.source);l.prepared(s,TEST_TIME);return l.beginSubmission(s,TEST_TIME)}
    private suspend fun rejects(block:suspend()->Unit){try{block();fail("Expected fail closed")}catch(e:Exception){assertTrue(e is SendFailure||e is ReadFailure)}}
    private fun adapter(w:Wire,opened:(URL)->Unit={}):MainnetBroadcastTransport=MainnetBroadcastTransport(TlsConnections{opened(it);w},{TEST_TIME})
    @Test fun canonicalPayloadOneOperationAndExactAcknowledgment()=runBlocking{
        val w=Wire();var count=0
        assertEquals(BroadcastReply.Accepted(fixture.draft.operationId),adapter(w){count++;assertEquals(RPC_ENDPOINT,it.toString())}.submit(fixture,permit()))
        val root=JsonParser.parseString(w.bytes.toString()).asJsonObject;assertEquals("send_operations",root["method"].asString)
        val batch=root["params"].asJsonArray;assertEquals(1,batch.size());val ops=batch[0].asJsonArray;assertEquals(1,ops.size())
        val op=ops[0].asJsonObject;assertEquals(setOf("serialized_content","creator_public_key","signature"),op.keySet())
        assertEquals(fixture.signature,op["signature"].asString);assertEquals(source().publicKey,op["creator_public_key"].asString)
        assertArrayEquals(fixture.draft.content,op["serialized_content"].asJsonArray.map{it.asInt.toByte()}.toByteArray())
        assertEquals(1,count);assertEquals("POST",w.requestMethod);assertFalse(w.instanceFollowRedirects);assertFalse(w.useCaches);assertEquals(5000,w.connectTimeout);assertEquals(7000,w.readTimeout);assertTrue(w.disconnected)
    }
    @Test fun rawSignatureWithoutDurablePermitCannotOpenConnection()=runBlocking{var opened=0;rejects{adapter(Wire()){opened++}.submit(fixture)};assertEquals(0,opened)}
    @Test fun samePermitCannotSubmitTwice()=runBlocking{val p=permit();var n=0;val a=adapter(Wire()){n++};a.submit(fixture,p);rejects{a.submit(fixture,p)};assertEquals(1,n)}
    @Test fun recoveredAttemptCannotMintPermit(){val l=TransferLedger(MemoryJournal());val s=fixture;l.acquire(s.draft.source);l.prepared(s,TEST_TIME);l.beginSubmission(s,TEST_TIME);try{l.beginSubmission(s,TEST_TIME);fail()}catch(e:SendFailure){assertEquals(SendError.JOURNAL,e.reason)}}
    @Test fun permitCannotBeReboundToAnotherSignedDraft()=runBlocking{var n=0;rejects{adapter(Wire()){n++}.submit(signed(review(amount="2")),permit())};assertEquals(0,n)}
    @Test fun expiredPermitCannotTransmit()=runBlocking{var n=0;rejects{MainnetBroadcastTransport(TlsConnections{n++;Wire()},{TEST_TIME+20_001}).submit(fixture,permit())};assertEquals(0,n)}
    @Test fun journalAttemptFailureDoesNotMintAuthority(){val d=MemoryJournal();val l=TransferLedger(d);val s=fixture;l.acquire(s.draft.source);l.prepared(s,TEST_TIME);d.failWrite=2;try{l.beginSubmission(s,TEST_TIME);fail()}catch(e:SendFailure){assertEquals(SendError.JOURNAL,e.reason)}}
    @Test fun explicitRejectionDoesNotProveNeverPropagated()=runBlocking{val w=Wire().apply{response={r->"""{"jsonrpc":"2.0","id":${r["id"]},"error":{"code":-1,"message":"rejected"}}"""}};assertEquals(BroadcastReply.Rejected,adapter(w).submit(fixture,permit()))}
    @Test fun malformedMismatchedMultipleAndMissingIdsFailClosed()=runBlocking{for(result in listOf("[]","[\"invalid\"]","[\"${fixture.draft.operationId}\",\"extra\"]","null","{}")){val w=Wire().apply{response={r->"""{"jsonrpc":"2.0","id":${r["id"]},"result":$result}"""}};rejects{adapter(w).submit(fixture,permit())}}}
    @Test fun wrongAttributionAndVersionFailClosed()=runBlocking{for(value in listOf("""{"jsonrpc":"2.0","id":"wrong","result":[]}""","""{"jsonrpc":"1.0","id":"wrong","result":[]}""")){rejects{adapter(Wire().apply{response={value}}).submit(fixture,permit())}}}
    @Test fun duplicateKeysOrConflictingEnvelopeFailClosed()=runBlocking{for(value in listOf("""{"id":"a","id":"b"}""","""{"jsonrpc":"2.0","id":"wrong","result":[],"error":{}}""","not json")){rejects{adapter(Wire().apply{response={value}}).submit(fixture,permit())}}}
    @Test fun redirectsHttpAndWrongMediaNeverRetry()=runBlocking{for(code in listOf(302,401,429,500)){var n=0;rejects{adapter(Wire().apply{status=code}){n++}.submit(fixture,permit())};assertEquals(1,n)};rejects{adapter(Wire().apply{media="text/html"}).submit(fixture,permit())}}
    @Test fun sizeBoundAppliesToDeclaredAndStreamingBodies()=runBlocking{rejects{adapter(Wire().apply{declared=65_537}).submit(fixture,permit())};rejects{adapter(Wire().apply{response={" ".repeat(65_537)}}).submit(fixture,permit())}}
    @Test fun packetLossAfterWriteConsumesPermitAndNeverRetries()=runBlocking{val p=permit();val w=Wire().apply{lost=true};var n=0;val a=adapter(w){n++};rejects{a.submit(fixture,p)};assertTrue(w.bytes.size()>0);rejects{a.submit(fixture,p)};assertEquals(1,n)}
    @Test fun uniqueRequestIdsForDifferentAuthorizedOperations()=runBlocking{val ids=mutableSetOf<String>();for(amount in listOf("1","2")){val s=signed(review(amount=amount));val w=Wire().apply{response={r->ids+=r["id"].asString;"""{"jsonrpc":"2.0","id":${r["id"]},"result":["${s.draft.operationId}"]}"""}};adapter(w).submit(s,permit(s))};assertEquals(2,ids.size)}
    @Test fun actualAdapterThroughCoordinatorPreservesUncertaintyLock()=runBlocking{val w=Wire().apply{lost=true};val h=TransferHarness(broadcast=adapter(w)).start();h.send();assertEquals(TransferState.UNKNOWN,h.record().state);assertNotNull(h.ledger.pending(h.record().signed.draft.source));assertTrue(w.bytes.size()>0)}
    @Test fun connectionDelayCannotWriteAnExpiredReviewedOperation()=runBlocking{
        val now=java.util.concurrent.atomic.AtomicLong(TEST_TIME)
        val w=Wire().apply{beforeOutput={now.addAndGet(20_001)}}
        val a=MainnetBroadcastTransport(TlsConnections{w},{now.get()})
        rejects{a.submit(fixture,permit())};assertEquals(0,w.bytes.size())
    }
    @Test fun cancellationDuringConnectionCannotStartOperationBody()=runBlocking{
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val w=Wire().apply{beforeOutput={entered.countDown();try{release.await(3,java.util.concurrent.TimeUnit.SECONDS)}catch(_:InterruptedException){/* Simulate a connection returning after cancellation. */}};onDisconnect={release.countDown()}}
        val p=permit();val a=adapter(w)
        val task=async(Dispatchers.Default){a.submit(fixture,p)}
        assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));task.cancelAndJoin()
        assertTrue(w.disconnected);assertEquals(0,w.bytes.size());rejects{a.submit(fixture,p)}
    }

}
