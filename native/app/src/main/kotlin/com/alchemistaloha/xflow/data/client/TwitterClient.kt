package com.alchemistaloha.xflow.data.client

import com.alchemistaloha.xflow.core.client.TwitterFeatures
import com.alchemistaloha.xflow.core.client.TwitterParser
import com.alchemistaloha.xflow.core.client.XUri
import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.TweetResponse
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.db.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Thrown where Dart's `Future.timeout` threw its TimeoutException. */
class RequestTimeoutException(val seconds: Int) : Exception() {
    override fun toString(): String = "TimeoutException after 0:00:${"%02d".format(seconds)}.000000: Future not completed"
}

/**
 * The calls the app makes to X. Requests go out one at a time (see
 * [waitForTurn]); a 429 pauses all of them for the cooldown. Building the
 * requests and reading the responses is done by [TwitterParser].
 */
class TwitterClient {

    companion object {
        const val graphqlSearchTimelineUriPath = TwitterFeatures.graphqlSearchTimelineUriPath
        const val graphqlUserByScreenNameUriPath = TwitterFeatures.graphqlUserByScreenNameUriPath
        const val graphqlUserTweetsUriPath = TwitterFeatures.graphqlUserTweetsUriPath
        const val graphqlFavoriteTweetUriPath = TwitterFeatures.graphqlFavoriteTweetUriPath
        const val graphqlUnfavoriteTweetUriPath = TwitterFeatures.graphqlUnfavoriteTweetUriPath
        const val graphqlCreateRetweetUriPath = TwitterFeatures.graphqlCreateRetweetUriPath
        const val graphqlDeleteRetweetUriPath = TwitterFeatures.graphqlDeleteRetweetUriPath
        const val graphqlCreateTweetUriPath = TwitterFeatures.graphqlCreateTweetUriPath
        const val graphqlTweetDetailUriPath = TwitterFeatures.graphqlTweetDetailUriPath
        const val graphqlUserByScreenNameNewUriPath = TwitterFeatures.graphqlUserByScreenNameNewUriPath
        private const val legacyGraphqlSearchTimelineUriPath = TwitterFeatures.legacyGraphqlSearchTimelineUriPath

        val defaultFeatures: JsonObject get() = TwitterFeatures.defaultFeatures
        val followingFeatures: JsonObject get() = TwitterFeatures.followingFeatures
        val searchTimelineFeatures: JsonObject get() = TwitterFeatures.searchTimelineFeatures
        val searchTimelineFieldToggles: JsonObject get() = TwitterFeatures.searchTimelineFieldToggles
        val newProfileFeatures: JsonObject get() = TwitterFeatures.newProfileFeatures
        val newProfileFieldToggles: JsonObject get() = TwitterFeatures.newProfileFieldToggles

        /**
         * The Flutter client gives its turn back only when a request returns.
         * When a request throws (a timeout, no network) in fetchFollowing,
         * fetchTrendingMedia, fetchUserTimeline, fetchVideoMixer,
         * fetchAlgorithmicTimeline or fetchChronologicalTimeline, the turn
         * stays taken and every later request waits until [resetQueue] runs
         * (the app coming back to the foreground). `false` keeps that
         * behaviour; `true` gives the turn back on such an error.
         */
        private const val RELEASE_TURN_ON_ERROR = false

        // Rate limiting prevention
        private val queueLock = Any()
        private var isRequestInProgress = false
        private val requestQueue = ArrayDeque<CompletableDeferred<Unit>>()

        @Volatile
        private var rateLimitResetTime: Long? = null

        @Volatile
        private var subscriptionChunkIndex = 0

        @Volatile
        private var lastSubscribedQuery: String? = null

        private suspend fun waitForTurn() {
            val resetTime = rateLimitResetTime
            if (resetTime != null) {
                val now = System.currentTimeMillis()
                if (now < resetTime) {
                    val waitTime = resetTime - now
                    AppLogger.log("Rate limit active. Waiting ${waitTime / 1000}s...")
                    delay(waitTime)
                    rateLimitResetTime = null
                }
            }

            val completer = synchronized(queueLock) {
                if (!isRequestInProgress) {
                    isRequestInProgress = true
                    null
                } else {
                    CompletableDeferred<Unit>().also { requestQueue.addLast(it) }
                }
            } ?: return

            try {
                completer.await()
            } catch (e: CancellationException) {
                // The caller was cancelled while waiting (Dart has no such
                // case). Leave the queue; if the turn was handed over in the
                // same moment, pass it on so nobody waits for a dead caller.
                val handedOver = synchronized(queueLock) { !requestQueue.remove(completer) }
                if (handedOver && completer.isCompleted && !completer.isCancelled) {
                    releaseTurn()
                }
                throw e
            }
        }

        private fun releaseTurn() {
            val next = synchronized(queueLock) {
                if (requestQueue.isNotEmpty()) {
                    requestQueue.removeFirst()
                } else {
                    isRequestInProgress = false
                    null
                }
            }
            next?.complete(Unit)
        }

        fun resetQueue() {
            AppLogger.log("XFLOW: Resetting request queue")
            val waiting = synchronized(queueLock) {
                isRequestInProgress = false
                rateLimitResetTime = null
                val all = requestQueue.toList()
                requestQueue.clear()
                all
            }
            for (next in waiting) {
                if (!next.isCompleted) {
                    next.completeExceptionally(Exception("Queue reset due to app lifecycle change"))
                }
            }
        }

        private fun handleRateLimit(minutes: Int) {
            // minutes comes from settings
            rateLimitResetTime = System.currentTimeMillis() + minutes * 60_000L
            AppLogger.log("429 Rate Limit Exceeded. Pausing requests for $minutes minutes.")
        }

        private fun decode(body: String): JsonElement? {
            val element = Json.parseToJsonElement(body)
            return if (element is JsonNull) null else element
        }

        /** Dart: `Map<String, dynamic>` expected; anything else was a TypeError the caller caught. */
        private fun JsonElement?.asMap(): JsonObject =
            this as? JsonObject ?: throw IllegalStateException("response is not a JSON object")

        private fun JsonElement?.child(key: String): JsonElement? {
            if (this == null || this is JsonNull) return null
            val value = asMap()[key]
            return if (value is JsonNull) null else value
        }
    }

