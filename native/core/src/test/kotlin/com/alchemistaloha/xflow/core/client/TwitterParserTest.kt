package com.alchemistaloha.xflow.core.client

import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.TweetResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Parser tests on hand-written responses (src/test/resources/fixtures/client). None of it is real X data. */
class TwitterParserTest {
    private fun fixture(name: String): JsonObject {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/client/$name")) {
            "missing fixture $name"
        }
        return Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }) as JsonObject
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun searchTimeline(): TweetResponse {
        val root = fixture("search_timeline.json")
        val timeline = ((root["data"] as JsonObject)["search_by_raw_query"] as JsonObject)["search_timeline"]
        return TwitterParser.parseTweets(timeline as JsonObject)
    }

    private fun TweetResponse.byId(id: String): Tweet = tweets.single { it.id == id }

    @Test
    fun readsEveryReadablePostInOrder() {
        val response = searchTimeline()
        assertEquals(listOf("1001", "1002", "900", "1004", "1005", "1007", "1010"), response.tweets.map { it.id })
    }

    @Test
    fun readsTopAndBottomCursors() {
        val response = searchTimeline()
        assertEquals("TOP_CURSOR", response.cursorTop)
        assertEquals("BOTTOM_CURSOR", response.cursorBottom)
    }

    @Test
    fun videoPostPicksHighestBitrateMp4() {
        val tweet = searchTimeline().byId("1001")
        assertTrue(tweet.isVideo)
        assertEquals(listOf("https://video.example/2001/vid/720x1280/high.mp4"), tweet.mediaUrls)
        assertEquals("https://img.example/thumbs/2001.jpg", tweet.thumbnailUrl)
        assertEquals("13_2001", tweet.mediaKey)
        assertEquals("A video post https://t.example/v1", tweet.text)
        assertEquals("@video_maker", tweet.userHandle)
        assertEquals("https://img.example/avatars/video_maker_normal.jpg", tweet.userAvatarUrl)
        assertEquals("https://img.example/avatars/video_maker.jpg", tweet.userAvatarUrlHighRes)
        assertEquals(1539202764000L, tweet.createdAtMillis)
        assertTrue(tweet.isLiked)
        assertFalse(tweet.isRetweeted)
        assertEquals(321, tweet.favoriteCount)
        assertEquals(4, tweet.replyCount)
        assertEquals(17, tweet.retweetCount)
        assertEquals(12345, tweet.viewCount)
        assertFalse(tweet.isBookmarked)
        assertNull(tweet.source)
    }

    @Test
    fun photoPostKeepsEveryImageAndReadsTheNewUserShape() {
        val tweet = searchTimeline().byId("1002")
        assertFalse(tweet.isVideo)
        assertEquals(
            listOf(
                "https://img.example/media/3001.jpg",
                "https://img.example/media/3002.jpg",
                "https://img.example/media/3003.jpg",
            ),
            tweet.mediaUrls,
        )
        assertEquals("https://img.example/media/3001.jpg", tweet.thumbnailUrl)
        assertEquals("3_3001", tweet.mediaKey)
        assertEquals("@photo_taker", tweet.userHandle)
        assertEquals("https://img.example/avatars/photo_taker_normal.jpg", tweet.userAvatarUrl)
        assertEquals(1735787045000L, tweet.createdAtMillis)
        assertFalse(tweet.isLiked)
        assertTrue(tweet.isRetweeted)
        assertEquals(77, tweet.viewCount) // falls back to legacy.views
    }

    @Test
    fun retweetBecomesTheOriginalPost() {
        val response = searchTimeline()
        assertTrue(response.tweets.none { it.id == "1003" })
        val tweet = response.byId("900")
        assertEquals("The original post", tweet.text)
        assertEquals("@original_author", tweet.userHandle)
        assertEquals("https://img.example/avatars/original_normal.jpg", tweet.userAvatarUrl)
        assertEquals(5000, tweet.favoriteCount)
        assertEquals(250, tweet.retweetCount)
        assertEquals(30, tweet.replyCount)
        assertEquals(listOf("https://img.example/media/4001.jpg"), tweet.mediaUrls)
        assertEquals(1735732800000L, tweet.createdAtMillis)
    }

    @Test
    fun textOnlyPostHasNoMediaAndReadsCreatedAtMs() {
        val tweet = searchTimeline().byId("1004")
        assertEquals("Only words here", tweet.text)
        assertTrue(tweet.mediaUrls.isEmpty())
        assertNull(tweet.thumbnailUrl)
        assertNull(tweet.mediaKey)
        assertFalse(tweet.isVideo)
        assertEquals(1735689600000L, tweet.createdAtMillis)
        assertEquals(2, tweet.favoriteCount)
        assertEquals(0, tweet.viewCount)
        assertNull(tweet.userAvatarUrl)
    }

    @Test
    fun visibilityWrapperIsUnwrappedAndGifFallsBackToFirstVariant() {
        val tweet = searchTimeline().byId("1005")
        assertEquals("@limited", tweet.userHandle)
        assertTrue(tweet.isVideo)
        assertEquals(listOf("https://video.example/5001/gif.webm"), tweet.mediaUrls)
        assertEquals("https://img.example/thumbs/5001.jpg", tweet.thumbnailUrl)
    }

    @Test
    fun postWithAWrongTypedFieldIsSkippedNotFatal() {
        assertTrue(searchTimeline().tweets.none { it.id == "1006" })
    }

    @Test
    fun noteTweetMediaIsUsedWhenThePostHasNone() {
        val tweet = searchTimeline().byId("1007")
        assertEquals(listOf("https://img.example/media/7001.jpg"), tweet.mediaUrls)
        assertEquals("https://img.example/media/7001.jpg", tweet.thumbnailUrl)
        assertEquals("3_7001", tweet.mediaKey)
        assertFalse(tweet.isVideo)
    }

    @Test
    fun moduleItemsAreReadAndEqualBitratesKeepTheirOrder() {
        val tweet = searchTimeline().byId("1010")
        assertEquals("@grid_user", tweet.userHandle)
        assertEquals(listOf("https://video.example/6001/a.mp4"), tweet.mediaUrls)
    }

    @Test
    fun agnosticParserFindsInstructionsAnywhereUnderData() {
        val response = TwitterParser.parseAgnosticTimeline(fixture("search_timeline.json"))
        assertEquals(7, response.tweets.size)
        assertEquals("BOTTOM_CURSOR", response.cursorBottom)
    }

    @Test
    fun agnosticParserWithoutInstructionsIsEmpty() {
        val response = TwitterParser.parseAgnosticTimeline(
            json("""{"errors": [{"message": "nope"}], "data": {"home": {"x": [1, 2]}}}""") as JsonObject,
        )
        assertTrue(response.tweets.isEmpty())
        assertNull(response.cursorTop)
        assertTrue(TwitterParser.parseAgnosticTimeline(json("{}") as JsonObject).tweets.isEmpty())
    }

    @Test
    fun replaceEntryInstructionIsReadAsOneEntry() {
        val response = TwitterParser.parseTweets(
            json(
                """{"instructions": [{"type": "TimelineReplaceEntry", "entry":
                    {"entryId": "cursor-bottom-1", "content": {"value": "REPLACED"}}}]}""",
            ) as JsonObject,
        )
        assertEquals("REPLACED", response.cursorBottom)
        assertTrue(response.tweets.isEmpty())
    }

    @Test
    fun missingScreenNameBecomesUnknownAndEntryIdIsTheFallbackId() {
        val tweets = mutableListOf<Tweet>()
        TwitterParser.parseTweetResult(
            json("""{"tweet_results": {"result": {"legacy": {"text": "x"}}}}""") as JsonObject,
            "tweet-77",
            tweets,
        )
        assertEquals(1, tweets.size)
        assertEquals("tweet-77", tweets[0].id)
        assertEquals("@Unknown", tweets[0].userHandle)
        assertNull(tweets[0].createdAtMillis)
    }

    @Test
    fun postWithoutLegacyIsSkipped() {
        val tweets = mutableListOf<Tweet>()
        TwitterParser.parseTweetResult(
            json("""{"itemContent": {"tweet_results": {"result": {"rest_id": "1"}}}}""") as JsonObject,
            "tweet-1",
            tweets,
        )
        assertTrue(tweets.isEmpty())
    }

    @Test
    fun aNonObjectEntryFailsTheWholeParseAsInDart() {
        assertFailsWith<JsonShapeException> {
            TwitterParser.parseTweets(json("""{"instructions": [{"entries": ["text"]}]}""") as JsonObject)
        }
    }

    // --- filters ---

    private fun tweet(id: String, isVideo: Boolean, urls: List<String>) =
        Tweet(id = id, text = "", userHandle = "@u", isVideo = isVideo, mediaUrls = urls)

    private val mixed = listOf(
        tweet("video", true, listOf("v.mp4")),
        tweet("image", false, listOf("a.jpg", "b.jpg")),
        tweet("text", false, emptyList()),
        tweet("video-without-url", true, emptyList()),
    )

    @Test
    fun filtersKeepMatchingPosts() {
        assertEquals(
            listOf("video", "video-without-url"),
            TwitterParser.applyFilters(mixed, setOf(MediaFilter.video)).map { it.id },
        )
        assertEquals(listOf("image"), TwitterParser.applyFilters(mixed, setOf(MediaFilter.image)).map { it.id })
        assertEquals(
            listOf("text", "video-without-url"),
            TwitterParser.applyFilters(mixed, setOf(MediaFilter.text)).map { it.id },
        )
        assertEquals(
            listOf("video", "image", "video-without-url"),
            TwitterParser.applyFilters(mixed, setOf(MediaFilter.video, MediaFilter.image)).map { it.id },
        )
        assertEquals(4, TwitterParser.applyFilters(mixed, MediaFilter.entries.toSet()).size)
        assertTrue(TwitterParser.applyFilters(mixed, emptySet()).isEmpty())
    }

    // --- following and profile ---

    @Test
    fun followingPageReadsBothUserShapesAndTheBottomCursor() {
        val subs = mutableListOf<Subscription>()
        val page = assertNotNull(TwitterParser.parseFollowingPage(fixture("following.json"), subs))
        assertEquals(3, page.newFound)
        assertEquals("NEXT|42", page.nextCursor)
        assertEquals(
            listOf(
                Subscription("601", "old_shape", "Old Shape", "https://img.example/avatars/601_normal.jpg"),
                Subscription("602", "new_shape", "New Shape", "https://img.example/avatars/602_normal.jpg"),
                Subscription("604", "no_name", "", null),
            ),
            subs,
        )
    }

    @Test
    fun followingPageWithoutEntriesEndsPaging() {
        val subs = mutableListOf<Subscription>()
        assertNull(TwitterParser.parseFollowingPage(json("""{"data": {}}"""), subs))
        assertNull(
            TwitterParser.parseFollowingPage(
                json("""{"data": {"user": {"result": {"timeline": {"timeline": {"instructions":
                    [{"type": "TimelineClearCache"}]}}}}}}"""),
                subs,
            ),
        )
        assertNull(
            TwitterParser.parseFollowingPage(
                json("""{"data": {"user": {"result": {"timeline": {"timeline": {"instructions":
                    [{"__typename": "TimelineAddEntries", "entries": []}]}}}}}}"""),
                subs,
            ),
        )
        assertTrue(subs.isEmpty())
    }

    @Test
    fun profileIsReadWithAvatarPreferredOverLegacyImage() {
        val sub = assertNotNull(TwitterParser.parseProfile(fixture("profile.json"), "some_user"))
        assertEquals(
            Subscription(
                id = "701",
                screenName = "Some_User",
                name = "Some User",
                profileImageUrl = "https://img.example/avatars/701_normal.jpg",
                description = "Line one\nline two",
                followersCount = 1234,
                followingCount = 56,
            ),
            sub,
        )
        assertEquals("https://img.example/avatars/701.jpg", sub.profileImageUrlHighRes)
    }

    @Test
    fun profileFallsBackToTheRequestedNameAndNullWhenNoUser() {
        val sub = assertNotNull(
            TwitterParser.parseProfile(json("""{"data": {"user": {"result": {"rest_id": "9"}}}}"""), "asked"),
        )
        assertEquals(Subscription("9", "asked", "asked"), sub)
        assertNull(TwitterParser.parseProfile(json("""{"data": {"user": {}}}"""), "asked"))
        assertNull(TwitterParser.parseProfile(json("""{"errors": []}"""), "asked"))
    }
}
