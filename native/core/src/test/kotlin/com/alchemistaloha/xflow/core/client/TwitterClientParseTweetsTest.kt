package com.alchemistaloha.xflow.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Port of test/core/client/twitter_client_test.dart from the branch
 * testing-twitter-client-parse-tweets-9795557154318944049 (group
 * "TwitterClient _parseTweets").
 *
 * Two of its cases read a `cursorType` fallback that only exists in that
 * branch's older parser. The parser on the current branch falls back to
 * `content.itemContent.value` instead, so those two cases assert what the
 * current code does (and the current fallback is covered next to them).
 */
class TwitterClientParseTweetsTest {
    private fun parse(text: String) =
        TwitterParser.parseTweets(Json.parseToJsonElement(text) as JsonObject)

    @Test
    fun handlesEmptyTimelineCorrectly() {
        val result = parse("{}")
        assertTrue(result.tweets.isEmpty())
        assertNull(result.cursorTop)
        assertNull(result.cursorBottom)
    }

    @Test
    fun handlesTimelineWithEmptyInstructions() {
        val result = parse("""{"timeline": {"instructions": []}}""")
        assertTrue(result.tweets.isEmpty())
    }

    @Test
    fun handlesMissingTimelineAddEntriesInstruction() {
        val result = parse("""{"instructions": [{"type": "TimelineClearCache"}]}""")
        assertTrue(result.tweets.isEmpty())
    }

    @Test
    fun parsesCursorTopFromCursorTopEntryIdUsingValue() {
        val result = parse(
            """{"instructions": [{"type": "TimelineAddEntries", "entries": [
                {"entryId": "cursor-top-123", "content": {"value": "top_cursor_value"}}
            ]}]}""",
        )
        assertEquals("top_cursor_value", result.cursorTop)
    }

    @Test
    fun sqCursorTopWithOnlyCursorTypeHasNoCursorOnCurrentParser() {
        val result = parse(
            """{"instructions": [{"type": "TimelineAddEntries", "entries": [
                {"entryId": "sq-cursor-top-456", "content": {"cursorType": "top_cursor_fallback"}}
            ]}]}""",
        )
        assertNull(result.cursorTop)
    }

    @Test
    fun parsesCursorTopFromSqCursorTopEntryIdUsingItemContentFallback() {
        val result = parse(
            """{"instructions": [{"type": "TimelineAddEntries", "entries": [
                {"entryId": "sq-cursor-top-456", "content": {"itemContent": {"value": "top_cursor_fallback"}}}
            ]}]}""",
        )
        assertEquals("top_cursor_fallback", result.cursorTop)
    }

    @Test
    fun parsesCursorBottomFromCursorBottomEntryIdUsingValue() {
        val result = parse(
            """{"instructions": [{"type": "TimelineAddEntries", "entries": [
                {"entryId": "cursor-bottom-123", "content": {"value": "bottom_cursor_value"}}
            ]}]}""",
        )
        assertEquals("bottom_cursor_value", result.cursorBottom)
    }

    @Test
    fun sqCursorBottomWithOnlyCursorTypeHasNoCursorOnCurrentParser() {
        val result = parse(
            """{"instructions": [{"type": "TimelineAddEntries", "entries": [
                {"entryId": "sq-cursor-bottom-456", "content": {"cursorType": "bottom_cursor_fallback"}}
            ]}]}""",
        )
        assertNull(result.cursorBottom)
    }

    @Test
    fun parsesCursorBottomFromSqCursorBottomEntryIdUsingItemContentFallback() {
        val result = parse(
            """{"instructions": [{"type": "TimelineAddEntries", "entries": [
                {"entryId": "sq-cursor-bottom-456", "content": {"itemContent": {"value": "bottom_cursor_fallback"}}}
            ]}]}""",
        )
        assertEquals("bottom_cursor_fallback", result.cursorBottom)
    }

    @Test
    fun safelyIgnoresEntriesWithoutContent() {
        val result = parse(
            """{"instructions": [{"type": "TimelineAddEntries", "entries": [
                {"entryId": "tweet-123"}
            ]}]}""",
        )
        assertTrue(result.tweets.isEmpty())
    }

    @Test
    fun parsesTimelineTimelineItemSuccessfully() {
        val result = parse(
            """{"instructions": [{"type": "TimelineAddEntries", "entries": [
                {"entryId": "tweet-123", "content": {
                    "entryType": "TimelineTimelineItem",
                    "itemContent": {"tweet_results": {"result": {
                        "rest_id": "tweet_id_1",
                        "legacy": {"full_text": "Hello world", "created_at": "Wed Oct 10 20:19:24 +0000 2018"},
                        "core": {"user_results": {"result": {"legacy": {"screen_name": "testuser"}}}}
                    }}}
                }}
            ]}]}""",
        )
        assertTrue(result.tweets.isNotEmpty())
        assertEquals("tweet_id_1", result.tweets.first().id)
        assertEquals("Hello world", result.tweets.first().text)
        assertEquals("@testuser", result.tweets.first().userHandle)
    }

    @Test
    fun parsesTimelineTimelineModuleContainingItems() {
        val result = parse(
            """{"instructions": [{"type": "TimelineAddEntries", "entries": [
                {"entryId": "module-123", "content": {
                    "entryType": "TimelineTimelineModule",
                    "items": [{"item": {"itemContent": {"tweet_results": {"result": {
                        "rest_id": "module_tweet_1",
                        "legacy": {"full_text": "Module tweet", "created_at": "Wed Oct 10 20:19:24 +0000 2018"},
                        "core": {"user_results": {"result": {"legacy": {"screen_name": "moduleuser"}}}}
                    }}}}}]
                }}
            ]}]}""",
        )
        assertTrue(result.tweets.isNotEmpty())
        assertEquals("module_tweet_1", result.tweets.first().id)
        assertEquals("Module tweet", result.tweets.first().text)
        assertEquals("@moduleuser", result.tweets.first().userHandle)
    }
}
