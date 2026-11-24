package com.alchemistaloha.xflow.core.client

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.TweetResponse
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.core.util.parseTwitterDateTime
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A value in a response had a type the Dart code could not have used (for
 * example a number where a string is read). Dart threw a TypeError there and
 * the surrounding `catch` logged it and skipped the item; this keeps that.
 */
class JsonShapeException(message: String) : RuntimeException(message)

/** One page of the Following list. */
data class FollowingPage(val newFound: Int, val nextCursor: String?)

/**
 * The parts of the Flutter `TwitterClient` that need neither Android nor the
 * network: building queries, variables and request addresses, and turning
 * GraphQL responses into [Tweet] and [Subscription] lists.
 */
object TwitterParser {

    // ---------------------------------------------------------------------
    // Reading JSON the way the Dart code read `dynamic` values
    // ---------------------------------------------------------------------

    private fun JsonElement?.orNull(): JsonElement? = if (this == null || this is JsonNull) null else this

    private fun typeName(element: JsonElement): String = when (element) {
        is JsonObject -> "Map"
        is JsonArray -> "List"
        is JsonPrimitive -> if (element.isString) "String" else "num/bool"
    }

    /** Dart `value[key]`: fails on null and on anything that is not a map. */
    private fun JsonElement?.at(key: String): JsonElement? {
        val element = orNull()
            ?: throw JsonShapeException("NoSuchMethodError: The method '[]' was called on null (key '$key')")
        if (element !is JsonObject) {
            throw JsonShapeException("type '${typeName(element)}' has no key '$key'")
        }
        return element[key].orNull()
    }

    /** Dart `value?[key]`. */
    private fun JsonElement?.opt(key: String): JsonElement? {
        val element = orNull() ?: return null
        return element.at(key)
    }

    /** A value assigned to a Dart `String?`. */
    private fun JsonElement?.string(): String? {
        val element = orNull() ?: return null
        if (element is JsonPrimitive && element.isString) return element.content
        throw JsonShapeException("type '${typeName(element)}' is not a subtype of type 'String?'")
    }

    private fun JsonElement?.requireString(what: String): String =
        string() ?: throw JsonShapeException("type 'Null' is not a subtype of type 'String' ($what)")

    /** A value assigned to a Dart `int?` (a JSON integer). */
    private fun JsonElement?.int(): Int? {
        val element = orNull() ?: return null
        if (element is JsonPrimitive && !element.isString) {
            val value = element.content.toLongOrNull()
            if (value != null) return value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
        throw JsonShapeException("type '${typeName(element)}' is not a subtype of type 'int?'")
    }

    /** A value assigned to a Dart `bool?`. */
    private fun JsonElement?.bool(): Boolean? {
        val element = orNull() ?: return null
        if (element is JsonPrimitive && !element.isString) {
            if (element.content == "true") return true
            if (element.content == "false") return false
        }
        throw JsonShapeException("type '${typeName(element)}' is not a subtype of type 'bool?'")
    }

    /** Dart `List.from(value ?? [])`. */
    private fun JsonElement?.list(): List<JsonElement?> {
        val element = orNull() ?: return emptyList()
        if (element is JsonArray) return element.map { it.orNull() }
        throw JsonShapeException("type '${typeName(element)}' is not a subtype of type 'Iterable<dynamic>'")
    }

    /** Dart `value == 'text'` on a dynamic value. */
    private fun JsonElement?.isText(text: String): Boolean {
        val element = orNull() ?: return false
        return element is JsonPrimitive && element.isString && element.content == text
    }

    /** Dart `int.tryParse`. */
    private fun dartIntTryParse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val negative = trimmed.startsWith("-")
        val unsigned = if (negative || trimmed.startsWith("+")) trimmed.substring(1) else trimmed
        val value = if (unsigned.startsWith("0x") || unsigned.startsWith("0X")) {
            unsigned.substring(2).toLongOrNull(16)
        } else {
            if (unsigned.isEmpty() || !unsigned.all { it in '0'..'9' }) null else unsigned.toLongOrNull()
        } ?: return null
        return if (negative) -value else value
    }

