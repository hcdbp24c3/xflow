package com.alchemistaloha.xflow.core.client

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.Tweet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Posts in the layout X serves since November 2026 (`details`, `counts`,
 * `media_entities2`). The field names are those of real answers of
 * TweetResultByRestId on 2026-11-24; the values are made up.
 */
class NewPostShapeTest {
    private fun entry(id: String, result: String) =
        """{"entryId":"tweet-$id","content":{"entryType":"TimelineTimelineItem","itemContent":{"tweet_results":{"result":$result}}}}"""

    private fun user(name: String) =
        """{"user_results":{"rest_id":"7","result":{"__typename":"User","avatar":{"image_url":"https://pbs.example/${name}_normal.jpg"},"core":{"name":"N","screen_name":"$name"},"rest_id":"7"}}}"""

    private val video = """{"__typename":"Tweet","rest_id":"100","core":${user("maker")},
        "counts":{"bookmark_count":3,"favorite_count":120,"quote_count":1,"reply_count":4,"retweet_count":9},
        "details":{"created_at_ms":1795451335000,"display_text_range":[0,5],"full_text":"hello https://t.co/x"},
        "legacy":{"entities":{"media":[{"expanded_url":"https://x.com/maker/status/100/video/1"}]},"lang":"en"},
        "media_entities2":[{"id_str":"900","media_url_https":"https://pbs.example/thumb.png","type":"video",
          "video_info":{"duration_millis":1000,"variants":[
            {"content_type":"application/x-mpegURL","url":"https://video.example/pl.m3u8"},
            {"bitrate":832000,"content_type":"video/mp4","url":"https://video.example/360.mp4"},
            {"bitrate":2176000,"content_type":"video/mp4","url":"https://video.example/720.mp4"}]}}],
        "views":{"count":"6645"}}"""

    private val photo = """{"__typename":"Tweet","rest_id":"101","core":${user("maker")},
        "counts":{"favorite_count":7,"reply_count":0,"retweet_count":0},
        "details":{"created_at_ms":1795453257000,"full_text":"a photo"},
        "legacy":{"lang":"en"},
        "media_entities2":[{"id_str":"901","media_url_https":"https://pbs.example/p.jpg","type":"photo"}]}"""

    private val reply = """{"__typename":"Tweet","rest_id":"102","core":${user("maker")},
        "counts":{"favorite_count":1},"details":{"created_at_ms":1795453257001,"full_text":"@x yes"},
        "legacy":{"lang":"en"},"media_entities2":[],"reply_to_results":{"rest_id":"55"}}"""

    private fun timeline(vararg entries: String) =
        Json.parseToJsonElement("""{"instructions":[{"type":"TimelineAddEntries","entries":[${entries.joinToString(",")}]}]}""") as JsonObject

    @Test
    fun readsTextDateCountsAuthorAndMedia() {
        val tweets = TwitterParser.parseTweets(timeline(entry("100", video), entry("101", photo))).tweets
        assertEquals(2, tweets.size)

        val v = tweets[0]
        assertEquals("100", v.id)
        assertEquals("hello https://t.co/x", v.text)
        assertEquals("@maker", v.userHandle)
        assertEquals("https://pbs.example/maker_normal.jpg", v.userAvatarUrl)
        assertTrue(v.isVideo)
        assertEquals(listOf("https://video.example/720.mp4"), v.mediaUrls)
        assertEquals("https://pbs.example/thumb.png", v.thumbnailUrl)
        assertEquals(1795451335000, v.createdAtMillis)
        assertEquals(120, v.favoriteCount)
        assertEquals(4, v.replyCount)
        assertEquals(9, v.retweetCount)
        assertEquals(6645, v.viewCount)
        // What translation and subtitles need: the post's language and the video's playlist.
        assertEquals("en", v.lang)
        assertEquals("https://video.example/pl.m3u8", v.hlsUrl)

        val p = tweets[1]
        assertEquals(listOf("https://pbs.example/p.jpg"), p.mediaUrls)
        assertEquals(false, p.isVideo)
        assertEquals("en", p.lang)
        assertNull(p.hlsUrl)
        assertEquals(7, p.favoriteCount)
    }

    @Test
    fun aPostInTheOlderLayoutIsLeftAlone() {
        val old = Json.parseToJsonElement("""{"rest_id":"1","details":{"full_text":"new"},"legacy":{"full_text":"old"}}""")
        assertEquals(old, TwitterParser.withLegacyShape(old))
    }

