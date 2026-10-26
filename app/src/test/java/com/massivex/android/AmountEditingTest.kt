package com.massivex.android
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.massivex.android.transfer.SendInput
import org.junit.Assert.*
import org.junit.Test
class AmountEditingTest {
    @Test fun commaImmediatelyNormalizesWithCursorAndSelectionIntact(){for(range in listOf(TextRange(3),TextRange(0,3),TextRange(2,0))){val result=normalizeAmountEdit(TextFieldValue("0,1",range));assertEquals("0.1",result.text);assertEquals(range,result.selection);assertEquals(100_000_000uL,SendInput.amount(result.text).nano)}}
    @Test fun pasteNormalizesButNeverRoundsOrPermitsMalformedMoney(){val bounded=normalizeAmountEdit(TextFieldValue("1".repeat(100_000),TextRange(100_000)));assertEquals(40,bounded.text.length);assertEquals(TextRange(40),bounded.selection);for(value in listOf("-1","1,2,3","18446744074","0,0000000001","1e2")){val normalized=normalizeAmountEdit(TextFieldValue(value)).text;try{SendInput.amount(normalized);fail(value)}catch(_:Exception){}}}
}