    // ---------------------------------------------------------------------
    // Queries, variables and request addresses
    // ---------------------------------------------------------------------

    /** The search text `fetchTrendingMedia` sends as `rawQuery`. */
    fun buildSearchQuery(
        query: String?,
        sort: FeedSort?,
        filters: Set<MediaFilter>?,
        minFaves: Int?,
    ): String {
        var finalQuery = query ?: ""

        // Always exclude replies from feed discovery/trending queries to avoid duplicates and noise
        if (!finalQuery.contains("-filter:replies")) {
            finalQuery = if (finalQuery.isEmpty()) "-filter:replies" else "$finalQuery -filter:replies"
        }

        if (filters != null && filters.isNotEmpty()) {
            val filterQueries = mutableListOf<String>()
            for (f in filters) {
                when (f) {
                    MediaFilter.video -> filterQueries.add("filter:videos")
                    MediaFilter.image -> filterQueries.add("filter:images")
                    MediaFilter.text -> filterQueries.add("-filter:images -filter:videos")
                }
            }
            val combinedFilter = "(${filterQueries.joinToString(" OR ")})"
            finalQuery = if (finalQuery.isEmpty()) combinedFilter else "$finalQuery $combinedFilter"
        } else if (query == null) {
            // Default "All" case: empty query means no extra filters
            finalQuery = ""
        }

        if (sort == FeedSort.popular) {
            val faves = minFaves ?: 100
            finalQuery += " min_faves:$faves"
        }
        return finalQuery
    }

    fun searchTimelineVariables(finalQuery: String, count: Int, sort: FeedSort?, cursor: String?): JsonObject =
        buildJsonObject {
            put("rawQuery", finalQuery)
            put("count", count.toString())
            put("product", if (sort == FeedSort.trending) "Top" else "Latest")
            put("querySource", "typed_query")
            put("withDownvotePerspective", false)
            put("withReactionsMetadata", false)
            put("withReactionsPerspective", false)
            if (cursor != null) put("cursor", cursor)
        }

    fun buildSearchTimelineUri(path: String, variables: JsonObject): XUri =
        XUri.https(
            "x.com", "/i/api$path",
            linkedMapOf(
                "variables" to variables.toString(),
                "features" to TwitterFeatures.searchTimelineFeatures.toString(),
                "fieldToggles" to TwitterFeatures.searchTimelineFieldToggles.toString(),
            ),
        )

    /** `@name` and `name` are the same account. */
    fun normalizeScreenName(screenName: String): String =
        if (screenName.startsWith("@")) screenName.substring(1) else screenName

    private fun profileVariables(screenName: String): JsonObject = buildJsonObject {
        put("screen_name", screenName)
        put("withHighlightedLabel", true)
        put("withSafetyModeUserFields", true)
        put("withSuperFollowsUserFields", true)
    }

    /** UserByScreenName with the new query id (IGgvgiOx4QZndDHuD3x9TQ). */
    fun newProfileUri(screenName: String): XUri =
        XUri.https(
            "x.com", "/i/api${TwitterFeatures.graphqlUserByScreenNameNewUriPath}",
            linkedMapOf(
                "variables" to profileVariables(screenName).toString(),
                "features" to TwitterFeatures.merge(
                    TwitterFeatures.defaultFeatures, TwitterFeatures.newProfileFeatures,
                ).toString(),
                "fieldToggles" to TwitterFeatures.newProfileFieldToggles.toString(),
            ),
        )

    /** UserByScreenName with the old query id (oUZZZ8Oddwxs8Cd3iW3UEA). */
    fun oldProfileUri(screenName: String): XUri =
        XUri.https(
            "x.com", "/i/api${TwitterFeatures.graphqlUserByScreenNameFallbackUriPath}",
            linkedMapOf(
                "variables" to profileVariables(screenName).toString(),
                "features" to TwitterFeatures.defaultFeatures.toString(),
            ),
        )