    /**
     * Dart: `await _waitForTurn(); response = await fetch(...); _releaseTurn();`
     * A coroutine cancelled while it holds the turn gives it back.
     */
    private suspend fun fetchInTurn(
        uri: XUri,
        timeoutSeconds: Int? = null,
        cacheDurationMillis: Long? = null,
    ): HttpResult {
        waitForTurn()
        val response = try {
            if (timeoutSeconds != null) {
                withTimeoutOrNull(timeoutSeconds * 1000L) {
                    TwitterAccount.fetch(uri, cacheDurationMillis = cacheDurationMillis)
                } ?: throw RequestTimeoutException(timeoutSeconds)
            } else {
                TwitterAccount.fetch(uri, cacheDurationMillis = cacheDurationMillis)
            }
        } catch (e: CancellationException) {
            releaseTurn()
            throw e
        } catch (e: Exception) {
            if (RELEASE_TURN_ON_ERROR) releaseTurn()
            throw e
        }
        releaseTurn()
        return response
    }

    private fun logTimelineRequest(label: String, uri: XUri, context: Any? = null) {
        val contextText = if (context == null) "" else " context=${TwitterAccount.compactForLog(context)}"
        AppLogger.log("Timeline request [$label]: ${TwitterAccount.formatUriForLog(uri)}$contextText")
    }

    private fun logTimelineResult(label: String, response: TweetResponse, context: Any? = null) {
        val contextText = if (context == null) "" else " context=${TwitterAccount.compactForLog(context)}"
        AppLogger.log(
            "Timeline result [$label]: tweets=${response.tweets.size} cursorTop=${response.cursorTop ?: "null"} cursorBottom=${response.cursorBottom ?: "null"}$contextText",
        )
    }

