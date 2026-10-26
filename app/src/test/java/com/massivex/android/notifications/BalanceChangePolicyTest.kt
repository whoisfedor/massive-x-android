package com.massivex.android.notifications
import org.junit.Assert.*
import org.junit.Test
class BalanceChangePolicyTest {
    private fun s(amount:String="1",period:Long=100,at:Long=100_000,identity:String="public-test",version:String="MAIN.5.1")=ConfirmedSample(identity,amount,period,version,at)
    @Test fun initialBaselineNeverAlerts(){assertFalse(BalanceChangePolicy.changed(null,s(),s(at=101_000),101_000))}
    @Test fun stableConfirmedChangeAtNewCursorAlerts(){assertTrue(BalanceChangePolicy.changed(s("0",99,0),s(),s(at=101_000),101_000))}
    @Test fun duplicateAmountNeverAlerts(){assertFalse(BalanceChangePolicy.changed(s(),s(period=101),s(period=101,at=101_000),101_000))}
    @Test fun cursorMustAdvance(){assertFalse(BalanceChangePolicy.changed(s("0",100),s(period=99),s(period=101,at=101_000),101_000));for(p in listOf(99L,100L))assertFalse(BalanceChangePolicy.changed(s("0"),s(period=p),s(period=p,at=101_000),101_000))}
    @Test fun changedWalletOrVersionRebaselines(){assertFalse(BalanceChangePolicy.changed(s("0",99,identity="other"),s(),s(at=101_000),101_000));assertFalse(BalanceChangePolicy.changed(s("0",99,version="MAIN.5.0"),s(),s(at=101_000),101_000))}
    @Test fun conflictsStaleRegressionAndUnseparatedReadsRejected(){for(second in listOf(s("2",at=101_000),s(period=99,at=101_000),s(at=100_500),s(identity="other",at=101_000),s(version="MAIN.5.0",at=101_000))){try{BalanceChangePolicy.changed(s("0",99),s(),second,101_000);fail()}catch(_:IllegalArgumentException){}};try{BalanceChangePolicy.changed(null,s(),s(at=101_000),130_000);fail()}catch(_:IllegalArgumentException){}}
}