    fun followingUri(userId: String, cursor: String?): XUri {
        val variables = buildJsonObject {
            put("userId", userId)
            put("count", 100)
            put("includePromotedContent", false)
            put("withGrokTranslatedBio", false)
            if (cursor != null) put("cursor", cursor)
        }
        return XUri.https(
            "x.com", "/i/api${TwitterFeatures.graphqlFollowingUriPath}",
            linkedMapOf(
                "variables" to variables.toString(),
                "features" to TwitterFeatures.followingFeatures.toString(),
            ),
        )
    }

    fun userTweetsUri(userId: String, count: Int, cursor: String?): XUri {
        val variables = buildJsonObject {
            put("userId", userId)
            put("count", count)
            put("includePromotedContent", false)
            put("withQuickPromoteEligibilityTweetFields", true)
            put("withVoice", true)
            put("withV2Timeline", true)
            if (cursor != null) put("cursor", cursor)
        }
        return XUri.https(
            "x.com", "/i/api${TwitterFeatures.graphqlUserTweetsUriPath}",
            linkedMapOf(
                "variables" to variables.toString(),
                "features" to TwitterFeatures.defaultFeatures.toString(),
                "fieldToggles" to buildJsonObject { put("withArticlePlainText", false) }.toString(),
            ),
        )
    }

    fun tweetDetailUri(focalTweetId: String, cursor: String?): XUri {
        val variables = buildJsonObject {
            put("focalTweetId", focalTweetId)
            put("with_rux_injections", false)
            put("includePromotedContent", true)
            put("withCommunity", true)
            put("withQuickPromoteEligibilityTweetFields", true)
            put("withBirdwatchNotes", true)
            put("withVoice", true)
            put("withV2Timeline", true)
            if (cursor != null) put("cursor", cursor)
        }
        return XUri.https(
            "x.com", "/i/api${TwitterFeatures.graphqlTweetDetailUriPath}",
            linkedMapOf(
                "variables" to variables.toString(),
                "features" to TwitterFeatures.defaultFeatures.toString(),
            ),
        )
    }

    /** The address shared by MediaTabVideoMixer, HomeTimeline and HomeLatestTimeline. */
    fun homeTimelineUri(path: String, count: Int, cursor: String?): XUri {
        val variables = buildJsonObject {
            put("count", count)
            put("includePromotedContent", true)
            put("latestControlAvailable", true)
            put("requestContext", "launch")
            if (cursor != null) put("cursor", cursor)
        }
        return XUri.https(
            "x.com", "/i/api$path",
            linkedMapOf(
                "variables" to variables.toString(),
                "features" to TwitterFeatures.followingFeatures.toString(),
            ),
        )
    }

    fun videoMixerUri(count: Int, cursor: String?): XUri =
        homeTimelineUri(TwitterFeatures.graphqlMediaTabVideoMixerUriPath, count, cursor)

    fun algorithmicTimelineUri(count: Int, cursor: String?): XUri =
        homeTimelineUri(TwitterFeatures.graphqlHomeTimelineUriPath, count, cursor)

    fun chronologicalTimelineUri(count: Int, cursor: String?): XUri =
        homeTimelineUri(TwitterFeatures.graphqlHomeLatestTimelineUriPath, count, cursor)

    /** The address of a POST operation (like, retweet, post). */
    fun mutationUri(path: String): XUri = XUri.https("x.com", "/i/api$path")

    private fun mutationBody(variableName: String, tweetId: String, queryId: String): String =
        buildJsonObject {
            putJsonObject("variables") { put(variableName, tweetId) }
            put("queryId", queryId)
        }.toString()

    fun favoriteTweetBody(tweetId: String): String =
        mutationBody("tweet_id", tweetId, TwitterFeatures.favoriteTweetQueryId)

    fun unfavoriteTweetBody(tweetId: String): String =
        mutationBody("tweet_id", tweetId, TwitterFeatures.unfavoriteTweetQueryId)

    fun retweetTweetBody(tweetId: String): String =
        mutationBody("source_tweet_id", tweetId, TwitterFeatures.createRetweetQueryId)

    fun unretweetTweetBody(tweetId: String): String =
        mutationBody("source_tweet_id", tweetId, TwitterFeatures.deleteRetweetQueryId)