    suspend fun fetchProfile(screenName: String): Subscription? {
        @Suppress("NAME_SHADOWING")
        val screenName = TwitterParser.normalizeScreenName(screenName)

        // 1. Attempt using the NEW query ID (IGgvgiOx4QZndDHuD3x9TQ)
        try {
            AppLogger.log(
                "Attempting to fetch profile using new query ID (IGgvgiOx4QZndDHuD3x9TQ) for @$screenName",
            )
            val newUri = TwitterParser.newProfileUri(screenName)

            val response = TwitterAccount.fetch(newUri)
            if (response.statusCode == 200) {
                val data = decode(response.body)
                val profile = TwitterParser.parseProfile(data, screenName)
                if (profile != null) {
                    AppLogger.log("Successfully fetched profile using NEW query for @$screenName")
                    return profile
                }
            }
            AppLogger.log(
                "New query returned status ${response.statusCode} or empty data. Attempting fallback...",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("New query failed for @$screenName: $e. Attempting fallback...")
        }

        // 2. Fallback to the OLD query ID (oUZZZ8Oddwxs8Cd3iW3UEA)
        try {
            AppLogger.log(
                "Attempting fallback fetch using old query ID (oUZZZ8Oddwxs8Cd3iW3UEA) for @$screenName",
            )
            val oldUri = TwitterParser.oldProfileUri(screenName)

            val response = TwitterAccount.fetch(oldUri)
            if (response.statusCode != 200) {
                AppLogger.log("Fallback query also failed with status ${response.statusCode}")
                return null
            }

            val data = decode(response.body)
            val profile = TwitterParser.parseProfile(data, screenName)
            if (profile == null) {
                AppLogger.log("Fallback query returned status 200 but no user result")
                return null
            }

            AppLogger.log("Successfully fetched profile using FALLBACK query for @$screenName")
            return profile
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Error in fallback profile fetch: $e")
            return null
        }
    }

    suspend fun fetchUserTweets(
        screenName: String,
        cursor: String? = null,
        sort: FeedSort? = null,
        filters: Set<MediaFilter>? = null,
        count: Int = 20,
        timeoutSeconds: Int = 15,
    ): TweetResponse {
        AppLogger.log(
            "Timeline request [fetchUserTweets]: screenName=$screenName cursor=${cursor ?: "null"} sort=${sort?.name ?: "latest"} count=$count filters=${filters?.joinToString(",") { it.name } ?: "none"}",
        )
        return fetchTrendingMedia(
            query = "from:$screenName",
            cursor = cursor,
            sort = sort,
            filters = filters,
            count = count,
            timeoutSeconds = timeoutSeconds,
        )
    }

    suspend fun fetchFollowing(
        userId: String,
        maxCount: Int = 2000,
        cooldownMinutes: Int = 15,
    ): List<Subscription> {
        val allSubs = mutableListOf<Subscription>()
        var currentCursor: String? = null

        try {
            while (allSubs.size < maxCount) {
                val uri = TwitterParser.followingUri(userId, currentCursor)

                logTimelineRequest(
                    "fetchFollowing",
                    uri,
                    context = linkedMapOf(
                        "userId" to userId,
                        "cursor" to currentCursor,
                        "foundSoFar" to allSubs.size,
                    ),
                )

                val response = fetchInTurn(uri, cacheDurationMillis = 60 * 60 * 1000L)

                if (response.statusCode == 429) {
                    handleRateLimit(cooldownMinutes)
                    break
                }
                if (response.statusCode != 200) {
                    AppLogger.log("fetchFollowing Error: ${response.statusCode} ${response.body}")
                    break
                }

                val data = decode(response.body)
                val page = TwitterParser.parseFollowingPage(data, allSubs) ?: break
                val newFound = page.newFound
                val nextCursor = page.nextCursor

                if (newFound == 0 || nextCursor == null || nextCursor == currentCursor) {
                    AppLogger.log(
                        "Timeline result [fetchFollowing]: stopping newFound=$newFound nextCursor=${nextCursor ?: "null"} currentCursor=${currentCursor ?: "null"} total=${allSubs.size}",
                    )
                    break
                }
                AppLogger.log(
                    "Timeline result [fetchFollowing]: batchAdded=$newFound nextCursor=$nextCursor total=${allSubs.size}",
                )
                currentCursor = nextCursor
            }
            return allSubs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Error fetching following: $e")
            return allSubs
        }
    }

    suspend fun fetchTrendingMedia(
        cursor: String? = null,
        query: String? = null,
        sort: FeedSort? = null,
        filters: Set<MediaFilter>? = null,
        count: Int = 20,
        cooldownMinutes: Int = 15,
        minFaves: Int? = null,
        timeoutSeconds: Int = 15,
    ): TweetResponse {
        val finalQuery = TwitterParser.buildSearchQuery(query, sort, filters, minFaves)
        val variables = TwitterParser.searchTimelineVariables(finalQuery, count, sort, cursor)

        try {
            val attemptPaths = listOf(
                graphqlSearchTimelineUriPath,
                legacyGraphqlSearchTimelineUriPath,
            )

            for (i in attemptPaths.indices) {
                val path = attemptPaths[i]
                val uri = TwitterParser.buildSearchTimelineUri(path, variables)
                val context = linkedMapOf(
                    "query" to finalQuery,
                    "cursor" to cursor,
                    "sort" to (sort?.name ?: "latest"),
                    "count" to count,
                    "filters" to filters?.map { it.name },
                    "minFaves" to minFaves,
                    "attempt" to i + 1,
                    "operationPath" to path,
                )

                logTimelineRequest("fetchTrendingMedia", uri, context = context)

                val response = fetchInTurn(uri, timeoutSeconds = timeoutSeconds)

                if (response.statusCode == 429) {
                    handleRateLimit(cooldownMinutes)
                    return TweetResponse(tweets = emptyList())
                }
                if (response.statusCode == 404 && i < attemptPaths.size - 1) {
                    AppLogger.log(
                        "SearchTimeline returned 404 for $path. Retrying with alternate operation id.",
                    )
                    continue
                }
                if (response.statusCode != 200) {
                    AppLogger.log("Error status: ${response.statusCode} body: ${response.body}")
                    return TweetResponse(tweets = emptyList())
                }

                val result = decode(response.body)
                val timeline = result.child("data").child("search_by_raw_query").child("search_timeline")
                    ?: return TweetResponse(tweets = emptyList())

                val tweetResponse = TwitterParser.parseTweets(timeline.asMap())
                logTimelineResult(
                    "fetchTrendingMedia",
                    tweetResponse,
                    context = linkedMapOf(
                        "query" to finalQuery,
                        "cursor" to cursor,
                        "sort" to (sort?.name ?: "latest"),
                        "attempt" to i + 1,
                        "operationPath" to path,
                    ),
                )

                return tweetResponse
            }

            return TweetResponse(tweets = emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Exception in fetchTrendingMedia: $e")
            return TweetResponse(tweets = emptyList())
        }
    }

    suspend fun fetchSubscribedMedia(
        cursor: String? = null,
        sort: FeedSort? = null,
        filters: Set<MediaFilter>? = null,
        subBatchSize: Int = 10,
        loadBatchSize: Int = 20,
        cooldownMinutes: Int = 15,
        strictSubscriptionsOnly: Boolean = true,
        includeNativeRetweets: Boolean = false,
        useChunkedSubscriptions: Boolean = true,
        minFaves: Int? = null,
        maxQueryLength: Int = 480,
        timeoutSeconds: Int = 15,
    ): TweetResponse {
        var subs = Repository.getSubscriptions()

        if (subs.isEmpty()) {
            val currentAccount = TwitterAccount.currentAccount
            if (currentAccount != null && currentAccount.restId.isNotEmpty()) {
                subs = fetchFollowing(currentAccount.restId, cooldownMinutes = cooldownMinutes)
                if (subs.isNotEmpty()) {
                    Repository.insertSubscriptions(subs)
                }
            }
        }

        if (subs.isEmpty()) {
            if (strictSubscriptionsOnly) {
                return TweetResponse(tweets = emptyList())
            }
            return fetchTrendingMedia(
                cursor = cursor,
                sort = sort,
                filters = filters,
                count = loadBatchSize,
                cooldownMinutes = cooldownMinutes,
                minFaves = minFaves,
            )
        }

        val query: String
        val lastQuery = lastSubscribedQuery
        if (cursor != null && lastQuery != null) {
            // Continue pagination on the same query chunk.
            query = lastQuery
            AppLogger.log("Fetching subscribed media (Pagination) using last query: $query")
        } else if (useChunkedSubscriptions) {
            val queries = TwitterParser.buildChunkedQueries(subs, includeNativeRetweets, maxQueryLength)
            if (queries.isEmpty()) {
                if (strictSubscriptionsOnly) return TweetResponse(tweets = emptyList())
                return fetchTrendingMedia(
                    cursor = cursor,
                    sort = sort,
                    filters = filters,
                    count = loadBatchSize,
                    cooldownMinutes = cooldownMinutes,
                    minFaves = minFaves,
                    timeoutSeconds = timeoutSeconds,
                )
            }
            val idx = subscriptionChunkIndex % queries.size
            query = queries[idx]
            AppLogger.log(
                "Fetching subscribed media (Chunked): Chunk ${idx + 1} of ${queries.size}. Total Subs: ${subs.size}",
            )
            subscriptionChunkIndex = (subscriptionChunkIndex + 1) % queries.size
            lastSubscribedQuery = query
        } else {
            val pickedSubs = subs.shuffled().take(subBatchSize)
            val users = TwitterParser.buildUsersClause(pickedSubs)
            query = TwitterParser.buildQueryFromUsersClause(users, includeNativeRetweets)
            AppLogger.log("Fetching subscribed media (Random Sample): ${pickedSubs.size} accounts selected")
            lastSubscribedQuery = query
        }

        val response = fetchTrendingMedia(
            cursor = cursor,
            query = query,
            sort = sort,
            filters = filters,
            count = loadBatchSize,
            cooldownMinutes = cooldownMinutes,
            minFaves = minFaves,
            timeoutSeconds = timeoutSeconds,
        )

        if (!strictSubscriptionsOnly && cursor == null && response.tweets.size < 5) {
            val trendingResponse = fetchTrendingMedia(
                sort = sort,
                filters = filters,
                count = loadBatchSize,
                cooldownMinutes = cooldownMinutes,
                timeoutSeconds = timeoutSeconds,
            )
            val combined = response.tweets.toMutableList()
            val seenIds = response.tweets.map { it.id }.toSet()
            for (t in trendingResponse.tweets) {
                if (!seenIds.contains(t.id)) combined.add(t)
            }
            return TweetResponse(
                tweets = combined,
                cursorTop = response.cursorTop,
                cursorBottom = response.cursorBottom,
            )
        }

        return response
    }

    suspend fun fetchUserTimeline(
        userId: String,
        cursor: String? = null,
        cooldownMinutes: Int = 15,
        count: Int = 20,
        timeoutSeconds: Int = 15,
    ): TweetResponse {
        AppLogger.log("Fetching user timeline for userId: $userId, cursor: $cursor")
        val uri = TwitterParser.userTweetsUri(userId, count, cursor)

        try {
            logTimelineRequest(
                "fetchUserTimeline",
                uri,
                context = linkedMapOf(
                    "userId" to userId,
                    "cursor" to cursor,
                    "count" to count,
                ),
            )
            val response = fetchInTurn(uri, timeoutSeconds = timeoutSeconds)

            if (response.statusCode == 429) {
                handleRateLimit(cooldownMinutes)
                return TweetResponse(tweets = emptyList())
            }
            if (response.statusCode != 200) {
                AppLogger.log("Error fetching user timeline: Status ${response.statusCode}")
                return TweetResponse(tweets = emptyList())
            }

            val data = decode(response.body)
            val timeline = data.asMap().child("data").child("user").child("result").child("timeline_v2").child("timeline")
            if (timeline == null) {
                AppLogger.log("User timeline result is null for userId: $userId")
                return TweetResponse(tweets = emptyList())
            }

            AppLogger.log("Successfully fetched user timeline for userId: $userId")
            val tweetResponse = TwitterParser.parseTweets(timeline.asMap())
            logTimelineResult(
                "fetchUserTimeline",
                tweetResponse,
                context = linkedMapOf(
                    "userId" to userId,
                    "cursor" to cursor,
                    "count" to count,
                ),
            )
            return tweetResponse
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Error fetching user timeline: $e")
            return TweetResponse(tweets = emptyList())
        }
    }

    suspend fun fetchUserTimelineByScreenName(
        screenName: String,
        cursor: String? = null,
        cooldownMinutes: Int = 15,
    ): TweetResponse {
        AppLogger.log(
            "Timeline request [fetchUserTimelineByScreenName]: screenName=$screenName cursor=${cursor ?: "null"} cooldownMinutes=$cooldownMinutes",
        )
        return fetchTrendingMedia(
            query = "from:$screenName",
            cursor = cursor,
            filters = emptySet(), // All content
            cooldownMinutes = cooldownMinutes,
        )
    }

    /**
     * The shape shared by the like, retweet and post calls: wait for the
     * turn, POST, and give the turn back whatever happened.
     */
    private suspend fun postInTurn(startLog: String, errorLog: String, path: String, body: String): Boolean {
        val uri = TwitterParser.mutationUri(path)
        var cancelledWhileWaiting = false
        var waiting = false
        try {
            AppLogger.log(startLog)
            waiting = true
            waitForTurn()
            waiting = false
            val response = TwitterAccount.fetch(uri, method = "POST", body = body)

            return response.statusCode == 200
        } catch (e: CancellationException) {
            // Cancelled in the queue: there is no turn to give back.
            cancelledWhileWaiting = waiting
            throw e
        } catch (e: Exception) {
            AppLogger.log("$errorLog: $e")
            return false
        } finally {
            if (!cancelledWhileWaiting) releaseTurn()
        }
    }

    suspend fun favoriteTweet(tweetId: String): Boolean = postInTurn(
        "Favoriting tweet: $tweetId", "Error favoriting tweet",
        graphqlFavoriteTweetUriPath, TwitterParser.favoriteTweetBody(tweetId),
    )

    suspend fun unfavoriteTweet(tweetId: String): Boolean = postInTurn(
        "Unfavoriting tweet: $tweetId", "Error unfavoriting tweet",
        graphqlUnfavoriteTweetUriPath, TwitterParser.unfavoriteTweetBody(tweetId),
    )

    suspend fun retweetTweet(tweetId: String): Boolean = postInTurn(
        "Retweeting tweet: $tweetId", "Error retweeting tweet",
        graphqlCreateRetweetUriPath, TwitterParser.retweetTweetBody(tweetId),
    )

    suspend fun unretweetTweet(tweetId: String): Boolean = postInTurn(
        "Unretweeting tweet: $tweetId", "Error unretweeting tweet",
        graphqlDeleteRetweetUriPath, TwitterParser.unretweetTweetBody(tweetId),
    )

    suspend fun postTweet(text: String, inReplyToTweetId: String? = null): Boolean = postInTurn(
        "Posting tweet: $text", "Error posting tweet",
        graphqlCreateTweetUriPath, TwitterParser.postTweetBody(text, inReplyToTweetId),
    )

    suspend fun fetchTweetDetail(focalTweetId: String, cursor: String? = null): TweetResponse {
        val uri = TwitterParser.tweetDetailUri(focalTweetId, cursor)

        var cancelledWhileWaiting = false
        var waiting = false
        try {
            logTimelineRequest(
                "fetchTweetDetail",
                uri,
                context = linkedMapOf(
                    "focalTweetId" to focalTweetId,
                    "cursor" to cursor,
                ),
            )
            waiting = true
            waitForTurn()
            waiting = false
            val response = TwitterAccount.fetch(uri)

            if (response.statusCode != 200) return TweetResponse(tweets = emptyList())
            val result = decode(response.body)

            // Agnostic parser can handle the nested instructions in TweetDetail
            val tweetResponse = TwitterParser.parseAgnosticTimeline(result.asMap())
            logTimelineResult(
                "fetchTweetDetail",
                tweetResponse,
                context = linkedMapOf(
                    "focalTweetId" to focalTweetId,
                    "cursor" to cursor,
                ),
            )
            return tweetResponse
        } catch (e: CancellationException) {
            cancelledWhileWaiting = waiting
            throw e
        } catch (e: Exception) {
            AppLogger.log("Error fetching tweet detail: $e")
            return TweetResponse(tweets = emptyList())
        } finally {
            if (!cancelledWhileWaiting) releaseTurn()
        }
    }

    /** The shape shared by the three home timelines (video mixer, algorithmic, chronological). */
    private suspend fun fetchHomeTimeline(
        label: String,
        errorLog: String,
        uri: XUri,
        cursor: String?,
        count: Int,
        filters: Set<MediaFilter>?,
    ): TweetResponse {
        try {
            logTimelineRequest(
                label,
                uri,
                context = linkedMapOf(
                    "cursor" to cursor,
                    "count" to count,
                    "filters" to filters?.map { it.name },
                ),
            )
            val response = fetchInTurn(uri)

            if (response.statusCode != 200) return TweetResponse(tweets = emptyList())
            val result = decode(response.body)
            var tweetResponse = TwitterParser.parseAgnosticTimeline(result.asMap())

            if (filters != null && filters.isNotEmpty()) {
                tweetResponse = tweetResponse.copy(tweets = TwitterParser.applyFilters(tweetResponse.tweets, filters))
            }

            logTimelineResult(
                label,
                tweetResponse,
                context = linkedMapOf(
                    "cursor" to cursor,
                    "count" to count,
                    "filters" to filters?.map { it.name },
                ),
            )
            return tweetResponse
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("$errorLog: $e")
            return TweetResponse(tweets = emptyList())
        }
    }

    suspend fun fetchVideoMixer(
        cursor: String? = null,
        count: Int = 20,
        filters: Set<MediaFilter>? = null,
    ): TweetResponse = fetchHomeTimeline(
        "fetchVideoMixer", "Error fetching VideoMixer",
        TwitterParser.videoMixerUri(count, cursor), cursor, count, filters,
    )

    suspend fun fetchAlgorithmicTimeline(
        cursor: String? = null,
        count: Int = 20,
        filters: Set<MediaFilter>? = null,
    ): TweetResponse = fetchHomeTimeline(
        "fetchAlgorithmicTimeline", "Error fetching algorithmic timeline",
        TwitterParser.algorithmicTimelineUri(count, cursor), cursor, count, filters,
    )

    suspend fun fetchChronologicalTimeline(
        cursor: String? = null,
        count: Int = 20,
        filters: Set<MediaFilter>? = null,
    ): TweetResponse = fetchHomeTimeline(
        "fetchChronologicalTimeline", "Error fetching chronological timeline",
        TwitterParser.chronologicalTimelineUri(count, cursor), cursor, count, filters,
    )

    /** Reads the post of one timeline item into [tweets] (public in the Flutter client too). */
    fun parseTweetResult(itemContent: JsonObject, entryId: String, tweets: MutableList<Tweet>) =
        TwitterParser.parseTweetResult(itemContent, entryId, tweets)
}
