package com.alchemistaloha.xflow.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory log shown in Settings > log viewer. Keeps the last 1000 lines. */
object AppLogger {
    private const val MAX_LOGS = 1000
    private val entries = ArrayDeque<String>()

    /** Optional extra sink (the app points this at Logcat). */
    @Volatile
    var sink: ((String) -> Unit)? = null

    val logs: List<String>
        get() = synchronized(entries) { entries.toList() }

    fun log(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "[$timestamp] $message"
        synchronized(entries) {
            entries.addLast(entry)
            if (entries.size > MAX_LOGS) entries.removeFirst()
        }
        sink?.invoke(entry)
    }

    fun clear() = synchronized(entries) { entries.clear() }
}
