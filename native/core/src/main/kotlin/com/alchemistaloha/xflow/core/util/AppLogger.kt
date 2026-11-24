package com.alchemistaloha.xflow.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory log shown in Settings > log viewer. Keeps the last [MAX_LOGS]
 * lines. Callers must never pass cookies, tokens or request headers.
 */
object AppLogger {
    const val MAX_LOGS = 2000
    private val entries = ArrayDeque<String>()
    private val mutableRevision = MutableStateFlow(0)

    /** Optional extra sink (the app points this at Logcat). Gets the message without the timestamp. */
    @Volatile
    var sink: ((String) -> Unit)? = null

    /** Changes every time a line is added or the log is cleared. */
    val revision: StateFlow<Int> = mutableRevision

    val logs: List<String>
        get() = synchronized(entries) { entries.toList() }

    fun log(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "[$timestamp] $message"
        synchronized(entries) {
            entries.addLast(entry)
            if (entries.size > MAX_LOGS) entries.removeFirst()
        }
        mutableRevision.value = mutableRevision.value + 1
        try {
            sink?.invoke(message)
        } catch (e: Exception) {
            // A broken sink must never break the caller.
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
        mutableRevision.value = mutableRevision.value + 1
    }
}
