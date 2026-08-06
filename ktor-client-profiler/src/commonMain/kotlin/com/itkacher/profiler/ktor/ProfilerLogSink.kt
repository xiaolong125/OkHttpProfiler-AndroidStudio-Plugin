package com.itkacher.profiler.ktor

fun interface ProfilerLogSink {
    fun log(tag: String, message: String)
}

object NoOpProfilerLogSink : ProfilerLogSink {
    override fun log(tag: String, message: String) = Unit
}

internal expect fun platformDefaultProfilerLogSink(): ProfilerLogSink
