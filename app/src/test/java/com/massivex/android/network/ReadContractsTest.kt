package com.massivex.android.network

import com.google.gson.*
import com.massivex.massa.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

private const val A = "AU1rqxSMewKgJU2m5orM2HFuMg7ioEFiECuhX1sSken92VxUBfQy"
private const val B = "AU12MBEEKVAFZhxYzeyNYpAAcKEu667KKjHBos9i65htLwPkHyQG4"
internal fun json(text: String) = ReadJson.parse(HttpPayload(200, "application/json", text.toByteArray()))
internal fun expectRead(error: ReadError, block: () -> Unit) { try { block(); fail("Expected structured read rejection") } catch (e: ReadFailure) { assertEquals(error, e.reason) } }
internal fun operationRow(id: String = MassaCodec.encode(ByteArray(32) { 3 }, "O"), type: String = "Transaction", from: String = A, to: String? = B, value: String = "1234567890", time: String = "1700000000000"): JsonObject = JsonObject().apply {
    addProperty("hash", id); addProperty("type", type); addProperty("from", from); if (to != null) addProperty("to", to)
    addProperty("value", value); addProperty("transaction_fee", "1000000"); addProperty("block_time", time); addProperty("status", "Final"); addProperty("op_exec_status", false)
}
internal fun pageJson(vararg rows: JsonObject, created: Boolean = true): JsonObject = JsonObject().apply { add(if (created) "operationsCreated" else "operationsReceived", JsonArray().apply { rows.forEach(::add) }) }

