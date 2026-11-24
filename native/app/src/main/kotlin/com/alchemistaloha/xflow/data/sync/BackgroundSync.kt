package com.alchemistaloha.xflow.data.sync

import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.client.TwitterClient
import com.alchemistaloha.xflow.data.db.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the local cache topped up while the app is open: one sync at start,
 * then one every `syncInterval` minutes, and a prune of the cache a minute
 * after start. Call from the main thread.
 */
object BackgroundSync {
    private var syncTimer: Job? = null
    private var isSyncing = false

    @Volatile
    var enabled: Boolean = true

    fun start(client: TwitterClient, settings: SettingsState) {
        if (!enabled) return
        if (syncTimer != null) return

        // Initial sync
        AppGraph.scope.launch { sync(client, settings) }

        // A zero or negative interval would spin; wait at least a minute.
        val intervalMillis = settings.syncInterval.coerceAtLeast(1) * 60_000L
        syncTimer = AppGraph.scope.launch {
            while (isActive) {
                delay(intervalMillis)
                launch { sync(client, settings) }
            }
        }

        // Prune DB 1 minute after start
        AppGraph.scope.launch {
            delay(60_000L)
            try {
                Repository.pruneCachedMedia(threshold = settings.pruneThreshold)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("Background prune error: $e")
            }
        }
    }

    fun restart(client: TwitterClient, settings: SettingsState) {
        stop()
        start(client, settings)
    }

    fun stop() {
        syncTimer?.cancel()
        syncTimer = null
    }

    /** Runs one sync now, outside the interval (after an import). */
    fun syncNow(client: TwitterClient, settings: SettingsState) {
        if (!enabled) return
        AppGraph.scope.launch { sync(client, settings) }
    }

    private suspend fun sync(client: TwitterClient, settings: SettingsState) {
        if (isSyncing) return
        isSyncing = true

        try {
            val subs = Repository.getSubscriptions()
            if (subs.isEmpty()) {
                AppLogger.log("SYNC skipped: no subscriptions")
                return
            }

            // Pick a random subset from settings
            val targets = subs.shuffled().take(settings.syncBatchSize)
            val usersQuery = targets.joinToString(" OR ") { "from:${it.screenName}" }
            val query = "include:nativeretweets ($usersQuery) -filter:replies"

            // Fetch latest
            val response = client.fetchTrendingMedia(
                query = query,
                count = settings.loadBatchSize,
                cooldownMinutes = settings.cooldownDuration,
            )

            if (response.tweets.isNotEmpty()) {
                Repository.insertCachedMedia(response.tweets)
            }
            AppLogger.log(
                "SYNC done: accounts=${targets.size}/${subs.size} fetched=${response.tweets.size} " +
                    "result=${response.outcome?.issue?.name ?: "unknown"} totalCached=${Repository.getCachedMediaCount()}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Background sync error: $e")
        } finally {
            isSyncing = false
        }
    }
}
