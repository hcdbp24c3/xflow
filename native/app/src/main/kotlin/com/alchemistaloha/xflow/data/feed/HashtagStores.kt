package com.alchemistaloha.xflow.data.feed

import com.alchemistaloha.xflow.core.feed.HashtagQueries
import com.alchemistaloha.xflow.core.feed.TrendingMediaFetcher
import com.alchemistaloha.xflow.core.feed.copyWith
import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.FeedState
import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.data.RebuildingStore
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException

/** The followed hashtags, newest first (Dart `hashtagListProvider` / `HashtagListNotifier`). */
object HashtagListStore : RebuildingStore<List<String>>(watchesSettings = false) {
    override suspend fun build(settings: SettingsState): List<String> = Repository.getHashtags()

    suspend fun addHashtag(tag: String) {
        val cleanTag = HashtagQueries.cleanHashtagTag(tag)
        Repository.addHashtag(cleanTag)
        invalidate()
    }

    suspend fun removeHashtag(tag: String) {
        Repository.deleteHashtag(tag)
        invalidate()
    }
}

/** The media feed of one hashtag (Dart `hashtagMediaProvider(tag)` / `HashtagMediaNotifier`). */
class HashtagMediaStore(val arg: String) : RebuildingStore<FeedState>(watchesSettings = true) {
    private var activeQuery: String? = null

    private val fetch: TrendingMediaFetcher = { query, cursor, count, sort ->
        twitterClient.fetchTrendingMedia(query = query, cursor = cursor, count = count, sort = sort)
    }

    override fun onReset() {
        activeQuery = null
    }

    override suspend fun build(settings: SettingsState): FeedState {
        val loaded = HashtagQueries.loadInitial(arg, settings.timelineBatchSize, fetch)
        activeQuery = loaded.activeQuery
        return loaded.state
    }

    suspend fun refresh() {
        invalidate()
    }

    suspend fun fetchMore() = inBuild {
        val gen = generation
        val currentState = value
        if (currentState == null ||
            currentState.isLoadingMore ||
            currentState.cursorBottom == null
        ) {
            return@inBuild
        }

        mutableState.value = Async.Data(currentState.copyWith(isLoadingMore = true))

        val settings = SettingsStore.state.value
        val query = activeQuery ?: HashtagQueries.mediaSearchQuery(arg)

        try {
            val next = HashtagQueries.loadMore(currentState, query, settings.loadBatchSize, fetch)
            if (isCurrent(gen)) mutableState.value = Async.Data(next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isCurrent(gen)) mutableState.value = Async.Data(currentState.copyWith(isLoadingMore = false))
        }
    }
}

/** One [HashtagMediaStore] per hashtag, kept for the life of the process. */
object HashtagMediaStores {
    private val stores = HashMap<String, HashtagMediaStore>()

    @Synchronized
    fun get(tag: String): HashtagMediaStore = stores.getOrPut(tag) { HashtagMediaStore(tag) }
}
