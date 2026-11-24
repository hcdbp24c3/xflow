package com.alchemistaloha.xflow.data.client

import com.alchemistaloha.xflow.AppGraph
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A small text cache on disk where every entry has its own lifetime. Stands
 * in for the `ffcache` package of the Flutter app. One file per key: the
 * first line is the time the entry expires (epoch milliseconds), the rest is
 * the value.
 */
class HttpCache(
    private val directoryProvider: () -> File = { File(AppGraph.appContext.cacheDir, "http_cache") },
) {
    private val lock = Any()

    private fun fileFor(key: String): File = File(directoryProvider(), key)

    /** The value stored under [key], or null when there is none or it has expired. */
    suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = fileFor(key)
            if (!file.isFile) return@synchronized null
            try {
                val text = file.readText(Charsets.UTF_8)
                val lineEnd = text.indexOf('\n')
                val expiresAt = if (lineEnd < 0) null else text.substring(0, lineEnd).toLongOrNull()
                if (expiresAt == null || System.currentTimeMillis() >= expiresAt) {
                    file.delete()
                    null
                } else {
                    text.substring(lineEnd + 1)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Stores [value] under [key] for [timeoutMillis]. */
    suspend fun setStringWithTimeout(key: String, value: String, timeoutMillis: Long) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    val directory = directoryProvider()
                    directory.mkdirs()
                    val expiresAt = System.currentTimeMillis() + timeoutMillis
                    val temp = File(directory, "$key.tmp")
                    temp.writeText("$expiresAt\n$value", Charsets.UTF_8)
                    val target = fileFor(key)
                    if (!temp.renameTo(target)) {
                        target.delete()
                        if (!temp.renameTo(target)) temp.delete()
                    }
                } catch (_: Exception) {
                    // A cache that cannot be written is only a slower app.
                }
            }
        }
    }

    /** Removes every entry. */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                directoryProvider().listFiles()?.forEach { it.delete() }
            }
        }
    }
}
