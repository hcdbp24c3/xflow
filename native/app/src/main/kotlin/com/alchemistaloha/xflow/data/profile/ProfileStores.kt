package com.alchemistaloha.xflow.data.profile

import com.alchemistaloha.xflow.core.feed.copyWith
import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.FeedState
import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.TweetResponse
import com.alchemistaloha.xflow.core.profile.UserMediaLogic
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.RebuildingStore
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.feed.twitterClient
import com.alchemistaloha.xflow.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Loads a profile from the API (Dart `userProfileProvider`, uncached). */
suspend fun fetchUserProfile(screenName: String): Subscription? = twitterClient.fetchProfile(screenName)

/** The profile header of one user, loaded once and kept (Dart `userProfileProvider(screenName)`). */
class UserProfileStore(val screenName: String) : RebuildingStore<UserProfileStore.Profile>(watchesSettings = false) {
    /** Wrapper so "no such profile" (null) is still a value. */
    data class Profile(val subscription: Subscription?)

    override suspend fun build(settings: SettingsState): Profile = Profile(fetchUserProfile(screenName))
}

/** One [UserProfileStore] per screen name. */
object UserProfileStores {
    private val stores = HashMap<String, UserProfileStore>()

    @Synchronized
    fun get(screenName: String): UserProfileStore = stores.getOrPut(screenName) { UserProfileStore(screenName) }
}

/** The media feed of one user (Dart `userMediaNotifierProvider(screenName)` / `UserMediaNotifier`). */
class UserMediaStore(val arg: String) : RebuildingStore<FeedState>(watchesSettings = true) {
    private val screenName: String get() = UserMediaLogic.screenNameOf(arg)

    override suspend fun build(settings: SettingsState): FeedState {
        // 1. Try to load from cache immediately to show SOMETHING
        val cached = Repository.getUserCachedMedia(screenName, settings.loadBatchSize)

        return FeedState(
            tweets = cached.map { it.copy(source = "Cache") },
            isRefreshing = true, // Mark as refreshing while we fetch
        )
    }

    override fun onBuilt(settings: SettingsState) {
        // Trigger async fetch in the background
        buildScope.launch { fetchFreshData(screenName, settings) }
    }

    suspend fun refresh() = inBuild {
        val currentState = value
        if (currentState != null) {
            mutableState.value = Async.Data(currentState.copyWith(isRefreshing = true))
        }
        fetchFreshData(screenName, SettingsStore.state.value)
    }

    private suspend fun fetchFreshData(screenName: String, settings: SettingsState) {
        val gen = generation
        try {
            val response = fetchUserMedia(screenName, cursor = null, cooldownMinutes = settings.cooldownDuration)

            if (!isCurrent(gen)) return

            if (response.tweets.isNotEmpty()) {
                Repository.insertCachedMedia(response.tweets)
            }

            // Update state by MERGING to avoid jumps
            if (!isCurrent(gen)) return
            val current = value
            if (current != null) {
                mutableState.value = Async.Data(UserMediaLogic.mergeFresh(current, response))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Background user media fetch error: $e")
            if (!isCurrent(gen)) return
            val current = value
            if (current != null) {
                mutableState.value = Async.Data(current.copyWith(isRefreshing = false))
            }
        }
    }

    suspend fun fetchMore() = inBuild {
        val gen = generation
        val currentState = value
        val screenName = screenName

        if (currentState == null || currentState.isLoadingMore) {
            return@inBuild
        }

        val settings = SettingsStore.state.value
        mutableState.value = Async.Data(currentState.copyWith(isLoadingMore = true))

        try {
            val response = fetchUserMedia(
                screenName,
                cursor = currentState.cursorBottom,
                cooldownMinutes = settings.cooldownDuration,
            )

            val newTweets = response.tweets
            if (newTweets.isNotEmpty()) {
                Repository.insertCachedMedia(newTweets)
            }

            if (isCurrent(gen)) mutableState.value = Async.Data(UserMediaLogic.appendMore(currentState, response))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Error fetching more user media: $e")
            if (isCurrent(gen)) mutableState.value = Async.Data(currentState.copyWith(isLoadingMore = false))
        }
    }

    private suspend fun fetchUserMedia(screenName: String, cursor: String?, cooldownMinutes: Int): TweetResponse {
        val client = twitterClient
        return UserMediaLogic.fetchUserMedia(
            screenName,
            cursor,
            cooldownMinutes,
            fetchProfile = { name -> client.fetchProfile(name) },
            fetchUserTimeline = { userId, c, cooldown ->
                client.fetchUserTimeline(userId, cursor = c, cooldownMinutes = cooldown)
            },
            fetchUserTimelineByScreenName = { name, c, cooldown ->
                client.fetchUserTimelineByScreenName(name, cursor = c, cooldownMinutes = cooldown)
            },
        )
    }
}

/** One [UserMediaStore] per screen name, kept for the life of the process. */
object UserMediaStores {
    private val stores = HashMap<String, UserMediaStore>()

    @Synchronized
    fun get(screenName: String): UserMediaStore = stores.getOrPut(screenName) { UserMediaStore(screenName) }
}
