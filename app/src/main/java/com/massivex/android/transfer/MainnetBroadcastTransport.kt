package com.massivex.android.transfer

import com.massivex.android.network.*
import kotlinx.coroutines.ensureActive
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


/** Only this adapter may write RPC. No URL/method parameter, retry, redirect or recovered permit. */
internal class MainnetBroadcastTransport(private val connections: TlsConnections = TlsConnections { it.openConnection() as HttpsURLConnection },private val clock:()->Long=System::currentTimeMillis) : BroadcastTransport {
    override val enabled = true
    // Unjournaled callers cannot transmit, even with a valid signature.
    override suspend fun submit(signed: SignedTransfer): BroadcastReply = throw SendFailure(SendError.JOURNAL)
    override suspend fun submit(signed: SignedTransfer, permit: SubmissionPermit): BroadcastReply {
        signed.validate()
        SendIdentityPolicy.production(signed.draft.source, signed.draft.recipient)
        demand(age(clock(),signed.draft.reviewedAt),SendError.STALE)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        permit.consume(signed)
        val id = java.util.UUID.randomUUID().toString()
        val input = JsonObject().apply {
            add("serialized_content", JsonArray().apply { signed.draft.content.forEach { add(it.toInt() and 255) } })
            addProperty("creator_public_key", signed.draft.source.publicKey)
            addProperty("signature", signed.signature)
        }
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", "send_operations")
            add("params", JsonArray().apply { add(JsonArray().apply { add(input) }) })
        }.toString().toByteArray(Charsets.UTF_8)
        demand(request.size <= 4096, SendError.JOURNAL)
        // A lost/malformed/rejected reply never proves that the operation was not propagated.
        val root = ReadJson.parse(exchange(request,signed.draft.reviewedAt), RESPONSE_LIMIT).obj()
        checkRead(root.text("jsonrpc") == "2.0" && root.text("id") == id)
        if(root.has("error")) {
            checkRead(!root.has("result") && root.required("error").isJsonObject)
            return BroadcastReply.Rejected
        }
        val result = root.required("result").array()
        checkRead(result.size() == 1 && result[0].string() == signed.draft.operationId)
        return BroadcastReply.Accepted(signed.draft.operationId)
    }
    companion object {
        private const val RESPONSE_LIMIT = 65_536
        private val workers = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(4), java.util.concurrent.ThreadFactory { r -> Thread(r, "mainnet-submit").apply { isDaemon = true } })
        private val deadlines = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "submit-deadline").apply { isDaemon = true } }
    }
    private suspend fun exchange(bytes: ByteArray, reviewedAt: Long): HttpPayload = suspendCancellableCoroutine { continuation ->
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
                    checkRead(age(clock(),reviewedAt))
                    val url = URL(RPC_ENDPOINT)
                    val c = connections.open(url); connection.set(c)
                    if (done.get()) return@submit
                    c.instanceFollowRedirects = false; c.useCaches = false; c.connectTimeout = 5000; c.readTimeout = 7000
                    c.requestMethod = "POST"
                    c.setRequestProperty("Accept", "application/json")
                    c.setRequestProperty("User-Agent", "MassiveX/0.5")
                    run {
                        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.setFixedLengthStreamingMode(bytes.size)
                        c.outputStream.use {
                            // Connection/queue time can outlive review or cancellation. No operation
                            // bytes start after that boundary; already-started IO remains uncertain.
                            checkRead(!done.get() && age(clock(),reviewedAt))
                            it.write(bytes)
                        }
                    }
                    val status = c.responseCode
                    when {
                        status in 300..399 -> throw ReadFailure(ReadError.REDIRECT, status)
                        status == 429 -> throw ReadFailure(ReadError.RATE_LIMIT, status)
                        status != 200 -> throw ReadFailure(ReadError.HTTP, status)
                    }
                    if (c.contentLengthLong > RESPONSE_LIMIT) throw ReadFailure(ReadError.TOO_LARGE)
                    val media = c.contentType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
                    checkRead(media == "application/json")
                    val bytes = c.inputStream.use { stream ->
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (!done.get()) {
                            val count = stream.read(buffer); if (count < 0) break
                            if (out.size() + count > RESPONSE_LIMIT) throw ReadFailure(ReadError.TOO_LARGE)
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
