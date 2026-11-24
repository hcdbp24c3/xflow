package com.alchemistaloha.xflow.core.profile

import com.alchemistaloha.xflow.core.model.FeedState
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.TweetResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the pure logic behind test/features/profile/profile_provider_test.dart
 * (the notifier itself reads the SQLite cache, which needs Android here).
 */
class UserMediaLogicTest {
    private class TestTwitterClient {
        val profileByScreenName = HashMap<String, Subscription?>()
        val timelineByUserId = HashMap<String, TweetResponse>()
        val timelineByScreenName = HashMap<String, TweetResponse>()
        val fetchUserTimelineByScreenNameCalls = ArrayList<String>()
        var profileThrows = false
        var timelineThrows = false

        suspend fun fetchProfile(screenName: String): Subscription? {
            if (profileThrows) throw IllegalStateException("profile down")
            return profileByScreenName[screenName]
        }

        suspend fun fetchUserTimeline(userId: String, cursor: String?, cooldownMinutes: Int): TweetResponse {
            if (timelineThrows) throw IllegalStateException("timeline down")
            return timelineByUserId[userId] ?: TweetResponse(tweets = emptyList())
        }

        suspend fun fetchUserTimelineByScreenName(screenName: String, cursor: String?, cooldownMinutes: Int): TweetResponse {
            fetchUserTimelineByScreenNameCalls.add(screenName)
            return timelineByScreenName[screenName] ?: TweetResponse(tweets = emptyList())
        }

        suspend fun fetchUserMedia(screenName: String, cursor: String? = null) = UserMediaLogic.fetchUserMedia(
            screenName, cursor, 15, ::fetchProfile, ::fetchUserTimeline, ::fetchUserTimelineByScreenName,
        )
    }

    private val testHandle = "testuser"

    // Dart: "loads from cache and then merges API data" (the merge step; the
    // cache read itself is Repository.getUserCachedMedia).
    @Test
    fun `loads from cache and then merges API data`() = runBlocking {
        val mockClient = TestTwitterClient()
        val localTweet = Tweet(
            id = "local_1",
            text = "Local Tweet",
            userHandle = "@$testHandle",
            mediaUrls = listOf("url1"),
            isVideo = true,
            createdAtMillis = 1672531200000L, // 2023-01-01
        )
        val apiTweet = Tweet(
            id = "api_1",
            text = "API Tweet",
            userHandle = "@$testHandle",
            mediaUrls = listOf("url2"),
            isVideo = true,
            createdAtMillis = 1672617600000L, // 2023-01-02
        )
        mockClient.profileByScreenName[testHandle] =
            Subscription(id = "user_rest_id", screenName = testHandle, name = "Test User")
        mockClient.timelineByUserId["user_rest_id"] =
            TweetResponse(tweets = listOf(apiTweet), cursorBottom = "new_cursor")

        // What build() returns from the cache.
        val cachedState = FeedState(tweets = listOf(localTweet.copy(source = "Cache")), isRefreshing = true)

        val response = mockClient.fetchUserMedia(UserMediaLogic.screenNameOf(testHandle))
        val finalState = UserMediaLogic.mergeFresh(cachedState, response)

        assertEquals(2, finalState.tweets.size)
        assertTrue(finalState.tweets.any { it.id == "api_1" })
        assertFalse(finalState.isRefreshing)
        // newest first, API items tagged, cursor taken from the response
        assertEquals(listOf("api_1", "local_1"), finalState.tweets.map { it.id })
        assertEquals(listOf("API", "Cache"), finalState.tweets.map { it.source })
        assertEquals("new_cursor", finalState.cursorBottom)
    }

