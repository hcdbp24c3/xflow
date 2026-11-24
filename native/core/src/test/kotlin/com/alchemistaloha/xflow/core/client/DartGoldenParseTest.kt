package com.alchemistaloha.xflow.core.client

import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.TweetResponse
import java.io.File
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

/**
 * The hand-made X responses in fixtures/diff/responses, read by the Kotlin
 * parser and compared with what the Flutter parser made of the same files
 * (golden_dart/parse_golden.json, written by running the real Dart client
 * behind a fake HTTP client; see fixtures/diff/README.md).
 *
 * Each kind of response goes through the same steps as in
 * native/app/.../data/client/TwitterClient.kt, repeated here because that
 * class needs Android.
 *
 * Two fields are known to differ and are checked on their own:
 *  - createdAtMillis: the Flutter app reads X's "Wed Oct 10 20:19:24 +0000
 *    2018" as null (its DateFormat cannot parse the zone), the port reads it.
 *    Where Dart has a date the port must have the same one; where Dart has
 *    none the port's value is pinned in kotlin_created_at.json.
 *  - viewCount: Dart ints are 64 bit, the port's field is an Int and stops at
 *    Int.MAX_VALUE.
 */
class DartGoldenParseTest {
    private val golden = DartGolden.json("golden_dart/parse_golden.json")["fixtures"] as JsonObject
    private val kotlinDates = DartGolden.json("kotlin_created_at.json")["fixtures"] as JsonObject

    private fun fixture(name: String): JsonElement? {
        val element = Json.parseToJsonElement(DartGolden.resource("responses/$name.json"))
        return if (element is JsonNull) null else element
    }

    // --- the glue of the Android TwitterClient ---

    private fun JsonElement?.asMap(): JsonObject =
        this as? JsonObject ?: throw IllegalStateException("response is not a JSON object")

    private fun JsonElement?.child(key: String): JsonElement? {
        if (this == null || this is JsonNull) return null
        val value = asMap()[key]
        return if (value is JsonNull) null else value
    }

    private val empty = TweetResponse(tweets = emptyList())

    /** fetchTrendingMedia */
    private fun search(data: JsonElement?): TweetResponse = try {
        val timeline = data.child("data").child("search_by_raw_query").child("search_timeline")
        if (timeline == null) empty else TwitterParser.parseTweets(timeline.asMap())
    } catch (e: Exception) {
        empty
    }

    /** fetchUserTimeline */
    private fun userTimeline(data: JsonElement?): TweetResponse = try {
        val timeline = data.asMap().child("data").child("user").child("result").child("timeline_v2").child("timeline")
        if (timeline == null) empty else TwitterParser.parseTweets(timeline.asMap())
    } catch (e: Exception) {
        empty
    }

    /** fetchTweetDetail and the three home timelines */
    private fun agnostic(data: JsonElement?, filters: Set<MediaFilter>? = null): TweetResponse = try {
        val response = TwitterParser.parseAgnosticTimeline(data.asMap())
        if (filters != null && filters.isNotEmpty()) {
            response.copy(tweets = TwitterParser.applyFilters(response.tweets, filters))
        } else {
            response
        }
    } catch (e: Exception) {
        empty
    }

    /** fetchFollowing, first page: the accounts found and the cursor asked for next (none when paging stops). */
    private fun following(data: JsonElement?): Pair<List<Subscription>, List<String>> {
        val allSubs = mutableListOf<Subscription>()
        val asked = mutableListOf<String>()
        try {
            val page = TwitterParser.parseFollowingPage(data, allSubs)
            val next = page?.nextCursor
            if (page != null && page.newFound != 0 && next != null) asked.add(next)
        } catch (e: Exception) {
            // Dart: caught, what was found so far is returned
        }
        return allSubs to asked
    }

    /** fetchProfile: the new query, then the old one, both given the same response here. */
    private fun profile(data: JsonElement?, asked: String): Subscription? {
        val screenName = TwitterParser.normalizeScreenName(asked)
        repeat(2) {
            try {
                TwitterParser.parseProfile(data, screenName)?.let { return it }
            } catch (e: Exception) {
                // next attempt
            }
        }
        return null
    }