    fun postTweetBody(text: String, inReplyToTweetId: String? = null): String =
        buildJsonObject {
            putJsonObject("variables") {
                put("tweet_text", text)
                put("dark_request", false)
                putJsonObject("media") {
                    putJsonArray("media_entities") {}
                    put("possibly_sensitive", false)
                }
                if (inReplyToTweetId != null) {
                    putJsonObject("reply") {
                        put("in_reply_to_tweet_id", inReplyToTweetId)
                        put("exclude_reply_user_ids", buildJsonArray {})
                    }
                }
            }
            put("queryId", TwitterFeatures.createTweetQueryId)
            put("features", TwitterFeatures.createTweetFeatures)
        }.toString()

    // --- subscription queries (fetchSubscribedMedia) ---

    fun buildUsersClause(selectedSubs: Iterable<Subscription>): String =
        selectedSubs.joinToString(" OR ") { "from:${it.screenName}" }

    fun buildQueryFromUsersClause(usersClause: String, includeNativeRetweets: Boolean): String =
        if (includeNativeRetweets) {
            "include:nativeretweets ($usersClause) -filter:replies"
        } else {
            "($usersClause) -filter:replies -filter:retweets"
        }

    /** Splits the shuffled subscriptions into search queries no longer than [maxQueryLength]. */
    fun buildChunkedQueries(
        list: List<Subscription>,
        includeNativeRetweets: Boolean,
        maxQueryLength: Int,
        random: Random = Random.Default,
    ): List<String> {
        val shuffled = list.shuffled(random)
        val queries = mutableListOf<String>()

        var currentUsers = ""
        for (sub in shuffled) {
            val candidate = if (currentUsers.isEmpty()) {
                "from:${sub.screenName}"
            } else {
                "$currentUsers OR from:${sub.screenName}"
            }
            val candidateQuery = buildQueryFromUsersClause(candidate, includeNativeRetweets)

            // Keep query comfortably below API query size limits.
            if (candidateQuery.length > maxQueryLength && currentUsers.isNotEmpty()) {
                queries.add(buildQueryFromUsersClause(currentUsers, includeNativeRetweets))
                currentUsers = "from:${sub.screenName}"
            } else {
                currentUsers = candidate
            }
        }

        if (currentUsers.isNotEmpty()) {
            queries.add(buildQueryFromUsersClause(currentUsers, includeNativeRetweets))
        }
        return queries
    }

    // ---------------------------------------------------------------------
    // Responses
    // ---------------------------------------------------------------------

    /** The user of a UserByScreenName response, or null when it has no user result. */
    fun parseProfile(data: JsonElement?, screenName: String): Subscription? {
        val userRes = data.at("data").opt("user").opt("result") ?: return null
        val legacy = userRes.at("legacy")
        return Subscription(
            id = userRes.at("rest_id").requireString("rest_id"),
            screenName = legacy.opt("screen_name").string() ?: screenName,
            name = legacy.opt("name").string() ?: screenName,
            profileImageUrl = userRes.at("avatar").opt("image_url").string()
                ?: legacy.opt("profile_image_url_https").string(),
            description = legacy.opt("description").string(),
            followersCount = legacy.opt("followers_count").int(),
            followingCount = legacy.opt("friends_count").int(),
        )
    }

