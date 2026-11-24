package com.alchemistaloha.xflow.core.subscriptions

import com.alchemistaloha.xflow.core.model.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Dart file test/features/subscriptions/subscription_list_test.dart holds
 * two widget tests. Their state-level content is the first two cases here;
 * the rest covers the search / sort / follow logic the screen relies on.
 */
class SubscriptionListStateTest {
    private fun sub(id: String, handle: String, name: String, followers: Int? = null) =
        Subscription(id = id, screenName = handle, name = name, followersCount = followers)

    @Test
    fun `renders empty state when no subscriptions`() {
        val state = SubscriptionListState(allSubscriptions = emptyList(), isLoading = false)
        assertTrue(state.filteredSubscriptions.isEmpty())
        assertTrue(state.searchQuery.isEmpty())
    }

    @Test
    fun `renders list of subscriptions`() {
        val mockSubs = listOf(
            Subscription(
                id = "1",
                screenName = "user1",
                name = "User One",
                profileImageUrl = "https://test.com/u1.jpg",
            ),
        )
        val state = SubscriptionListState(allSubscriptions = mockSubs, isLoading = false)
        val shown = state.filteredSubscriptions
        assertEquals(1, shown.size)
        assertEquals("User One", shown[0].name)
        assertEquals("@user1", subscriptionSubtitle(shown[0], 0))
    }

    @Test
    fun `defaults match the Dart state`() {
        val state = SubscriptionListState()
        assertTrue(state.isLoading)
        assertTrue(state.isAscending)
        assertEquals(SubscriptionSort.Name, state.sort)
        assertEquals("", state.searchQuery)
    }

    @Test
    fun `search matches name or handle ignoring case`() {
        val state = SubscriptionListState(
            allSubscriptions = listOf(
                sub("1", "alpha", "Zed"),
                sub("2", "Beta", "Yan"),
                sub("3", "gamma", "BETAmax"),
            ),
            searchQuery = "beta",
        )
        assertEquals(listOf("3", "2"), state.filteredSubscriptions.map { it.id })
    }

    @Test
    fun `sorts by name handle followers and views in both directions`() {
        val subs = listOf(
            sub("1", "charlie", "bob", followers = 5),
            sub("2", "Alpha", "Carol", followers = null),
            sub("3", "bravo", "alice", followers = 100),
        )
        val views = mapOf("charlie" to 2, "alpha" to 9)
        val base = SubscriptionListState(allSubscriptions = subs, userViews = views)

        assertEquals(listOf("3", "1", "2"), base.filteredSubscriptions.map { it.id })
        assertEquals(
            listOf("2", "1", "3"),
            base.copyWith(isAscending = false).filteredSubscriptions.map { it.id },
        )
        assertEquals(
            listOf("2", "3", "1"),
            base.copyWith(sort = SubscriptionSort.Handle).filteredSubscriptions.map { it.id },
        )
        assertEquals(
            listOf("2", "1", "3"),
            base.copyWith(sort = SubscriptionSort.Followers).filteredSubscriptions.map { it.id },
        )
        assertEquals(
            listOf("3", "1", "2"),
            base.copyWith(sort = SubscriptionSort.Views).filteredSubscriptions.map { it.id },
        )
        assertEquals(
            listOf("2", "1", "3"),
            base.copyWith(sort = SubscriptionSort.Views, isAscending = false)
                .filteredSubscriptions.map { it.id },
        )
    }

    @Test
    fun `isSubscribed ignores case and a leading at sign`() {
        val state = SubscriptionListState(allSubscriptions = listOf(sub("1", "User1", "User One")))
        assertTrue(state.isSubscribed("user1"))
        assertTrue(state.isSubscribed("@USER1"))
        assertFalse(state.isSubscribed("user2"))
    }

    @Test
    fun `withToggled adds a new subscription and removes an existing one`() {
        val existing = sub("1", "User1", "User One")
        val state = SubscriptionListState(allSubscriptions = listOf(existing))
        assertTrue(state.withToggled(sub("9", "user1", "Other")).isEmpty())
        assertEquals(
            listOf("1", "2"),
            state.withToggled(sub("2", "user2", "User Two")).map { it.id },
        )
    }

    @Test
    fun `formats counts and subtitle like the Dart screen`() {
        assertEquals("999", formatSubscriptionCount(999))
        assertEquals("1.0K", formatSubscriptionCount(1000))
        assertEquals("1.2K", formatSubscriptionCount(1234))
        assertEquals("2.5M", formatSubscriptionCount(2500000))
        assertEquals(
            "@user1 • 1.2K followers • 3 views",
            subscriptionSubtitle(sub("1", "user1", "User One", followers = 1234), 3),
        )
        assertEquals("@user1 • 0 followers", subscriptionSubtitle(sub("1", "user1", "U", followers = 0), 0))
    }
}