    @Test
    fun repliesAndRepostsCanBeLeftOut() {
        val oldReply = """{"rest_id":"200","core":${user("maker")},"legacy":{"full_text":"r","in_reply_to_status_id_str":"9"}}"""
        val oldRepost = """{"rest_id":"201","core":${user("maker")},"legacy":{"full_text":"RT",
            "retweeted_status_result":{"result":{"rest_id":"300","core":${user("other")},"legacy":{"full_text":"orig"}}}}}"""
        val all = timeline(entry("100", video), entry("102", reply), entry("200", oldReply), entry("201", oldRepost))

        assertEquals(listOf("100", "102", "200", "300"), TwitterParser.parseTweets(all).tweets.map { it.id })
        assertEquals(
            listOf("100", "300"),
            TwitterParser.parseTweets(all, skipReplies = true).tweets.map { it.id },
        )
        assertEquals(
            listOf("100"),
            TwitterParser.parseTweets(all, skipReplies = true, skipRetweets = true).tweets.map { it.id },
        )
    }

    // ---- the per-account replacement for a refused search -----------------

    @Test
    fun readsTheAccountsAndSwitchesOutOfTheQuery() {
        val plan = AccountTimelineFallback.plan("(from:Alpha OR from:beta_2 OR from:alpha) -filter:replies -filter:retweets (filter:videos)")!!
        assertEquals(listOf("Alpha", "beta_2"), plan.screenNames)
        assertTrue(plan.skipReplies)
        assertTrue(plan.skipRetweets)
        assertNull(plan.minFaves)

        val sync = AccountTimelineFallback.plan("include:nativeretweets (from:a OR from:b) -filter:replies min_faves:50")!!
        assertEquals(false, sync.skipRetweets)
        assertEquals(50, sync.minFaves)

        assertNull(AccountTimelineFallback.plan("#hashtag -filter:replies"))
        assertNull(AccountTimelineFallback.plan("upfrom:x"))
    }

    @Test
    fun pagesWalkThroughTheAccounts() {
        val names = (1..10).map { "u$it" }
        assertEquals(0, AccountTimelineFallback.offset(null))
        assertEquals(0, AccountTimelineFallback.offset("DAACCgACGC"))
        assertEquals(listOf("u1", "u2", "u3", "u4"), AccountTimelineFallback.page(names, 0))
        val second = AccountTimelineFallback.nextCursor(names, 0)!!
        assertTrue(AccountTimelineFallback.isCursor(second))
        assertEquals(4, AccountTimelineFallback.offset(second))
        val third = AccountTimelineFallback.nextCursor(names, 4)!!
        assertEquals(listOf("u9", "u10"), AccountTimelineFallback.page(names, AccountTimelineFallback.offset(third)))
        assertNull(AccountTimelineFallback.nextCursor(names, 8))
    }

    @Test
    fun mergeDropsDuplicatesFiltersAndOrders() {
        fun t(id: String, at: Long, likes: Int, video: Boolean) = Tweet(
            id = id, text = "", userHandle = "@a", mediaUrls = listOf("u"), isVideo = video,
            createdAtMillis = at, favoriteCount = likes,
        )
        val a = listOf(t("1", 10, 5, true), t("2", 30, 500, false))
        val b = listOf(t("3", 20, 50, true), t("1", 10, 5, true))

        assertEquals(listOf("2", "3", "1"), AccountTimelineFallback.merge(listOf(a, b), FeedSort.latest, null, null).map { it.id })
        assertEquals(listOf("3", "1"), AccountTimelineFallback.merge(listOf(a, b), FeedSort.latest, setOf(MediaFilter.video), null).map { it.id })
        assertEquals(listOf("2", "3"), AccountTimelineFallback.merge(listOf(a, b), FeedSort.popular, null, 50).map { it.id })
    }
}

class ProfileTimelineInstructionsTest {
    private fun post(id: String) =
        """{"entryId":"tweet-$id","content":{"entryType":"TimelineTimelineItem","itemContent":{"tweet_results":{"result":{"rest_id":"$id","core":{"user_results":{"result":{"core":{"screen_name":"a"}}}},"legacy":{"full_text":"t"}}}}}}"""

    private val timeline = Json.parseToJsonElement(
        """{"instructions":[{"type":"TimelineClearCache"},{"type":"TimelinePinEntry","entry":${post("1")}},
           {"type":"TimelineAddEntries","entries":[${post("2")},${post("3")},
             {"entryId":"cursor-bottom-9","content":{"entryType":"TimelineTimelineCursor","cursorType":"Bottom","value":"next"}}]}]}""",
    ) as JsonObject

    @Test
    fun thePinnedPostDoesNotHideTheRest() {
        // As the Flutter app reads it: the first instruction with entries only.
        assertEquals(listOf("1"), TwitterParser.parseTweets(timeline).tweets.map { it.id })
        val all = TwitterParser.parseTweets(timeline, allInstructions = true)
        assertEquals(listOf("1", "2", "3"), all.tweets.map { it.id })
        assertEquals("next", all.cursorBottom)
    }
}
