package com.alchemistaloha.xflow.core.client

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.Subscription
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The queries, variables and request bodies the client sends. */
class TwitterParserQueryTest {
    @Test
    fun searchQueryAlwaysExcludesRepliesOnce() {
        assertEquals("from:a -filter:replies", TwitterParser.buildSearchQuery("from:a", null, null, null))
        assertEquals(
            "(from:a) -filter:replies -filter:retweets",
            TwitterParser.buildSearchQuery("(from:a) -filter:replies -filter:retweets", null, null, null),
        )
    }

    @Test
    fun searchQueryWithoutQueryAndFiltersIsEmpty() {
        assertEquals("", TwitterParser.buildSearchQuery(null, null, null, null))
        assertEquals("", TwitterParser.buildSearchQuery(null, FeedSort.latest, emptySet(), null))
        // An empty (not null) query keeps the reply exclusion.
        assertEquals("-filter:replies", TwitterParser.buildSearchQuery("", null, emptySet(), null))
    }

    @Test
    fun searchQueryAddsMediaFilters() {
        assertEquals(
            "-filter:replies (filter:videos)",
            TwitterParser.buildSearchQuery(null, null, linkedSetOf(MediaFilter.video), null),
        )
        assertEquals(
            "from:a -filter:replies (filter:videos OR filter:images OR -filter:images -filter:videos)",
            TwitterParser.buildSearchQuery(
                "from:a", null, linkedSetOf(MediaFilter.video, MediaFilter.image, MediaFilter.text), null,
            ),
        )
    }

    @Test
    fun popularSortAddsMinFaves() {
        assertEquals(" min_faves:100", TwitterParser.buildSearchQuery(null, FeedSort.popular, null, null))
        assertEquals(
            "from:a -filter:replies min_faves:50",
            TwitterParser.buildSearchQuery("from:a", FeedSort.popular, null, 50),
        )
        assertEquals("from:a -filter:replies", TwitterParser.buildSearchQuery("from:a", FeedSort.trending, null, 50))
    }

    @Test
    fun subscriptionQueries() {
        val subs = listOf(Subscription("1", "alice", "A"), Subscription("2", "bob", "B"))
        assertEquals("from:alice OR from:bob", TwitterParser.buildUsersClause(subs))
        assertEquals(
            "(from:alice OR from:bob) -filter:replies -filter:retweets",
            TwitterParser.buildQueryFromUsersClause("from:alice OR from:bob", false),
        )
        assertEquals(
            "include:nativeretweets (from:alice OR from:bob) -filter:replies",
            TwitterParser.buildQueryFromUsersClause("from:alice OR from:bob", true),
        )
    }

    @Test
    fun chunkedQueriesCoverEveryAccountOnceWithinTheLengthLimit() {
        val subs = (1..60).map { Subscription("$it", "account_number_$it", "n") }
        val queries = TwitterParser.buildChunkedQueries(subs, false, 480, Random(7))
        assertTrue(queries.size > 1)
        val seen = mutableListOf<String>()
        for (query in queries) {
            assertTrue(query.length <= 480, "query too long: ${query.length}")
            assertTrue(query.startsWith("(from:"))
            assertTrue(query.endsWith(") -filter:replies -filter:retweets"))
            seen += Regex("from:(\\w+)").findAll(query).map { it.groupValues[1] }
        }
        assertEquals(subs.map { it.screenName }.sorted(), seen.sorted())
    }

    @Test
    fun chunkedQueriesEdgeCases() {
        assertTrue(TwitterParser.buildChunkedQueries(emptyList(), false, 480).isEmpty())
        // A single name longer than the limit still gets its own query.
        val long = Subscription("1", "x".repeat(40), "n")
        val other = Subscription("2", "y".repeat(40), "n")
        val queries = TwitterParser.buildChunkedQueries(listOf(long, other), true, 10, Random(1))
        assertEquals(2, queries.size)
        assertTrue(queries.all { it.startsWith("include:nativeretweets (from:") && !it.contains(" OR ") })
    }

