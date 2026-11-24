package com.alchemistaloha.xflow.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of test/core/client/twitter_client_api_doc_test.dart. The Dart test
 * expects the id R0u1RWRf748KzyGBXvOYRA, which the code no longer uses (the
 * Dart test fails on master). The intent is kept: the operation ids sent to X
 * are pinned here, so a change to them is a deliberate one.
 */
class TwitterClientApiDocTest {
    @Test
    fun usesCurrentSearchTimelineOperationId() {
        assertEquals(
            "/graphql/hz_94eVAtrtQo_vO3my7Rw/SearchTimeline",
            TwitterFeatures.graphqlSearchTimelineUriPath,
        )
        assertEquals(
            "/graphql/SpDsqmz6FfYESd1e7TPcAw/SearchTimeline",
            TwitterFeatures.legacyGraphqlSearchTimelineUriPath,
        )
    }

    @Test
    fun pinsTheOtherOperationIds() {
        assertEquals("/graphql/oUZZZ8Oddwxs8Cd3iW3UEA/UserByScreenName", TwitterFeatures.graphqlUserByScreenNameUriPath)
        assertEquals("/graphql/IGgvgiOx4QZndDHuD3x9TQ/UserByScreenName", TwitterFeatures.graphqlUserByScreenNameNewUriPath)
        assertEquals("/graphql/rIIwMe1ObkGh_ByBtTCtRQ/UserTweets", TwitterFeatures.graphqlUserTweetsUriPath)
        assertEquals("/graphql/lI07N6Otwv1PhnEgXILM7A/FavoriteTweet", TwitterFeatures.graphqlFavoriteTweetUriPath)
        assertEquals("/graphql/ZYKSe-w7KEslx3JhSIk5LA/UnfavoriteTweet", TwitterFeatures.graphqlUnfavoriteTweetUriPath)
        assertEquals("/graphql/mbRO74GrOvSfRcJnlMapnQ/CreateRetweet", TwitterFeatures.graphqlCreateRetweetUriPath)
        assertEquals("/graphql/ZyZigVsNiFO6v1dEks1eWg/DeleteRetweet", TwitterFeatures.graphqlDeleteRetweetUriPath)
        assertEquals("/graphql/hIL9XdleMYEtVXOZVbr8Bg/CreateTweet", TwitterFeatures.graphqlCreateTweetUriPath)
        assertEquals("/graphql/tCivIG3o9ls-9cLxTsdxZQ/TweetDetail", TwitterFeatures.graphqlTweetDetailUriPath)
        assertEquals("/graphql/FEcMGoVOUjm0aU9BJrrGZA/Following", TwitterFeatures.graphqlFollowingUriPath)
        assertEquals("/graphql/rAqW5uh6Unfi46lidxFwzA/MediaTabVideoMixer", TwitterFeatures.graphqlMediaTabVideoMixerUriPath)
        assertEquals("/graphql/Yf4WJo0fW46TnqrHUw_1Ow/HomeTimeline", TwitterFeatures.graphqlHomeTimelineUriPath)
        assertEquals("/graphql/hlno2aLQsxiQlOrK-a2V-w/HomeLatestTimeline", TwitterFeatures.graphqlHomeLatestTimelineUriPath)
    }

    @Test
    fun queryIdsMatchTheirPaths() {
        assertTrue(TwitterFeatures.graphqlFavoriteTweetUriPath.contains(TwitterFeatures.favoriteTweetQueryId))
        assertTrue(TwitterFeatures.graphqlUnfavoriteTweetUriPath.contains(TwitterFeatures.unfavoriteTweetQueryId))
        assertTrue(TwitterFeatures.graphqlCreateRetweetUriPath.contains(TwitterFeatures.createRetweetQueryId))
        assertTrue(TwitterFeatures.graphqlDeleteRetweetUriPath.contains(TwitterFeatures.deleteRetweetQueryId))
        assertTrue(TwitterFeatures.graphqlCreateTweetUriPath.contains(TwitterFeatures.createTweetQueryId))
    }

    @Test
    fun featureMapsKeepTheirSize() {
        assertEquals(78, TwitterFeatures.defaultFeatures.size)
        assertEquals(37, TwitterFeatures.searchTimelineFeatures.size)
        assertEquals(4, TwitterFeatures.searchTimelineFieldToggles.size)
        assertEquals(13, TwitterFeatures.newProfileFeatures.size)
        assertEquals(2, TwitterFeatures.newProfileFieldToggles.size)
        assertEquals(22, TwitterFeatures.createTweetFeatures.size)
        assertEquals(TwitterFeatures.defaultFeatures, TwitterFeatures.followingFeatures)
        // The profile request merges both maps: shared keys keep their place, new values win.
        val merged = TwitterFeatures.merge(TwitterFeatures.defaultFeatures, TwitterFeatures.newProfileFeatures)
        assertEquals("true", merged["hidden_profile_subscriptions_enabled"].toString())
        assertEquals("false", TwitterFeatures.defaultFeatures["hidden_profile_subscriptions_enabled"].toString())
        assertEquals(TwitterFeatures.defaultFeatures.keys.first(), merged.keys.first())
    }
}
