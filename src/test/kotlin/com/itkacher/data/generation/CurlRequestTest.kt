package com.itkacher.data.generation

import com.itkacher.data.DebugRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlRequestTest {
    @Test
    fun `does not emit exact duplicate headers from mutable request data`() {
        val request = DebugRequest("curl-replayed").apply {
            method = "GET"
            url = "https://example.test/init"
            requestHeaders += "X-App-Info: 2.8.1/dayfics/android"
            requestHeaders += "x-app-info:   2.8.1/dayfics/android  "
            requestHeaders += "X-App-Info: another-value"
        }

        val curl = CurlRequest(request).toString()

        assertEquals(2, "-H '".toRegex().findAll(curl).count())
        assertTrue(curl.contains("-H 'X-App-Info: 2.8.1/dayfics/android'"))
        assertTrue(curl.contains("-H 'X-App-Info: another-value'"))
    }
}
