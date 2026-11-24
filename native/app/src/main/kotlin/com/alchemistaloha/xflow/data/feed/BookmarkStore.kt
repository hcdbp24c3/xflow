package com.alchemistaloha.xflow.data.feed

import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.RebuildingStore
import com.alchemistaloha.xflow.data.db.Repository
import kotlinx.coroutines.CancellationException

/** The saved tweets, newest first (Dart `bookmarkListProvider` / `BookmarkListNotifier`). */
object BookmarkStore : RebuildingStore<List<Tweet>>(watchesSettings = false) {
    override suspend fun build(settings: SettingsState): List<Tweet> = loadBookmarksFromDb()

    private suspend fun loadBookmarksFromDb(): List<Tweet> {
        try {
            val bookmarks = Repository.getBookmarks()
            AppLogger.log("XFLOW: Loaded ${bookmarks.size} bookmarks")
            return bookmarks
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Error loading bookmarks: $e\n${e.stackTraceToString()}")
            return emptyList()
        }
    }

    suspend fun loadBookmarks() = inApp {
        ensureStarted()
        mutableState.value = Async.Loading
        mutableState.value = Async.Data(loadBookmarksFromDb())
    }

    suspend fun toggleBookmark(tweet: Tweet) = inApp {
        val currentState = value ?: return@inApp

        val newIsBookmarked = !tweet.isBookmarked

        if (newIsBookmarked) {
            // Add bookmark: optimistic insert at the top
            val updatedTweet = tweet.copy(isBookmarked = true)
            mutableState.value = Async.Data(listOf(updatedTweet) + currentState)

            try {
                Repository.insertBookmark(tweet)
                AppLogger.log("XFLOW: Bookmarked tweet ${tweet.id}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("XFLOW: Failed to bookmark tweet ${tweet.id}: $e")
                mutableState.value = Async.Data(loadBookmarksFromDb())
            }
        } else {
            // Remove bookmark: optimistic removal
            val updated = currentState.filter { it.id != tweet.id }
            mutableState.value = Async.Data(updated)

            try {
                Repository.removeBookmark(tweet.id)
                AppLogger.log("XFLOW: Removed bookmark for tweet ${tweet.id}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("XFLOW: Failed to remove bookmark ${tweet.id}: $e")
                mutableState.value = Async.Data(loadBookmarksFromDb())
            }
        }
    }

    suspend fun addBookmark(tweet: Tweet) = inApp {
        val currentState = value ?: return@inApp

        val updatedTweet = tweet.copy(isBookmarked = true)
        mutableState.value = Async.Data(listOf(updatedTweet) + currentState)

        try {
            Repository.insertBookmark(tweet)
            AppLogger.log("XFLOW: Bookmarked tweet ${tweet.id}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Failed to bookmark tweet ${tweet.id}: $e")
            mutableState.value = Async.Data(loadBookmarksFromDb())
        }
    }
}
