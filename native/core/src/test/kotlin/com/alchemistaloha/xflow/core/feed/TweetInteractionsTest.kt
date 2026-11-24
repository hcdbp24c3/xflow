package com.alchemistaloha.xflow.core.feed

import com.alchemistaloha.xflow.core.model.FeedState
import com.alchemistaloha.xflow.core.model.Tweet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of test/features/feed/feed_like_test.dart against the toggle logic
 * FeedStore delegates to (the store itself needs Android).
 */
class TweetInteractionsTest {
    private fun tweet(id: String, liked: Boolean = false, likes: Int = 5) = Tweet(
        id = id,
        text = "tweet $id",
        userHandle = "@someone",
        mediaUrls = listOf("https://test.com/$id.mp4"),
        isVideo = true,
        isLiked = liked,
        favoriteCount = likes,
    )

    private class FakeTwitterClient {
        var succeed = true
        val favorited = ArrayList<String>()
        val unfavorited = ArrayList<String>()
        val retweeted = ArrayList<String>()

        suspend fun favoriteTweet(tweetId: String): Boolean { favorited.add(tweetId); return succeed }
        suspend fun unfavoriteTweet(tweetId: String): Boolean { unfavorited.add(tweetId); return succeed }
        suspend fun retweetTweet(tweetId: String): Boolean { retweeted.add(tweetId); return succeed }
        suspend fun unretweetTweet(tweetId: String): Boolean = succeed
    }

    /** The feed + overrides pair with toggleLike / toggleRetweet wired like FeedStore. */
    private class Harness(val client: FakeTwitterClient, feedTweets: List<Tweet>?) {
        var feed: FeedState? = feedTweets?.let { FeedState(tweets = it) }
        val overrides = LinkedHashMap<String, Tweet>()
        private val toggler = InteractionToggler(
            readFeed = { feed },
            writeFeed = { feed = it },
            readOverride = { overrides[it] },
            putOverride = { overrides[it.id] = it },
        )

        suspend fun toggleLike(tweetId: String, tweet: Tweet? = null) = toggler.toggle(
            tweetId, fallback = tweet, label = "like",
            apply = TweetInteractions::applyLikeToggle,
            send = { updated ->
                if (updated.isLiked) client.favoriteTweet(tweetId) else client.unfavoriteTweet(tweetId)
            },
        )

        suspend fun toggleRetweet(tweetId: String, tweet: Tweet? = null) = toggler.toggle(
            tweetId, fallback = tweet, label = "retweet",
            apply = TweetInteractions::applyRetweetToggle,
            send = { updated ->
                if (updated.isRetweeted) client.retweetTweet(tweetId) else client.unretweetTweet(tweetId)
            },
        )
    }

    @Test
    fun `likes a tweet that is in the main feed`() = runBlocking {
        val client = FakeTwitterClient()
        val h = Harness(client, listOf(tweet("a"), tweet("b")))

        h.toggleLike("b")

        val b = h.feed!!.tweets[1]
        assertTrue(b.isLiked)
        assertEquals(6, b.favoriteCount)
        assertEquals(listOf("b"), client.favorited)
        assertTrue(h.overrides.isEmpty())
    }

    @Test
    fun `likes a tweet that is NOT in the main feed (hashtag or profile feed)`() = runBlocking {
        // Regression: toggleLike returned silently when the tweet was not in
        // the main feed, so likes did nothing on hashtag and profile feeds.
        val client = FakeTwitterClient()
        val h = Harness(client, listOf(tweet("a")))
        val outside = tweet("outside")

        h.toggleLike("outside", tweet = outside)

        assertEquals(listOf("outside"), client.favorited)
        val stored = h.overrides["outside"]
        assertNotNull(stored)
        assertTrue(stored!!.isLiked)
        assertEquals(6, stored.favoriteCount)

        // A second toggle starts from the stored state and unlikes.
        h.toggleLike("outside", tweet = outside)
        assertEquals(listOf("outside"), client.unfavorited)
        val after = h.overrides["outside"]!!
        assertFalse(after.isLiked)
        assertEquals(5, after.favoriteCount)
    }

