package com.alchemistaloha.xflow.core.profile

import com.alchemistaloha.xflow.core.feed.copyWith
import com.alchemistaloha.xflow.core.model.FeedState
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.TweetResponse
import com.alchemistaloha.xflow.core.util.AppLogger
import kotlinx.coroutines.CancellationException

/** The pure parts of the profile media feed. */
object UserMediaLogic {
    /** The screen name without one leading '@'. */
    fun screenNameOf(arg: String): String = if (arg.startsWith("@")) arg.substring(1) else arg

    /**
     * Resolves the profile id and loads the id-based timeline; falls back to
     * the search by screen name when the id is unknown or that request fails.
     */
    suspend fun fetchUserMedia(
        screenName: String,
        cursor: String?,
        cooldownMinutes: Int,
        fetchProfile: suspend (screenName: String) -> Subscription?,
        fetchUserTimeline: suspend (userId: String, cursor: String?, cooldownMinutes: Int) -> TweetResponse,
        fetchUserTimelineByScreenName: suspend (screenName: String, cursor: String?, cooldownMinutes: Int) -> TweetResponse,
    ): TweetResponse {
        var profile: Subscription? = null
        try {
            profile = fetchProfile(screenName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Could not resolve @$screenName profile id: $e")
        }

        val userId = profile?.id
        if (userId != null && userId.isNotEmpty()) {
            try {
                return fetchUserTimeline(userId, cursor, cooldownMinutes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("XFLOW: User timeline fetch failed for $userId: $e")
            }
        }

        return fetchUserTimelineByScreenName(screenName, cursor, cooldownMinutes)
    }

    /**
     * Merges a fresh API page into what is shown (cache first), newest first,
     * without jumps: only tweets not already present are added.
     */
    fun mergeFresh(current: FeedState, response: TweetResponse): FeedState {
        if (response.tweets.isEmpty()) return current.copyWith(isRefreshing = false)

        val freshTweets = response.tweets.map { it.copy(source = "API") }
        val currentTweets = current.tweets
        val existingIds = currentTweets.map { it.id }.toSet()
        val uniqueFresh = freshTweets.filter { !existingIds.contains(it.id) }

        if (uniqueFresh.isEmpty()) return current.copyWith(isRefreshing = false)

        // Tweets without a date sort last (Dart used DateTime(0)).
        val merged = (currentTweets + uniqueFresh)
            .sortedByDescending { it.createdAtMillis ?: Long.MIN_VALUE }

        return FeedState(
            tweets = merged,
            cursorBottom = response.cursorBottom ?: current.cursorBottom,
            isRefreshing = false,
        )
    }

    /** `fetchMore`: new ids appended in order. */
    fun appendMore(currentState: FeedState, response: TweetResponse): FeedState {
        val seenIds = currentState.tweets.map { it.id }.toSet()
        val uniqueNewTweets: List<Tweet> = response.tweets.filter { !seenIds.contains(it.id) }
        return currentState.copyWith(
            tweets = currentState.tweets + uniqueNewTweets,
            cursorBottom = response.cursorBottom,
            isLoadingMore = false,
        )
    }
}
