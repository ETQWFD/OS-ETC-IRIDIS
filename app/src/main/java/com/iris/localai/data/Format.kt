package com.iris.localai.data

import java.util.Calendar
import java.util.Locale

object Format {
    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", b / (1024.0 * 1024))
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    fun params(n: Long): String = when {
        n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
        n >= 1_000_000 -> String.format(Locale.US, "%.0fM", n / 1e6)
        else -> n.toString()
    }

    fun seconds(ms: Long): String =
        if (ms < 10_000) String.format(Locale.US, "%.1f", ms / 1000.0) else (ms / 1000).toString()

    /** Drawer section for a timestamp, ChatGPT-style. */
    fun dateGroup(ts: Long, now: Long = System.currentTimeMillis()): String {
        val today = startOfDay(now)
        val day = 24L * 3600 * 1000
        return when {
            ts >= today -> "今天"
            ts >= today - day -> "昨天"
            ts >= today - 7 * day -> "7 天内"
            ts >= today - 30 * day -> "30 天内"
            else -> {
                val c = Calendar.getInstance().apply { timeInMillis = ts }
                val n = Calendar.getInstance().apply { timeInMillis = now }
                if (c.get(Calendar.YEAR) == n.get(Calendar.YEAR)) "${c.get(Calendar.MONTH) + 1} 月"
                else "${c.get(Calendar.YEAR)} 年"
            }
        }
    }

    private fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
