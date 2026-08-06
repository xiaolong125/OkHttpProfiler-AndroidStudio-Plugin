package com.itkacher.profiler.ktor

import platform.Foundation.NSLog

object IosSystemLogSink : ProfilerLogSink {
    override fun log(tag: String, message: String) {
        val escapedLog = "$tag: $message".replace("%", "%%")
        NSLog(escapedLog)
    }
}

internal actual fun platformDefaultProfilerLogSink(): ProfilerLogSink = IosSystemLogSink
