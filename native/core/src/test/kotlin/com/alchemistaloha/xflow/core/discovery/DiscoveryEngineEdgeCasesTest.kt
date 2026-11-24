package com.alchemistaloha.xflow.core.discovery

import com.alchemistaloha.xflow.core.model.Tweet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun createTweet(id: String, handle: String, mediaUrl: String? = null): Tweet = Tweet(
    id = id,
    userHandle = handle,
    text = "Tweet $id",
    mediaUrls = listOf(mediaUrl ?: "url_$id"),
    createdAtMillis = System.currentTimeMillis(),
)

/** Port of test/core/client/discovery_engine_edge_cases_test.dart. */
class DiscoveryEngineEdgeCasesTest {
    @Test
    fun `Edge Case - Case-sensitivity in handles`() {
        // Setup: Threshold 1, but handles vary by case
        val tweets = listOf(
            createTweet("1", "UserA"),
            createTweet("2", "usera"),
            createTweet("3", "UserB"),
        )

        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, windowSize = 5)

        val hasDuplicateInWindow = result[0].userHandle.lowercase() == result[1].userHandle.lowercase()

        assertFalse("Saturation ignored case-sensitivity", hasDuplicateInWindow)
    }

    @Test
    fun `Edge Case - Duplicate Media URL (Different ID)`() {
        // Setup: Different IDs, but same Media URL
        val tweets = listOf(
            createTweet("100", "user_a", mediaUrl = "same_vid"),
            createTweet("101", "user_b", mediaUrl = "other_vid"),
            createTweet("102", "user_c", mediaUrl = "third_vid"), // Added to provide swap candidate
            createTweet("103", "user_d", mediaUrl = "same_vid"),
        )

        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, mediaThreshold = 1, windowSize = 5)

        var hasDuplicateMedia = false
        for (i in 0 until result.size - 1) {
            if (result[i].mediaUrls.first() == result[i + 1].mediaUrls.first()) {
                hasDuplicateMedia = true
            }
        }
        assertFalse("Duplicate media found consecutively", hasDuplicateMedia)
    }

    @Test
    fun `Edge Case - Clump larger than lookahead`() {
        // Setup: threshold 1, window 10.
        val tweets = List(20) { i -> createTweet("$i", "user_a") } +
            createTweet("99", "user_b") +
            List(20) { i -> createTweet("${i + 30}", "user_c") } +
            List(20) { i -> createTweet("${i + 60}", "user_d") }

        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, windowSize = 5)

        // Verify if it managed to pull non-'a' items up
        var userACount = 0
        for (i in 0 until 5) {
            if (result[i].userHandle == "user_a") userACount++
        }

        assertTrue("Failed to break large clump effectively", userACount <= 2)
    }

    @Test
    fun `Extreme - All same user except one at the very end`() {
        val tweets = List(10) { i -> createTweet("$i", "user_a") } + createTweet("99", "user_b")

        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, windowSize = 10)
        // Should have moved user_b as far up as possible without violating other rules
        assertEquals("user_b", result[1].userHandle)
    }
}
