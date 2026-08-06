package com.itkacher.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestDataSourceTest {
    @After
    fun tearDown() {
        RequestDataSource.clear()
    }

    @Test
    fun `assembles a request from engine independent protocol events`() {
        val id = "ktor-1"
        RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_URL, "https://example.test/books")
        RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_METHOD, "POST")
        RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_HEADER, "Content-Type: application/json")
        RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_BODY, "{\"title\":")
        RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_BODY, "\"Ktor\"}")
        RequestDataSource.getRequestFromMessage(id, MessageType.RESPONSE_STATUS, "201")
        RequestDataSource.getRequestFromMessage(id, MessageType.RESPONSE_BODY, "{\"id\":1}")
        val request = RequestDataSource.getRequestFromMessage(id, MessageType.RESPONSE_END, "")!!

        assertEquals("POST", request.method)
        assertEquals("{\"title\":\"Ktor\"}", request.getRequestBodyString())
        assertEquals(201, request.responseCode)
        assertEquals("{\"id\":1}", request.getResponseBodyString())
        assertTrue(request.isClosed)
    }

    @Test
    fun `exposes response data when legacy client omits response end`() {
        val id = "ktor-without-response-end"
        RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_METHOD, "GET")
        RequestDataSource.getRequestFromMessage(id, MessageType.RESPONSE_STATUS, "200")
        RequestDataSource.getRequestFromMessage(id, MessageType.RESPONSE_HEADER, "Content-Type: application/json")
        val request = RequestDataSource.getRequestFromMessage(id, MessageType.RESPONSE_BODY, "{\"ok\":true}")!!

        assertTrue(request.hasResponse())
        assertTrue(!request.isClosed)
        assertEquals(200, request.responseCode)
        assertEquals("{\"ok\":true}", request.getResponseBodyString())
    }

    @Test
    fun `deduplicates replayed request and response headers`() {
        val id = "okhttp-replayed"

        repeat(4) {
            RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_HEADER, "X-App-Info: 2.8.1/dayfics/android")
            RequestDataSource.getRequestFromMessage(id, MessageType.RESPONSE_HEADER, "Content-Type: application/json")
        }

        val request = RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_END, "")!!
        assertEquals(listOf("X-App-Info: 2.8.1/dayfics/android"), request.requestHeaders)
        assertEquals(listOf("Content-Type: application/json"), request.responseHeaders)
    }

    @Test
    fun `header identity ignores name casing and surrounding value whitespace`() {
        val id = "header-formatting"
        RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_HEADER, "Token: value")
        RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_HEADER, "token:   value  ")

        val request = RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_END, "")!!
        assertEquals(listOf("Token: value"), request.requestHeaders)
    }

    @Test
    fun `preserves different values for the same header name`() {
        val id = "multi-value"
        RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_HEADER, "X-Feature: first")
        RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_HEADER, "x-feature: second")

        val request = RequestDataSource.getRequestFromMessage(id, MessageType.REQUEST_END, "")!!
        assertEquals(listOf("X-Feature: first", "x-feature: second"), request.requestHeaders)
    }
}
