package com.alchemistaloha.xflow.data.client

import com.alchemistaloha.xflow.core.client.AccountTimelineFallback
import com.alchemistaloha.xflow.core.client.FetchDiagnostics
import com.alchemistaloha.xflow.core.client.FetchIssue
import com.alchemistaloha.xflow.core.client.FetchOutcome
import com.alchemistaloha.xflow.core.client.PageOperation
import com.alchemistaloha.xflow.core.client.PageOperations
import com.alchemistaloha.xflow.core.client.TwitterFeatures
import com.alchemistaloha.xflow.core.client.TwitterParser
import com.alchemistaloha.xflow.core.client.XUri
import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.TweetResponse
import com.alchemistaloha.xflow.core.translate.Translation
import com.alchemistaloha.xflow.core.translate.TranslationResult
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.db.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Thrown where Dart's `Future.timeout` threw its TimeoutException. */
// The message matters: R8 renames this class, so the name cannot say it is a timeout.
class RequestTimeoutException(val seconds: Int) : Exception("request timeout after ${seconds}s") {
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
        private const val RELEASE_TURN_ON_ERROR = true

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

        /** Logs [outcome] and wraps it in an empty response, so the caller can show why. */
        private fun failed(outcome: FetchOutcome): TweetResponse {
            AppLogger.log(outcome.toLogLine())
            return TweetResponse(tweets = emptyList(), outcome = outcome)
        }

        /** Marks a parsed response with its outcome and logs how much of the body became posts. */
        private fun parsed(operation: String, response: TweetResponse, body: String): TweetResponse {
            val outcome = FetchOutcome(
                operation,
                if (response.tweets.isEmpty()) FetchIssue.empty else FetchIssue.ok,
                httpStatus = 200,
                items = response.tweets.size,
                detail = "entries=${countEntries(body)} bytes=${body.length}",
            )
            AppLogger.log(outcome.toLogLine())
            return response.copy(outcome = outcome)
        }

        /** How many timeline entries the body holds, whatever the parser made of them. */
        private fun countEntries(body: String): Int {
            var count = 0
            var index = body.indexOf("\"entryId\"")
            while (index >= 0) {
                count++
                index = body.indexOf("\"entryId\"", index + 9)
            }
            return count
        }

        /** The first message of a GraphQL `errors` array, if the body has one. */
        private fun graphqlErrorText(result: JsonElement?): String? {
            val errors = (result as? JsonObject)?.get("errors") as? JsonArray ?: return null
            val first = errors.firstOrNull() as? JsonObject ?: return null
            val message = (first["message"] as? JsonPrimitive)?.content
            val code = (first["code"] as? JsonPrimitive)?.content
            return listOfNotNull(message, code?.let { "code=$it" }).joinToString(" ").ifEmpty { null }
        }

        // ---- SearchTimeline attempts -------------------------------------
        // X answers 404 when it does not accept the operation id or the
        // x-client-transaction-id of a search. The Flutter client only
        // switched the operation id; here the header is varied too, because
        // the two clients do not send the same header: the Flutter WebView
        // call returned the text of an unresolved promise ("{}") and that is
        // what it sent, while this client waits for the real id.

        private class SearchAttempt(val path: String, val mode: TransactionIdMode) {
            override fun toString(): String = "${path.substringAfter("/graphql/").substringBefore('/')}/${mode.name}"
        }

        private val searchAttempts = listOf(
            SearchAttempt(graphqlSearchTimelineUriPath, TransactionIdMode.generated),
            SearchAttempt(legacyGraphqlSearchTimelineUriPath, TransactionIdMode.generated),
            SearchAttempt(graphqlSearchTimelineUriPath, TransactionIdMode.placeholder),
            SearchAttempt(graphqlSearchTimelineUriPath, TransactionIdMode.none),
        )

        /** The attempt that last got an answer other than 404; tried first from then on. */
        @Volatile
        private var workingSearchAttempt: SearchAttempt? = null

        /** When every attempt was last refused; for a minute after that only the first two are tried. */
        @Volatile
        private var allSearchAttemptsFailedAt = 0L

        private const val SEARCH_RETRY_ALL_AFTER_MS = 60_000L

