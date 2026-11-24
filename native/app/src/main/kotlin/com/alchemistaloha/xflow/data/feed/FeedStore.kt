package com.alchemistaloha.xflow.data.feed

import com.alchemistaloha.xflow.core.client.FetchDiagnostics
import com.alchemistaloha.xflow.core.client.FetchOutcome
import com.alchemistaloha.xflow.core.discovery.FeedPipeline
import com.alchemistaloha.xflow.core.feed.InteractionToggler
import com.alchemistaloha.xflow.core.feed.TweetInteractions
import com.alchemistaloha.xflow.core.feed.copyWith
import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.FeedState
import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.TweetResponse
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.data.RebuildingStore
import com.alchemistaloha.xflow.data.client.TwitterClient
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.player.PlayerPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The one client every store shares (Dart `twitterClientProvider`). */
val twitterClient: TwitterClient by lazy { TwitterClient() }

/**
 * Like/retweet state for tweets shown outside the main feed (hashtag and
 * profile feeds). Those tweets are not in [FeedState.tweets], so their
 * optimistic updates are kept here, keyed by tweet id.
 */
object TweetOverridesStore {
    private val _state = MutableStateFlow<Map<String, Tweet>>(emptyMap())
    val state: StateFlow<Map<String, Tweet>> = _state.asStateFlow()

    fun put(tweet: Tweet) {
        _state.value = _state.value + (tweet.id to tweet)
    }
}

/**
 * Follows the freshest copy of [tweet]: the one in the main feed if it is
 * there, otherwise the locally updated copy, otherwise [tweet] itself.
 * (Dart `watchLiveTweet`.)
 */
fun liveTweetFlow(tweet: Tweet): Flow<Tweet> =
    combine(FeedStore.state, TweetOverridesStore.state) { feed, overrides ->
        TweetInteractions.resolveLiveTweet(feed.valueOrNull?.tweets, overrides, tweet)
    }.distinctUntilChanged()

/** Same lookup as [liveTweetFlow] without subscribing, for event handlers. (Dart `readLiveTweet`.) */
fun currentTweet(tweet: Tweet): Tweet = TweetInteractions.resolveLiveTweet(
    FeedStore.peek()?.tweets,
    TweetOverridesStore.state.value,
    tweet,
)

/** The main feed (Dart `feedNotifierProvider` / `FeedNotifier`). */
object FeedStore : RebuildingStore<FeedState>(watchesSettings = true) {
    private var viewIndex = 0

    // The main feed follows the settings even while another screen covers it.
    override val alwaysRebuild: Boolean get() = true

    override fun onReset() {
        viewIndex = 0
    }

    private val mutableLastOutcome = MutableStateFlow<FetchOutcome?>(null)

    /** How the feed's last request to X went, so an empty feed can say why it is empty. */
    val lastOutcome: StateFlow<FetchOutcome?> = mutableLastOutcome.asStateFlow()

    /**
     * Call after subscriptions were added (an import, a follow): an empty
     * feed is rebuilt at once, a feed that has items is left alone and gets
     * the new accounts with its next refresh.
     */
    fun onSubscriptionsChanged() {
        AppGraph.scope.launch {
            val current = peek()
            if (current == null || current.tweets.isEmpty()) {
                AppLogger.log("XFLOW: Subscriptions changed while the feed is empty. Rebuilding the feed.")
                invalidate()
            }
        }
    }

    /** Tries again when the feed is built, empty and idle (the app coming back to the foreground). */
    fun retryIfEmpty() {
        AppGraph.scope.launch {
            val current = peek() ?: return@launch
            if (current.tweets.isEmpty() && !current.isRefreshing) {
                AppLogger.log("XFLOW: Feed is empty; refreshing it again.")
                refresh()
            }
        }
    }

    /** The feed's current value without starting or subscribing to it. */
    internal fun peek(): FeedState? = mutableState.value.valueOrNull

    private val toggler = InteractionToggler(
        readFeed = { value },
        writeFeed = { mutableState.value = Async.Data(it) },
        readOverride = { TweetOverridesStore.state.value[it] },
        putOverride = { TweetOverridesStore.put(it) },
    )

    /**
     * The feed screen reports which item the user is on, so a background
     * refresh never reshuffles what is on screen.
     */
    fun reportViewIndex(index: Int) {
        if (index >= 0) viewIndex = index
    }

