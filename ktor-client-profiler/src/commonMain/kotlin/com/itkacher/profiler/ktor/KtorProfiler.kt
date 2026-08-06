@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.itkacher.profiler.ktor

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.charset
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.util.AttributeKey
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.charsets.decode
import io.ktor.utils.io.readRemaining
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock

class KtorProfilerConfig {
    var sink: ProfilerLogSink = platformDefaultProfilerLogSink()
    var maxBodyLength: Int = 300_000
    var filter: (HttpRequestBuilder) -> Boolean = { true }

    private val sanitizedHeaders = mutableSetOf(
        HttpHeaders.Authorization.lowercase(),
        HttpHeaders.Cookie.lowercase(),
        HttpHeaders.SetCookie.lowercase()
    )

    fun sanitizeHeader(name: String) {
        sanitizedHeaders += name.lowercase()
    }

    internal fun isSanitized(name: String): Boolean = name.lowercase() in sanitizedHeaders
}

private data class Exchange(
    val id: String,
    val startedAtMillis: Long,
    val finished: AtomicBoolean = AtomicBoolean(false)
)

private val ExchangeKey = AttributeKey<Exchange>("KtorProfilerExchange")
private val idSequence = AtomicLong(0)

val KtorProfiler = createClientPlugin("KtorProfiler", ::KtorProfilerConfig) {
    val sink = pluginConfig.sink
    val maxBodyLength = pluginConfig.maxBodyLength
    val filter = pluginConfig.filter

    require(maxBodyLength >= 0) { "maxBodyLength must not be negative" }

    fun emit(exchange: Exchange, event: Event, value: String = "") {
        val chunks = when {
            value.isEmpty() -> listOf(EMPTY_EVENT_MARKER)
            event.isBody -> value.chunked(LOGCAT_CHUNK_LENGTH)
            else -> listOf(value.take(LOGCAT_CHUNK_LENGTH))
        }
        chunks.forEach { sink.log("KTRPRFL_${exchange.id}_${event.code}", it) }
    }

    fun headerValue(name: String, values: List<String>): String {
        val value = if (pluginConfig.isSanitized(name)) REDACTED else values.joinToString(", ")
        return "$name: $value"
    }

    fun requestHeaders(request: HttpRequestBuilder, content: OutgoingContent): List<Pair<String, String>> {
        val headers = LinkedHashMap<Pair<String, String>, Pair<String, String>>()

        fun add(name: String, value: String) {
            val identity = name.lowercase() to value.trim()
            if (identity !in headers) {
                headers[identity] = name to value
            }
        }

        request.headers.entries().forEach { (name, values) ->
            values.forEach { add(name, it) }
        }
        content.headers.entries().forEach { (name, values) ->
            values.forEach { add(name, it) }
        }
        content.contentType?.let { type ->
            if (headers.keys.none { it.first == HttpHeaders.ContentType.lowercase() }) {
                add(HttpHeaders.ContentType, type.toString())
            }
        }
        return headers.values.toList()
    }

    suspend fun requestBody(content: OutgoingContent): String? = when (content) {
        is OutgoingContent.ByteArrayContent -> {
            if (isInspectable(content.contentType)) content.bytes().decodeToString() else null
        }
        is OutgoingContent.ContentWrapper -> requestBody(content.delegate())
        is OutgoingContent.NoContent,
        is OutgoingContent.ProtocolUpgrade,
        is OutgoingContent.ReadChannelContent,
        is OutgoingContent.WriteChannelContent -> null
    }

    suspend fun emitResponse(response: HttpResponse) {
        val exchange = response.call.request.attributes.getOrNull(ExchangeKey) ?: return
        if (!exchange.finished.compareAndSet(false, true)) return

        emit(exchange, Event.RESPONSE_TIME, (response.responseTime.timestamp - response.requestTime.timestamp).toString())
        emit(exchange, Event.RESPONSE_STATUS, response.status.value.toString())
        response.headers.entries().forEach { (name, values) ->
            emit(exchange, Event.RESPONSE_HEADER, headerValue(name, values))
        }

        val contentType = response.contentType()
        if (isInspectable(contentType) && contentType != ContentType.Text.EventStream && response.status.value != 101) {
            runCatching {
                val limit = maxBodyLength.toLong() + 1
                val bytes = response.bodyAsChannel().readRemaining(limit).readByteArray()
                val charset = contentType?.charset() ?: Charsets.UTF_8
                val text = charset.newDecoder().decode(Buffer().apply { write(bytes) })
                if (bytes.size > maxBodyLength) text.take(maxBodyLength) + TRUNCATED_SUFFIX else text
            }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { emit(exchange, Event.RESPONSE_BODY, it) }
        }
        emit(exchange, Event.RESPONSE_END)
    }

    on(SendingRequest) { request, content ->
        if (!filter(request)) return@on

        val exchange = Exchange(
            id = "${Clock.System.now().toEpochMilliseconds().toString(36)}-${idSequence.incrementAndFetch().toString(36)}",
            startedAtMillis = Clock.System.now().toEpochMilliseconds()
        ).also { request.attributes.put(ExchangeKey, it) }

        emit(exchange, Event.REQUEST_URL, request.url.buildString())
        emit(exchange, Event.REQUEST_TIME, exchange.startedAtMillis.toString())
        emit(exchange, Event.REQUEST_METHOD, request.method.value)
        requestHeaders(request, content).forEach { (name, value) ->
            emit(exchange, Event.REQUEST_HEADER, headerValue(name, listOf(value)))
        }
        requestBody(content)
            ?.takeIf { it.isNotEmpty() }
            ?.let { emit(exchange, Event.REQUEST_BODY, truncate(it, maxBodyLength)) }
        emit(exchange, Event.REQUEST_END)
    }

    on(Send) { request ->
        try {
            proceed(request)
        } catch (cause: Throwable) {
            request.attributes.getOrNull(ExchangeKey)?.let { exchange ->
                if (exchange.finished.compareAndSet(false, true)) {
                    emit(exchange, Event.RESPONSE_TIME, (Clock.System.now().toEpochMilliseconds() - exchange.startedAtMillis).toString())
                    emit(exchange, Event.RESPONSE_ERROR, cause.message ?: cause::class.simpleName ?: "Unknown error")
                    emit(exchange, Event.RESPONSE_END)
                }
            }
            throw cause
        }
    }

    val observer = ResponseObserver.prepare {
        onResponse { emitResponse(it) }
    }
    ResponseObserver.install(observer, client)
}

