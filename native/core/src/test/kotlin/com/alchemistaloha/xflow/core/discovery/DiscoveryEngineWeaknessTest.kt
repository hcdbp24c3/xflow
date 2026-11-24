package com.alchemistaloha.xflow.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Port of test/core/client/discovery_engine_weakness_test.dart. */
class DiscoveryEngineWeaknessTest {
    @Test
    fun `Weakness - applyUnseenSubscriptionBoost violates saturation`() {
        val tweets = listOf(
            createTweet("1", "user_b"),
            createTweet("2", "user_a"),
            createTweet("3", "user_c"),
            createTweet("4", "user_d"),
            createTweet("5", "user_a"),
        )

        val playedCounts = mapOf(
            "user_a" to 0,
            "user_b" to 10,
            "user_c" to 10,
            "user_d" to 10,
        )

        val saturated = DiscoveryEngine.applySaturation(tweets, threshold = 1, windowSize = 2)
        val boosted = DiscoveryEngine.applyUnseenSubscriptionBoost(saturated, playedCounts, lookahead = 5)

        var hasConsecutive = false
        for (i in 0 until boosted.size - 1) {
            if (boosted[i].userHandle == boosted[i + 1].userHandle) {
                hasConsecutive = true
            }
        }

        assertFalse("Boost re-introduced consecutive handles", hasConsecutive)
    }

    @Test
    fun `Weakness - applySaturation fallback ignores window threshold`() {
        // Setup: Enough variety to actually satisfy the threshold
        // 3 'a's, 6 others. Total 9. Window 2 means 2 items between each 'a'.
        val tweets = listOf(
            createTweet("1", "user_a"),
            createTweet("2", "user_a"),
            createTweet("3", "user_a"),
            createTweet("4", "user_b"),
            createTweet("5", "user_c"),
            createTweet("6", "user_d"),
            createTweet("7", "user_e"),
            createTweet("8", "user_f"),
            createTweet("9", "user_g"),
        )

        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, windowSize = 2)

        var hasConsecutive = false
        for (i in 0 until result.size - 1) {
            if (result[i].userHandle == result[i + 1].userHandle) {
                hasConsecutive = true
            }
        }
        assertFalse("Saturation left consecutive handles", hasConsecutive)

        // Check if threshold is met for 'user_a'
        for (i in result.indices) {
            if (result[i].userHandle == "user_a") {
                val start = (i - 2).coerceIn(0, result.size)
                val window = result.subList(start, i)
                val count = window.count { it.userHandle == "user_a" }
                assertEquals("Threshold violated for user_a at index $i", 0, count)
            }
        }
    }
}
