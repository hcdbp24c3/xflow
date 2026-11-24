package com.alchemistaloha.xflow.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of test/core/client/twitter_account_test.dart. The log helpers live in
 * [TwitterLogFormat]; the app's TwitterAccount forwards to them.
 */
class TwitterAccountTest {
    @Test
    fun compactForLogFlattensWhitespaceAndTruncatesLongValues() {
        val compact = TwitterLogFormat.compactForLog(
            "  line one\n   line two\tline three  ",
            maxLength = 18,
        )

        assertEquals("line one line two...", compact)
    }

    @Test
    fun compactForLogKeepsFullValueWhenMaxLengthIsOmitted() {
        val compact = TwitterLogFormat.compactForLog(
            "  line one\n   line two\tline three  ",
        )

        assertEquals("line one line two line three", compact)
    }

    @Test
    fun formatUriForLogDecodesGraphqlQueryParams() {
        val uri = XUri.https(
            "x.com", "/i/api/graphql/test/SearchTimeline",
            linkedMapOf(
                "variables" to "%7B%22rawQuery%22%3A%22%23latex+-filter%3Areplies%22%7D",
                "features" to "%7B%22rweb_video_screen_enabled%22%3Afalse%7D",
            ),
        )

        val formatted = TwitterLogFormat.formatUriForLog(uri)

        assertTrue(formatted.contains("/i/api/graphql/test/SearchTimeline"))
        assertTrue(formatted.contains("rawQuery: #latex -filter:replies"))
        assertTrue(formatted.contains("rweb_video_screen_enabled: false"))
        assertFalse(formatted.contains("%7B"))
    }

    @Test
    fun formatTransactionIdForLogReturnsMissingForNullAndKeepsValue() {
        assertEquals("missing", TwitterLogFormat.formatTransactionIdForLog(null))
        assertEquals("txid-123456", TwitterLogFormat.formatTransactionIdForLog("txid-123456"))
    }

    // --- added ---

    @Test
    fun formatTransactionIdForLogReturnsMissingForEmpty() {
        assertEquals("missing", TwitterLogFormat.formatTransactionIdForLog(""))
    }

    @Test
    fun compactForLogPrintsNullAndMapsLikeDart() {
        assertEquals("null", TwitterLogFormat.compactForLog(null))
        val context = linkedMapOf<String, Any?>(
            "query" to "from:a",
            "cursor" to null,
            "count" to 20,
            "filters" to listOf("video", "image"),
        )
        assertEquals(
            "{query: from:a, cursor: null, count: 20, filters: [video, image]}",
            TwitterLogFormat.compactForLog(context),
        )
    }

    @Test
    fun formatUriForLogWithoutQueryIsThePath() {
        assertEquals(
            "/i/api/graphql/x/FavoriteTweet",
            TwitterLogFormat.formatUriForLog(XUri.https("x.com", "/i/api/graphql/x/FavoriteTweet")),
        )
    }

    @Test
    fun summarizeRequestShowsMethodQueryAndBody() {
        val post = XUri.https("x.com", "/i/api/graphql/x/FavoriteTweet")
        assertEquals("POST body={\"a\":1}", TwitterLogFormat.summarizeRequest(post, "POST", "{\"a\":1}"))
        val get = XUri.https("x.com", "/p", linkedMapOf("variables" to "{\"count\":20}"))
        assertEquals("GET /p params={variables: {count: 20}}", TwitterLogFormat.summarizeRequest(get, "GET", null))
    }

    @Test
    fun uriEncodesQueryLikeDart() {
        val uri = XUri.https("x.com", "/i/api/p", linkedMapOf("variables" to "{\"q\":\"#a b-c_d.e~f/é\"}"))
        assertEquals(
            "https://x.com/i/api/p?variables=%7B%22q%22%3A%22%23a+b-c_d.e~f%2F%C3%A9%22%7D",
            uri.toString(),
        )
        assertEquals("https://x.com/i/api/p", XUri.https("x.com", "/i/api/p").toString())
        assertEquals("", XUri.https("x.com", "/i/api/p").query)
    }
}
