package com.alchemistaloha.xflow.data.client

import com.alchemistaloha.xflow.core.client.TransactionIdAssets
import com.alchemistaloha.xflow.core.client.TransactionIdGenerator
import com.alchemistaloha.xflow.core.client.TransactionIdIndices
import com.alchemistaloha.xflow.core.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The `x-client-transaction-id` computed in Kotlin ([TransactionIdGenerator])
 * from the public x.com document, for when the hidden page gives none.
 *
 * The document (key and loading frames) is kept for [PAGE_TTL_MS] and read
 * again sooner when X answers 404 to a request that carried an id made
 * here. Which key bytes the generator reads is looked up in X's public
 * scripts once per build of those scripts; when that cannot be done, the
 * bytes known at the time of writing are used.
 */
object LocalTransactionId {
    private const val HOME_URL = "https://x.com/"
    private const val PAGE_TTL_MS = 60 * 60_000L
    private const val RETRY_AFTER_FAILURE_MS = 5 * 60_000L
    private const val REFRESH_AFTER_REFUSAL_MS = 10 * 60_000L
    private const val MAX_SCRIPTS = 24

    private class State(val key: ByteArray, val animationKey: String, val loadedAt: Long)

    private val mutex = Mutex()

    @Volatile private var state: State? = null
    @Volatile private var failedAt = 0L
    @Volatile private var refused = false
    private var indices: TransactionIdIndices = TransactionIdIndices.DEFAULT
    private var indicesSource = "built-in"
    private var indicesForEntry: String? = null
    private var okCount = 0

    fun statusLine(): String =
        "TXID kotlin: page=${if (state != null) "loaded" else "none"} indices=$indicesSource($indices) made=$okCount"

    /** Call when X answered 404 to a request that carried an id made here. */
    fun onRefused() {
        val current = state ?: return
        if (System.currentTimeMillis() - current.loadedAt > REFRESH_AFTER_REFUSAL_MS) refused = true
    }

    /** The id for [method] [path], or null when the page cannot be loaded or read. */
    suspend fun generate(path: String, method: String): String? {
        val now = System.currentTimeMillis()
        var current = state
        if (current == null || refused || now - current.loadedAt > PAGE_TTL_MS) {
            if (now - failedAt < RETRY_AFTER_FAILURE_MS) return current?.let { make(it, path, method) }
            current = mutex.withLock {
                val again = state
                if (again != null && !refused && System.currentTimeMillis() - again.loadedAt <= PAGE_TTL_MS) {
                    again
                } else {
                    try {
                        load()?.also { state = it; refused = false; failedAt = 0L }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.log("TXID kotlin: loading the x.com page failed: ${e::class.simpleName}: ${e.message}")
                        null
                    } ?: run {
                        failedAt = System.currentTimeMillis()
                        // An older page is better than none.
                        again
                    }
                }
            }
        }
        return current?.let { make(it, path, method) }
    }

    private fun make(state: State, path: String, method: String): String {
        okCount++
        return TransactionIdGenerator.generate(method, path, state.key, state.animationKey)
    }

    private suspend fun load(): State? {
        val html = TwitterAccount.fetchPublicText(HOME_URL)
        if (html == null) {
            AppLogger.log("TXID kotlin: the x.com page could not be fetched")
            return null
        }
        val page = TransactionIdGenerator.parsePage(html)
        if (page == null) {
            AppLogger.log("TXID kotlin: no key or loading frames in the x.com page (bytes=${html.length})")
            return null
        }
        findIndices(html)
        val problem = TransactionIdGenerator.problem(page, indices)
        if (problem != null) {
            AppLogger.log("TXID kotlin: the page does not fit the indices ($indices): $problem")
            return null
        }
        AppLogger.log("TXID kotlin: page loaded (key=${page.key.size} bytes, frames=${page.frames.size}), indices=$indicesSource($indices)")
        return State(page.key, TransactionIdGenerator.animationKey(page, indices), System.currentTimeMillis())
    }

    /** Looks the indices up in X's scripts; keeps the ones in use when anything is missing. */
    private suspend fun findIndices(html: String) {
        val entries = TransactionIdAssets.documentScripts(html)
        val entryKey = entries.joinToString(" ")
        if (entries.isEmpty()) {
            AppLogger.log("TXID kotlin: the page names no script; keeping indices=$indicesSource")
            return
        }
        if (entryKey == indicesForEntry) return

        val queue = ArrayDeque(entries)
        val seen = HashSet(entries)
        var fetched = 0
        while (queue.isNotEmpty() && fetched < MAX_SCRIPTS) {
            val url = queue.removeFirst()
            val script = TwitterAccount.fetchPublicText(url) ?: continue
            fetched++
            if (script.contains(TransactionIdAssets.HEADER_NAME)) {
                val generatorUrl = TransactionIdAssets.generatorUrl(script, url)
                val found = generatorUrl?.let { TwitterAccount.fetchPublicText(it) }?.let { TransactionIdAssets.indices(it) }
                if (found != null) {
                    indices = found
                    indicesSource = "x-scripts"
                    indicesForEntry = entryKey
                    AppLogger.log("TXID kotlin: indices read from X's scripts ($found) after $fetched scripts")
                    return
                }
            }
            // Only the scripts of the entry itself are followed: the one that sets the header is among them.
            if (url in entries) {
                for (next in TransactionIdAssets.referencedScripts(script, url)) if (seen.add(next)) queue.addLast(next)
            }
        }
        AppLogger.log("TXID kotlin: indices not found in $fetched scripts; keeping indices=$indicesSource($indices)")
    }
}