    // --- comparing ---

    private fun text(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun tweetFields(t: Tweet): Map<String, JsonElement> = linkedMapOf(
        "id" to JsonPrimitive(t.id),
        "text" to JsonPrimitive(t.text),
        "userHandle" to JsonPrimitive(t.userHandle),
        "userAvatarUrl" to text(t.userAvatarUrl),
        "mediaKey" to text(t.mediaKey),
        "mediaUrls" to JsonArray(t.mediaUrls.map { JsonPrimitive(it) }),
        "thumbnailUrl" to text(t.thumbnailUrl),
        "isVideo" to JsonPrimitive(t.isVideo),
        "source" to text(t.source),
        "isLiked" to JsonPrimitive(t.isLiked),
        "favoriteCount" to JsonPrimitive(t.favoriteCount),
        "replyCount" to JsonPrimitive(t.replyCount),
        "retweetCount" to JsonPrimitive(t.retweetCount),
        "isRetweeted" to JsonPrimitive(t.isRetweeted),
        "viewCount" to JsonPrimitive(t.viewCount),
        "isBookmarked" to JsonPrimitive(t.isBookmarked),
    )

    private fun compareTweets(name: String, want: JsonObject, got: TweetResponse, problems: MutableList<String>) {
        with(DartGolden) {
            if (want.str("cursorTop") != got.cursorTop) {
                problems += "$name cursorTop: dart=${want.str("cursorTop")} kotlin=${got.cursorTop}"
            }
            if (want.str("cursorBottom") != got.cursorBottom) {
                problems += "$name cursorBottom: dart=${want.str("cursorBottom")} kotlin=${got.cursorBottom}"
            }
            val wantTweets = want.arr("tweets")!!.map { it as JsonObject }
            val wantIds = wantTweets.map { it.str("id") }
            val gotIds = got.tweets.map { it.id }
            if (wantIds != gotIds) {
                problems += "$name posts:\n    dart   (${wantIds.size}): $wantIds\n    kotlin (${gotIds.size}): $gotIds"
                return
            }
            val pinned = kotlinDates.arr(name)
            for ((index, tweet) in got.tweets.withIndex()) {
                val wantTweet = wantTweets[index]
                for ((field, value) in tweetFields(tweet)) {
                    var wantValue = wantTweet[field] ?: JsonNull
                    if (field == "viewCount") {
                        wantValue = JsonPrimitive(wantTweet.long(field)!!.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    }
                    if (wantValue != value) {
                        problems += "$name post ${tweet.id} $field: dart=$wantValue kotlin=$value"
                    }
                }
                val dartDate = wantTweet.long("createdAtMillis")
                if (dartDate != null) {
                    if (dartDate != tweet.createdAtMillis) {
                        problems += "$name post ${tweet.id} createdAtMillis: dart=$dartDate kotlin=${tweet.createdAtMillis}"
                    }
                } else if (pinned != null) {
                    val pinnedDate = (pinned[index] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLong()
                    if (pinnedDate != tweet.createdAtMillis) {
                        problems += "$name post ${tweet.id} createdAtMillis (Dart has none): pinned=$pinnedDate kotlin=${tweet.createdAtMillis}"
                    }
                }
            }
        }
    }

    private fun subFields(s: Subscription?): JsonElement = if (s == null) {
        JsonNull
    } else {
        JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive(s.id),
                "screenName" to JsonPrimitive(s.screenName),
                "name" to JsonPrimitive(s.name),
                "profileImageUrl" to text(s.profileImageUrl),
                "description" to text(s.description),
                "followersCount" to (s.followersCount?.let { JsonPrimitive(it) } ?: JsonNull),
                "followingCount" to (s.followingCount?.let { JsonPrimitive(it) } ?: JsonNull),
            ),
        )
    }

    private fun filterSet(names: String): Set<MediaFilter> =
        names.split(",").mapTo(LinkedHashSet()) { MediaFilter.valueOf(it) }

    @Test
    fun everyFixtureReadsAsTheDartParserReadIt() {
        val problems = mutableListOf<String>()
        val recorded = linkedMapOf<String, JsonElement>()
        var posts = 0
        var accounts = 0

        for ((name, wantElement) in golden) {
            val want = wantElement as JsonObject
            val data = fixture(name)
            when (name.substringBefore('_')) {
                "search", "usertweets", "detail", "home" -> {
                    val got = when (name.substringBefore('_')) {
                        "search" -> search(data)
                        "usertweets" -> userTimeline(data)
                        else -> agnostic(data)
                    }
                    posts += got.tweets.size
                    recorded[name] = JsonArray(got.tweets.map { t -> t.createdAtMillis?.let { JsonPrimitive(it) } ?: JsonNull })
                    compareTweets(name, want, got, problems)
                    (want["filteredIds"] as JsonObject?)?.forEach { (filters, ids) ->
                        val wantIds = (ids as JsonArray).map { (it as JsonPrimitive).content }
                        val gotIds = agnostic(data, filterSet(filters)).tweets.map { it.id }
                        if (wantIds != gotIds) problems += "$name filters=$filters: dart=$wantIds kotlin=$gotIds"
                    }
                }
                "following" -> {
                    val (subs, asked) = following(data)
                    accounts += subs.size
                    val gotSubs = JsonArray(subs.map { subFields(it) })
                    if (want["subscriptions"] != gotSubs) {
                        problems += "$name accounts:\n    dart:   ${want["subscriptions"]}\n    kotlin: $gotSubs"
                    }
                    val wantAsked = with(DartGolden) { want.strings("nextCursorsRequested")!! }
                    if (wantAsked != asked) problems += "$name next cursor: dart=$wantAsked kotlin=$asked"
                }
                "profile" -> {
                    val got = subFields(profile(data, "@asked_name"))
                    if ((want["profile"] ?: JsonNull) != got) {
                        problems += "$name:\n    dart:   ${want["profile"]}\n    kotlin: $got"
                    }
                }
                else -> fail("unknown fixture kind: $name")
            }
        }

        // XFLOW_RECORD_DATES=<file> writes what the port read as dates (to refresh kotlin_created_at.json).
        System.getenv("XFLOW_RECORD_DATES")?.let { path ->
            val lines = recorded.entries.joinToString(",\n") { "  ${JsonPrimitive(it.key)}: ${it.value}" }
            File(path).writeText(
                "{\n \"about\": \"createdAtMillis theo bản Kotlin cho từng post (theo thứ tự) của mỗi fixture. KHÔNG phải golden từ Dart: bản Dart đọc ngày kiểu X ra null. Chỉ dùng ở chỗ Dart ra null.\",\n \"fixtures\": {\n$lines\n }\n}\n",
            )
        }

        println("DartGoldenParseTest: ${golden.size} fixtures, $posts posts, $accounts accounts compared")
        assertTrue(golden.size >= 40, "too few fixtures: ${golden.size}")
        if (problems.isNotEmpty()) {
            fail("${problems.size} differences from the Dart parser:\n" + problems.take(40).joinToString("\n"))
        }
    }

    /** X's own date format, which the Flutter app reads as null. */
    @Test
    fun readsTheDateFormatOfX() {
        val tweets = search(fixture("search_basic_mixed")).tweets
        assertEquals(1539202764000L, tweets.first().createdAtMillis) // Wed Oct 10 20:19:24 +0000 2018
        assertEquals(1583020799000L, tweets.last().createdAtMillis) // Sat Feb 29 23:59:59 +0000 2020
        val odd = search(fixture("search_missing_and_wrong_types")).tweets.associateBy { it.text }
        assertEquals(1704042000000L, odd.getValue("offset date").createdAtMillis) // Mon Jan 01 00:00:00 +0700 2024
        assertEquals(null, odd.getValue("garbage date").createdAtMillis)
        assertEquals(null, odd.getValue("no date at all").createdAtMillis)
    }
}
