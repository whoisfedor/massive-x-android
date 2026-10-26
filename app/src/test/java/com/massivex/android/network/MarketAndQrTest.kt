package com.massivex.android.network

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.massivex.android.receive.ReceiveQr
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class MarketAndQrTest {
    private val now=1700000000000L
    private fun response(text:String,status:Int=200)=HttpPayload(status,"application/json",text.toByteArray())
    private fun service(price:String="""{"massa":{"usd":0.25,"eur":0.2,"last_updated_at":1700000000},"bitcoin":{"usd":25000,"last_updated_at":1700000000}}""", fx:String="""{"date":"2023-11-14","base":"USD","quote":"AED","rate":3.67}""")=PublicMarketReads(ReadTransport { request -> when {
        request.url.contains("supported_vs_currencies") -> response("[\"usd\",\"eur\",\"fake\",\"bits\"]")
        request.url.endsWith("/currencies") -> response("[{\"iso_code\":\"AED\",\"end_date\":\"2023-11-14\"}]")
        request.url.contains("/v2/rate/") -> response(fx)
        else -> response(price)
    } })
    @Test fun currencyOrderingOnlyVerifiedCodes()=runBlocking { assertEquals(listOf("USD","EUR","BTC","ETH","SOL","AED"),service().currencies(now)) }
    @Test fun directRateAndExactConversion()=runBlocking { val q=service().quote("USD",now); assertEquals("0.25",q.perMas.toPlainString()); assertEquals("0.31",q.value(1230000000uL)); assertEquals("CoinGecko",q.source) }
    @Test fun cryptoUsesUsdCrossAndFreshTimes()=runBlocking { val q=service().quote("BTC",now); assertEquals(0,q.perMas.compareTo(BigDecimal("0.00001"))); assertEquals("0.000010000000",q.value(1000000000uL)) }
    @Test fun fxOnlyUsesDeclaredCurrencyAndIdentity()=runBlocking { val q=service().quote("AED",now); assertEquals(0,q.perMas.compareTo(BigDecimal("0.9175"))); assertEquals("CoinGecko + Frankfurter",q.source) }
    @Test fun unsupportedCurrencyCannotMakePriceRequest()=runBlocking { try{service().quote("ZZZ",now);fail("Expected unsupported")}catch(e:ReadFailure){assertEquals(ReadError.UNSUPPORTED,e.reason)} }
    @Test fun missingPriceFailsWithoutFabrication()=runBlocking { try{service("{}").quote("USD",now);fail("Expected missing price")}catch(e:ReadFailure){assertEquals(ReadError.INVALID,e.reason)} }
    @Test fun negativeZeroNumericStringAndExponentBounds() { for(v in listOf("0","-1","\"0.25\"","1e100")) expectRead(ReadError.INVALID){ PublicMarketReads.decimal(json(v)) } }
    @Test fun smallScientificPriceRemainsDecimal() { assertEquals(0,PublicMarketReads.decimal(json("1e-12")).compareTo(BigDecimal("0.000000000001"))) }
    @Test fun stalePriceRejected()=runBlocking { try{service("""{"massa":{"usd":1,"last_updated_at":1699999000}}""").quote("USD",now);fail("Expected stale")}catch(e:ReadFailure){assertEquals(ReadError.STALE,e.reason)} }
    @Test fun futurePriceRejected()=runBlocking { try{service("""{"massa":{"usd":1,"last_updated_at":1700000100}}""").quote("USD",now);fail("Expected future")}catch(e:ReadFailure){assertEquals(ReadError.STALE,e.reason)} }
    @Test fun cryptoTimeMismatchRejected()=runBlocking { try{service("""{"massa":{"usd":1,"last_updated_at":1700000000},"bitcoin":{"usd":2,"last_updated_at":1699999600}}""").quote("BTC",now);fail("Expected skew")}catch(e:ReadFailure){assertEquals(ReadError.INVALID,e.reason)} }
    @Test fun wrongFxBaseAndDateRejected()=runBlocking { for(fx in listOf("""{"base":"EUR","quote":"AED","date":"2023-11-14","rate":1}""","""{"base":"USD","quote":"AED","date":"2023-02-30","rate":1}""")){try{service(fx=fx).quote("AED",now);fail("Expected bad FX")}catch(e:ReadFailure){assertEquals(ReadError.INVALID,e.reason)}} }
    @Test fun fxOlderThanSevenDaysRejected()=runBlocking { try{service(fx="""{"base":"USD","quote":"AED","date":"2023-11-01","rate":1}""").quote("AED",now);fail("Expected stale FX")}catch(e:ReadFailure){assertEquals(ReadError.STALE,e.reason)} }
    @Test fun optionalFxFailureDoesNotBreakDirectQuotes()=runBlocking { val s=PublicMarketReads(ReadTransport { r -> if(r.url.endsWith("/currencies")) response("{}",500) else if(r.url.contains("supported_vs")) response("[\"usd\"]") else response("""{"massa":{"usd":1,"last_updated_at":1700000000}}""") });assertEquals(BigDecimal.ONE,s.quote("USD",now).perMas) }
    @Test fun providerRateLimitExplicit()=runBlocking {try{PublicMarketReads(ReadTransport{response("{}",429)}).currencies(now);fail("Expected rate limit")}catch(e:ReadFailure){assertEquals(ReadError.RATE_LIMIT,e.reason)} }
    @Test fun currencyMetadataCachedSixHours()=runBlocking { var count=0;val s=PublicMarketReads(ReadTransport{r->count++; if(r.url.contains("supported_vs"))response("[\"usd\"]")else response("[]")});s.currencies(now);s.currencies(now+1000);assertEquals(2,count);s.currencies(now+6*3600000+1);assertEquals(4,count) }
    @Test fun quoteExpiresAndNeverDisplaysStaleFiat() {val q=MarketQuote("USD",BigDecimal.ONE,now);assertTrue(q.usable(now+900000));assertFalse(q.usable(now+900001));val s=ReadUi(quote=q,currency="USD",now=now+900001);assertNull(s.quoteUsable)}
    @Test fun tinyPositiveEstimateHasHonestThreshold() {val q=MarketQuote("USD",BigDecimal("0.000001"),now);assertEquals("<0.01",q.value(1uL))}
    @Test fun fiatRoundingDoesNotAlterNanoMas() {val q=MarketQuote("USD",BigDecimal.ONE,now);assertEquals("1.00",q.value(1005000000uL));assertEquals("1.02",q.value(1015000000uL))}
    @Test fun qrDecodeMatchesExactAuTextAtPracticalSizes() {val addresses=listOf("AU1rqxSMewKgJU2m5orM2HFuMg7ioEFiECuhX1sSken92VxUBfQy","AU12MBEEKVAFZhxYzeyNYpAAcKEu667KKjHBos9i65htLwPkHyQG4");for(a in addresses)for(size in listOf(192,256,384)){val pixels=ReceiveQr.pixels(a,size);val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size,size,pixels))));assertEquals(a,decoded.text)}}
    @Test fun qrRejectsPrivateMaterialAndPaymentUris() {for(a in listOf("Sinvalid","massa:AUbad","https://bad.example/","AUbad")){try{ReceiveQr.matrix(a);fail("Expected AU-only QR")}catch(_:IllegalArgumentException){}}}
    @Test fun qrHasOpaqueWhiteQuietZoneAndDeterministicPixels() {val a="AU1rqxSMewKgJU2m5orM2HFuMg7ioEFiECuhX1sSken92VxUBfQy";val p=ReceiveQr.pixels(a);assertEquals(-1,p[0]);assertArrayEquals(p,ReceiveQr.pixels(a));assertTrue(p.all{it== -1||it==0xff000000.toInt()})}
    @Test fun fxCoverageDoesNotOfferObsoleteCurrency()=runBlocking{
        val s=PublicMarketReads(ReadTransport{r->if(r.url.contains("supported_vs"))response("[\"usd\"]")else response("[{\"iso_code\":\"ZAR\",\"end_date\":\"2000-01-01\"}]")})
        assertFalse("ZAR" in s.currencies(now))
    }
    @Test fun fxDateBoundsPreventFreshnessOverflow(){for(v in listOf("+999999999-12-31","-292275055-01-01","2023-02-30"))expectRead(ReadError.INVALID){PublicMarketReads.dateEpoch(v)}}

}
