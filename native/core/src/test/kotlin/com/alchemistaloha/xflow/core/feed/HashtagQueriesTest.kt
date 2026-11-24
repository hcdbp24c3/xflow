package com.alchemistaloha.xflow.core.feed

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.TweetResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of test/features/feed/hashtag_provider_test.dart against the logic
 * HashtagMediaStore delegates to.
 */
class HashtagQueriesTest {
    private data class Key(val query: String?, val sort: FeedSort?, val cursor: String?)

    private class FakeTwitterClient {
        private val responses = HashMap<Key, TweetResponse>()
        val counts = ArrayList<Int>()

        fun stub(query: String?, sort: FeedSort?, cursor: String? = null, response: TweetResponse) {
            responses[Key(query, sort, cursor)] = response
        }

        val fetch: TrendingMediaFetcher = { query, cursor, count, sort ->
            counts.add(count)
            responses[Key(query, sort, cursor)]
                ?: throw IllegalStateException("Missing stub for query=$query sort=$sort cursor=$cursor")
        }
    }

    // Dart test settings: timelineBatchSize 20, loadBatchSize 20
    private val timelineBatchSize = 20
    private val loadBatchSize = 20

    @Test
    fun `falls back to latest media search when top search is empty`() = runBlocking {
        val mockClient = FakeTwitterClient()
        val latestTweet = Tweet(
            id = "hashtag_latest_1",
            text = "Hashtag media",
            userHandle = "@creator",
            mediaUrls = listOf("https://test.com/hash.jpg"),
            createdAtMillis = 1704067200000L,
        )

        mockClient.stub(
            query = "#nature (filter:images OR filter:videos)",
            sort = FeedSort.trending,
            response = TweetResponse(tweets = emptyList()),
        )
        mockClient.stub(
            query = "#nature (filter:images OR filter:videos)",
            sort = FeedSort.latest,
            response = TweetResponse(tweets = listOf(latestTweet), cursorBottom = "latest_cursor"),
        )

        val loaded = HashtagQueries.loadInitial("#nature", timelineBatchSize, mockClient.fetch)
        val state = loaded.state

        assertTrue(state.tweets.map { it.id }.contains("hashtag_latest_1"))
        assertEquals("latest_cursor", state.cursorBottom)
        assertEquals("#nature (filter:images OR filter:videos)", loaded.activeQuery)
    }

    @Test
    fun `falls back to plain hashtag search and keeps that query for paging`() = runBlocking {
        val mockClient = FakeTwitterClient()
        val fallbackTweet = Tweet(
            id = "hashtag_post_1",
            text = "Plain hashtag post",
            userHandle = "@creator",
            mediaUrls = emptyList(),
            createdAtMillis = 1704067200000L,
        )
        val nextTweet = Tweet(
            id = "hashtag_post_2",
            text = "Next plain hashtag post",
            userHandle = "@creator2",
            mediaUrls = emptyList(),
            createdAtMillis = 1704153600000L,
        )

        mockClient.stub(
            query = "#nature (filter:images OR filter:videos)",
            sort = FeedSort.trending,
            response = TweetResponse(tweets = emptyList()),
        )
        mockClient.stub(
            query = "#nature (filter:images OR filter:videos)",
            sort = FeedSort.latest,
            response = TweetResponse(tweets = emptyList()),
        )
        mockClient.stub(
            query = "#nature",
            sort = FeedSort.latest,
            response = TweetResponse(tweets = listOf(fallbackTweet), cursorBottom = "plain_cursor"),
        )
        mockClient.stub(
            query = "#nature",
            sort = FeedSort.latest,
            cursor = "plain_cursor",
            response = TweetResponse(tweets = listOf(nextTweet), cursorBottom = "plain_cursor_2"),
        )

        val loaded = HashtagQueries.loadInitial("#nature", timelineBatchSize, mockClient.fetch)
        val initialState = loaded.state

        assertTrue(initialState.tweets.map { it.id }.contains("hashtag_post_1"))
        assertEquals("plain_cursor", initialState.cursorBottom)

        val pagedState = HashtagQueries.loadMore(initialState, loaded.activeQuery, loadBatchSize, mockClient.fetch)

        assertTrue(pagedState.tweets.map { it.id }.containsAll(listOf("hashtag_post_1", "hashtag_post_2")))
        assertEquals("plain_cursor_2", pagedState.cursorBottom)
    }

    // ---- not in the Dart file ----

    @Test
    fun `hashtag normalisation`() {
        assertEquals("#nature", HashtagQueries.normalizeHashtag("nature"))
        assertEquals("#nature", HashtagQueries.normalizeHashtag("  #nature "))
        assertEquals("", HashtagQueries.normalizeHashtag("   "))
        assertEquals("#nature (filter:images OR filter:videos)", HashtagQueries.mediaSearchQuery("nature"))
        assertEquals("#nature", HashtagQueries.plainSearchQuery(" nature"))
        assertEquals("#cats", HashtagQueries.cleanHashtagTag("cats"))
        assertEquals("#cats", HashtagQueries.cleanHashtagTag("#cats"))
        assertEquals("# cats", HashtagQueries.cleanHashtagTag(" cats"))
    }

    @Test
    fun `loadMore skips ids already shown and keeps the cursor when none comes back`() = runBlocking {
        val a = Tweet(id = "a", text = "", userHandle = "@u")
        val b = Tweet(id = "b", text = "", userHandle = "@u")
        val current = com.alchemistaloha.xflow.core.model.FeedState(
            tweets = listOf(a), cursorBottom = "c1", isLoadingMore = true,
        )
        val fetch: TrendingMediaFetcher = { _, cursor, _, sort ->
            assertEquals("c1", cursor)
            assertEquals(FeedSort.latest, sort)
            TweetResponse(tweets = listOf(a, b), cursorBottom = null)
        }
        val next = HashtagQueries.loadMore(current, "#q", 20, fetch)
        assertEquals(listOf("a", "b"), next.tweets.map { it.id })
        assertEquals("c1", next.cursorBottom)
        assertEquals(false, next.isLoadingMore)
    }
}