    /**
     * Reads one Following response, adding the accounts to [allSubs] as they
     * are found. Returns null when the response has no entries to read, which
     * ends the paging.
     */
    fun parseFollowingPage(data: JsonElement?, allSubs: MutableList<Subscription>): FollowingPage? {
        val instructions = data.at("data").opt("user").opt("result")
            .opt("timeline").opt("timeline").opt("instructions").list()

        if (instructions.isEmpty()) return null

        var addEntries: JsonObject? = null
        for (instruction in instructions) {
            if (instruction is JsonObject &&
                (instruction.at("type").isText("TimelineAddEntries") ||
                    instruction.at("__typename").isText("TimelineAddEntries"))
            ) {
                addEntries = instruction
                break
            }
        }
        if (addEntries == null) return null

        val entries = addEntries.at("entries").list()
        if (entries.isEmpty()) return null

        var nextCursor: String? = null
        var newFound = 0

        for (entry in entries) {
            val entryId = entry.at("entryId").string() ?: ""
            if (entryId.startsWith("cursor-bottom-") || entryId.startsWith("sq-cursor-bottom-")) {
                nextCursor = entry.at("content").opt("value").string()
                continue
            }

            val userResult = entry.at("content").opt("itemContent").opt("user_results").opt("result")
                ?: continue

            val legacy = (
                if (userResult.at("core").opt("screen_name") != null) userResult.at("core")
                else userResult.at("legacy")
                ) ?: continue

            allSubs.add(
                Subscription(
                    id = userResult.at("rest_id").requireString("rest_id"),
                    screenName = legacy.at("screen_name").requireString("screen_name"),
                    name = legacy.at("name").string() ?: "",
                    profileImageUrl = userResult.at("avatar").opt("image_url").string()
                        ?: legacy.at("profile_image_url_https").string(),
                ),
            )
            newFound++
        }
        return FollowingPage(newFound, nextCursor)
    }

    fun applyFilters(tweets: List<Tweet>, filters: Set<MediaFilter>): List<Tweet> {
        return tweets.filter { tweet ->
            var matches = false
            for (filter in filters) {
                when (filter) {
                    MediaFilter.video -> if (tweet.isVideo) matches = true
                    MediaFilter.image -> if (!tweet.isVideo && tweet.mediaUrls.isNotEmpty()) matches = true
                    MediaFilter.text -> if (tweet.mediaUrls.isEmpty()) matches = true
                }
                if (matches) break
            }
            matches
        }
    }

    /** Deep search for the 'instructions' array within the GraphQL response. */
    fun parseAgnosticTimeline(response: JsonObject): TweetResponse {
        fun findTimeline(obj: JsonElement?): JsonObject? {
            if (obj !is JsonObject) return null
            if (obj.containsKey("instructions")) return obj
            for (value in obj.values) {
                val result = findTimeline(value)
                if (result != null) return result
            }
            return null
        }

        val timeline = findTimeline(response["data"].orNull() ?: JsonObject(emptyMap()))
        if (timeline == null) {
            AppLogger.log(
                "Agnostic Parser: Could not find instructions in response keys: " +
                    response.keys.joinToString(", ", "(", ")"),
            )
            return TweetResponse(tweets = emptyList())
        }
        return parseTweets(timeline)
    }

