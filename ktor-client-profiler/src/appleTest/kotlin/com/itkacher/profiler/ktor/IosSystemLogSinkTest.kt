package com.itkacher.profiler.ktor

import kotlin.test.Test

class IosSystemLogSinkTest {
    @Test
    fun `logs percent placeholders as plain text`() {
        IosSystemLogSink.log(
            tag = "KTRPRFL_%@_RQU",
            message = "https://example.test/100%?value=%@-%s-%d-%"
        )
    }
}