private fun truncate(value: String, maxLength: Int): String {
    if (value.length <= maxLength) return value
    return value.take(maxLength) + TRUNCATED_SUFFIX
}

private fun isInspectable(contentType: ContentType?): Boolean {
    val value = contentType?.withoutParameters()?.toString()?.lowercase() ?: return true
    return value.startsWith("text/") ||
        value == "application/json" ||
        value == "application/xml" ||
        value == "application/x-www-form-urlencoded" ||
        value.endsWith("+json") ||
        value.endsWith("+xml")
}

private enum class Event(val code: String, val isBody: Boolean = false) {
    REQUEST_URL("RQU"),
    REQUEST_TIME("RQT"),
    REQUEST_METHOD("RQM"),
    REQUEST_HEADER("RQH"),
    REQUEST_BODY("RQB", isBody = true),
    REQUEST_END("RQD"),
    RESPONSE_TIME("RST"),
    RESPONSE_STATUS("RSS"),
    RESPONSE_HEADER("RSH"),
    RESPONSE_BODY("RSB", isBody = true),
    RESPONSE_END("RSD"),
    RESPONSE_ERROR("REE")
}

private const val LOGCAT_CHUNK_LENGTH = 1_000
private const val EMPTY_EVENT_MARKER = "-"
private const val REDACTED = "[REDACTED]"
private const val TRUNCATED_SUFFIX = "\n[Body truncated by Ktor Profiler]"