    /**
     * [skipReplies] and [skipRetweets] leave out what a search query removes
     * with `-filter:replies` / `-filter:retweets`; a profile timeline has no
     * such switch, so it is done here.
     *
     * [allInstructions] reads the entries of every instruction. Without it
     * only the first instruction that has any is read, as the Flutter app
     * does; on a profile timeline with a pinned post that is the pinned post
     * alone, so the per-account route asks for all of them.
     */
    fun parseTweets(
        timeline: JsonObject,
        skipReplies: Boolean = false,
        skipRetweets: Boolean = false,
        allInstructions: Boolean = false,
    ): TweetResponse {
        val tweets = mutableListOf<Tweet>()
        val instructions = (timeline.at("instructions") ?: timeline.at("timeline").opt("instructions")).list()

        val entries = ArrayList<JsonElement?>()
        for (instruction in instructions) {
            if (instruction !is JsonObject) continue
            val list = instruction.at("entries")
            val single = instruction.at("entry")
            if (list == null && single == null) continue
            if (list != null) entries.addAll(list.list()) else entries.add(single)
            if (!allInstructions) break
        }

        if (entries.isEmpty() && instructions.none { it is JsonObject && (it.at("entries") != null || it.at("entry") != null) }) {
            val types = instructions.joinToString(", ", "(", ")") {
                TwitterLogFormat.dartToString(it.at("type") ?: it.at("__typename"))
            }
            AppLogger.log("No entries found in instructions. Available types: $types")
            return TweetResponse(tweets = emptyList())
        }

        var cursorTop: String? = null
        var cursorBottom: String? = null

        for (entry in entries) {
            val entryId = entry.at("entryId").string() ?: ""

            if (entryId.startsWith("cursor-top-") || entryId.startsWith("sq-cursor-top-")) {
                cursorTop = (
                    entry.at("content").opt("value")
                        ?: entry.at("content").opt("itemContent").opt("value")
                    ).string()
            } else if (entryId.startsWith("cursor-bottom-") || entryId.startsWith("sq-cursor-bottom-")) {
                cursorBottom = (
                    entry.at("content").opt("value")
                        ?: entry.at("content").opt("itemContent").opt("value")
                    ).string()
            }

            try {
                val content = entry.at("content") ?: continue

                if (content.at("entryType").isText("TimelineTimelineModule")) {
                    val items = content.at("items").list()
                    for (item in items) {
                        val itemEntry = item.at("item")
                        if (itemEntry != null) {
                            parseTweetResult(itemEntry.asMap(), entryId, tweets, skipReplies, skipRetweets)
                        }
                    }
                    continue
                }

                if (content.at("entryType").isText("TimelineTimelineItem")) {
                    parseTweetResult(content.asMap(), entryId, tweets, skipReplies, skipRetweets)
                }
            } catch (e: Exception) {
                AppLogger.log("Error parsing entry $entryId: $e")
            }
        }

        AppLogger.log(
            "Parsing complete. Processed ${entries.size} entries. Found ${tweets.size} tweets.",
        )

        return TweetResponse(
            tweets = tweets,
            cursorTop = cursorTop,
            cursorBottom = cursorBottom,
        )
    }

    private fun JsonElement.asMap(): JsonObject =
        this as? JsonObject
            ?: throw JsonShapeException("type '${typeName(this)}' is not a subtype of type 'Map<String, dynamic>'")

