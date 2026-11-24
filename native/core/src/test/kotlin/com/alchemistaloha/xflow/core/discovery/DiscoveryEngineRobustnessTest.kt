package com.alchemistaloha.xflow.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Port of test/core/client/discovery_engine_robustness_test.dart. */
class DiscoveryEngineRobustnessTest {
    @Test
    fun `Requirement - Saturation must handle both Handle and Media`() {
        val tweets = listOf(
            createTweet("1", "user_a", mediaUrl = "vid_1"),
            createTweet("2", "user_b", mediaUrl = "vid_1"), // Duplicate Media
            createTweet("3", "user_a", mediaUrl = "vid_2"), // Duplicate Handle
            createTweet("4", "user_c", mediaUrl = "vid_3"),
            createTweet("5", "user_d", mediaUrl = "vid_4"),
        )

        // With threshold 1, window 5:
        // Index 1 (vid_1) should be swapped away.
        // Index 2 (user_a) should be swapped away.
        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, windowSize = 5)

        // Check Window 0-2
        val window = result.subList(0, 3)
        val handles = window.map { it.userHandle }.toSet()
        val media = window.map { it.mediaUrls.first() }.toSet()

        assertEquals("Handle duplicate in window", 3, handles.size)
        assertEquals("Media duplicate in window", 3, media.size)
    }

    @Test
    fun `Requirement - Multi-pass saturation to prevent leaks`() {
        // Setup: Clump of 3 user_a, followed by 2 unique users.
        // [a, a, a, b, c]
        val tweets = listOf(
            createTweet("1", "user_a"),
            createTweet("2", "user_a"),
            createTweet("3", "user_a"),
            createTweet("4", "user_b"),
            createTweet("5", "user_c"),
        )

        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, windowSize = 10)

        // Index 1 and 2 should no longer be user_a
        assertEquals("user_a", result[0].userHandle)
        assertNotEquals("user_a", result[1].userHandle)
        assertNotEquals("user_a", result[2].userHandle)
    }
}
