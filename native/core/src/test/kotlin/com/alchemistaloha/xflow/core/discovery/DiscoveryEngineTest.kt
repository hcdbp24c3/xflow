package com.alchemistaloha.xflow.core.discovery

import com.alchemistaloha.xflow.core.model.Tweet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of test/core/client/discovery_engine_test.dart. */
class DiscoveryEngineTest {
    private fun t(id: String, userHandle: String, text: String = "") =
        Tweet(id = id, userHandle = userHandle, text = text, mediaUrls = emptyList())

    // ---- DiscoveryEngine.interleave ----

    @Test
    fun `interleaves fresh and cached items based on ratio`() {
        val fresh = List(20) { i -> t("f$i", "u", "fresh") }
        val cached = List(20) { i -> t("c$i", "u", "cache") }

        // 0.3 ratio -> ~3 fresh items per 10 items
        val result = DiscoveryEngine.interleave(fresh, cached, 0.3)

        assertEquals(40, result.size)

        val first10 = result.take(10)
        val freshInFirst10 = first10.count { it.id.startsWith("f") }
        val cachedInFirst10 = first10.count { it.id.startsWith("c") }

        assertEquals(3, freshInFirst10)
        assertEquals(7, cachedInFirst10)
    }

    @Test
    fun `handles empty buckets gracefully`() {
        val fresh = emptyList<Tweet>()
        val cached = listOf(t("c1", "u", "cache"))

        val result = DiscoveryEngine.interleave(fresh, cached, 0.5)
        assertEquals(1, result.size)
        assertEquals("c1", result.first().id)
    }

    // ---- DiscoveryEngine.applySaturation ----

    @Test
    fun `enforces saturation threshold and avoids consecutive repeats`() {
        val tweets = listOf(
            t("1", "user_a"),
            t("2", "user_a"),
            t("3", "user_a"),
            t("4", "user_b"),
        )

        val result = DiscoveryEngine.applySaturation(tweets, threshold = 2)

        assertEquals("user_a", result[0].userHandle)
        assertEquals("user_b", result[1].userHandle) // Swapped to avoid consecutive user_a
        assertEquals("user_a", result[2].userHandle)
        assertEquals("user_a", result[3].userHandle) // Two user_a at the end is okay because no one left to swap
    }

    @Test
    fun `handles clumps by spreading them out`() {
        val tweets = listOf(
            t("1", "@A"),
            t("2", "@A"),
            t("3", "@B"),
            t("4", "@B"),
            t("5", "@C"),
            t("6", "@C"),
        )

        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1)

        for (i in 0 until result.size - 1) {
            assertNotEquals("Failed at index $i", result[i + 1].userHandle, result[i].userHandle)
        }
    }

    @Test
    fun `does nothing if under threshold and not consecutive`() {
        val tweets = listOf(
            t("1", "user_a"),
            t("2", "user_b"),
        )
        val result = DiscoveryEngine.applySaturation(tweets, threshold = 2)
        assertEquals(tweets, result)
    }

    // ---- DiscoveryEngine.applyUnseenSubscriptionBoost ----

    @Test
    fun `promotes less-watched accounts within local window`() {
        val tweets = listOf(
            t("1", "@heavy"),
            t("2", "@heavy"),
            t("3", "@light"),
            t("4", "@new"),
        )

        val boosted = DiscoveryEngine.applyUnseenSubscriptionBoost(
            tweets,
            mapOf("heavy" to 50, "light" to 5, "new" to 0),
            lookahead = 4,
        )

        // Expect a lower-played account to surface ahead of heavy users.
        assertTrue(boosted.first().userHandle in setOf("@light", "@new"))
    }

    @Test
    fun `keeps order when no play stats are available`() {
        val tweets = listOf(
            t("1", "@a"),
            t("2", "@b"),
        )

        val boosted = DiscoveryEngine.applyUnseenSubscriptionBoost(tweets, emptyMap())
        assertEquals(tweets, boosted)
    }

    @Test
    fun `protects items before startIndex from being swapped`() {
        val tweets = listOf(
            t("1", "@heavy"),
            t("2", "@light"),
        )

        // Even though @light is "better", it won't be boosted to index 0 if startIndex is 1
        val boosted = DiscoveryEngine.applyUnseenSubscriptionBoost(
            tweets,
            mapOf("heavy" to 100, "light" to 0),
            startIndex = 1,
        )

        assertEquals("@heavy", boosted[0].userHandle)
    }

    // ---- DiscoveryEngine startIndex protection ----

    @Test
    fun `applySaturation does not swap items before startIndex`() {
        val tweets = listOf(
            t("1", "@A"),
            t("2", "@A"), // Consecutive!
            t("3", "@B"),
        )

        // Normally index 1 would be swapped. If we protect index 1, it stays.
        val result = DiscoveryEngine.applySaturation(tweets, threshold = 1, startIndex = 2)

        assertEquals("@A", result[0].userHandle)
        assertEquals("@A", result[1].userHandle)
        assertEquals("@B", result[2].userHandle)
    }
}