    /** Reads the post of one timeline item and appends it to [tweets]; a post that cannot be read is skipped. */
    fun parseTweetResult(
        itemContent: JsonObject,
        entryId: String,
        tweets: MutableList<Tweet>,
        skipReplies: Boolean = false,
        skipRetweets: Boolean = false,
    ) {
        try {
            var tweetResult: JsonElement? =
                itemContent.at("itemContent").opt("tweet_results").opt("result")
                    ?: itemContent.at("tweet_results").opt("result")
            if (tweetResult == null) return

            if (tweetResult.at("__typename").isText("TweetWithVisibilityResults")) {
                tweetResult = tweetResult.at("tweet_results").opt("result")
            }
            if (tweetResult == null) return

            if (tweetResult.at("rest_id") == null && tweetResult.at("tweet") != null) {
                tweetResult = tweetResult.at("tweet")
            }

            tweetResult = withLegacyShape(tweetResult ?: return)
            var legacy: JsonElement = tweetResult.at("legacy") ?: return

            if (skipReplies && legacy.at("in_reply_to_status_id_str") != null) return

            var tweetId: String =
                (tweetResult.at("rest_id") ?: tweetResult.at("tweet").opt("rest_id")).string() ?: entryId

            // The Dart code wrote the retweeted author into tweetResult['core'];
            // the JSON tree is read-only here, so the replacement is kept aside.
            var coreOverride: JsonElement? = null

            val retweetedStatusResult = tweetResult.at("retweeted_status_result")
                ?: legacy.at("retweeted_status_result")
                ?: legacy.at("repostedStatusResults")
            if (retweetedStatusResult != null && retweetedStatusResult.at("result") != null) {
                if (skipRetweets) return
                var retweetedResult = retweetedStatusResult.at("result")
                if (retweetedResult.at("rest_id") == null && retweetedResult.at("tweet") != null) {
                    retweetedResult = retweetedResult.at("tweet")
                }
                retweetedResult = retweetedResult?.let { withLegacyShape(it) }
                val retweetedLegacy = retweetedResult.at("legacy")
                if (retweetedLegacy != null) {
                    legacy = retweetedLegacy
                    // Use original tweet ID for retweets to allow deduplication
                    tweetId = retweetedResult.at("rest_id").string() ?: tweetId

                    val retweetedCore = retweetedResult.at("core") ?: retweetedResult.at("tweet").opt("core")
                    val retweetedUserResults = retweetedCore.opt("user_results").opt("result")
                    val retweetedScreenName = retweetedUserResults.opt("legacy").opt("screen_name")
                    if (retweetedScreenName != null) {
                        coreOverride = retweetedCore
                    }
                }
            }

            val core = coreOverride ?: tweetResult.at("core") ?: tweetResult.at("tweet").opt("core")
            val userResults = core.opt("user_results").opt("result")

            val screenName = userResults.opt("legacy").opt("screen_name")
                ?: userResults.opt("core").opt("screen_name")
            val screenNameText = if (screenName == null) "Unknown" else TwitterLogFormat.dartToString(screenName)

            val userAvatarUrl = (
                userResults.opt("legacy").opt("profile_image_url_https")
                    ?: userResults.opt("avatar").opt("image_url")
                ).string()

            val media = legacy.at("entities").opt("media").list()
            val extendedMedia = legacy.at("extended_entities").opt("media").list()
            val allMedia = (if (extendedMedia.isNotEmpty()) extendedMedia else media).toMutableList()

            if (allMedia.isEmpty()) {
                val noteTweetResult = tweetResult.at("note_tweet").opt("note_tweet_results").opt("result")
                if (noteTweetResult != null) {
                    val noteMedia = noteTweetResult.at("entity_set").opt("media").list()
                    val noteExtendedMedia = noteTweetResult.at("extended_entities").opt("media").list()
                    allMedia.addAll(if (noteExtendedMedia.isNotEmpty()) noteExtendedMedia else noteMedia)
                }
            }

            val mediaUrls = mutableListOf<String>()
            var thumbnailUrl: String? = null
            var mediaKey: String? = null
            var isVideo = false

            if (allMedia.isNotEmpty() && allMedia.first().at("media_url_https") != null) {
                thumbnailUrl = allMedia.first().at("media_url_https").string()
                mediaKey = allMedia.first().at("media_key").string()
            }

            for (m in allMedia) {
                if (m.at("type").isText("video") || m.at("type").isText("animated_gif")) {
                    isVideo = true
                    val variants = m.at("video_info").opt("variants").list()
                    if (variants.isEmpty()) continue

                    // Highest bitrate first; equal bitrates keep their order.
                    val bestVariant = variants
                        .filter { it.at("content_type").isText("video/mp4") && it.at("url") != null }
                        .sortedByDescending { bitrateOf(it) }

                    if (bestVariant.isNotEmpty()) {
                        mediaUrls.add(bestVariant.first().at("url").requireString("url"))
                    } else if (variants.first().at("url") != null) {
                        mediaUrls.add(variants.first().at("url").requireString("url"))
                    }
                } else if (m.at("type").isText("photo")) {
                    if (m.at("media_url_https") != null) {
                        mediaUrls.add(m.at("media_url_https").requireString("media_url_https"))
                    }
                }
            }

            var createdAt: Long? = null
            if (legacy.at("created_at") != null) {
                createdAt = parseTwitterDateTime(TwitterLogFormat.dartToString(legacy.at("created_at")))
            } else if (legacy.at("created_at_ms") != null) {
                try {
                    val ms = dartIntTryParse(TwitterLogFormat.dartToString(legacy.at("created_at_ms")))
                    if (ms != null) {
                        createdAt = ms
                    }
                } catch (e: Exception) {
                    AppLogger.log(
                        "XFLOW: Error parsing date_ms ${TwitterLogFormat.dartToString(legacy.at("created_at_ms"))}: $e",
                    )
                }
            }

            if (createdAt == null) {
                AppLogger.log(
                    "XFLOW: No date found in legacy: ${(legacy as JsonObject).keys.joinToString(", ", "[", "]")}",
                )
            }

            val viewsText = TwitterLogFormat.dartToString(
                tweetResult.at("views").opt("count") ?: legacy.at("views").opt("count") ?: JsonPrimitive("0"),
            )

            tweets.add(
                Tweet(
                    id = tweetId,
                    text = (legacy.at("full_text") ?: legacy.at("text")).string() ?: "",
                    userHandle = "@$screenNameText",
                    userAvatarUrl = userAvatarUrl,
                    mediaKey = mediaKey,
                    mediaUrls = mediaUrls,
                    thumbnailUrl = thumbnailUrl,
                    isVideo = isVideo,
                    createdAtMillis = createdAt,
                    isLiked = legacy.at("favorited").bool() ?: false,
                    favoriteCount = legacy.at("favorite_count").int() ?: 0,
                    replyCount = legacy.at("reply_count").int() ?: 0,
                    retweetCount = legacy.at("retweet_count").int() ?: 0,
                    isRetweeted = legacy.at("retweeted").bool() ?: false,
                    viewCount = (dartIntTryParse(viewsText) ?: 0L)
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
                ),
            )
        } catch (e: Exception) {
            AppLogger.log("Error in parseTweetResult for $entryId: $e")
        }
    }