    /**
     * How many leading items a background refresh must leave untouched: the
     * item being watched plus the next one, and never fewer than two.
     */
    fun protectedHeadLength(viewIndex: Int, currentLength: Int, resetHead: Boolean): Int =
        FeedPipeline.protectedHeadLength(viewIndex, currentLength, resetHead)

    override suspend fun build(settings: SettingsState): FeedState {
        AppLogger.log("XFLOW: Building FeedNotifier. fetchStrategy: FeedSort.${settings.fetchStrategy}")

        try {
            // Stage 1: Immediate local candidate retrieval
            val localPool = Repository.getCachedMediaCandidates(
                settings.loadBatchSize * settings.dbCandidateMultiplier,
                avoidWatchedContent = settings.avoidWatchedContent,
                filters = settings.filters,
            )
            val localTagged = localPool.map { it.copy(source = "Cache") }
            AppLogger.log("XFLOW: Cold start: Retrieved ${localPool.size} local candidates")

            // EARLY MEDIA WARMUP (main thread: builds run on AppGraph.scope)
            if (localTagged.isNotEmpty()) {
                var i = 0
                while (i < localTagged.size && i < 3) {
                    val tweet = localTagged[i]
                    if (tweet.isVideo && tweet.mediaUrls.isNotEmpty()) {
                        PlayerPool.warmup(tweet.id, tweet.mediaUrls.first())
                    }
                    i++
                }
            }

            return FeedState(
                tweets = localTagged,
                cursorBottom = null,
                isRefreshing = localTagged.isEmpty(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Error in build(): $e\n${e.stackTraceToString()}")
            return FeedState(tweets = emptyList(), isRefreshing = false)
        }
    }

    override fun onBuilt(settings: SettingsState) {
        // TRIGGER BACKGROUND SYNC
        if (!settings.isInitialized) {
            // The saved settings could not be loaded. The Flutter app waited for
            // them here, which left the feed on its spinner for good; refresh
            // with the defaults instead.
            AppLogger.log("XFLOW: Settings are not loaded; refreshing the feed with default settings.")
        }
        buildScope.launch { refreshInBackground() }
    }

    suspend fun refresh() = inBuild {
        val currentState = value
        if (currentState == null || currentState.isRefreshing) return@inBuild

        mutableState.value = Async.Data(currentState.copyWith(isRefreshing = true))
        refreshInBackground(resetHead = true)
    }

    private suspend fun refreshInBackground(resetHead: Boolean = false) {
        val gen = generation
        val client = twitterClient
        val settings = SettingsStore.state.value

        AppLogger.log(
            "XFLOW: Background refresh started. source=${settings.fetchStrategy} filters=${settings.filters.joinToString(",") { it.name }} " +
                "minFaves=${settings.minFavesFilter} strictSubs=${settings.strictSubscriptionsOnly} avoidWatched=${settings.avoidWatchedContent}",
        )

        try {
            // 1. Fetch from API
            val freshResponse: TweetResponse = if (settings.fetchStrategy == FeedSort.videomixer) {
                client.fetchVideoMixer(
                    count = settings.timelineBatchSize,
                    filters = settings.filters,
                )
            } else if (settings.fetchStrategy == FeedSort.algorithmic) {
                client.fetchAlgorithmicTimeline(
                    count = settings.timelineBatchSize,
                    filters = settings.filters,
                )
            } else if (settings.fetchStrategy == FeedSort.chronological) {
                client.fetchChronologicalTimeline(
                    count = settings.timelineBatchSize,
                    filters = settings.filters,
                )
            } else {
                client.fetchSubscribedMedia(
                    sort = settings.fetchStrategy,
                    filters = settings.filters,
                    subBatchSize = settings.syncBatchSize,
                    loadBatchSize = settings.initialSyncCount,
                    cooldownMinutes = settings.cooldownDuration,
                    strictSubscriptionsOnly = settings.strictSubscriptionsOnly,
                    includeNativeRetweets = settings.includeNativeRetweets,
                    useChunkedSubscriptions = settings.useChunkedSubscriptions,
                    minFaves = settings.minFavesFilter,
                )
            }

            mutableLastOutcome.value = freshResponse.outcome
            val freshPool = freshResponse.tweets
            val freshTagged = freshPool.map { it.copy(source = "API") }

            AppLogger.log("XFLOW: Background refresh returned ${freshPool.size} fresh tweets")

            if (freshPool.isNotEmpty()) {
                Repository.insertCachedMedia(freshPool)
            }

            // 2. Fetch local pool (now including fresh items)
            val localPool = Repository.getCachedMediaCandidates(
                settings.loadBatchSize * settings.dbCandidateMultiplier,
                avoidWatchedContent = settings.avoidWatchedContent,
                filters = settings.filters,
            )
            val localTagged = localPool.map { it.copy(source = "Cache") }
            AppLogger.log(
                "XFLOW: DB after refresh: wrote=${freshPool.size} candidates=${localPool.size} totalCached=${Repository.getCachedMediaCount()}",
            )

            if (!isCurrent(gen)) return
            if (value != null) {
                val playedByUser = if (settings.unseenSubscriptionBoost) {
                    Repository.getPlayedCountsByUser()
                } else {
                    emptyMap()
                }
                if (!isCurrent(gen)) return
                val current = value ?: return

                // PROTECT THE ACTIVE VIDEO: everything up to the item the user is
                // watching, plus the next one, keeps its place. Protecting only the
                // first two swapped the video under the user when the refresh
                // finished after they had already scrolled further.
                val processed = FeedPipeline.runDiscoveryPipeline(
                    freshTagged,
                    localTagged,
                    settings,
                    playedByUser,
                    protectedIndex = protectedHeadLength(
                        viewIndex = viewIndex,
                        currentLength = current.tweets.size,
                        resetHead = resetHead,
                    ),
                    currentTweets = if (resetHead) emptyList() else current.tweets,
                )

                mutableState.value = Async.Data(
                    current.copyWith(
                        tweets = processed,
                        cursorBottom = freshResponse.cursorBottom,
                        isRefreshing = false,
                    )
                )
                AppLogger.log("XFLOW: Feed state updated from background refresh. Total: ${processed.size}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Background refresh error: $e\n${e.stackTraceToString().take(1500)}")
            mutableLastOutcome.value = FetchDiagnostics.forException("Feed refresh", e)
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
        if (currentState == null || currentState.isLoadingMore) return@inBuild

        val settings = SettingsStore.state.value

        mutableState.value = Async.Data(currentState.copyWith(isLoadingMore = true))

        try {
            val startTweets = value!!.tweets
            val seenIds = startTweets.map { it.id }.toSet()

            // Use dynamic deduplication window from settings
            val dedupeWindow = FeedPipeline.dedupeWindow(startTweets, settings.mediaDeduplicationWindow)

            val seenMedia = FeedPipeline.firstMediaUrls(dedupeWindow)

            val allNewTweets = ArrayList<Tweet>()
            var currentCursor: String? = value!!.cursorBottom
            val seenCursors = HashSet<String>()
            var apiRetries = 0
            var chunkRotations = 0
            val maxRetries = settings.apiRetryLimit * 2 // Increase limit for robustness

            while (allNewTweets.size < settings.minNewTweetsThreshold &&
                apiRetries < maxRetries &&
                chunkRotations < settings.chunkRotationLimit
            ) {
                // 1. Try to fetch from DB first (refresh pool)
                val dbCandidates = Repository.getCachedMediaCandidates(
                    settings.loadBatchSize * settings.dbCandidateMultiplier,
                    avoidWatchedContent = settings.avoidWatchedContent,
                    filters = settings.filters,
                )

                val localNew = FeedPipeline.filterUnseen(dbCandidates, seenIds, seenMedia)

                if (localNew.isNotEmpty()) {
                    allNewTweets.addAll(localNew)
                    if (allNewTweets.size >= settings.minNewTweetsThreshold) break
                }

                // 2. Hit the API
                val client = twitterClient
                if (currentCursor != null) seenCursors.add(currentCursor)

                try {
                    val response: TweetResponse = if (settings.fetchStrategy == FeedSort.videomixer) {
                        client.fetchVideoMixer(
                            cursor = currentCursor,
                            count = settings.timelineBatchSize,
                            filters = settings.filters,
                        )
                    } else if (settings.fetchStrategy == FeedSort.algorithmic) {
                        client.fetchAlgorithmicTimeline(
                            cursor = currentCursor,
                            count = settings.timelineBatchSize,
                            filters = settings.filters,
                        )
                    } else if (settings.fetchStrategy == FeedSort.chronological) {
                        client.fetchChronologicalTimeline(
                            cursor = currentCursor,
                            count = settings.timelineBatchSize,
                            filters = settings.filters,
                        )
                    } else {
                        client.fetchSubscribedMedia(
                            cursor = currentCursor,
                            sort = settings.fetchStrategy,
                            filters = settings.filters,
                            subBatchSize = settings.syncBatchSize,
                            loadBatchSize = settings.loadBatchSize,
                            cooldownMinutes = settings.cooldownDuration,
                            strictSubscriptionsOnly = settings.strictSubscriptionsOnly,
                            includeNativeRetweets = settings.includeNativeRetweets,
                            useChunkedSubscriptions = settings.useChunkedSubscriptions,
                            minFaves = settings.minFavesFilter,
                            maxQueryLength = settings.maxQueryLength,
                            timeoutSeconds = settings.apiTimeoutSeconds,
                        )
                    }

                    if (response.outcome != null) mutableLastOutcome.value = response.outcome
                    val freshUnique = FeedPipeline.filterUnseen(response.tweets, seenIds, seenMedia)

                    if (response.tweets.isNotEmpty()) {
                        Repository.insertCachedMedia(response.tweets)
                    }

                    allNewTweets.addAll(freshUnique)
                    apiRetries++

                    // Handle Pagination vs Rotation
                    val nextCursor = response.cursorBottom
                    if (nextCursor != null &&
                        nextCursor != currentCursor &&
                        !seenCursors.contains(nextCursor)
                    ) {
                        currentCursor = nextCursor
                    } else {
                        // Chunk exhausted or stuck cursor
                        AppLogger.log(
                            "XFLOW: Chunk exhausted or stuck cursor. Rotating to next subscription chunk."
                        )
                        currentCursor = null
                        chunkRotations++
                        delay(300)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.log("XFLOW: fetchMore API error: $e")
                    apiRetries++
                    delay(1000)
                }

                if (allNewTweets.size < settings.minNewTweetsThreshold) {
                    delay(500)
                }
                if (!isCurrent(gen)) return@inBuild
            }

            if (!isCurrent(gen)) return@inBuild
            val latest = value ?: return@inBuild

            if (allNewTweets.isEmpty()) {
                mutableState.value = Async.Data(latest.copyWith(isLoadingMore = false))
                return@inBuild
            }

            val (combined, added) = FeedPipeline.appendNewTweets(latest.tweets, allNewTweets, settings)

            mutableState.value = Async.Data(
                latest.copyWith(
                    tweets = combined,
                    cursorBottom = currentCursor,
                    isLoadingMore = false,
                )
            )
            AppLogger.log("XFLOW: fetchMore complete. Added $added tweets. Total: ${combined.size}")
        } catch (e: CancellationException) {
            if (isCurrent(gen)) value?.let { mutableState.value = Async.Data(it.copyWith(isLoadingMore = false)) }
            throw e
        } catch (e: Exception) {
            AppLogger.log("Error fetching more: $e\n${e.stackTraceToString()}")
            if (isCurrent(gen)) value?.let { mutableState.value = Async.Data(it.copyWith(isLoadingMore = false)) }
        }
    }

    /**
     * Toggles the like on [tweetId]. Pass [tweet] when the tweet may live
     * outside the main feed (hashtag or profile feed); without it such a
     * tweet cannot be found and nothing happens.
     */
    suspend fun toggleLike(tweetId: String, tweet: Tweet? = null) = inApp {
        val client = twitterClient
        toggler.toggle(
            tweetId,
            fallback = tweet,
            label = "like",
            apply = TweetInteractions::applyLikeToggle,
            send = { updated ->
                if (updated.isLiked) client.favoriteTweet(tweetId) else client.unfavoriteTweet(tweetId)
            },
        )
    }

    suspend fun toggleRetweet(tweetId: String, tweet: Tweet? = null) = inApp {
        val client = twitterClient
        toggler.toggle(
            tweetId,
            fallback = tweet,
            label = "retweet",
            apply = TweetInteractions::applyRetweetToggle,
            send = { updated ->
                if (updated.isRetweeted) client.retweetTweet(tweetId) else client.unretweetTweet(tweetId)
            },
        )
    }

    suspend fun postReply(tweetId: String, text: String): Boolean {
        val client = twitterClient
        val success = client.postTweet(text, inReplyToTweetId = tweetId)

        if (success) {
            AppLogger.log("XFLOW: Successfully posted reply to $tweetId")
            // Refresh the tweet detail to show new reply
            // The caller should handle UI refresh
        } else {
            AppLogger.log("XFLOW: Failed to post reply to $tweetId")
        }
        return success
    }
}
