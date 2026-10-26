package com.massivex.android.network

import com.google.gson.*
import com.massivex.massa.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.UUID

const val MAINNET_CHAIN = 77_658_377L
data class NodeStatus(val version: String, val cycle: Long)
data class BalanceRead(val address: String, val confirmed: MASAmount, val candidate: MASAmount, val node: NodeStatus, val fetchedAt: Long)
interface BalanceReader { suspend fun balance(address: String): BalanceRead }
class MainnetReads(private val transport: ReadTransport, private val now: () -> Long = System::currentTimeMillis) : BalanceReader {
    private suspend fun rpc(method: RpcRead, address: String? = null): JsonElement {
        val id = UUID.randomUUID().toString()
        return envelope(readPublicJson(transport, ReadRequest.rpc(method, id, address)), id)
    }
    override suspend fun balance(address: String): BalanceRead {
        publicAddress(address)
        val status = decodeStatus(rpc(RpcRead.STATUS)) // Validate chain BEFORE disclosing the address.
        val amounts = decodeBalance(rpc(RpcRead.ADDRESSES, address), address)
        return BalanceRead(address, amounts.first, amounts.second, status, now())
    }
    companion object {
        fun envelope(json: JsonElement, id: String): JsonElement {
            val root = json.obj(); checkRead(root.text("jsonrpc") == "2.0" && root.text("id") == id)
            val result = root.get("result")?.takeUnless { it.isJsonNull }; val error = root.get("error")?.takeUnless { it.isJsonNull }
            checkRead((result != null) xor (error != null))
            if (error != null) {
                val code = error.obj().required("code")
                checkRead(code.isJsonPrimitive && code.asJsonPrimitive.isNumber && code.asString.matches(Regex("-?[0-9]{1,10}")))
                throw ReadFailure(ReadError.INVALID)
            }
            return result!!
        }
        fun decodeStatus(json: JsonElement): NodeStatus {
            val obj = json.obj()
            if (obj.required("chain_id").integer() != MAINNET_CHAIN) throw ReadFailure(ReadError.WRONG_NETWORK)
            val version = obj.text("version"); checkRead(version.length in 1..64 && version.none { it.code < 32 })
            return NodeStatus(version, obj.required("current_cycle").integer())
        }
        fun decodeBalance(json: JsonElement, address: String): Pair<MASAmount, MASAmount> {
            publicAddress(address); val rows = json.array(); checkRead(rows.size() == 1)
            val row = rows[0].obj(); checkRead(row.text("address") == address)
            return amount(row.text("final_balance")) to amount(row.text("candidate_balance"))
        }
        private fun amount(text: String): MASAmount = try { MASAmount.parse(text) } catch (_: Exception) { invalid() }
    }
}