    /**
     * Since November 2026 X also serves posts with the text and date under
     * `details`, the counters under `counts` and the media under
     * `media_entities2`, and `legacy` holds little more than the language.
     * Returns such a post with a `legacy` object in the older layout, which
     * is what the rest of this parser reads; any other post is returned as
     * it is.
     */
    fun withLegacyShape(result: JsonElement): JsonElement {
        if (result !is JsonObject) return result
        val details = result["details"] as? JsonObject ?: return result
        val oldLegacy = result["legacy"] as? JsonObject
        if (oldLegacy != null && (oldLegacy.containsKey("full_text") || oldLegacy.containsKey("extended_entities"))) {
            return result
        }

        val legacy = LinkedHashMap<String, JsonElement>()
        if (oldLegacy != null) legacy.putAll(oldLegacy)
        for (key in listOf("full_text", "created_at_ms", "display_text_range")) {
            details[key].orNull()?.let { legacy[key] = it }
        }
        (result["counts"] as? JsonObject)?.let { counts ->
            for ((key, value) in counts) if (value !is JsonNull) legacy[key] = value
        }
        val media = result["media_entities2"] as? JsonArray
        if (media != null && media.isNotEmpty()) {
            legacy["extended_entities"] = JsonObject(mapOf("media" to media))
        }
        // A reply names the post it answers; the exact layout of that object is not relied on.
        val replyTo = result["reply_to_results"].orNull() ?: details["reply_to_results"].orNull()
        if (replyTo != null && !legacy.containsKey("in_reply_to_status_id_str")) {
            legacy["in_reply_to_status_id_str"] =
                JsonPrimitive(replyTo.opt("rest_id").string() ?: replyTo.opt("result").opt("rest_id").string() ?: "")
        }
        (result["viewer_state"] as? JsonObject ?: result["viewer"] as? JsonObject)?.let { viewer ->
            for (key in listOf("favorited", "retweeted", "bookmarked")) {
                viewer[key].orNull()?.let { if (!legacy.containsKey(key)) legacy[key] = it }
            }
        }

        val out = LinkedHashMap<String, JsonElement>(result)
        out["legacy"] = JsonObject(legacy)
        return JsonObject(out)
    }

    /** Dart `v['bitrate'] ?? 0`, which had to be a number to be compared. */
    private fun bitrateOf(variant: JsonElement?): Double {
        val bitrate = variant.at("bitrate") ?: return 0.0
        if (bitrate is JsonPrimitive && !bitrate.isString) {
            bitrate.content.toDoubleOrNull()?.let { return it }
        }
        throw JsonShapeException("type '${typeName(bitrate)}' is not a subtype of type 'num'")
    }

    // ---------------------------------------------------------------------
    // Transaction id
    // ---------------------------------------------------------------------

    /** Turns what the WebView returned for a script into the id, or null. */
    fun normalizeJavaScriptResult(value: Any?): String? {
        if (value == null) return null
        val text = value.toString()
        if (text == "null" || text.isEmpty()) return null
        if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
            return text.substring(1, text.length - 1)
        }
        return text
    }
}
