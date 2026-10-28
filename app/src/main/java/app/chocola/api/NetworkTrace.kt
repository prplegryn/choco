package app.chocola.api

import android.os.SystemClock
import app.chocola.data.*
import okhttp3.*
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** No URLs, headers, payloads, cookies, IP addresses or credential values are collected. */
internal class HttpTraceListener(private val logs: Diagnostics?, private val id: String = newId(),
    private val session: String = "", private val origin: String = "api", private val operation: String = "", private val attempt: Int = 0) : EventListener() {
    private var span: TraceSpan? = null
    private var connects = 0
    private var stageStarted = 0L
    private fun mark(stage: String, fields: Map<String, Any?> = emptyMap()) {
        val now = SystemClock.elapsedRealtime()
        span?.stage(stage, fields + mapOf("request" to id, "previousStageMs" to (now - stageStarted).coerceAtLeast(0)), record = true)
        stageStarted = now
    }
    override fun callStart(call: Call) {
        if (logs == null) return
        stageStarted = SystemClock.elapsedRealtime()
        span = logs.begin("net", id, session, mapOf("request" to id, "operation" to operation, "attempt" to attempt,
            "origin" to origin, "host" to call.request().url.host, "method" to call.request().method))
    }
    override fun dnsStart(call: Call, domainName: String) = mark("dns")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = mark("dns_done", mapOf("addressCount" to inetAddressList.size))
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { connects++; mark("connect", mapOf("proxyType" to proxy.type().name)) }
    override fun secureConnectStart(call: Call) = mark("tls")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = mark("tls_done", mapOf("tls" to handshake?.tlsVersion?.javaName))
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?) = mark("connect_done", mapOf("transportProtocol" to protocol?.toString()))
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?, ioe: IOException) = mark("connect_failed", mapOf("error" to ioe.javaClass.simpleName))
    override fun connectionAcquired(call: Call, connection: Connection) = mark("connection_acquired", mapOf("pooled" to (connects == 0), "transportProtocol" to connection.protocol().toString()))
    override fun requestHeadersStart(call: Call) = mark("request_headers")
    override fun requestHeadersEnd(call: Call, request: Request) = mark("request_headers_sent")
    override fun requestBodyStart(call: Call) = mark("request_body")
    override fun requestBodyEnd(call: Call, byteCount: Long) = mark("request_body_sent", mapOf("bytes" to byteCount))
    override fun responseHeadersStart(call: Call) = mark("response_headers")
    override fun responseHeadersEnd(call: Call, response: Response) = mark("response_headers_received", mapOf("httpStatus" to response.code,
        "transportProtocol" to response.protocol.toString(), "gzip" to (response.header("Content-Encoding") == "gzip"),
        "contentType" to response.header("Content-Type")?.take(160)))
    override fun responseBodyStart(call: Call) = mark("response_body")
    override fun responseBodyEnd(call: Call, byteCount: Long) = mark("response_body_done", mapOf("bytes" to byteCount))
    override fun requestFailed(call: Call, ioe: IOException) = mark("request_failed", mapOf("error" to ioe.javaClass.simpleName))
    override fun responseFailed(call: Call, ioe: IOException) = mark("response_failed", mapOf("error" to ioe.javaClass.simpleName))
    override fun callEnd(call: Call) { span?.finish("done", mapOf("request" to id)) }
    override fun callFailed(call: Call, ioe: IOException) { span?.finish(if (call.isCanceled()) "cancelled" else "failed", mapOf("request" to id, "error" to ioe.javaClass.simpleName)) }
}

internal fun OkHttpClient.traced(logs: Diagnostics?, origin: String, session: String = ""): OkHttpClient =
    if (logs == null) this else newBuilder().eventListenerFactory { HttpTraceListener(logs, session = session, origin = origin) }.build()

/** Volatile counters make a blocked read visible to the independent runtime probe. */
internal class StreamTrace(private val logs: Diagnostics?, val id: String, session: String, operation: String, attempt: Int,
    private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val started = clock()
    private val span = logs?.begin("api", id, session, mapOf("request" to id, "operation" to operation, "attempt" to attempt))
    @Volatile private var readStarted = 0L
    @Volatile private var lastRead = 0L
    @Volatile private var firstRead = 0L
    @Volatile private var bytes = 0L
    @Volatile private var reads = 0L
    @Volatile private var maxRead = 0L
    @Volatile private var parsed = 0L
    @Volatile private var lastParsed = 0L
    @Volatile private var consumerStarted = 0L
    @Volatile private var maxConsumer = 0L
    @Volatile private var callbackAt = 0L
    private var lastProgress = started
    private var lastConsumerWarning = 0L
    @Volatile private var protocolFields: (() -> Map<String, Any?>)? = null
    init { span?.samples(::fields) }
    fun protocolSamples(sample: () -> Map<String, Any?>) { protocolFields = sample }
    fun stage(name: String) { span?.stage(name) }
    fun callback() { callbackAt = clock(); stage("response_callback") }
    fun resumed() { span?.event("api.headers_resumed", mapOf("request" to id, "callbackToReadMs" to if (callbackAt == 0L) null else clock() - callbackAt)); stage("reading") }
    fun source(source: Source): Source = object : ForwardingSource(source) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            readStarted = clock(); stage("source_read")
            try {
                val count = super.read(sink, byteCount)
                val now = clock()
                maxRead = maxOf(maxRead, now - readStarted); reads++
                if (count > 0) {
                    bytes += count; lastRead = now
                    if (firstRead == 0L) { firstRead = now; span?.event("api.first_data", mapOf("request" to id, "sinceStartMs" to now - started, "decodedBytes" to count)) }
                }
                return count
            } finally { maxRead = maxOf(maxRead, clock() - readStarted); readStarted = 0; stage("parsing"); progress() }
        }
    }
    fun parsed() { parsed++; lastParsed = clock(); progress() }
    suspend fun consume(block: suspend () -> Unit) {
        val start = clock(); consumerStarted = start; stage("consumer")
        try { block() } finally {
            val now = clock(); val duration = now - start
            maxConsumer = maxOf(maxConsumer, duration); consumerStarted = 0
            logs?.timing("api.delta_consumer", duration * 1_000_000)
            if (duration >= 150 && now - lastConsumerWarning >= 10_000) { lastConsumerWarning = now; span?.event("api.consumer_slow", fields() + ("durationMs" to duration)) }
            stage("reading"); progress()
        }
    }
    private fun progress() {
        val now = clock()
        if (now - lastProgress >= 5000) { lastProgress = now; span?.event("api.progress", fields() + ("request" to id)) }
    }
    fun fields(): Map<String, Any?> {
        val now = clock()
        return mapOf("decodedBytes" to bytes, "reads" to reads, "eventsParsed" to parsed, "firstDataMs" to firstRead.takeIf { it > 0 }?.minus(started),
            "sinceLastReadMs" to lastRead.takeIf { it > 0 }?.let { now - it }, "readInFlightMs" to readStarted.takeIf { it > 0 }?.let { now - it },
            "sinceLastEventMs" to lastParsed.takeIf { it > 0 }?.let { now - it }, "maxReadMs" to maxRead,
            "consumerInFlightMs" to consumerStarted.takeIf { it > 0 }?.let { now - it }, "maxConsumerMs" to maxConsumer) + protocolFields?.invoke().orEmpty()
    }
    fun elapsedMs(): Long = clock() - started
    fun finish(outcome: String) { span?.finish(outcome, fields() + ("request" to id)) }
}
