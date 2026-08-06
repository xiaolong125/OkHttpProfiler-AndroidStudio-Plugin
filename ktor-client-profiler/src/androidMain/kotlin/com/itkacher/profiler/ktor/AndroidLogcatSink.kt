package com.itkacher.profiler.ktor

import android.util.Log

object AndroidLogcatSink : ProfilerLogSink {
    override fun log(tag: String, message: String) {
        Log.d(tag, message)
    }
}

internal actual fun platformDefaultProfilerLogSink(): ProfilerLogSink = AndroidLogcatSink
