package com.alchemistaloha.xflow.core.discovery

import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.model.Tweet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FeedPipelineTest {
    private val settings = SettingsState(unseenSubscriptionBoost = false)

    private fun tweets(prefix: String, n: Int, handle: (Int) -> String = { "u_$prefix$it" }): List<Tweet> =
        List(n) { i -> createTweet("$prefix$i", handle(i)) }

    @Test
    fun `protectedHeadLength keeps the watched item and the next one`() {
        assertEquals(2, FeedPipeline.protectedHeadLength(viewIndex = 0, currentLength = 10, resetHead = false))
        assertEquals(7, FeedPipeline.protectedHeadLength(viewIndex = 5, currentLength = 10, resetHead = false))
        assertEquals(2, FeedPipeline.protectedHeadLength(viewIndex = -5, currentLength = 10, resetHead = false))
    }

    @Test
    fun `protectedHeadLength is capped by the list and zero on reset`() {
        assertEquals(1, FeedPipeline.protectedHeadLength(viewIndex = 0, currentLength = 1, resetHead = false))
        assertEquals(10, FeedPipeline.protectedHeadLength(viewIndex = 9, currentLength = 10, resetHead = false))
        assertEquals(0, FeedPipeline.protectedHeadLength(viewIndex = 5, currentLength = 10, resetHead = true))
    }

    @Test
    fun `protected head keeps its place and order`() {
        val current = tweets("cur", 6)
        val result = FeedPipeline.runDiscoveryPipeline(
            tweets("f", 5), tweets("l", 10), settings, emptyMap(),
            protectedIndex = 4, currentTweets = current, random = Random(1),
        )
        assertEquals(current.take(4).map { it.id }, result.take(4).map { it.id })
    }

    @Test
    fun `drops ids and media already on screen and duplicates between pools`() {
        val current = tweets("cur", 3)
        val fresh = listOf(
            current[0], // same id as a visible tweet
            createTweet("f_dupmedia", "x", mediaUrl = current[1].mediaUrls.first()), // same media
            createTweet("f_ok", "y"),
        )
        val local = listOf(
            createTweet("f_ok", "y"), // already taken from the fresh pool
            createTweet("l_samemedia", "z", mediaUrl = "url_f_ok"),
            createTweet("l_ok", "w"),
        )
        val result = FeedPipeline.runDiscoveryPipeline(
            fresh, local, settings, emptyMap(),
            protectedIndex = 2, currentTweets = current, random = Random(2),
        )
        assertEquals(listOf("cur0", "cur1"), result.take(2).map { it.id })
        assertEquals(setOf("f_ok", "l_ok"), result.drop(2).map { it.id }.toSet())
    }

    @Test
    fun `deduplication only looks at the last window of visible tweets`() {
        val current = tweets("cur", 10)
        val s = settings.copy(mediaDeduplicationWindow = 3)
        val pool = listOf(current[0].copy(), current[9].copy())
        val result = FeedPipeline.runDiscoveryPipeline(
            emptyList(), pool, s, emptyMap(),
            protectedIndex = 0, currentTweets = current, random = Random(3),
        )
        // cur0 is outside the 3-item window so it may come back; cur9 is inside it.
        assertEquals(listOf("cur0"), result.map { it.id })
    }

    @Test
    fun `mixes by fresh ratio`() {
        val fresh = tweets("f", 20)
        val local = tweets("l", 20)
        val result = FeedPipeline.runDiscoveryPipeline(
            fresh, local, settings.copy(freshMixRatio = 0.3), emptyMap(), random = Random(4),
        )
        assertEquals(40, result.size)
        assertEquals(40, result.map { it.id }.toSet().size)
        assertEquals(3, result.take(10).count { it.id.startsWith("f") })
    }

    @Test
    fun `saturation does not touch the protected head`() {
        val head = listOf(createTweet("h1", "same"), createTweet("h2", "same"))
        val result = FeedPipeline.runDiscoveryPipeline(
            emptyList(), tweets("l", 5), settings.copy(saturationThreshold = 1), emptyMap(),
            protectedIndex = 2, currentTweets = head, random = Random(5),
        )
        assertEquals(listOf("h1", "h2"), result.take(2).map { it.id })
    }

    @Test
    fun `boost promotes the least watched account after the head`() {
        val head = listOf(createTweet("h1", "a"), createTweet("h2", "b"))
        val local = listOf(createTweet("l1", "heavy"), createTweet("l2", "fresh_account"))
        val played = mapOf("heavy" to 100, "fresh_account" to 0, "a" to 0, "b" to 0)
        repeat(5) { seed ->
            val result = FeedPipeline.runDiscoveryPipeline(
                emptyList(), local, settings.copy(unseenSubscriptionBoost = true), played,
                protectedIndex = 2, currentTweets = head, random = Random(seed),
            )
            assertEquals(listOf("h1", "h2", "l2", "l1"), result.map { it.id })
        }
    }

    @Test
    fun `filterUnseen drops seen ids and seen media`() {
        val candidates = listOf(
            createTweet("1", "a"),
            createTweet("2", "b", mediaUrl = "seen_media"),
            createTweet("3", "c"),
            Tweet(id = "4", text = "", userHandle = "d", mediaUrls = emptyList()),
        )
        val out = FeedPipeline.filterUnseen(candidates, setOf("1"), setOf("seen_media"))
        assertEquals(listOf("3", "4"), out.map { it.id })
    }

    @Test
    fun `appendNewTweets takes one batch tags it and keeps the existing part`() {
        val current = tweets("cur", 4)
        val incoming = tweets("n", 10).mapIndexed { i, t -> if (i % 2 == 0) t.copy(source = "API") else t }
        val (combined, added) = FeedPipeline.appendNewTweets(
            current, incoming, settings.copy(loadBatchSize = 6), Random(6),
        )
        assertEquals(6, added)
        assertEquals(10, combined.size)
        assertEquals(current.map { it.id }, combined.take(4).map { it.id })
        val tail = combined.drop(4)
        assertTrue(tail.all { it.source == "API" || it.source == "Mixed" })
        assertFalse(tail.any { it.id.startsWith("cur") })
    }

    @Test
    fun `dedupeWindow returns the tail`() {
        val list = tweets("t", 5)
        assertEquals(listOf("t3", "t4"), FeedPipeline.dedupeWindow(list, 2).map { it.id })
        assertEquals(5, FeedPipeline.dedupeWindow(list, 50).size)
    }
}
