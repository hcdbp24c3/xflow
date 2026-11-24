package com.alchemistaloha.xflow.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of test/core/client/discovery_engine_failure_test.dart. */
class DiscoveryEngineFailureTest {
    @Test
    fun `Failure - Total Pool Exhaustion (No valid swap candidates)`() {
        // Setup: 10 items, ALL from user_a.
        // threshold 1, window 10.
        val tweets = List(10) { i -> createTweet("$i", "user_a") }

        // Saturation will attempt to swap every single item but find nobody else.
        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, windowSize = 10)

        // Should remain all user_a, but we should verify it didn't crash
        assertEquals(10, result.size)
        assertTrue(result.all { it.userHandle == "user_a" })
    }

    @Test
    fun `Failure - Tail Deadlock (Clump at the end)`() {
        // Setup: Diverse start, clump at the end.
        val tweets = listOf(
            createTweet("1", "user_a"),
            createTweet("2", "user_b"),
            createTweet("3", "user_c"),
            createTweet("4", "user_a"),
            createTweet("5", "user_a"), // Clump at end
        )

        // threshold 1. Index 4 (user_a) is consecutive with index 3.
        // It looks for swaps after index 4. There are none.
        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, windowSize = 5)

        // WEAKNESS: It cannot fix the tail.
        assertEquals("user_a", result[4].userHandle)
        assertEquals("user_a", result[3].userHandle)
    }
}
