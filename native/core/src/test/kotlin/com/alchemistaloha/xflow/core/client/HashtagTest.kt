package com.alchemistaloha.xflow.core.client

import com.alchemistaloha.xflow.core.model.FeedSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stands in for test/core/client/hashtag_test.dart. That file is not a test:
 * it is a script that calls two SearchTimeline addresses on x.com and prints
 * the status codes. Nothing here may call X, so this checks the part that can
 * be checked offline: the SearchTimeline request built for a hashtag, and
 * that both operation ids the client tries are the ones in the code.
 */
class HashtagTest {
    @Test
    fun hashtagMediaSearchBuildsTheExpectedRequest() {
        val query = TwitterParser.buildSearchQuery(
            "#latex (filter:images OR filter:videos)", FeedSort.trending, null, null,
        )
        assertEquals("#latex (filter:images OR filter:videos) -filter:replies", query)

        val variables = TwitterParser.searchTimelineVariables(query, 20, FeedSort.trending, null)
        assertEquals(
            "{\"rawQuery\":\"#latex (filter:images OR filter:videos) -filter:replies\",\"count\":\"20\"," +
                "\"product\":\"Top\",\"querySource\":\"typed_query\",\"withDownvotePerspective\":false," +
                "\"withReactionsMetadata\":false,\"withReactionsPerspective\":false}",
            variables.toString(),
        )

        val uri = TwitterParser.buildSearchTimelineUri(TwitterFeatures.graphqlSearchTimelineUriPath, variables)
        assertEquals("x.com", uri.host)
        assertEquals("/i/api/graphql/hz_94eVAtrtQo_vO3my7Rw/SearchTimeline", uri.path)
        assertEquals(listOf("variables", "features", "fieldToggles"), uri.queryParameters.keys.toList())
        assertTrue(uri.toString().startsWith("https://x.com/i/api/graphql/hz_94eVAtrtQo_vO3my7Rw/SearchTimeline?variables=%7B%22rawQuery%22%3A%22%23latex+"))
        assertEquals(
            "{\"withArticleRichContentState\":true,\"withArticlePlainText\":false," +
                "\"withGrokAnalyze\":false,\"withDisallowedReplyControls\":false}",
            uri.queryParameters["fieldToggles"],
        )
    }

    @Test
    fun plainHashtagSearchUsesLatestAndCursor() {
        val query = TwitterParser.buildSearchQuery("#latex", FeedSort.latest, null, null)
        assertEquals("#latex -filter:replies", query)
        val variables = TwitterParser.searchTimelineVariables(query, 10, FeedSort.latest, "CURSOR")
        assertEquals("\"Latest\"", variables["product"].toString())
        assertEquals("\"CURSOR\"", variables["cursor"].toString())
        assertEquals("cursor", variables.keys.last())
    }

    @Test
    fun legacyOperationUsesTheSameQuery() {
        val variables = TwitterParser.searchTimelineVariables("#latex -filter:replies", 20, null, null)
        val current = TwitterParser.buildSearchTimelineUri(TwitterFeatures.graphqlSearchTimelineUriPath, variables)
        val legacy = TwitterParser.buildSearchTimelineUri(TwitterFeatures.legacyGraphqlSearchTimelineUriPath, variables)
        assertEquals("/i/api/graphql/SpDsqmz6FfYESd1e7TPcAw/SearchTimeline", legacy.path)
        assertEquals(current.query, legacy.query)
    }
}
