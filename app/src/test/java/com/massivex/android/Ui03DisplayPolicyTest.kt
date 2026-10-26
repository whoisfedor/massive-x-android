package com.massivex.android

import com.massivex.android.network.*
import com.massivex.massa.MASAmount
import org.junit.Assert.*
import org.junit.Test

class Ui03DisplayPolicyTest {
    private val balance=BalanceRead("public",MASAmount(0uL),MASAmount(0uL),NodeStatus("MAIN.5.1",1),100000L)
    @Test fun neverInferHealthFromNoStatus(){assertFalse(networkHealthy(ReadUi(now=100000)))}
    @Test fun freshValidatedReadSupportsHealth(){assertTrue(networkHealthy(ReadUi(balance=balance,now=160000)))}
    @Test fun staleFutureAndFailedReadsNeverShowGreen(){
        assertFalse(networkHealthy(ReadUi(balance=balance,now=160001)))
        assertFalse(networkHealthy(ReadUi(balance=balance,now=99999)))
        for(error in ReadError.entries)assertFalse(networkHealthy(ReadUi(balance=balance,now=100001,balanceError=error)))
    }
    @Test fun balancedAddressRetainsEveryCanonicalCharacter(){
        for(length in 49..70){val input="AU"+"x".repeat(length-2);val lines=balancedPublic(input).split('\n');assertEquals(input,lines.joinToString(""));assertTrue(kotlin.math.abs(lines[0].length-lines[1].length)<=1)}
    }
}
