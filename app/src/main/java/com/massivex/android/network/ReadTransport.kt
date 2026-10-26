package com.massivex.android.network

import com.massivex.massa.MassaCodec
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URL
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ReadError { OFFLINE, TIMEOUT, TLS, REDIRECT, HTTP, RATE_LIMIT, COOLDOWN, INVALID, TOO_LARGE, WRONG_NETWORK, UNSUPPORTED, STALE }
class ReadFailure(val reason: ReadError, val status: Int? = null) : Exception(reason.name)
internal fun invalid(): Nothing = throw ReadFailure(ReadError.INVALID)
internal fun checkRead(ok: Boolean) { if (!ok) invalid() }
internal fun publicAddress(address: String) { try { MassaCodec.validateAddress(address, userOnly = true) } catch (_: Exception) { invalid() } }
enum class RpcRead(val wire: String) { STATUS("get_status"), ADDRESSES("get_addresses") }
enum class HistorySide(val flag: String, val cursorField: String, val rows: String) {
    CREATED("from", "cursorCreated", "operationsCreated"), RECEIVED("to", "cursorReceived", "operationsReceived")
}
/** Only factories for fixed, required public reads. No provider URL or arbitrary RPC entry point. */
class ReadRequest private constructor(val url: String, body: ByteArray?, val explorer: Boolean = false, val limit: Int = 1_048_576) {
    private val payload = body?.copyOf()
    val body: ByteArray? get() = payload?.copyOf()
    companion object {
        const val RPC = "https://mainnet.massa.net/api/v2"
        fun rpc(method: RpcRead, id: String, address: String? = null): ReadRequest {
            checkRead(id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            val params = JsonArray()
            if (method == RpcRead.ADDRESSES) { publicAddress(address ?: invalid()); params.add(JsonArray().apply { add(address) }) }
            else checkRead(address == null)
            val json = JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", method.wire); add("params", params) }
            return ReadRequest(RPC, json.toString().toByteArray())
        }
        /** Additional fixed reads for transfer preflight/reconciliation; never arbitrary RPC text. */
        fun preflightAddresses(id: String, source: String, recipient: String): ReadRequest {
            publicAddress(source); publicAddress(recipient)
            return fixedRead(id, "get_addresses", JsonArray().apply { add(JsonArray().apply { add(source); add(recipient) }) })
        }
        fun operation(id: String, operationId: String): ReadRequest {
            try { com.massivex.massa.MassaCodec.decode(operationId, "O") } catch (_: Exception) { invalid() }
            return fixedRead(id, "get_operations", JsonArray().apply { add(JsonArray().apply { add(operationId) }) })
        }
        private fun fixedRead(id: String, method: String, params: JsonArray): ReadRequest {
            checkRead(id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            checkRead(method in setOf("get_addresses", "get_operations"))
            return ReadRequest(RPC, JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("id",id); addProperty("method",method); add("params",params) }.toString().toByteArray())
        }
        fun history(address: String, side: HistorySide, cursor: String?): ReadRequest {
            publicAddress(address)
            checkRead(cursor == null || (cursor.toByteArray().size <= 8192 && cursor.none { it.code < 32 }))
            val query = "${side.flag}=true" + if (cursor.isNullOrEmpty()) "" else "&${side.cursorField}=" + URLEncoder.encode(cursor, "UTF-8")
            return ReadRequest("https://explorer-api.massa.net/address/$address/operations?$query", null, true, 2_097_152)
        }
        fun currencies() = ReadRequest("https://api.coingecko.com/api/v3/simple/supported_vs_currencies", null)
        fun fxCurrencies() = ReadRequest("https://api.frankfurter.dev/v2/currencies", null)
        fun price(currency: String, asset: String? = null): ReadRequest {
            code(currency); checkRead(asset == null || asset in setOf("bitcoin", "ethereum", "solana"))
            val ids = if (asset == null) "massa" else "massa%2C$asset"
            return ReadRequest("https://api.coingecko.com/api/v3/simple/price?ids=$ids&vs_currencies=${currency.lowercase(java.util.Locale.ROOT)}&include_last_updated_at=true&precision=full", null)
        }
        fun fx(currency: String): ReadRequest { code(currency); return ReadRequest("https://api.frankfurter.dev/v2/rate/USD/$currency", null) }
        private fun code(value: String) { checkRead(value.matches(Regex("[A-Z]{3}"))) }
    }
    override fun toString() = "ReadRequest(<public-data-redacted>)"
}
data class HttpPayload(val status: Int, val contentType: String?, val bytes: ByteArray)
fun interface ReadTransport { suspend fun execute(request: ReadRequest): HttpPayload }
internal suspend fun readPublicJson(transport: ReadTransport, request: ReadRequest): JsonElement {
    val response = transport.execute(request)
    return withContext(Dispatchers.Default) { ReadJson.parse(response, request.limit) }
}
fun interface TlsConnections { fun open(url: URL): HttpsURLConnection }

/** Native platform TLS. Redirects/cookies/cache/retries absent; a wall-clock deadline also disconnects IO. */
class PlatformReadTransport(private val connections: TlsConnections = TlsConnections { it.openConnection() as HttpsURLConnection }) : ReadTransport {
    companion object {
        private val workers = java.util.concurrent.ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(12), java.util.concurrent.ThreadFactory { r -> Thread(r, "public-read").apply { isDaemon = true } })
        private val deadlines = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "read-deadline").apply { isDaemon = true } }
        val hosts = setOf("mainnet.massa.net", "explorer-api.massa.net", "api.coingecko.com", "api.frankfurter.dev")
        fun validateUrl(url: URL) {
            checkRead(url.protocol == "https" && url.host in hosts && url.userInfo == null && url.ref == null && url.port in listOf(-1, 443))
        }
    }
    override suspend fun execute(request: ReadRequest): HttpPayload = suspendCancellableCoroutine { continuation ->
        val done = AtomicBoolean(false); val connection = AtomicReference<HttpsURLConnection?>(); val work = AtomicReference<java.util.concurrent.Future<*>?>()
        fun finish(value: HttpPayload? = null, failure: ReadFailure? = null) {
            if (done.compareAndSet(false, true)) {
                try { connection.getAndSet(null)?.disconnect() } catch (_: Exception) { /* No body or URL logging. */ }
                if (failure != null) continuation.resumeWithException(failure) else continuation.resume(value!!)
            }
        }
        val deadline = deadlines.schedule({
            finish(failure = ReadFailure(ReadError.TIMEOUT)); connection.get()?.disconnect()
            work.get()?.let { it.cancel(true); if (it is Runnable) workers.remove(it) }
        }, 15, TimeUnit.SECONDS)
        val future = try {
            workers.submit {
                try {
                    if (done.get()) return@submit
                    val url = URL(request.url); validateUrl(url)
                    val c = connections.open(url); connection.set(c)
                    if (done.get()) return@submit
                    c.instanceFollowRedirects = false; c.useCaches = false; c.connectTimeout = 5000; c.readTimeout = 7000
                    c.requestMethod = if (request.body == null) "GET" else "POST"
                    c.setRequestProperty("Accept", "application/json")
                    c.setRequestProperty("User-Agent", "MassiveX/0.5")
                    if (request.explorer) c.setRequestProperty("x-network", "mainnet")
                    request.body?.let { bytes ->
                        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.setFixedLengthStreamingMode(bytes.size)
                        c.outputStream.use { it.write(bytes) }
                    }
                    val status = c.responseCode
                    when {
                        status in 300..399 -> throw ReadFailure(ReadError.REDIRECT, status)
                        status == 429 -> throw ReadFailure(ReadError.RATE_LIMIT, status)
                        status != 200 -> throw ReadFailure(ReadError.HTTP, status)
                    }
                    if (c.contentLengthLong > request.limit) throw ReadFailure(ReadError.TOO_LARGE)
                    val media = c.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
                    checkRead(media == "application/json")
                    val bytes = c.inputStream.use { stream ->
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (!done.get()) {
                            val count = stream.read(buffer); if (count < 0) break
                            if (out.size() + count > request.limit) throw ReadFailure(ReadError.TOO_LARGE)
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    finish(HttpPayload(status, media, bytes))
                } catch (e: ReadFailure) { finish(failure = e) }
                catch (_: SocketTimeoutException) { finish(failure = ReadFailure(ReadError.TIMEOUT)) }
                catch (_: SSLException) { finish(failure = ReadFailure(ReadError.TLS)) }
                catch (_: Exception) { finish(failure = ReadFailure(ReadError.OFFLINE)) }
                finally { deadline.cancel(false); connection.getAndSet(null)?.disconnect() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            deadline.cancel(false); finish(failure = ReadFailure(ReadError.COOLDOWN)); return@suspendCancellableCoroutine
        }
        work.set(future)
        continuation.invokeOnCancellation { done.set(true); deadline.cancel(false); connection.get()?.disconnect(); future.cancel(true); if (future is Runnable) workers.remove(future) }
    }
}

/** Strict UTF-8, duplicate-key rejection, bounded depth/nodes/string sizes, and exact JSON number lexemes. */
object ReadJson {
    fun parse(response: HttpPayload, limit: Int = 2_097_152): JsonElement {
        if (response.status == 429) throw ReadFailure(ReadError.RATE_LIMIT, 429)
        if (response.status in 300..399) throw ReadFailure(ReadError.REDIRECT, response.status)
        if (response.status != 200) throw ReadFailure(ReadError.HTTP, response.status)
        if (response.bytes.size > limit) throw ReadFailure(ReadError.TOO_LARGE)
        checkRead(response.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT) == "application/json")
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.bytes)).toString()
            JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                var nodes = 0
                fun read(depth: Int): JsonElement {
                    checkRead(depth <= 20 && ++nodes <= 25000)
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                            reader.beginObject()
                            while (reader.hasNext()) { val name = reader.nextName(); checkRead(name.length <= 256 && !has(name)); add(name, read(depth + 1)) }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> JsonArray().apply { reader.beginArray(); while (reader.hasNext()) { checkRead(size() < 2000); add(read(depth + 1)) }; reader.endArray() }
                        JsonToken.STRING -> JsonPrimitive(reader.nextString().also { checkRead(it.length <= 16384) })
                        JsonToken.NUMBER -> JsonParser.parseString(reader.nextString().also { checkRead(it.length <= 96) })
                        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                        else -> invalid()
                    }
                }
                return read(0).also { checkRead(reader.peek() == JsonToken.END_DOCUMENT) }
            }
        } catch (e: ReadFailure) { throw e } catch (_: Exception) { invalid() }
    }
}
internal fun JsonElement.obj(): JsonObject { checkRead(isJsonObject); return asJsonObject }
internal fun JsonElement.array(): JsonArray { checkRead(isJsonArray); return asJsonArray }
internal fun JsonObject.required(name: String): JsonElement = get(name)?.takeUnless { it.isJsonNull } ?: invalid()
internal fun JsonElement.string(): String { checkRead(isJsonPrimitive && asJsonPrimitive.isString); return asString }
internal fun JsonObject.text(name: String): String = required(name).string()
internal fun JsonObject.optionalText(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.string()
internal fun JsonElement.integer(): Long {
    checkRead(isJsonPrimitive && asJsonPrimitive.isNumber && asString.matches(Regex("0|[1-9][0-9]{0,18}")))
    return asString.toLongOrNull() ?: invalid()
}
