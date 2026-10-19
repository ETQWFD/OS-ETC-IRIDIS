package com.iris.localai.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records why the app died last time — Kotlin exceptions via the default handler, and native
 * crashes / low-memory kills via ApplicationExitInfo — so the next launch can show it.
 */
object CrashReporter {
    /** 1.14.3: internal so 设置 › 存储空间 can list (and clear) the crash record. */
    internal const val FILE = "last-crash.txt"
    private const val SEEN = "crash-seen-ts"

    @Volatile private var crumbFile: File? = null

    /**
     * True when the previous process died with native voice work in flight (a breadcrumb was left
     * behind). Startup then skips the automatic speech warm-up so one bad model can't crash-loop the app.
     */
    @Volatile var diedInNativeWorkLastRun = false
        private set

    /** Notes what native work is about to run, so a native crash report can say where it died. */
    fun breadcrumb(text: String?) {
        val f = crumbFile ?: return
        runCatching { if (text == null) f.delete() else f.writeText("${stamp(System.currentTimeMillis())} $text") }
    }

    fun install(context: Context) {
        val app = context.applicationContext
        crumbFile = File(app.filesDir, "breadcrumb.txt").also { diedInNativeWorkLastRun = it.exists() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(app.filesDir, FILE).writeText(
                    "${stamp(System.currentTimeMillis())} · 线程 ${t.name}\n${header()}\n${e.stackTraceToString().take(6000)}",
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** The unseen report from the previous run, or null. Marks it seen. */
    fun takeReport(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (f.exists()) {
            val text = f.readText()
            f.delete()
            return text
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, 0L)
        val info = runCatching { am.getHistoricalProcessExitReasons(null, 0, 1) }.getOrNull()?.firstOrNull() ?: return null
        if (info.timestamp <= seen) return null
        prefs.edit().putLong(SEEN, info.timestamp).apply()
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生库崩溃 (native)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足，被系统结束"
            ApplicationExitInfo.REASON_ANR -> "无响应 (ANR)"
            ApplicationExitInfo.REASON_CRASH -> "程序异常"
            ApplicationExitInfo.REASON_SIGNALED -> if (info.status == 9) "被系统强制结束（多半是内存不足）" else "收到信号 ${info.status}"
            else -> return null // normal exits, user swipes, updates
        }
        val crumb = File(context.filesDir, "breadcrumb.txt").takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
        val trace = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) tombstoneSummary(info) else ""
        val mem = "PSS ${info.pss / 1024} MB · RSS ${info.rss / 1024} MB"
        return buildString {
            append("${stamp(info.timestamp)} · $reason\n$mem\n${header()}\n${info.description ?: ""}")
            if (crumb != null) append("\n崩溃时正在：$crumb")
            if (trace.isNotBlank()) append("\n$trace")
        }
    }

    /** Pulls readable lines (signal, abort message, library frames) out of the tombstone proto. */
    private fun tombstoneSummary(info: ApplicationExitInfo): String = runCatching {
        val bytes = info.traceInputStream?.use { it.readBytes() } ?: return ""
        val runs = ArrayList<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val ch = b.toInt() and 0xFF
            if (ch in 0x20..0x7E) sb.append(ch.toChar()) else { if (sb.length >= 6) runs.add(sb.toString()); sb.setLength(0) }
        }
        if (sb.length >= 6) runs.add(sb.toString())
        val interesting = runs.filter { r ->
            listOf("SIG", "abort", "Abort", "sherpa", "onnx", "litert", "llama", "ggml", "Check failed", "error", ".so", "iris").any { it in r }
        }.distinct()
        interesting.take(40).joinToString("\n")
    }.getOrDefault("")

    private fun header() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""}"
    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