        private fun searchAttemptOrder(): List<SearchAttempt> {
            val preferred = workingSearchAttempt
            if (preferred != null) return listOf(preferred) + searchAttempts.filter { it !== preferred }
            if (System.currentTimeMillis() - allSearchAttemptsFailedAt < SEARCH_RETRY_ALL_AFTER_MS) {
                return searchAttempts.take(2)
            }
            return searchAttempts
        }

        // ---- when SearchTimeline stays refused -----------------------------
        // 1. The search operation the x.com search page itself uses, read
        //    from that page ([PageOperations]).
        // 2. The timelines of the accounts the query names
        //    ([AccountTimelineFallback]).

        /** The search operation found in the x.com search page, per "latest" / "top". */
        private val pageSearchOperations = HashMap<Boolean, Pair<PageOperation, String>>()

        /** Until when the search page is not read again after it gave no usable operation. */
        @Volatile
        private var pageSearchPausedUntil = 0L

        /** Until when searches naming accounts go straight to those accounts' timelines. */
        @Volatile
        private var accountRouteUntil = 0L

        private const val PAGE_SEARCH_PAUSE_MS = 30 * 60_000L
        private const val ACCOUNT_ROUTE_MS = 10 * 60_000L

        /** At most this many pages of accounts are read for one page of the feed. */
        private const val ACCOUNT_ROUTE_MAX_PAGES = 3
        private const val ACCOUNT_ROUTE_ENOUGH = 6

        /** A timeline read for the account route is reused this long, to stay well inside X's limit for UserTweets. */
        private const val ACCOUNT_TIMELINE_CACHE_MS = 10 * 60_000L

        /** What X's rate limit headers last said about UserTweets: requests left, and when the window ends. */
        @Volatile
        private var userTweetsRemaining: Int? = null

        @Volatile
        private var userTweetsResetAt = 0L

        private const val USER_TWEETS_RESERVE = 3

