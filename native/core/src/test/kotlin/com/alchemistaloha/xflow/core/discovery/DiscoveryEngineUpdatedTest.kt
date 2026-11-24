package com.alchemistaloha.xflow.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of test/core/client/discovery_engine_updated_test.dart. */
class DiscoveryEngineUpdatedTest {
    @Test
    fun `Verified - Media URL saturation works`() {
        val tweets = listOf(
            createTweet("1", "user_a", mediaUrl = "vid_1"),
            createTweet("2", "user_b", mediaUrl = "vid_1"), // Duplicate Media
            createTweet("3", "user_c", mediaUrl = "vid_2"),
        )

        val result = DiscoveryEngine.applySaturation(tweets, threshold = 2, mediaThreshold = 1, windowSize = 5)

        assertEquals("vid_1", result[0].mediaUrls.first())
        assertEquals("vid_2", result[1].mediaUrls.first())
        assertEquals("vid_1", result[2].mediaUrls.first())
    }

    @Test
    fun `Verified - Multi-pass breaks clumps`() {
        // [a, a, a, b, c]
        val tweets = listOf(
            createTweet("1", "user_a"),
            createTweet("2", "user_a"),
            createTweet("3", "user_a"),
            createTweet("4", "user_b"),
            createTweet("5", "user_c"),
        )

        // With threshold 1, window 10.
        // Pass 1: [a, b, a, a, c] -> swap 2 with 4
        // Pass 2: [a, b, c, a, a] -> swap 3 with 5
        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, windowSize = 10, maxPasses = 5)

        assertEquals("user_a", result[0].userHandle)
        assertEquals("user_b", result[1].userHandle)
        assertEquals("user_c", result[2].userHandle)
    }
}