    @Test
    fun timelineAddressesCarryTheirVariables() {
        val following = TwitterParser.followingUri("42", null)
        assertEquals("/i/api/graphql/FEcMGoVOUjm0aU9BJrrGZA/Following", following.path)
        assertEquals(
            "{\"userId\":\"42\",\"count\":100,\"includePromotedContent\":false,\"withGrokTranslatedBio\":false}",
            following.queryParameters["variables"],
        )
        assertEquals(listOf("variables", "features"), following.queryParameters.keys.toList())
        assertTrue(TwitterParser.followingUri("42", "C").queryParameters.getValue("variables").endsWith(",\"cursor\":\"C\"}"))

        val userTweets = TwitterParser.userTweetsUri("42", 20, null)
        assertEquals("/i/api/graphql/rIIwMe1ObkGh_ByBtTCtRQ/UserTweets", userTweets.path)
        assertEquals(
            "{\"userId\":\"42\",\"count\":20,\"includePromotedContent\":false," +
                "\"withQuickPromoteEligibilityTweetFields\":true,\"withVoice\":true,\"withV2Timeline\":true}",
            userTweets.queryParameters["variables"],
        )
        assertEquals("{\"withArticlePlainText\":false}", userTweets.queryParameters["fieldToggles"])

        val detail = TwitterParser.tweetDetailUri("77", "C")
        assertEquals("/i/api/graphql/tCivIG3o9ls-9cLxTsdxZQ/TweetDetail", detail.path)
        assertEquals(
            "{\"focalTweetId\":\"77\",\"with_rux_injections\":false,\"includePromotedContent\":true," +
                "\"withCommunity\":true,\"withQuickPromoteEligibilityTweetFields\":true," +
                "\"withBirdwatchNotes\":true,\"withVoice\":true,\"withV2Timeline\":true,\"cursor\":\"C\"}",
            detail.queryParameters["variables"],
        )

        val home = "{\"count\":20,\"includePromotedContent\":true,\"latestControlAvailable\":true,\"requestContext\":\"launch\"}"
        assertEquals(home, TwitterParser.videoMixerUri(20, null).queryParameters["variables"])
        assertEquals("/i/api/graphql/rAqW5uh6Unfi46lidxFwzA/MediaTabVideoMixer", TwitterParser.videoMixerUri(20, null).path)
        assertEquals("/i/api/graphql/Yf4WJo0fW46TnqrHUw_1Ow/HomeTimeline", TwitterParser.algorithmicTimelineUri(20, null).path)
        assertEquals(
            "/i/api/graphql/hlno2aLQsxiQlOrK-a2V-w/HomeLatestTimeline",
            TwitterParser.chronologicalTimelineUri(20, null).path,
        )
    }

    @Test
    fun profileAddresses() {
        assertEquals("name", TwitterParser.normalizeScreenName("@name"))
        assertEquals("name", TwitterParser.normalizeScreenName("name"))
        val variables = "{\"screen_name\":\"name\",\"withHighlightedLabel\":true," +
            "\"withSafetyModeUserFields\":true,\"withSuperFollowsUserFields\":true}"
        val newUri = TwitterParser.newProfileUri("name")
        assertEquals("/i/api/graphql/IGgvgiOx4QZndDHuD3x9TQ/UserByScreenName", newUri.path)
        assertEquals(variables, newUri.queryParameters["variables"])
        assertEquals("{\"withPayments\":true,\"withAuxiliaryUserLabels\":true}", newUri.queryParameters["fieldToggles"])
        val oldUri = TwitterParser.oldProfileUri("name")
        assertEquals("/i/api/graphql/oUZZZ8Oddwxs8Cd3iW3UEA/UserByScreenName", oldUri.path)
        assertEquals(variables, oldUri.queryParameters["variables"])
        assertFalse(oldUri.queryParameters.containsKey("fieldToggles"))
        assertEquals(TwitterFeatures.defaultFeatures.toString(), oldUri.queryParameters["features"])
    }

    @Test
    fun mutationBodies() {
        assertEquals("https://x.com/i/api/graphql/lI07N6Otwv1PhnEgXILM7A/FavoriteTweet",
            TwitterParser.mutationUri(TwitterFeatures.graphqlFavoriteTweetUriPath).toString())
        assertEquals(
            "{\"variables\":{\"tweet_id\":\"5\"},\"queryId\":\"lI07N6Otwv1PhnEgXILM7A\"}",
            TwitterParser.favoriteTweetBody("5"),
        )
        assertEquals(
            "{\"variables\":{\"tweet_id\":\"5\"},\"queryId\":\"ZYKSe-w7KEslx3JhSIk5LA\"}",
            TwitterParser.unfavoriteTweetBody("5"),
        )
        assertEquals(
            "{\"variables\":{\"source_tweet_id\":\"5\"},\"queryId\":\"mbRO74GrOvSfRcJnlMapnQ\"}",
            TwitterParser.retweetTweetBody("5"),
        )
        assertEquals(
            "{\"variables\":{\"source_tweet_id\":\"5\"},\"queryId\":\"ZyZigVsNiFO6v1dEks1eWg\"}",
            TwitterParser.unretweetTweetBody("5"),
        )
    }

    @Test
    fun postTweetBody() {
        val plain = TwitterParser.postTweetBody("hi \"there\"")
        assertTrue(
            plain.startsWith(
                "{\"variables\":{\"tweet_text\":\"hi \\\"there\\\"\",\"dark_request\":false," +
                    "\"media\":{\"media_entities\":[],\"possibly_sensitive\":false}}," +
                    "\"queryId\":\"hIL9XdleMYEtVXOZVbr8Bg\",\"features\":{" +
                    "\"communities_web_enable_tweet_community_results_fetch\":true,",
            ),
            plain,
        )
        assertTrue(plain.endsWith("\"responsive_web_enhance_cards_enabled\":false}}"))

        val reply = Json.parseToJsonElement(TwitterParser.postTweetBody("re", inReplyToTweetId = "88")) as JsonObject
        assertEquals(
            "{\"in_reply_to_tweet_id\":\"88\",\"exclude_reply_user_ids\":[]}",
            (reply["variables"] as JsonObject)["reply"].toString(),
        )
    }
}