    @Test
    fun `reverts when the request fails`() = runBlocking {
        val client = FakeTwitterClient().apply { succeed = false }
        val h = Harness(client, listOf(tweet("a")))
        val outside = tweet("outside")

        h.toggleLike("a")
        h.toggleLike("outside", tweet = outside)

        assertFalse(h.feed!!.tweets[0].isLiked)
        assertFalse(h.overrides["outside"]!!.isLiked)
    }

    @Test
    fun `an unknown tweet without a fallback is ignored`() = runBlocking {
        val client = FakeTwitterClient()
        val h = Harness(client, listOf(tweet("a")))

        h.toggleLike("nope")

        assertTrue(client.favorited.isEmpty())
        assertTrue(h.overrides.isEmpty())
    }

    @Test
    fun `retweet works for a tweet outside the main feed`() = runBlocking {
        val client = FakeTwitterClient()
        val h = Harness(client, listOf(tweet("a")))

        h.toggleRetweet("outside", tweet = tweet("outside"))

        assertEquals(listOf("outside"), client.retweeted)
        assertTrue(h.overrides["outside"]!!.isRetweeted)
    }

    // ---- not in the Dart file ----

    @Test
    fun `reverts when the request throws`() = runBlocking {
        val h = Harness(FakeTwitterClient(), listOf(tweet("a")))
        val toggler = InteractionToggler({ h.feed }, { h.feed = it }, { h.overrides[it] }, { h.overrides[it.id] = it })
        toggler.toggle("a", null, "like", TweetInteractions::applyLikeToggle) { throw IllegalStateException("boom") }
        assertFalse(h.feed!!.tweets[0].isLiked)
        assertEquals(5, h.feed!!.tweets[0].favoriteCount)
    }

    @Test
    fun `works before the feed has any state`() = runBlocking {
        val client = FakeTwitterClient()
        val h = Harness(client, null)
        h.toggleLike("outside", tweet = tweet("outside"))
        assertTrue(h.overrides["outside"]!!.isLiked)
        assertEquals(null, h.feed)
    }

    @Test
    fun `counts never go below zero`() {
        val unliked = TweetInteractions.applyLikeToggle(tweet("x", liked = true, likes = 0))
        assertFalse(unliked.isLiked)
        assertEquals(0, unliked.favoriteCount)
        val t = tweet("y").copy(isRetweeted = true, retweetCount = 0)
        assertEquals(0, TweetInteractions.applyRetweetToggle(t).retweetCount)
        assertEquals(1, TweetInteractions.applyRetweetToggle(tweet("z")).retweetCount)
    }

    @Test
    fun `live tweet prefers the feed then the override then the given tweet`() {
        val given = tweet("a")
        val inFeed = given.copy(favoriteCount = 9)
        val overridden = given.copy(favoriteCount = 7)
        assertSame(inFeed, TweetInteractions.resolveLiveTweet(listOf(tweet("b"), inFeed), mapOf("a" to overridden), given))
        assertSame(overridden, TweetInteractions.resolveLiveTweet(listOf(tweet("b")), mapOf("a" to overridden), given))
        assertSame(overridden, TweetInteractions.resolveLiveTweet(null, mapOf("a" to overridden), given))
        assertSame(given, TweetInteractions.resolveLiveTweet(null, emptyMap(), given))
    }

    @Test
    fun `copyWith keeps the cursor when given null`() {
        val state = FeedState(tweets = listOf(tweet("a")), cursorBottom = "c1", isLoadingMore = true)
        val next = state.copyWith(cursorBottom = null, isLoadingMore = false)
        assertEquals("c1", next.cursorBottom)
        assertFalse(next.isLoadingMore)
        assertEquals("c2", state.copyWith(cursorBottom = "c2").cursorBottom)
        assertEquals(state, state.copyWith())
    }
}
