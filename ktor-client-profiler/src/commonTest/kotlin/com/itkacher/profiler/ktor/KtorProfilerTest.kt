package com.itkacher.profiler.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorProfilerTest {
    @Test
    fun `emits request and response using the profiler protocol`() = runBlocking {
        val logs = mutableListOf<LogEntry>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    assertEquals("secret", request.headers[HttpHeaders.Authorization])
                    respond(
                        content = "{\"id\":1}",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            }
            install(KtorProfiler) {
                sink = ProfilerLogSink { tag, message -> logs += LogEntry(tag, message) }
            }
        }

        val response = client.post("https://example.test/books") {
            headers.append(HttpHeaders.Authorization, "secret")
            setBody(TextContent("{\"title\":\"Ktor\"}", ContentType.Application.Json))
        }
        assertEquals("{\"id\":1}", response.bodyAsText())

        withTimeout(5_000) {
            while (logs.none { it.event == "RSD" }) delay(10)
        }
        client.close()

        assertEvent(logs, "RQM", "POST")
        assertEvent(logs, "RQU", "https://example.test/books")
        assertEvent(logs, "RQB", "{\"title\":\"Ktor\"}")
        assertEvent(logs, "RSS", "201")
        assertEvent(logs, "RSB", "{\"id\":1}")
        assertTrue(logs.any { it.event == "RQD" && it.message.isNotEmpty() })
        assertTrue(logs.any { it.event == "RSD" && it.message.isNotEmpty() })
        assertTrue(logs.any { it.event == "RQH" && it.message == "Authorization: [REDACTED]" })
    }

    @Test
    fun `emits an error and closes failed exchanges`() = runBlocking {
        val logs = mutableListOf<LogEntry>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { error("network unavailable") }
            }
            install(KtorProfiler) {
                sink = ProfilerLogSink { tag, message -> logs += LogEntry(tag, message) }
            }
        }

        runCatching { client.post("https://example.test/failure") }
        client.close()

        assertTrue(logs.any { it.event == "REE" && it.message.contains("network unavailable") })
        assertTrue(logs.any { it.event == "RSD" })
    }

    @Test
    fun `limits large response bodies without consuming the application body`() = runBlocking {
        val logs = mutableListOf<LogEntry>()
        val responseBody = "0123456789".repeat(100)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(responseBody, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
                }
            }
            install(KtorProfiler) {
                maxBodyLength = 20
                sink = ProfilerLogSink { tag, message -> logs += LogEntry(tag, message) }
            }
        }

        val response = client.post("https://example.test/large")
        assertEquals(responseBody, response.bodyAsText())
        withTimeout(5_000) {
            while (logs.none { it.event == "RSD" }) delay(10)
        }
        client.close()

        val captured = logs.filter { it.event == "RSB" }.joinToString("") { it.message }
        assertEquals("01234567890123456789\n[Body truncated by Ktor Profiler]", captured)
    }

    @Test
    fun `does not buffer binary response bodies`() = runBlocking {
        val logs = mutableListOf<LogEntry>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond("binary", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString()))
                }
            }
            install(KtorProfiler) {
                sink = ProfilerLogSink { tag, message -> logs += LogEntry(tag, message) }
            }
        }

        client.post("https://example.test/image") {
            setBody(ByteArrayContent(byteArrayOf(0, 1, 2, 3), ContentType.Application.OctetStream))
        }.bodyAsText()
        withTimeout(5_000) {
            while (logs.none { it.event == "RSD" }) delay(10)
        }
        client.close()

        assertTrue(logs.none { it.event == "RSB" })
        assertTrue(logs.none { it.event == "RQB" })
    }

    @Test
    fun `records redirect hops as separate exchanges`() = runBlocking {
        val logs = mutableListOf<LogEntry>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "https://example.test/final")
                    )
                }
                addHandler {
                    respond("done", headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
                }
            }
            install(KtorProfiler) {
                sink = ProfilerLogSink { tag, message -> logs += LogEntry(tag, message) }
            }
        }

        assertEquals("done", client.get("https://example.test/start").bodyAsText())
        withTimeout(5_000) {
            while (logs.count { it.event == "RSD" } < 2) delay(10)
        }
        client.close()

        val requestIds = logs.filter { it.event == "RQU" }.map { it.requestId }.distinct()
        assertEquals(2, requestIds.size)
        assertTrue(logs.any { it.event == "RQU" && it.message == "https://example.test/start" })
        assertTrue(logs.any { it.event == "RQU" && it.message == "https://example.test/final" })
        assertTrue(logs.any { it.event == "RSS" && it.message == "302" })
        assertTrue(logs.any { it.event == "RSS" && it.message == "200" })
    }

    @Test
    fun `emits overlapping request and content headers only once`() = runBlocking {
        val logs = mutableListOf<LogEntry>()
        val duplicateHeader = "X-App-Info"
        val duplicateValue = "2.8.1/dayfics/android"
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { respond("ok") }
            }
            install(KtorProfiler) {
                sink = ProfilerLogSink { tag, message -> logs += LogEntry(tag, message) }
            }
        }

        client.post("https://example.test/headers") {
            header(duplicateHeader, duplicateValue)
            setBody(object : OutgoingContent.ByteArrayContent() {
                override val headers = headersOf(duplicateHeader, duplicateValue)
                override fun bytes(): ByteArray = byteArrayOf()
            })
        }.bodyAsText()
        withTimeout(5_000) {
            while (logs.none { it.event == "RSD" }) delay(10)
        }
        client.close()

        assertEquals(
            1,
            logs.count { it.event == "RQH" && it.message.equals("$duplicateHeader: $duplicateValue", ignoreCase = true) }
        )
    }

    private fun assertEvent(logs: List<LogEntry>, event: String, message: String) {
        assertTrue(logs.any { it.event == event && it.message == message }, "Missing $event with '$message': $logs")
    }

    private data class LogEntry(val tag: String, val message: String) {
        val event: String = tag.substringAfterLast('_')
        val requestId: String = tag.substringAfter("KTRPRFL_").substringBeforeLast('_')
    }
}