    @Test
    fun `uses id-based user timeline when local-first cache is empty`() = runBlocking {
        val mockClient = TestTwitterClient()
        val apiTweet = Tweet(
            id = "api_from_timeline",
            text = "Timeline Tweet",
            userHandle = "@RealCaseUser",
            mediaUrls = listOf("url2"),
            isVideo = true,
            createdAtMillis = 1672617600000L,
        )
        mockClient.profileByScreenName["realcaseuser"] =
            Subscription(id = "real_user_id", screenName = "RealCaseUser", name = "Real Case User")
        mockClient.timelineByUserId["real_user_id"] =
            TweetResponse(tweets = listOf(apiTweet), cursorBottom = "timeline_cursor")

        val response = mockClient.fetchUserMedia("realcaseuser")
        val finalState = UserMediaLogic.mergeFresh(FeedState(tweets = emptyList(), isRefreshing = true), response)

        assertTrue(finalState.tweets.map { it.id }.contains("api_from_timeline"))
        assertTrue(mockClient.fetchUserTimelineByScreenNameCalls.isEmpty())
    }

    // ---- not in the Dart file ----

    @Test
    fun `falls back to the screen name search when the profile is unknown or a request fails`() = runBlocking {
        val byName = TweetResponse(tweets = listOf(Tweet(id = "s1", text = "", userHandle = "@x")))

        val unknown = TestTwitterClient().apply { timelineByScreenName["x"] = byName }
        assertEquals(byName, unknown.fetchUserMedia("x"))
        assertEquals(listOf("x"), unknown.fetchUserTimelineByScreenNameCalls)

        val profileDown = TestTwitterClient().apply { profileThrows = true; timelineByScreenName["x"] = byName }
        assertEquals(byName, profileDown.fetchUserMedia("x"))

        val timelineDown = TestTwitterClient().apply {
            profileByScreenName["x"] = Subscription(id = "1", screenName = "x", name = "X")
            timelineThrows = true
            timelineByScreenName["x"] = byName
        }
        assertEquals(byName, timelineDown.fetchUserMedia("x"))
        assertEquals(listOf("x"), timelineDown.fetchUserTimelineByScreenNameCalls)

        val emptyId = TestTwitterClient().apply {
            profileByScreenName["x"] = Subscription(id = "", screenName = "x", name = "X")
            timelineByScreenName["x"] = byName
        }
        assertEquals(byName, emptyId.fetchUserMedia("x"))
    }

    @Test
    fun `merge without new tweets only clears the refreshing flag`() {
        val a = Tweet(id = "a", text = "", userHandle = "@u", source = "Cache")
        val current = FeedState(tweets = listOf(a), cursorBottom = "old", isRefreshing = true)
        assertEquals(
            current.copy(isRefreshing = false),
            UserMediaLogic.mergeFresh(current, TweetResponse(tweets = emptyList(), cursorBottom = "x")),
        )
        assertEquals(
            current.copy(isRefreshing = false),
            UserMediaLogic.mergeFresh(current, TweetResponse(tweets = listOf(a.copy(source = null)), cursorBottom = "x")),
        )
    }

    @Test
    fun `merge keeps the old cursor when the response has none and sorts undated tweets last`() {
        val dated = Tweet(id = "d", text = "", userHandle = "@u", createdAtMillis = 10)
        val undated = Tweet(id = "n", text = "", userHandle = "@u")
        val newer = Tweet(id = "x", text = "", userHandle = "@u", createdAtMillis = 20)
        val current = FeedState(tweets = listOf(undated, dated), cursorBottom = "old", isRefreshing = true)
        val merged = UserMediaLogic.mergeFresh(current, TweetResponse(tweets = listOf(newer)))
        assertEquals(listOf("x", "d", "n"), merged.tweets.map { it.id })
        assertEquals("old", merged.cursorBottom)
    }

    @Test
    fun `appendMore adds unseen tweets in order`() {
        val a = Tweet(id = "a", text = "", userHandle = "@u")
        val b = Tweet(id = "b", text = "", userHandle = "@u")
        val current = FeedState(tweets = listOf(a), cursorBottom = "c1", isLoadingMore = true)
        val next = UserMediaLogic.appendMore(current, TweetResponse(tweets = listOf(a, b), cursorBottom = "c2"))
        assertEquals(listOf("a", "b"), next.tweets.map { it.id })
        assertEquals("c2", next.cursorBottom)
        assertFalse(next.isLoadingMore)
    }

    @Test
    fun `screen name loses one leading at sign`() {
        assertEquals("user", UserMediaLogic.screenNameOf("@user"))
        assertEquals("user", UserMediaLogic.screenNameOf("user"))
    }
}
