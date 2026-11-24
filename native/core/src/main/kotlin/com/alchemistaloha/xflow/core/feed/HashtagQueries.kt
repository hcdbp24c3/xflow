package com.alchemistaloha.xflow.core.feed

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.FeedState
import com.alchemistaloha.xflow.core.model.TweetResponse

/** Search for one hashtag page: `(query, cursor, count, sort)`. */
typealias TrendingMediaFetcher = suspend (query: String, cursor: String?, count: Int, sort: FeedSort) -> TweetResponse

/** Hashtag text helpers and the search-with-fallback logic of the hashtag feed. */
object HashtagQueries {
    /** What `HashtagListNotifier.addHashtag` stores: the tag with a leading '#'. */
    fun cleanHashtagTag(tag: String): String = if (tag.startsWith("#")) tag else "#$tag"

    fun normalizeHashtag(hashtag: String): String {
        val trimmed = hashtag.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    }

    fun mediaSearchQuery(hashtag: String): String {
        val normalized = normalizeHashtag(hashtag)
        return "$normalized (filter:images OR filter:videos)"
    }

    fun plainSearchQuery(hashtag: String): String = normalizeHashtag(hashtag)

    /** First page of a hashtag feed and the query later pages must keep using. */
    data class InitialLoad(val state: FeedState, val activeQuery: String)

    /**
     * Trending media search first; if empty, latest media; if still empty, a
     * plain latest search for the hashtag (which then becomes the paging query).
     */
    suspend fun loadInitial(hashtag: String, timelineBatchSize: Int, fetch: TrendingMediaFetcher): InitialLoad {
        val mediaQuery = mediaSearchQuery(hashtag)
        val plainQuery = plainSearchQuery(hashtag)

        var response = fetch(mediaQuery, null, timelineBatchSize, FeedSort.trending)
        var activeQuery = mediaQuery

        if (response.tweets.isEmpty()) {
            response = fetch(mediaQuery, null, timelineBatchSize, FeedSort.latest)
        }

        if (response.tweets.isEmpty()) {
            response = fetch(plainQuery, null, timelineBatchSize, FeedSort.latest)
            activeQuery = plainQuery
        }

        return InitialLoad(
            FeedState(
                tweets = response.tweets,
                cursorBottom = response.cursorBottom,
                isRefreshing = false,
            ),
            activeQuery,
        )
    }

    /** Next page: latest search with the stored cursor, new ids appended. */
    suspend fun loadMore(
        currentState: FeedState,
        query: String,
        loadBatchSize: Int,
        fetch: TrendingMediaFetcher,
    ): FeedState {
        val response = fetch(query, currentState.cursorBottom, loadBatchSize, FeedSort.latest)

        val seenIds = currentState.tweets.map { it.id }.toSet()
        val uniqueNew = response.tweets.filter { !seenIds.contains(it.id) }

        return currentState.copyWith(
            tweets = currentState.tweets + uniqueNew,
            cursorBottom = response.cursorBottom,
            isLoadingMore = false,
        )
    }
}
