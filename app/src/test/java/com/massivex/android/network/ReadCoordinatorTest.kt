package com.massivex.android.network

import com.massivex.massa.MASAmount
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ReadCoordinatorTest {
    private val a="AU1rqxSMewKgJU2m5orM2HFuMg7ioEFiECuhX1sSken92VxUBfQy"
    private val b="AU12MBEEKVAFZhxYzeyNYpAAcKEu667KKjHBos9i65htLwPkHyQG4"
    private var now=1700000000000L
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    private val prefs=object:DisplayPreferences{override var currency="USD";override var fiat=false;override var filter=ActivityFilter.ALL}
    private fun balance(address:String,value:ULong=1000000000uL)=BalanceRead(address,MASAmount(value),MASAmount(value),NodeStatus("MAIN.5.1",1),now)
    private val market=object:MarketReader{override suspend fun currencies(now:Long)=listOf("USD","EUR");override suspend fun quote(currency:String,now:Long)=MarketQuote(currency,BigDecimal.ONE,now)}
    private val history=object:HistoryReader{override suspend fun page(address:String,cursor:HistoryCursor?)=HistoryPage(emptyList(),null)}
    private fun coordinator(reader:BalanceReader=object:BalanceReader{override suspend fun balance(address:String)=this@ReadCoordinatorTest.balance(address,1000000000uL)}, pages:HistoryReader=history, quotes:MarketReader=market)=ReadCoordinator(scope,reader,pages,quotes,prefs){now}
    @After fun cleanup(){scope.cancel()}
    @Test fun selectionClearsSynchronouslyAndRejectsLateCrossWalletResult()=runBlocking {
        val pending=CompletableDeferred<BalanceRead>();val c=coordinator(object:BalanceReader{override suspend fun balance(address:String)=if(address==a)withContext(NonCancellable){pending.await()}else this@ReadCoordinatorTest.balance(address,2000000000uL)})
        c.select("a",a);c.resume();assertTrue(c.state.value.balanceLoading);now+=3000;c.select("b",b);assertEquals(b,c.state.value.balance!!.address)
        pending.complete(balance(a));yield();assertEquals(b,c.state.value.balance!!.address);assertEquals("b",c.state.value.walletId)
    }
    @Test fun presentationGatesStaleFrameBeforeEffectRuns(){val s=ReadUi(walletId="a",address=a,balance=balance(a));assertNull(s.forWallet("b",b).balance);assertTrue(s.forWallet("b",b).operations.isEmpty())}
    @Test fun stopCancelsAndRejectsLateCallback()=runBlocking {val pending=CompletableDeferred<BalanceRead>();val c=coordinator(object:BalanceReader{override suspend fun balance(address:String)=withContext(NonCancellable){pending.await()}});c.select("a",a);c.resume();c.stop();pending.complete(balance(a));yield();assertNull(c.state.value.balance);assertFalse(c.state.value.balanceLoading)}
    @Test fun failureCannotFabricateZero(){val c=coordinator(object:BalanceReader{override suspend fun balance(address:String):BalanceRead=throw ReadFailure(ReadError.TIMEOUT)});c.select("a",a);c.resume();assertNull(c.state.value.balance);assertEquals(ReadError.TIMEOUT,c.state.value.balanceError)}
    @Test fun confirmedDataRetainedOnlyForSameWalletOnRefreshFailure(){var fail=false;val c=coordinator(object:BalanceReader{override suspend fun balance(address:String)=if(fail)throw ReadFailure(ReadError.OFFLINE)else this@ReadCoordinatorTest.balance(address)});c.select("a",a);c.resume();fail=true;now+=3000;c.refreshBalance();assertNotNull(c.state.value.balance);assertTrue(c.state.value.stale);c.select("b",b);assertNull(c.state.value.balance)}
    @Test fun manualRefreshDebounced(){var count=0;val c=coordinator(object:BalanceReader{override suspend fun balance(address:String):BalanceRead{count++;return this@ReadCoordinatorTest.balance(address)}});c.select("a",a);c.resume();c.refreshBalance();assertEquals(1,count);now+=2000;c.refreshBalance();assertEquals(2,count)}
    @Test fun backgroundHasNoPeriodicReads(){var count=0;val c=coordinator(object:BalanceReader{override suspend fun balance(address:String):BalanceRead{count++;return this@ReadCoordinatorTest.balance(address)}});c.select("a",a);assertEquals(0,count);c.resume();assertEquals(1,count);c.stop();now+=100000;c.tick();c.refresh();assertEquals(1,count)}
    @Test fun quoteAndCurrencyPreferencesSeparateAndPersistent(){val c=coordinator();c.select("a",a);c.resume();c.toggleFiat();c.currency("EUR");assertTrue(prefs.fiat);assertEquals("EUR",prefs.currency);assertNull(c.state.value.quote);c.currency("ZZZ");assertEquals("EUR",prefs.currency)}
    @Test fun staleQuoteAutomaticallyBecomesUnusableOnTick(){val c=coordinator();c.select("a",a);c.resume();assertNotNull(c.state.value.quoteUsable);now+=900001;c.tick();assertNull(c.state.value.quoteUsable)}
    @Test fun historyFailureIsNotSuccessfulEmpty(){val c=coordinator(pages=object:HistoryReader{override suspend fun page(address:String,cursor:HistoryCursor?):HistoryPage=throw ReadFailure(ReadError.HTTP)});c.select("a",a);c.resume();assertFalse(c.state.value.historyLoaded);assertEquals(ReadError.HTTP,c.state.value.historyError)}
    @Test fun historySuccessEmptyDistinctFromIdle(){val c=coordinator();c.select("a",a);c.resume();assertTrue(c.state.value.historyLoaded);assertNull(c.state.value.historyError);assertTrue(c.state.value.operations.isEmpty())}
    @Test fun paginationBoundAndRepeatedCursorStopped(){var n=0;val cursor=HistoryCursor("same",null);val c=coordinator(pages=object:HistoryReader{override suspend fun page(address:String,cursor:HistoryCursor?):HistoryPage{n++;return HistoryPage(emptyList(),HistoryCursor("same",null))}});c.select("a",a);c.resume();assertEquals(cursor,c.state.value.cursor);now+=3000;c.refreshHistory(true);assertNull(c.state.value.cursor);now+=3000;c.refreshHistory(true);assertEquals(2,n)}
    @Test fun detailCannotEscapeCurrentWallet(){val c=coordinator();c.select("a",a);c.detail("nonexistent");assertNull(c.state.value.detailId);c.select("b",b);assertNull(c.state.value.detailId)}
    @Test fun sourceCannotReturnOtherWalletIdentity(){val c=coordinator(object:BalanceReader{override suspend fun balance(address:String)=this@ReadCoordinatorTest.balance(b)});c.select("a",a);c.resume();assertNull(c.state.value.balance);assertEquals(ReadError.INVALID,c.state.value.balanceError)}
    @Test fun selectingCurrentCurrencyKeepsValidQuote(){val c=coordinator();c.select("a",a);c.resume();val before=c.state.value.quote;c.currency("USD");assertEquals(before,c.state.value.quote)}
    @Test fun cancelledCurrencyRoundTripCannotOverwriteNewQuote()=runBlocking{
        val old=CompletableDeferred<MarketQuote>();var usdCalls=0
        val provider=object:MarketReader{
            override suspend fun currencies(now:Long)=listOf("USD","EUR")
            override suspend fun quote(currency:String,now:Long):MarketQuote{
                if(currency=="USD"&&++usdCalls==1)return withContext(NonCancellable){old.await()}
                return MarketQuote(currency,BigDecimal("3"),now)
            }
        }
        val c=coordinator(quotes=provider);c.select("a",a);c.resume();now+=16000;c.currency("EUR");now+=16000;c.currency("USD")
        old.complete(MarketQuote("USD",BigDecimal.ONE,now));yield();assertEquals(BigDecimal("3"),c.state.value.quote!!.perMas)
    }

    @Test fun backwardsClockMakesCachedBalanceExplicitlyStale(){val c=coordinator();c.select("a",a);c.resume();now-=120000;c.tick();assertTrue(c.state.value.stale);assertNotNull(c.state.value.balance)}

    @Test fun currencyChangesCoalesceUntilQuoteCooldownExpires()=runBlocking{
        val wait=CompletableDeferred<Unit>();var calls=0
        val provider=object:MarketReader{
            override suspend fun currencies(now:Long)=listOf("USD","EUR","GBP")
            override suspend fun quote(currency:String,now:Long):MarketQuote{calls++;return MarketQuote(currency,BigDecimal.ONE,now)}
        }
        val reader=object:BalanceReader{override suspend fun balance(address:String)=this@ReadCoordinatorTest.balance(address)}
        val c=ReadCoordinator(scope,reader,history,provider,prefs,pause={wait.await()}){now}
        c.select("a",a);c.resume();c.currency("EUR");c.currency("GBP")
        assertTrue(c.state.value.quoteLoading);assertEquals(1,calls)
        now+=15001;wait.complete(Unit);yield();assertEquals(2,calls);assertEquals("GBP",c.state.value.quote!!.currency)
    }

}