        private fun userTweetsExhausted(): Boolean {
            val remaining = userTweetsRemaining ?: return false
            return remaining <= USER_TWEETS_RESERVE && System.currentTimeMillis() < userTweetsResetAt
        }

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
        transactionIdMode: TransactionIdMode = TransactionIdMode.generated,
    ): HttpResult {
        waitForTurn()
        val response = try {
            if (timeoutSeconds != null) {
                withTimeoutOrNull(timeoutSeconds * 1000L) {
                    TwitterAccount.fetch(
                        uri,
                        cacheDurationMillis = cacheDurationMillis,
                        transactionIdMode = transactionIdMode,
                    )
                } ?: throw RequestTimeoutException(timeoutSeconds)
            } else {
                TwitterAccount.fetch(
                    uri,
                    cacheDurationMillis = cacheDurationMillis,
                    transactionIdMode = transactionIdMode,
                )
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
        AppLogger.log("Timeline request [$label]: ${uri.path}$contextText")
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
                    AppLogger.log(
                        "fetchFollowing Error: ${response.statusCode} ${FetchDiagnostics.shorten(response.body) ?: ""}",
                    )
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

        val operation = "SearchTimeline"
        val plan = AccountTimelineFallback.plan(finalQuery)
        try {
            if (plan != null &&
                (AccountTimelineFallback.isCursor(cursor) ||
                    (cursor == null && System.currentTimeMillis() < accountRouteUntil))
            ) {
                return fetchAccountTimelines(plan, cursor, sort, filters, minFaves, cooldownMinutes, timeoutSeconds)
            }
            if (AccountTimelineFallback.isCursor(cursor)) {
                return failed(FetchOutcome(operation, FetchIssue.empty, detail = "no account in the query for cursor $cursor"))
            }

            val attempts = searchAttemptOrder()
            var refused: FetchOutcome? = null

            for (i in attempts.indices) {
                val attempt = attempts[i]
                val path = attempt.path
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
                    "txIdMode" to attempt.mode.name,
                )

                logTimelineRequest("fetchTrendingMedia", uri, context = context)

                val response = fetchInTurn(uri, timeoutSeconds = timeoutSeconds, transactionIdMode = attempt.mode)

                if (response.statusCode == 429) {
                    handleRateLimit(cooldownMinutes)
                    return failed(FetchDiagnostics.forStatus(operation, 429, response.body))
                }
                if (response.statusCode == 404) {
                    refused = FetchDiagnostics.forStatus(operation, 404, response.body)
                    AppLogger.log(
                        "SearchTimeline returned 404 for attempt ${i + 1}/${attempts.size} ($attempt)." +
                            if (i < attempts.size - 1) " Retrying with the next operation id / transaction id mode." else "",
                    )
                    if (attempt === workingSearchAttempt) workingSearchAttempt = null
                    continue
                }
                if (response.statusCode != 200) {
                    return failed(FetchDiagnostics.forStatus(operation, response.statusCode, response.body))
                }

                if (workingSearchAttempt !== attempt) {
                    workingSearchAttempt = attempt
                    AppLogger.log("SearchTimeline accepted with $attempt; using it first from now on.")
                }

                val result = decode(response.body)
                val timeline = result.child("data").child("search_by_raw_query").child("search_timeline")
                    ?: return failed(
                        FetchOutcome(
                            operation, FetchIssue.badResponse, httpStatus = 200,
                            detail = FetchDiagnostics.shorten(
                                graphqlErrorText(result) ?: "no search_timeline in the response (bytes=${response.body.length})",
                            ),
                        ),
                    )

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

                return parsed(operation, tweetResponse, response.body)
            }

            allSearchAttemptsFailedAt = System.currentTimeMillis()

            val fromPage = searchThroughPageOperation(finalQuery, sort, cursor, count, cooldownMinutes, timeoutSeconds)
            if (fromPage != null) return fromPage

            if (plan != null && cursor == null) {
                accountRouteUntil = System.currentTimeMillis() + ACCOUNT_ROUTE_MS
                AppLogger.log(
                    "Search route: refused (${attempts.joinToString(", ")}); reading the timelines of the " +
                        "${plan.screenNames.size} accounts of the query instead, for ${ACCOUNT_ROUTE_MS / 60_000} minutes",
                )
                return fetchAccountTimelines(plan, null, sort, filters, minFaves, cooldownMinutes, timeoutSeconds)
            }
            return failed(
                (refused ?: FetchOutcome(operation, FetchIssue.http, httpStatus = 404)).let {
                    it.copy(detail = "refused with ${attempts.joinToString(", ")}" + (it.detail?.let { d -> " | $d" } ?: ""))
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Exception in fetchTrendingMedia: $e")
            return failed(FetchDiagnostics.forException(operation, e))
        }
    }

    /**
     * The search as the x.com search page runs it: the page's document names
     * the operation and its variables, and the request repeats them with
     * this query. Null when the page gives no such operation or X refuses
     * it too. (Could not be tried while writing: the search page needs a
     * login.)
     */
    private suspend fun searchThroughPageOperation(
        finalQuery: String,
        sort: FeedSort?,
        cursor: String?,
        count: Int,
        cooldownMinutes: Int,
        timeoutSeconds: Int,
    ): TweetResponse? {
        val latest = sort != FeedSort.trending
        var known = synchronized(pageSearchOperations) { pageSearchOperations[latest] }
        if (known == null) {
            if (System.currentTimeMillis() < pageSearchPausedUntil) return null
            val html = withTimeoutOrNull(timeoutSeconds * 1000L) {
                TwitterAccount.fetchSessionDocument(PageOperations.searchPageUri(finalQuery, latest))
            }
            val operations = if (html == null) emptyMap() else PageOperations.parse(html)
            val found = operations.values
                .mapNotNull { op -> PageOperations.variableHolding(op, finalQuery)?.let { op to it } }
                .sortedByDescending { it.first.name.contains("Search") }
                .firstOrNull()
            if (found == null) {
                pageSearchPausedUntil = System.currentTimeMillis() + PAGE_SEARCH_PAUSE_MS
                AppLogger.log(
                    "Search route: the x.com search page gave no search operation " +
                        "(page=${if (html == null) "not loaded" else "${html.length} bytes"}, operations=${operations.keys}); " +
                        "not read again for ${PAGE_SEARCH_PAUSE_MS / 60_000} minutes",
                )
                return null
            }
            AppLogger.log(
                "Search route: the x.com search page uses ${found.first.name} (${found.first.queryId}), " +
                    "query in '${found.second}', variables=${found.first.variables.keys}",
            )
            synchronized(pageSearchOperations) { pageSearchOperations[latest] = found }
            known = found
        }

        val (pageOperation, queryVariable) = known
        val overrides = LinkedHashMap<String, JsonElement>()
        overrides[queryVariable] = JsonPrimitive(finalQuery)
        if (pageOperation.variables["count"] != null) overrides["count"] = JsonPrimitive(count)
        if (cursor != null || pageOperation.variables.containsKey("cursor")) {
            overrides["cursor"] = cursor?.let { JsonPrimitive(it) } ?: JsonNull
        }
        val uri = pageOperation.uri(overrides)
        logTimelineRequest("searchThroughPageOperation", uri, context = linkedMapOf("query" to finalQuery, "cursor" to cursor))
        val response = fetchInTurn(uri, timeoutSeconds = timeoutSeconds)
        if (response.statusCode == 429) {
            handleRateLimit(cooldownMinutes)
            return failed(FetchDiagnostics.forStatus(pageOperation.name, 429, response.body))
        }
        if (response.statusCode != 200) {
            AppLogger.log("Search route: ${pageOperation.name} from the search page answered ${response.statusCode}; dropped")
            synchronized(pageSearchOperations) { pageSearchOperations.remove(latest) }
            pageSearchPausedUntil = System.currentTimeMillis() + PAGE_SEARCH_PAUSE_MS
            return null
        }
        val tweetResponse = TwitterParser.parseAgnosticTimeline(decode(response.body).asMap())
        AppLogger.log("Search route: ${pageOperation.name} from the search page, posts=${tweetResponse.tweets.size}")
        return parsed(pageOperation.name, tweetResponse, response.body)
    }

    /**
     * A page of the feed out of the timelines of the accounts in [plan],
     * for when the search is refused. See [AccountTimelineFallback] for how
     * this differs from the search.
     */
    private suspend fun fetchAccountTimelines(
        plan: AccountTimelineFallback.Plan,
        cursor: String?,
        sort: FeedSort?,
        filters: Set<MediaFilter>?,
        minFaves: Int?,
        cooldownMinutes: Int,
        timeoutSeconds: Int,
    ): TweetResponse {
        val operation = "UserTweets(accounts)"
        val known = Repository.getSubscriptions().associate { it.screenName.removePrefix("@").lowercase() to it.id }
        val wantedFaves = minFaves ?: plan.minFaves
        val timelines = ArrayList<List<Tweet>>()
        var offset = AccountTimelineFallback.offset(cursor)
        var next: String? = null
        var asked = 0
        var answered = 0
        var firstFailure: FetchOutcome? = null
        var merged = emptyList<Tweet>()

        var limited = false
        for (round in 0 until ACCOUNT_ROUTE_MAX_PAGES) {
            for (name in AccountTimelineFallback.page(plan.screenNames, offset)) {
                if (userTweetsExhausted()) {
                    limited = true
                    break
                }
                val userId = known[name.lowercase()]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?: fetchProfile(name)?.id?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                asked++
                if (userId == null) {
                    if (firstFailure == null) firstFailure = FetchOutcome(operation, FetchIssue.badResponse, detail = "no user id for an account")
                    continue
                }
                val timeline = fetchUserTimeline(
                    userId,
                    cooldownMinutes = cooldownMinutes,
                    timeoutSeconds = timeoutSeconds,
                    skipReplies = plan.skipReplies,
                    skipRetweets = plan.skipRetweets,
                    allInstructions = true,
                    cacheDurationMillis = ACCOUNT_TIMELINE_CACHE_MS,
                )
                val outcome = timeline.outcome
                if (outcome != null && outcome.issue != FetchIssue.ok && outcome.issue != FetchIssue.empty) {
                    if (firstFailure == null) firstFailure = outcome
                    if (outcome.httpStatus == 429) break
                    continue
                }
                answered++
                timelines.add(timeline.tweets)
            }
            next = AccountTimelineFallback.nextCursor(plan.screenNames, offset)
            merged = AccountTimelineFallback.merge(timelines, sort, filters, wantedFaves)
            if (limited) {
                // Come back to the same accounts once X's window has moved on.
                next = if (offset > 0) "accounts:$offset" else null
                break
            }
            if (merged.size >= ACCOUNT_ROUTE_ENOUGH || next == null || firstFailure?.httpStatus == 429) break
            offset = AccountTimelineFallback.offset(next)
        }

        AppLogger.log(
            "Search route: account timelines, accounts=$answered/$asked of ${plan.screenNames.size} " +
                "posts=${timelines.sumOf { it.size }} kept=${merged.size} minFaves=${wantedFaves ?: "none"} " +
                "skipReplies=${plan.skipReplies} skipReposts=${plan.skipRetweets} next=${next ?: "end"}" +
                if (limited) " (stopped: X's rate limit for UserTweets is nearly used up)" else "",
        )
        if (limited && merged.isEmpty()) {
            return failed(
                FetchOutcome(
                    operation, FetchIssue.rateLimited,
                    detail = "search refused; X's limit for reading account timelines is used up until " +
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(userTweetsResetAt)),
                ),
            )
        }
        if (answered == 0 && firstFailure != null) {
            return failed(firstFailure.copy(detail = "search refused, account timelines too" + (firstFailure.detail?.let { " | $it" } ?: "")))
        }
        val outcome = FetchOutcome(
            operation,
            if (merged.isEmpty()) FetchIssue.empty else FetchIssue.ok,
            httpStatus = 200,
            items = merged.size,
            detail = "search refused; read $answered/$asked account timelines, ${timelines.sumOf { it.size }} posts before filters",
        )
        AppLogger.log(outcome.toLogLine())
        return TweetResponse(tweets = merged, cursorBottom = next, outcome = outcome)
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
                return failed(
                    if (TwitterAccount.currentAccount == null) {
                        FetchOutcome("Subscriptions", FetchIssue.notLoggedIn)
                    } else {
                        FetchOutcome("Subscriptions", FetchIssue.noSubscriptions)
                    },
                )
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
                if (strictSubscriptionsOnly) {
                    return failed(
                        FetchOutcome(
                            "Subscriptions", FetchIssue.noSubscriptions,
                            detail = "no usable screen name in ${subs.size} subscriptions",
                        ),
                    )
                }
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
                outcome = if (combined.isEmpty()) trendingResponse.outcome ?: response.outcome else response.outcome,
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
        skipReplies: Boolean = false,
        skipRetweets: Boolean = false,
        allInstructions: Boolean = false,
        cacheDurationMillis: Long? = null,
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
            val response = fetchInTurn(uri, timeoutSeconds = timeoutSeconds, cacheDurationMillis = cacheDurationMillis)
            response.headers["x-rate-limit-remaining"]?.toIntOrNull()?.let { remaining ->
                userTweetsRemaining = remaining
                userTweetsResetAt = (response.headers["x-rate-limit-reset"]?.toLongOrNull() ?: 0L) * 1000L
                if (remaining <= USER_TWEETS_RESERVE) AppLogger.log("UserTweets: $remaining requests left in X's rate limit window")
            }

            if (response.statusCode == 429) {
                handleRateLimit(cooldownMinutes)
                return failed(FetchDiagnostics.forStatus("UserTweets", 429, response.body))
            }
            if (response.statusCode != 200) {
                AppLogger.log("Error fetching user timeline: Status ${response.statusCode}")
                return failed(FetchDiagnostics.forStatus("UserTweets", response.statusCode, response.body))
            }

            val data = decode(response.body)
            val timeline = data.asMap().child("data").child("user").child("result").child("timeline_v2").child("timeline")
            if (timeline == null) {
                AppLogger.log("User timeline result is null for userId: $userId")
                return failed(
                    FetchOutcome(
                        "UserTweets", FetchIssue.badResponse, httpStatus = 200,
                        detail = FetchDiagnostics.shorten(graphqlErrorText(data) ?: "no timeline in the response"),
                    ),
                )
            }

            AppLogger.log("Successfully fetched user timeline for userId: $userId")
            val tweetResponse = TwitterParser.parseTweets(timeline.asMap(), skipReplies, skipRetweets, allInstructions)
            logTimelineResult(
                "fetchUserTimeline",
                tweetResponse,
                context = linkedMapOf(
                    "userId" to userId,
                    "cursor" to cursor,
                    "count" to count,
                ),
            )
            return parsed("UserTweets", tweetResponse, response.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Error fetching user timeline: $e")
            return failed(FetchDiagnostics.forException("UserTweets", e))
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
            if (response.statusCode != 200) {
                AppLogger.log(
                    "$errorLog: HTTP ${response.statusCode} ${FetchDiagnostics.shorten(response.body) ?: ""}",
                )
            }

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
        "Posting tweet (${text.length} chars)", "Error posting tweet",
        graphqlCreateTweetUriPath, TwitterParser.postTweetBody(text, inReplyToTweetId),
    )

    /**
     * X's "Translate post" for one post, into [targetLang]. Tries the endpoint the web client
     * uses now on both of its hosts, then the one it used before. Every attempt is logged, so
     * a log shows which one answered.
     */
    suspend fun translateTweet(tweetId: String, targetLang: String): TranslationResult {
        var cancelledWhileWaiting = false
        var waiting = false
        try {
            AppLogger.log("Translate: post $tweetId into $targetLang")
            waiting = true
            waitForTurn()
            waiting = false

            var last: TranslationResult = TranslationResult.Failure("no answer")
            val body = Translation.grokBody(tweetId, targetLang)
            for (uri in Translation.grokUris()) {
                val response = TwitterAccount.fetch(uri, method = "POST", body = body)
                if (response.statusCode == 200) {
                    val result = Translation.parseGrok(response.body)
                    AppLogger.log(
                        "Translate: grok ${uri.host} 200 -> " + when (result) {
                            is TranslationResult.Success -> "ok chars=${result.text.length}"
                            is TranslationResult.Failure ->
                                "unusable (${result.reason}) body=${FetchDiagnostics.shorten(response.body, 300) ?: "(empty)"}"
                        },
                    )
                    if (result is TranslationResult.Success) return result
                    last = result
                } else {
                    AppLogger.log("Translate: grok ${uri.host} HTTP ${response.statusCode}")
                    last = TranslationResult.Failure("HTTP ${response.statusCode}")
                    if (response.statusCode == 429) return TranslationResult.Failure("rate limited, try later")
                }
            }

            val response = TwitterAccount.fetch(Translation.legacyUri(tweetId, targetLang))
            if (response.statusCode == 200) {
                val result = Translation.parseLegacy(response.body)
                AppLogger.log(
                    "Translate: legacy 200 -> " + when (result) {
                        is TranslationResult.Success -> "ok chars=${result.text.length} from=${result.sourceLang}"
                        is TranslationResult.Failure ->
                            "unusable (${result.reason}) body=${FetchDiagnostics.shorten(response.body, 300) ?: "(empty)"}"
                    },
                )
                if (result is TranslationResult.Success) return result
            } else {
                AppLogger.log("Translate: legacy HTTP ${response.statusCode}")
            }
            return last
        } catch (e: CancellationException) {
            cancelledWhileWaiting = waiting
            throw e
        } catch (e: Exception) {
            AppLogger.log("Translate: failed: $e")
            return TranslationResult.Failure(e.message ?: e::class.simpleName ?: "error")
        } finally {
            if (!cancelledWhileWaiting) releaseTurn()
        }
    }

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

            if (response.statusCode != 200) {
                return failed(FetchDiagnostics.forStatus("TweetDetail", response.statusCode, response.body))
            }
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
            return parsed("TweetDetail", tweetResponse, response.body)
        } catch (e: CancellationException) {
            cancelledWhileWaiting = waiting
            throw e
        } catch (e: Exception) {
            AppLogger.log("Error fetching tweet detail: $e")
            return failed(FetchDiagnostics.forException("TweetDetail", e))
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
        val operation = label.removePrefix("fetch")
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

            if (response.statusCode != 200) {
                return failed(FetchDiagnostics.forStatus(operation, response.statusCode, response.body))
            }
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
            return parsed(operation, tweetResponse, response.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("$errorLog: $e")
            return failed(FetchDiagnostics.forException(operation, e))
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
