package com.alchemistaloha.xflow.core.client

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.Subscription
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Reading the golden files written by the Flutter client (fixtures/diff/golden_dart). */
internal object DartGolden {
    fun resource(path: String): String {
        val stream = checkNotNull(DartGolden::class.java.classLoader.getResourceAsStream("fixtures/diff/$path")) {
            "missing resource fixtures/diff/$path"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun json(path: String): JsonObject = Json.parseToJsonElement(resource(path)) as JsonObject

    fun JsonElement?.orNull(): JsonElement? = if (this == null || this is JsonNull) null else this
    fun JsonObject.str(key: String): String? = (this[key].orNull() as JsonPrimitive?)?.content
    fun JsonObject.int(key: String): Int? = (this[key].orNull() as JsonPrimitive?)?.content?.toInt()
    fun JsonObject.long(key: String): Long? = (this[key].orNull() as JsonPrimitive?)?.content?.toLong()
    fun JsonObject.bool(key: String): Boolean? = (this[key].orNull() as JsonPrimitive?)?.content?.toBooleanStrict()
    fun JsonObject.arr(key: String): List<JsonElement>? = this[key].orNull() as JsonArray?
    fun JsonObject.strings(key: String): List<String>? = arr(key)?.map { (it as JsonPrimitive).content }
}

/**
 * Every request the Flutter client builds, against what the Kotlin port
 * builds for the same input: the whole address (path, order of the
 * parameters, the encoded JSON byte for byte) and the body of a POST.
 *
 * The expected side (golden_dart/uri_golden.json) was written by running the
 * real Dart client behind a fake HTTP client; see fixtures/diff/README.md.
 * The glue that lives in the Android module (which query a method passes on)
 * is repeated here from native/app/.../data/client/TwitterClient.kt.
 */
class DartGoldenUriTest {
    private data class Request(val method: String, val url: String, val body: String?)

    private val golden = DartGolden.json("golden_dart/uri_golden.json")
    private val blobs: Map<String, String> =
        (golden["blobs"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }

    private fun expected(case: JsonObject): List<Request> = with(DartGolden) {
        case.arr("requests")!!.map { element ->
            val request = element as JsonObject
            val params = request.arr("params")!!.map { pair ->
                val (name, value) = pair as JsonArray
                val text = if (value is JsonObject) blobs.getValue(value.str("blob")!!) else (value as JsonPrimitive).content
                "${(name as JsonPrimitive).content}=$text"
            }
            val base = request.str("base")!!
            Request(
                request.str("method")!!,
                if (params.isEmpty()) base else base + "?" + params.joinToString("&"),
                request.str("body"),
            )
        }
    }

    private fun get(uri: XUri) = Request("GET", uri.toString(), null)
    private fun post(path: String, body: String) = Request("POST", TwitterParser.mutationUri(path).toString(), body)

    /** fetchTrendingMedia with X answering 404 to the first operation id: both addresses go out. */
    private fun search(
        query: String?,
        sort: FeedSort?,
        filters: Set<MediaFilter>?,
        minFaves: Int?,
        cursor: String?,
        count: Int,
    ): List<Request> {
        val finalQuery = TwitterParser.buildSearchQuery(query, sort, filters, minFaves)
        val variables = TwitterParser.searchTimelineVariables(finalQuery, count, sort, cursor)
        return listOf(
            TwitterFeatures.graphqlSearchTimelineUriPath,
            TwitterFeatures.legacyGraphqlSearchTimelineUriPath,
        ).map { get(TwitterParser.buildSearchTimelineUri(it, variables)) }
    }

    private fun JsonObject.sort(): FeedSort? = with(DartGolden) { str("sort")?.let { FeedSort.valueOf(it) } }
    private fun JsonObject.filters(): Set<MediaFilter>? =
        with(DartGolden) { strings("filters")?.mapTo(LinkedHashSet()) { MediaFilter.valueOf(it) } }

    /** A shuffle that leaves the list as it is, so the order under test is the one given. */
    private object KeepOrder : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextInt(until: Int): Int = until - 1
    }

    private fun subscribed(case: JsonObject, problems: MutableList<String>): List<Request> = with(DartGolden) {
        val names = case.strings("names")!!
        val rawQuery = case.str("rawQuery")!!
        val includeNativeRetweets = case.bool("includeNativeRetweets")!!
        val picked = Regex("from:([^ )]+)").findAll(rawQuery).map { it.groupValues[1] }.toList()
        fun subs(list: List<String>) = list.map { Subscription(id = it, screenName = it, name = it) }
        val label = "subscribed '${case.str("label")}' rt=$includeNativeRetweets max=${case.int("maxQueryLength")}"

        val query: String
        if (case.bool("chunked")!!) {
            val maxQueryLength = case.int("maxQueryLength")!!
            // The Dart client shuffled, then read one chunk. Put the accounts
            // of that chunk where the chunk sits and keep the order.
            val sizes = TwitterParser.buildChunkedQueries(subs(names), includeNativeRetweets, maxQueryLength, KeepOrder)
                .map { Regex("from:").findAll(it).count() }
            val index = case.int("chunkIndex")!! % sizes.size
            val offset = sizes.take(index).sum()
            val rest = names - picked.toSet()
            val ordered = rest.take(offset) + picked + rest.drop(offset)
            if (ordered.sorted() != names.sorted()) problems += "$label: Dart chunk is not a subset: $rawQuery"
            val queries = TwitterParser.buildChunkedQueries(subs(ordered), includeNativeRetweets, maxQueryLength, KeepOrder)
            query = queries[index]
        } else {
            val batch = case.int("subBatchSize")!!
            if (picked.size != minOf(batch, names.size) || picked.toSet().size != picked.size || !names.containsAll(picked)) {
                problems += "$label: Dart sample is not $batch distinct accounts: $rawQuery"
            }
            query = TwitterParser.buildQueryFromUsersClause(
                TwitterParser.buildUsersClause(subs(picked)), includeNativeRetweets,
            )
        }
        if (query != rawQuery) problems += "$label\n  dart:   $rawQuery\n  kotlin: $query"
        search(query, null, null, null, case.str("cursor"), 20)
    }

    private fun actual(case: JsonObject, problems: MutableList<String>): List<Request> = with(DartGolden) {
        val cursor = case.str("cursor")
        when (val op = case.str("op")!!) {
            "search" -> search(
                case.str("query"), case.sort(), case.filters(), case.int("minFaves"), cursor, case.int("count")!!,
            )
            // fetchUserTweets
            "userTweets" -> search(
                "from:${case.str("screenName")}", case.sort(), case.filters(), null, cursor, case.int("count")!!,
            )
            // fetchUserTimelineByScreenName
            "userTimelineByScreenName" -> search("from:${case.str("screenName")}", null, emptySet(), null, cursor, 20)
            "profile" -> {
                val name = TwitterParser.normalizeScreenName(case.str("screenName")!!)
                listOf(get(TwitterParser.newProfileUri(name)), get(TwitterParser.oldProfileUri(name)))
            }
            "following" -> {
                val userId = case.str("userId")!!
                listOfNotNull(
                    get(TwitterParser.followingUri(userId, null)),
                    // The Flutter client cannot send a cursor with a non-ASCII character (see below).
                    case.str("cursorOfFirstPage")?.takeIf { cursor -> cursor.all { it.code <= 127 } }
                        ?.let { get(TwitterParser.followingUri(userId, it)) },
                )
            }
            "userTimeline" -> listOf(get(TwitterParser.userTweetsUri(case.str("userId")!!, case.int("count")!!, cursor)))
            "videoMixer" -> listOf(get(TwitterParser.videoMixerUri(case.int("count")!!, cursor)))
            "algorithmic" -> listOf(get(TwitterParser.algorithmicTimelineUri(case.int("count")!!, cursor)))
            "chronological" -> listOf(get(TwitterParser.chronologicalTimelineUri(case.int("count")!!, cursor)))
            "tweetDetail" -> listOf(get(TwitterParser.tweetDetailUri(case.str("focalTweetId")!!, cursor)))
            "favorite" -> listOf(
                post(TwitterFeatures.graphqlFavoriteTweetUriPath, TwitterParser.favoriteTweetBody(case.str("tweetId")!!)),
            )
            "unfavorite" -> listOf(
                post(TwitterFeatures.graphqlUnfavoriteTweetUriPath, TwitterParser.unfavoriteTweetBody(case.str("tweetId")!!)),
            )
            "retweet" -> listOf(
                post(TwitterFeatures.graphqlCreateRetweetUriPath, TwitterParser.retweetTweetBody(case.str("tweetId")!!)),
            )
            "unretweet" -> listOf(
                post(TwitterFeatures.graphqlDeleteRetweetUriPath, TwitterParser.unretweetTweetBody(case.str("tweetId")!!)),
            )
            "postTweet" -> listOf(
                post(
                    TwitterFeatures.graphqlCreateTweetUriPath,
                    TwitterParser.postTweetBody(case.str("text")!!, case.str("inReplyToTweetId")),
                ),
            )
            "subscribed" -> subscribed(case, problems)
            // jsonEncode + Uri.https on their own
            "encode" -> listOf(
                get(
                    XUri.https(
                        "x.com", "/i/api/graphql/x/Encode",
                        mapOf(
                            "variables" to buildJsonObject {
                                put("rawQuery", case.str("text"))
                                put("n", 1)
                                put("b", true)
                                put("z", JsonNull)
                            }.toString(),
                        ),
                    ),
                ),
            )
            else -> fail("unknown op $op")
        }
    }

    private fun firstDifference(a: String, b: String): String {
        val at = a.indices.firstOrNull { it >= b.length || a[it] != b[it] } ?: minOf(a.length, b.length)
        val from = maxOf(0, at - 60)
        return "differs at $at\n      dart:   …${a.substring(from, minOf(a.length, at + 80))}\n" +
            "      kotlin: …${b.substring(from, minOf(b.length, at + 80))}"
    }

    @Test
    fun everyRequestMatchesTheDartClient() {
        val problems = mutableListOf<String>()
        var compared = 0
        var requests = 0
        var notSentByDart = 0
        val perOp = sortedMapOf<String, Int>()

        for (element in golden["cases"] as JsonArray) {
            val case = element as JsonObject
            val op = with(DartGolden) { case.str("op")!! }
            val want = expected(case)
            if (want.isEmpty()) {
                // The Flutter client threw while writing this request to its
                // log and sent nothing (non-ASCII text or a stray '%' in the
                // address). The encoding of such text is covered by "encode".
                notSentByDart++
                continue
            }
            val got = actual(case, problems)
            compared++
            perOp[op] = (perOp[op] ?: 0) + 1
            requests += want.size
            if (want.size != got.size) {
                problems += "$case\n    dart sent ${want.size} requests, kotlin builds ${got.size}"
                continue
            }
            for (i in want.indices) {
                val w = want[i]
                val g = got[i]
                val input = case.filterKeys { it != "requests" }
                if (w.method != g.method) problems += "$input: method ${w.method} / ${g.method}"
                if (w.url != g.url) problems += "$input request ${i + 1}: address ${firstDifference(w.url, g.url)}"
                if (w.body != g.body) {
                    problems += "$input request ${i + 1}: body ${firstDifference(w.body ?: "null", g.body ?: "null")}"
                }
            }
        }

        println("DartGoldenUriTest: $compared cases ($requests requests) compared, $notSentByDart not sent by Dart; per op $perOp")
        assertTrue(compared > 900, "too few cases compared: $compared")
        if (problems.isNotEmpty()) {
            fail("${problems.size} differences from the Dart client:\n" + problems.take(25).joinToString("\n"))
        }
    }

    /** The cases the Flutter client could not send are exactly the ones with text its log code chokes on. */
    @Test
    fun dartSendsNothingOnlyForTextItsLogCannotDecode() {
        for (element in golden["cases"] as JsonArray) {
            val case = element as JsonObject
            if ((case["requests"] as JsonArray).isNotEmpty()) continue
            val text = with(DartGolden) {
                listOfNotNull(case.str("query"), case.str("screenName"), case.str("focalTweetId"), case.str("cursor"))
            }.joinToString(" ")
            val nonAscii = text.any { it.code > 127 }
            val strayPercent = Regex("%(?![0-9A-Fa-f]{2})").containsMatchIn(text)
            assertTrue(nonAscii || strayPercent, "Dart sent nothing for plain input: $case")
        }
    }

    @Test
    fun logFormattingNeverStopsARequest() {
        // Dart: Uri.decodeQueryComponent throws here and the request is never sent.
        for (text in listOf("100% sure", "tiếng Việt + dấu", "a%zzb", "%", "50%2", "ok %41")) {
            val uri = XUri.https("x.com", "/p", mapOf("variables" to """{"rawQuery":"$text"}"""))
            val line = TwitterLogFormat.formatUriForLog(uri)
            assertTrue(line.startsWith("/p params="), line)
            assertEquals(line, TwitterLogFormat.summarizeRequest(uri, "GET", null).removePrefix("GET "))
        }
    }
}
