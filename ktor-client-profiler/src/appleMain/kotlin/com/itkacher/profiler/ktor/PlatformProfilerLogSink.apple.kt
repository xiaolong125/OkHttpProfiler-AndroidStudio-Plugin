package com.itkacher.profiler.ktor

import platform.Foundation.NSLog

object IosSystemLogSink : ProfilerLogSink {
    override fun log(tag: String, message: String) {
        NSLog("%@: %@", tag, message)
    }
}

internal actual fun platformDefaultProfilerLogSink(): ProfilerLogSink = IosSystemLogSink
