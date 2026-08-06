package com.itkacher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilerLogMessageTest {
    @Test
    fun `parses legacy OkHttp tag`() {
        assertEquals(
            ProfilerLogMessage("42", MessageType.REQUEST_URL),
            ProfilerLogMessage.parse("OKPRFL_42_RQU")
        )
    }

    @Test
    fun `parses Ktor tag`() {
        assertEquals(
            ProfilerLogMessage("abc-1", MessageType.RESPONSE_BODY),
            ProfilerLogMessage.parse("KTRPRFL_abc-1_RSB")
        )
    }

    @Test
    fun `rejects malformed or unknown tags`() {
        assertNull(ProfilerLogMessage.parse("OKPRFL_42_NOPE"))
        assertNull(ProfilerLogMessage.parse("KTRPRFL__RQU"))
        assertNull(ProfilerLogMessage.parse("OTHER_42_RQU"))
        assertNull(ProfilerLogMessage.parse("OKPRFL_42_RQU_extra"))
    }
}