enum class ActivityFilter { ALL, SENT, RECEIVED, ASC;
    fun includes(operation: PublicOperation, address: String): Boolean {
        val contract = operation.type in setOf("CallSC", "ExecuteSC") || operation.internalTransfer
        if (this == ASC) return contract
        if (operation.type != "Transaction" || operation.internalTransfer) return false
        return when (this) { ALL -> true; SENT -> operation.sender == address; RECEIVED -> operation.recipient == address; ASC -> false }
    }
}
enum class OperationDirection { INCOMING, OUTGOING, SELF, CONTRACT, OTHER }
data class HistoryIdentity(val eventId: String, val parent: String?, val internalTransfer: Boolean) {
    companion object {
        fun parse(text: String): HistoryIdentity {
            checkRead(text.length in 1..256 && text.matches(Regex("[a-zA-Z0-9_]+")))
            if (text.startsWith("ASC_") && text.matches(Regex("ASC_[a-zA-Z0-9_]+_I[a-zA-Z0-9_]+_T[a-zA-Z0-9_]+"))) return HistoryIdentity(text, null, true)
            val split = text.split('_'); checkRead(split.size in 1..2)
            try { MassaCodec.decode(split[0], "O") } catch (_: Exception) { invalid() }
            if (split.size == 2) checkRead(split[1].toULongOrNull()?.toString() == split[1])
            return HistoryIdentity(text, split[0], split.size == 2)
        }
    }
}
data class PublicOperation(val identity: HistoryIdentity, val type: String, val sender: String, val recipient: String?, val amount: MASAmount?, val fee: MASAmount?, val millis: Long?, val providerStatus: String, val executed: Boolean?) {
    val id get() = identity.eventId
    val copyableId get() = identity.parent ?: identity.eventId
    val internalTransfer get() = identity.internalTransfer
    val explorerUrl get() = identity.parent?.let { "https://explorer.massa.net/mainnet/operation/$it" }
    fun direction(address: String): OperationDirection = when {
        type in setOf("CallSC", "ExecuteSC") || internalTransfer -> OperationDirection.CONTRACT
        type != "Transaction" -> OperationDirection.OTHER
        sender == address && recipient == address -> OperationDirection.SELF
        sender == address -> OperationDirection.OUTGOING
        recipient == address -> OperationDirection.INCOMING
        else -> OperationDirection.OTHER
    }
}
data class HistoryCursor(val created: String?, val received: String?)
data class HistoryPage(val operations: List<PublicOperation>, val cursor: HistoryCursor?, val invalidRows: Int = 0, val imprecise: Int = 0, val partial: Boolean = false)
interface HistoryReader { suspend fun page(address: String, cursor: HistoryCursor?): HistoryPage }
class ExplorerReads(private val transport: ReadTransport) : HistoryReader {
    override suspend fun page(address: String, cursor: HistoryCursor?): HistoryPage {
        publicAddress(address)
        val pages = mutableListOf<HistoryPage>(); var failed = false; var failure: ReadFailure? = null
        var created: String? = null; var received: String? = null
        for (side in HistorySide.entries) {
            val token = if (side == HistorySide.CREATED) cursor?.created else cursor?.received
            if (cursor != null && token == null) continue
            try {
                val response = transport.execute(ReadRequest.history(address, side, token))
                val page = withContext(Dispatchers.Default) { decode(ReadJson.parse(response), address, side) }; pages += page
                if (side == HistorySide.CREATED) created = page.cursor?.created else received = page.cursor?.received
            } catch (e: CancellationException) { throw e }
            catch (e: ReadFailure) {
                failed = true; failure = e
                if (side == HistorySide.CREATED) created = token ?: "" else received = token ?: ""
            }
        }
        if (pages.isEmpty()) throw failure ?: ReadFailure(ReadError.INVALID)
        val next = if (created == null && received == null) null else HistoryCursor(created, received)
        return HistoryPage(merge(emptyList(), pages.flatMap { it.operations }), next, pages.sumOf { it.invalidRows }, pages.sumOf { it.imprecise }, failed)
    }
    companion object {
        fun merge(old: List<PublicOperation>, new: List<PublicOperation>): List<PublicOperation> {
            // Deterministic provider refresh overwrite; bounded memory and stable timestamp/id ordering.
            val unique = LinkedHashMap<String, PublicOperation>()
            (old + new).forEach { unique[it.id] = it }
            return unique.values.sortedWith(compareByDescending<PublicOperation> { it.millis ?: Long.MAX_VALUE }.thenBy { it.id }).take(500)
        }
        fun decode(json: JsonElement, address: String, side: HistorySide): HistoryPage {
            publicAddress(address); val root = json.obj()
            val rows = root.required(side.rows).array().toList() + (root.get("operationsNodes")?.takeUnless { it.isJsonNull }?.array()?.toList() ?: emptyList())
            checkRead(rows.size <= 2000)
            var invalid = 0; var imprecise = 0
            val operations = rows.mapNotNull { item ->
                try {
                    val row = item.obj(); val identity = HistoryIdentity.parse(row.text("hash"))
                    val type = row.text("type"); checkRead(type.length in 1..64 && type.none { it.code < 32 })
                    val sender = row.text("from"); MassaCodec.validateAddress(sender)
                    val recipient = row.optionalText("to")?.takeUnless { it == "-" }?.also { MassaCodec.validateAddress(it) }
                    checkRead(sender == address || recipient == address)
                    fun nano(field: String): MASAmount? {
                        val value = row.optionalText(field)?.takeUnless { it == "-" } ?: return null
                        if (value.length > 45 || !value.matches(Regex("[0-9]{1,20}(\\.0{1,20})?"))) { imprecise++; return null }
                        val number = value.substringBefore('.').toULongOrNull()
                        if (number == null) { imprecise++; return null }
                        return MASAmount(number)
                    }
                    val amount = if (type == "Transaction") nano("value") else null
                    val fee = nano("transaction_fee")
                    val timestamp = row.optionalText("block_time")?.takeUnless { it.isEmpty() || it == "0" }?.let {
                        checkRead(it.matches(Regex("[0-9]{1,13}"))); val n = it.toLongOrNull() ?: invalid(); checkRead(n < 10_000_000_000_000); n
                    }
                    val status = row.text("status"); checkRead(status.length <= 64 && status.none { it.code < 32 })
                    val executed = row.get("op_exec_status")?.takeUnless { it.isJsonNull }?.let { checkRead(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean }
                    PublicOperation(identity, type, sender, recipient, amount, fee, timestamp, status, executed)
                } catch (_: Exception) { invalid++; null }
            }
            checkRead(rows.isEmpty() || operations.isNotEmpty())
            fun cursor(field: String): String? = root.optionalText(field)?.takeUnless { it.isEmpty() }?.also { checkRead(it.toByteArray().size <= 8192 && it.none { c -> c.code < 32 }) }
            val c = if (side == HistorySide.CREATED) cursor("nextCursorCreated") else null
            val r = if (side == HistorySide.RECEIVED) cursor("nextCursorReceived") else null
            return HistoryPage(merge(emptyList(), operations), if (c == null && r == null) null else HistoryCursor(c, r), invalid, imprecise)
        }
    }
}
