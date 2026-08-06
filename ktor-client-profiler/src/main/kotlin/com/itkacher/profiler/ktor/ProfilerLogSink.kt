package com.itkacher.profiler.ktor

fun interface ProfilerLogSink {
    fun log(tag: String, message: String)
}

object AndroidLogcatSink : ProfilerLogSink {
    private val logMethod by lazy {
        runCatching {
            Class.forName("android.util.Log")
                .getMethod("d", String::class.java, String::class.java)
        }.getOrNull()
    }

    override fun log(tag: String, message: String) {
        val method = logMethod
            ?: error("Android Logcat is unavailable. Configure KtorProfilerConfig.sink for this platform.")
        method.invoke(null, tag, message)
    }
}
