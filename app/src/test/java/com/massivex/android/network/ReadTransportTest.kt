package com.massivex.android.network

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.URL
import java.net.SocketTimeoutException
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

class ReadTransportTest {
    private class FakeConnection(val code:Int=200,val type:String="application/json",val data:ByteArray="{}".toByteArray(),val declared:Long=data.size.toLong(),val source:InputStream?=null):HttpsURLConnection(URL(ReadRequest.RPC)){
        var disconnected=false;val sent=ByteArrayOutputStream()
        override fun connect(){}
        override fun disconnect(){disconnected=true;source?.close()}
        override fun usingProxy()=false
        override fun getCipherSuite()="TLS_TEST_ONLY"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun getResponseCode()=code
        override fun getContentType()=type
        override fun getContentLengthLong()=declared
        override fun getInputStream():InputStream=source?:ByteArrayInputStream(data)
        override fun getOutputStream():OutputStream=sent
    }
    @Test fun transportEnforcesTlsHostAndAuthority(){for(u in listOf("http://mainnet.massa.net/api/v2","https://evil.example/","https://x@mainnet.massa.net/api/v2","https://mainnet.massa.net:444/api/v2","https://mainnet.massa.net/api/v2#fragment"))expectRead(ReadError.INVALID){PlatformReadTransport.validateUrl(URL(u))}}
    @Test fun nativePostHasBoundedTimeoutsAndNoRedirects()=runBlocking{val c=FakeConnection();val t=PlatformReadTransport(TlsConnections{c});val r=t.execute(ReadRequest.rpc(RpcRead.STATUS,"id"));assertEquals(200,r.status);assertEquals("POST",c.requestMethod);assertEquals(5000,c.connectTimeout);assertEquals(7000,c.readTimeout);assertFalse(c.instanceFollowRedirects);assertFalse(c.useCaches);assertTrue(c.sent.toString().contains("get_status"));assertTrue(c.disconnected)}
    @Test fun httpStatusesAreStructured()=runBlocking{for(code in listOf(401,403,404,429,500)){try{PlatformReadTransport(TlsConnections{FakeConnection(code)}).execute(ReadRequest.currencies());fail("Expected HTTP rejection")}catch(e:ReadFailure){assertEquals(if(code==429)ReadError.RATE_LIMIT else ReadError.HTTP,e.reason);assertEquals(code,e.status)}}}
    @Test fun redirectsNotFollowed()=runBlocking{val c=FakeConnection(302);try{PlatformReadTransport(TlsConnections{c}).execute(ReadRequest.currencies());fail("Expected redirect rejection")}catch(e:ReadFailure){assertEquals(ReadError.REDIRECT,e.reason);assertTrue(c.disconnected)}}
    @Test fun tlsFailureNoFallback()=runBlocking{var calls=0;try{PlatformReadTransport(TlsConnections{calls++;throw SSLHandshakeException("public-test-only")}).execute(ReadRequest.currencies());fail("Expected TLS rejection")}catch(e:ReadFailure){assertEquals(ReadError.TLS,e.reason);assertEquals(1,calls)}}
    @Test fun readTimeoutMappedWithoutLeakingExceptionText()=runBlocking{try{PlatformReadTransport(TlsConnections{throw SocketTimeoutException("not shown")}).execute(ReadRequest.currencies());fail("Expected timeout")}catch(e:ReadFailure){assertEquals(ReadError.TIMEOUT,e.reason);assertFalse(e.message!!.contains("not shown"))}}
    @Test fun headerSizeAndStreamingSizeBothBounded()=runBlocking{for(c in listOf(FakeConnection(declared=1048577),FakeConnection(data=ByteArray(1048577),declared=-1))){try{PlatformReadTransport(TlsConnections{c}).execute(ReadRequest.currencies());fail("Expected size bound")}catch(e:ReadFailure){assertEquals(ReadError.TOO_LARGE,e.reason);assertTrue(c.disconnected)}}}
    @Test fun nonJsonResponseRefused()=runBlocking{try{PlatformReadTransport(TlsConnections{FakeConnection(type="text/html")}).execute(ReadRequest.currencies());fail("Expected media type rejection")}catch(e:ReadFailure){assertEquals(ReadError.INVALID,e.reason)}}
    @Test fun cancellationDisconnectsActualTransportWork()=runBlocking{
        val entered=CountDownLatch(1);val released=CountDownLatch(1)
        val input=object:InputStream(){override fun read():Int{entered.countDown();released.await(3,TimeUnit.SECONDS);return -1};override fun close(){released.countDown()}}
        val c=FakeConnection(declared=-1,source=input);val task=async(Dispatchers.Default){PlatformReadTransport(TlsConnections{c}).execute(ReadRequest.currencies())}
        assertTrue(entered.await(3,TimeUnit.SECONDS));task.cancelAndJoin();assertTrue(task.isCancelled);assertTrue(c.disconnected)
    }
    @Test fun arbitraryRpcMethodHasNoProductionFactory(){assertEquals(2,RpcRead.entries.size);assertTrue(ReadRequest::class.java.declaredConstructors.filter{!it.isSynthetic}.all{java.lang.reflect.Modifier.isPrivate(it.modifiers)})}
    @Test fun wallClockDeadlineStopsAStalledBody()=runBlocking {
        val released=CountDownLatch(1)
        val input=object:InputStream(){override fun read():Int{released.await(20,TimeUnit.SECONDS);return -1};override fun close(){released.countDown()}}
        val c=FakeConnection(declared=-1,source=input)
        val start=System.nanoTime()
        try{PlatformReadTransport(TlsConnections{c}).execute(ReadRequest.currencies());fail("Expected bounded call timeout")}
        catch(e:ReadFailure){assertEquals(ReadError.TIMEOUT,e.reason);assertTrue(c.disconnected);assertTrue((System.nanoTime()-start)/1000000 < 19000)}
    }

}