class ReadContractsTest {
    private fun balances(final: String = "1.230000000", candidate: String = "2"): JsonElement = json("""[{"address":"$A","final_balance":"$final","candidate_balance":"$candidate"}]""")
    @Test fun finalAndCandidateRemainDistinctAndExact() { val pair = MainnetReads.decodeBalance(balances(), A); assertEquals(1230000000uL, pair.first.nano); assertEquals(2000000000uL, pair.second.nano) }
    @Test fun maximumUInt64AndZero() { assertEquals(ULong.MAX_VALUE, MainnetReads.decodeBalance(balances("18446744073.709551615", "0.000000000"), A).first.nano) }
    @Test fun invalidAmountsAreNeverZeroFallback() { listOf("-1", "NaN", "Infinity", "1e3", "01", " 1", ".1", "1.", "1.0000000001", "18446744073.709551616").forEach { v -> expectRead(ReadError.INVALID) { MainnetReads.decodeBalance(balances(v), A) } } }
    @Test fun addressIdentityMustMatch() { expectRead(ReadError.INVALID) { MainnetReads.decodeBalance(balances(), B) } }
    @Test fun emptyExtraAndMissingRowsRejected() { for (v in listOf("[]", "[{},{}]", "[{}]", "{}", "null")) expectRead(ReadError.INVALID) { MainnetReads.decodeBalance(json(v), A) } }
    @Test fun numericBalancesNotAcceptedAsStrings() { expectRead(ReadError.INVALID) { MainnetReads.decodeBalance(json("""[{"address":"$A","final_balance":1,"candidate_balance":"0"}]"""), A) } }
    @Test fun envelopeIdAndVersionChecked() { for (v in listOf("""{"jsonrpc":"1.0","id":"x","result":{}}""", """{"jsonrpc":"2.0","id":"other","result":{}}""", """{"jsonrpc":"2.0","id":1,"result":{}}""")) expectRead(ReadError.INVALID) { MainnetReads.envelope(json(v), "x") } }
    @Test fun resultErrorExclusivity() { for (v in listOf("""{"jsonrpc":"2.0","id":"x"}""", """{"jsonrpc":"2.0","id":"x","result":{},"error":{"code":1}}""", """{"jsonrpc":"2.0","id":"x","error":{"code":-1}}""")) expectRead(ReadError.INVALID) { MainnetReads.envelope(json(v), "x") } }
    @Test fun nodeChainMismatchStopsAddressDisclosure() = runBlocking {
        val methods = mutableListOf<String>()
        val client = MainnetReads(ReadTransport { request -> val body = JsonParser.parseString(request.body!!.toString(Charsets.UTF_8)).asJsonObject; methods += body["method"].asString
            HttpPayload(200, "application/json", """{"jsonrpc":"2.0","id":"${body["id"].asString}","result":{"chain_id":77,"version":"MAIN.5.1","current_cycle":1}}""".toByteArray()) })
        try { client.balance(A); fail("Expected identity guard") } catch (e: ReadFailure) { assertEquals(ReadError.WRONG_NETWORK, e.reason) }
        assertEquals(listOf("get_status"), methods)
    }
    @Test fun validLiveContractMockUsesOnlyTwoReads() = runBlocking {
        var count = 0
        val client = MainnetReads(ReadTransport { request -> count++; val body = JsonParser.parseString(request.body!!.toString(Charsets.UTF_8)).asJsonObject
            val result = if (body["method"].asString == "get_status") """{"chain_id":77658377,"version":"MAIN.5.0","current_cycle":1}""" else balances().toString()
            HttpPayload(200, "application/json", """{"jsonrpc":"2.0","id":"${body["id"].asString}","result":$result}""".toByteArray()) }, { 100 })
        assertEquals("MAIN.5.0", client.balance(A).node.version); assertEquals(2, count)
    }
    @Test fun malformedAndDuplicateJsonRejected() { for (v in listOf("{", "[] trailing", "{a:1}", "{\"a\":1,\"a\":2}", "NaN", "[01]")) expectRead(ReadError.INVALID) { json(v) } }
    @Test fun invalidUtf8Rejected() { expectRead(ReadError.INVALID) { ReadJson.parse(HttpPayload(200, "application/json", byteArrayOf(0xc3.toByte(), 0x28))) } }
    @Test fun oversizedAndDeepJsonRejected() { expectRead(ReadError.TOO_LARGE) { ReadJson.parse(HttpPayload(200, "application/json", ByteArray(2097153))) }; expectRead(ReadError.INVALID) { json("[".repeat(25) + "0" + "]".repeat(25)) } }
    @Test fun tooManyArrayElementsRejected() { expectRead(ReadError.INVALID) { json("[" + List(2001) { "0" }.joinToString(",") + "]") } }
    @Test fun httpErrorsNeverBecomeEmptyBalances() { for (status in listOf(401,403,404,429,500)) { val expected = if (status == 429) ReadError.RATE_LIMIT else ReadError.HTTP; expectRead(expected) { ReadJson.parse(HttpPayload(status,"application/json", "{}".toByteArray())) } } }
    @Test fun redirectAndContentTypeRejected() { expectRead(ReadError.REDIRECT) { ReadJson.parse(HttpPayload(302,"application/json", byteArrayOf())) }; expectRead(ReadError.INVALID) { ReadJson.parse(HttpPayload(200,"text/html", "{}".toByteArray())) } }
    @Test fun onlyFixedReadMethodsAndValidatedAddresses() { assertEquals(setOf("get_status", "get_addresses"), RpcRead.entries.map { it.wire }.toSet()); expectRead(ReadError.INVALID) { ReadRequest.rpc(RpcRead.ADDRESSES,"id", "AU?injection") }; expectRead(ReadError.INVALID) { ReadRequest.rpc(RpcRead.STATUS,"id",A) } }
    @Test fun cursorCannotInjectQueryOrHost() { val r = ReadRequest.history(A,HistorySide.CREATED,"a+b&evil=x"); assertTrue(r.url.endsWith("cursorCreated=a%2Bb%26evil%3Dx")); expectRead(ReadError.INVALID) { ReadRequest.history(A,HistorySide.CREATED,"x".repeat(8193)) } }
    @Test fun emptyHistoryIsSuccessfulEmptyPage() { val page = ExplorerReads.decode(pageJson(),A,HistorySide.CREATED); assertTrue(page.operations.isEmpty()); assertNull(page.cursor) }
    @Test fun malformedHistoryIsFailureNotEmpty() { expectRead(ReadError.INVALID) { ExplorerReads.decode(json("{}"),A,HistorySide.CREATED) }; expectRead(ReadError.INVALID) { ExplorerReads.decode(pageJson(JsonObject()),A,HistorySide.CREATED) } }
    @Test fun historyNanoUnitsAndFeeAreNotMasUnits() { val op = ExplorerReads.decode(pageJson(operationRow()),A,HistorySide.CREATED).operations.single(); assertEquals("1.23456789",op.amount!!.text); assertEquals("0.001",op.fee!!.text); assertEquals(OperationDirection.OUTGOING,op.direction(A)); assertFalse(op.executed!!) }
    @Test fun directionSelfIncomingAndUnknown() { val self = operationRow(to=A); assertEquals(OperationDirection.SELF, ExplorerReads.decode(pageJson(self),A,HistorySide.CREATED).operations.single().direction(A)); val incoming = operationRow(from=B,to=A); assertEquals(OperationDirection.INCOMING,ExplorerReads.decode(pageJson(incoming),A,HistorySide.CREATED).operations.single().direction(A)) }
    @Test fun subNanoAndOverflowNeverRounded() { for (v in listOf("1.1","18446744073709551616","-1","NaN")) { val page=ExplorerReads.decode(pageJson(operationRow(value=v)),A,HistorySide.CREATED); assertNull(page.operations.single().amount); assertTrue(page.imprecise>0) } }
    @Test fun zeroOnlyNanoFractionAcceptedExactly() { assertEquals("0.000000001", ExplorerReads.decode(pageJson(operationRow(value="1.000")),A,HistorySide.CREATED).operations.single().amount!!.text) }
    @Test fun contractsDoNotInventTransferAmounts() { val op=ExplorerReads.decode(pageJson(operationRow(type="CallSC")),A,HistorySide.CREATED).operations.single(); assertNull(op.amount); assertEquals(OperationDirection.CONTRACT,op.direction(A)); assertTrue(ActivityFilter.ASC.includes(op,A)); assertFalse(ActivityFilter.ALL.includes(op,A)) }
    @Test fun autonomousAndInternalIdsNeverCreateUnsafeLinks() { val raw=operationRow(id="ASC_1_I2_T3"); val op=ExplorerReads.decode(pageJson(raw),A,HistorySide.CREATED).operations.single(); assertNull(op.explorerUrl); assertTrue(ActivityFilter.ASC.includes(op,A)); expectRead(ReadError.INVALID) { HistoryIdentity.parse("ASC_../../x") } }
    @Test fun parentOperationInternalIndexAndChecksumValidated() { val id=MassaCodec.encode(ByteArray(32){7},"O"); assertEquals(id,HistoryIdentity.parse(id+"_0").parent); expectRead(ReadError.INVALID) { HistoryIdentity.parse(id+"_00") }; expectRead(ReadError.INVALID) { HistoryIdentity.parse("Obad") } }
    @Test fun unrelatedAddressRowsRejected() { expectRead(ReadError.INVALID) { ExplorerReads.decode(pageJson(operationRow(from=B,to=B)),A,HistorySide.CREATED) } }
    @Test fun invalidRowsPreserveValidRowsWithIssue() { val p=ExplorerReads.decode(pageJson(operationRow(),JsonObject()),A,HistorySide.CREATED); assertEquals(1,p.invalidRows); assertEquals(1,p.operations.size) }
    @Test fun filtersMatchExternalTransfersAndAsc() { val op=ExplorerReads.decode(pageJson(operationRow()),A,HistorySide.CREATED).operations.single(); assertTrue(ActivityFilter.ALL.includes(op,A)); assertTrue(ActivityFilter.SENT.includes(op,A)); assertFalse(ActivityFilter.RECEIVED.includes(op,A)); assertFalse(ActivityFilter.ASC.includes(op,A)) }
    @Test fun historySortingAndDedupStable() { val x=ExplorerReads.decode(pageJson(operationRow(time="1")),A,HistorySide.CREATED).operations.single(); val y=ExplorerReads.decode(pageJson(operationRow(id=MassaCodec.encode(ByteArray(32){8},"O"),time="2")),A,HistorySide.CREATED).operations.single(); assertEquals(listOf(y.id,x.id),ExplorerReads.merge(listOf(x),listOf(y,x)).map{it.id}) }
    @Test fun historyMemoryIsBounded() { val base=ExplorerReads.decode(pageJson(operationRow()),A,HistorySide.CREATED).operations.single(); val list=List(600){base.copy(identity=HistoryIdentity("row$it",null,false))}; assertEquals(500,ExplorerReads.merge(emptyList(),list).size) }
    @Test fun partialExplorerDirectionRetainedAndRetryCursorPreserved() = runBlocking { var calls=0; val reader=ExplorerReads(ReadTransport { r -> calls++; if(r.url.contains("from=true")) HttpPayload(500,"application/json",byteArrayOf()) else HttpPayload(200,"application/json",pageJson(operationRow(),created=false).toString().toByteArray()) }); val p=reader.page(A,null); assertTrue(p.partial); assertEquals("",p.cursor!!.created); assertEquals(1,p.operations.size); assertEquals(2,calls) }
    @Test fun historyTotalFailureIsNotEmpty() = runBlocking { try { ExplorerReads(ReadTransport { HttpPayload(404,"application/json",byteArrayOf()) }).page(A,null); fail("Expected service failure") } catch(e:ReadFailure){assertEquals(ReadError.HTTP,e.reason)} }
    @Test fun requestBodyCannotBeMutatedIntoAnotherMethod(){val request=ReadRequest.rpc(RpcRead.STATUS,"id");request.body!!.fill(0);assertEquals("get_status",JsonParser.parseString(request.body!!.toString(Charsets.UTF_8)).asJsonObject["method"].asString)}

    @Test fun copiedChainIdentityIsDistinctFromInternalOrAutonomousEvent(){
        val parent=MassaCodec.encode(ByteArray(32){9},"O")
        val internal=ExplorerReads.decode(pageJson(operationRow(id=parent+"_0")),A,HistorySide.CREATED).operations.single()
        assertEquals(parent,internal.copyableId);assertEquals(parent+"_0",internal.id)
        val autonomous=ExplorerReads.decode(pageJson(operationRow(id="ASC_1_I2_T3")),A,HistorySide.CREATED).operations.single()
        assertNull(autonomous.identity.parent);assertEquals(autonomous.id,autonomous.copyableId)
    }

}
