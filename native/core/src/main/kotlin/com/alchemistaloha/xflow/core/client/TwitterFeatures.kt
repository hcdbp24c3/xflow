package com.alchemistaloha.xflow.core.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * GraphQL operation paths, feature flags and field toggles sent to X, copied
 * from the Flutter client (twitter_client.dart). Generated from the Dart
 * maps; keep ids, keys, values and key order as they are.
 */
object TwitterFeatures {
    const val graphqlSearchTimelineUriPath = "/graphql/hz_94eVAtrtQo_vO3my7Rw/SearchTimeline"
    const val legacyGraphqlSearchTimelineUriPath = "/graphql/SpDsqmz6FfYESd1e7TPcAw/SearchTimeline"
    const val graphqlUserByScreenNameUriPath = "/graphql/oUZZZ8Oddwxs8Cd3iW3UEA/UserByScreenName"
    const val graphqlUserTweetsUriPath = "/graphql/rIIwMe1ObkGh_ByBtTCtRQ/UserTweets"
    const val graphqlFavoriteTweetUriPath = "/graphql/lI07N6Otwv1PhnEgXILM7A/FavoriteTweet"
    const val graphqlUnfavoriteTweetUriPath = "/graphql/ZYKSe-w7KEslx3JhSIk5LA/UnfavoriteTweet"
    const val graphqlCreateRetweetUriPath = "/graphql/mbRO74GrOvSfRcJnlMapnQ/CreateRetweet"
    const val graphqlDeleteRetweetUriPath = "/graphql/ZyZigVsNiFO6v1dEks1eWg/DeleteRetweet"
    const val graphqlCreateTweetUriPath = "/graphql/hIL9XdleMYEtVXOZVbr8Bg/CreateTweet"
    const val graphqlTweetDetailUriPath = "/graphql/tCivIG3o9ls-9cLxTsdxZQ/TweetDetail"
    const val graphqlUserByScreenNameNewUriPath = "/graphql/IGgvgiOx4QZndDHuD3x9TQ/UserByScreenName"

    const val graphqlFollowingUriPath = "/graphql/FEcMGoVOUjm0aU9BJrrGZA/Following"
    const val graphqlUserByScreenNameFallbackUriPath = "/graphql/oUZZZ8Oddwxs8Cd3iW3UEA/UserByScreenName"
    const val graphqlMediaTabVideoMixerUriPath = "/graphql/rAqW5uh6Unfi46lidxFwzA/MediaTabVideoMixer"
    const val graphqlHomeTimelineUriPath = "/graphql/Yf4WJo0fW46TnqrHUw_1Ow/HomeTimeline"
    const val graphqlHomeLatestTimelineUriPath = "/graphql/hlno2aLQsxiQlOrK-a2V-w/HomeLatestTimeline"

    const val favoriteTweetQueryId = "lI07N6Otwv1PhnEgXILM7A"
    const val unfavoriteTweetQueryId = "ZYKSe-w7KEslx3JhSIk5LA"
    const val createRetweetQueryId = "mbRO74GrOvSfRcJnlMapnQ"
    const val deleteRetweetQueryId = "ZyZigVsNiFO6v1dEks1eWg"
    const val createTweetQueryId = "hIL9XdleMYEtVXOZVbr8Bg"

    private fun flags(vararg pairs: Pair<String, Boolean>): JsonObject {
        val map = LinkedHashMap<String, JsonPrimitive>()
        for ((key, value) in pairs) map[key] = JsonPrimitive(value)
        return JsonObject(map)
    }

    /** Dart `{...a, ...b}`: keys of [a] keep their position, values of [b] win. */
    fun merge(a: JsonObject, b: JsonObject): JsonObject {
        val map = LinkedHashMap(a)
        map.putAll(b)
        return JsonObject(map)
    }

    val defaultFeatures: JsonObject = flags(
        "android_ad_formats_media_component_render_overlay_enabled" to false,
        "android_graphql_skip_api_media_color_palette" to false,
        "android_professional_link_spotlight_display_enabled" to false,
        "articles_api_enabled" to false,
        "articles_preview_enabled" to true,
        "blue_business_profile_image_shape_enabled" to false,
        "c9s_tweet_anatomy_moderator_badge_enabled" to true,
        "commerce_android_shop_module_enabled" to false,
        "communities_web_enable_tweet_community_results_fetch" to true,
        "creator_subscriptions_quote_tweet_preview_enabled" to false,
        "creator_subscriptions_subscription_count_enabled" to false,
        "creator_subscriptions_tweet_preview_api_enabled" to true,
        "freedom_of_speech_not_reach_fetch_enabled" to true,
        "graphql_is_translatable_rweb_tweet_is_translatable_enabled" to true,
        "grok_android_analyze_trend_fetch_enabled" to false,
        "grok_translations_community_note_auto_translation_is_enabled" to false,
        "grok_translations_community_note_translation_is_enabled" to false,
        "grok_translations_post_auto_translation_is_enabled" to false,
        "grok_translations_timeline_user_bio_auto_translation_is_enabled" to false,
        "hidden_profile_likes_enabled" to false,
        "highlights_tweets_tab_ui_enabled" to false,
        "immersive_video_status_linkable_timestamps" to false,
        "interactive_text_enabled" to false,
        "longform_notetweets_consumption_enabled" to true,
        "longform_notetweets_inline_media_enabled" to true,
        "longform_notetweets_richtext_consumption_enabled" to true,
        "longform_notetweets_rich_text_read_enabled" to true,
        "mobile_app_spotlight_module_enabled" to false,
        "payments_enabled" to false,
        "post_ctas_fetch_enabled" to true,
        "premium_content_api_read_enabled" to false,
        "profile_label_improvements_pcf_label_in_post_enabled" to true,
        "profile_label_improvements_pcf_label_in_profile_enabled" to false,
        "responsive_web_edit_tweet_api_enabled" to true,
        "responsive_web_enhance_cards_enabled" to false,
        "responsive_web_graphql_exclude_directive_enabled" to true,
        "responsive_web_graphql_skip_user_profile_image_extensions_enabled" to false,
        "responsive_web_graphql_timeline_navigation_enabled" to true,
        "responsive_web_grok_analysis_button_from_backend" to true,
        "responsive_web_grok_analyze_button_fetch_trends_enabled" to false,
        "responsive_web_grok_analyze_post_followups_enabled" to true,
        "responsive_web_grok_annotations_enabled" to true,
        "responsive_web_grok_image_annotation_enabled" to true,
        "responsive_web_grok_imagine_annotation_enabled" to true,
        "responsive_web_grok_share_attachment_enabled" to true,
        "responsive_web_grok_show_grok_translated_post" to false,
        "responsive_web_jetfuel_frame" to true,
        "responsive_web_media_download_video_enabled" to false,
        "responsive_web_profile_redirect_enabled" to false,
        "responsive_web_text_conversations_enabled" to false,
        "responsive_web_twitter_article_notes_tab_enabled" to false,
        "responsive_web_twitter_article_tweet_consumption_enabled" to true,
        "responsive_web_twitter_blue_verified_badge_is_enabled" to true,
        "rweb_lists_timeline_redesign_enabled" to true,
        "rweb_tipjar_consumption_enabled" to true,
        "rweb_video_screen_enabled" to false,
        "rweb_video_timestamps_enabled" to false,
        "spaces_2022_h2_clipping" to true,
        "spaces_2022_h2_spaces_communities" to true,
        "standardized_nudges_misinfo" to true,
        "subscriptions_feature_can_gift_premium" to false,
        "subscriptions_verification_info_enabled" to true,
        "subscriptions_verification_info_is_identity_verified_enabled" to false,
        "subscriptions_verification_info_reason_enabled" to true,
        "subscriptions_verification_info_verified_since_enabled" to true,
        "super_follow_badge_privacy_enabled" to false,
        "super_follow_exclusive_tweet_notifications_enabled" to false,
        "super_follow_tweet_api_enabled" to false,
        "super_follow_user_api_enabled" to false,
        "tweet_awards_web_tipping_enabled" to false,
        "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled" to true,
        "tweetypie_unmention_optimization_enabled" to false,
        "unified_cards_ad_metadata_container_dynamic_card_content_query_enabled" to false,
        "unified_cards_destination_url_params_enabled" to false,
        "verified_phone_label_enabled" to false,
        "vibe_api_enabled" to false,
        "view_counts_everywhere_api_enabled" to true,
        "hidden_profile_subscriptions_enabled" to false,
    )

    val followingFeatures: JsonObject = defaultFeatures

    val searchTimelineFeatures: JsonObject = flags(
        "rweb_video_screen_enabled" to false,
        "rweb_cashtags_enabled" to true,
        "profile_label_improvements_pcf_label_in_post_enabled" to true,
        "responsive_web_profile_redirect_enabled" to false,
        "rweb_tipjar_consumption_enabled" to false,
        "verified_phone_label_enabled" to false,
        "creator_subscriptions_tweet_preview_api_enabled" to true,
        "responsive_web_graphql_timeline_navigation_enabled" to true,
        "responsive_web_graphql_skip_user_profile_image_extensions_enabled" to false,
        "premium_content_api_read_enabled" to false,
        "communities_web_enable_tweet_community_results_fetch" to true,
        "c9s_tweet_anatomy_moderator_badge_enabled" to true,
        "responsive_web_grok_analyze_button_fetch_trends_enabled" to false,
        "responsive_web_grok_analyze_post_followups_enabled" to false,
        "responsive_web_jetfuel_frame" to true,
        "responsive_web_grok_share_attachment_enabled" to true,
        "responsive_web_grok_annotations_enabled" to true,
        "articles_preview_enabled" to true,
        "responsive_web_edit_tweet_api_enabled" to true,
        "graphql_is_translatable_rweb_tweet_is_translatable_enabled" to true,
        "view_counts_everywhere_api_enabled" to true,
        "longform_notetweets_consumption_enabled" to true,
        "responsive_web_twitter_article_tweet_consumption_enabled" to true,
        "content_disclosure_indicator_enabled" to true,
        "content_disclosure_ai_generated_indicator_enabled" to true,
        "responsive_web_grok_show_grok_translated_post" to true,
        "responsive_web_grok_analysis_button_from_backend" to true,
        "post_ctas_fetch_enabled" to false,
        "freedom_of_speech_not_reach_fetch_enabled" to true,
        "standardized_nudges_misinfo" to true,
        "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled" to true,
        "longform_notetweets_rich_text_read_enabled" to true,
        "longform_notetweets_inline_media_enabled" to false,
        "responsive_web_grok_image_annotation_enabled" to true,
        "responsive_web_grok_imagine_annotation_enabled" to true,
        "responsive_web_grok_community_note_auto_translation_is_enabled" to true,
        "responsive_web_enhance_cards_enabled" to false,
    )

    val searchTimelineFieldToggles: JsonObject = flags(
        "withArticleRichContentState" to true,
        "withArticlePlainText" to false,
        "withGrokAnalyze" to false,
        "withDisallowedReplyControls" to false,
    )

    val newProfileFeatures: JsonObject = flags(
        "hidden_profile_subscriptions_enabled" to true,
        "profile_label_improvements_pcf_label_in_post_enabled" to true,
        "responsive_web_profile_redirect_enabled" to false,
        "rweb_tipjar_consumption_enabled" to false,
        "verified_phone_label_enabled" to false,
        "subscriptions_verification_info_is_identity_verified_enabled" to true,
        "subscriptions_verification_info_verified_since_enabled" to true,
        "highlights_tweets_tab_ui_enabled" to true,
        "responsive_web_twitter_article_notes_tab_enabled" to true,
        "subscriptions_feature_can_gift_premium" to true,
        "creator_subscriptions_tweet_preview_api_enabled" to true,
        "responsive_web_graphql_skip_user_profile_image_extensions_enabled" to false,
        "responsive_web_graphql_timeline_navigation_enabled" to true,
    )

    val newProfileFieldToggles: JsonObject = flags(
        "withPayments" to true,
        "withAuxiliaryUserLabels" to true,
    )

    /** The feature map `postTweet` sends inline. */
    val createTweetFeatures: JsonObject = flags(
        "communities_web_enable_tweet_community_results_fetch" to true,
        "c9s_tweet_anatomy_moderator_badge_enabled" to true,
        "responsive_web_edit_tweet_api_enabled" to true,
        "graphql_is_translatable_rweb_tweet_is_translatable_enabled" to true,
        "view_counts_everywhere_api_enabled" to true,
        "longform_notetweets_consumption_enabled" to true,
        "responsive_web_twitter_article_tweet_consumption_enabled" to true,
        "tweet_awards_web_tipping_enabled" to false,
        "creator_subscriptions_quote_tweet_preview_enabled" to false,
        "longform_notetweets_rich_text_read_enabled" to true,
        "longform_notetweets_inline_media_enabled" to true,
        "articles_preview_enabled" to true,
        "rweb_video_timestamps_enabled" to true,
        "rweb_tipjar_consumption_enabled" to true,
        "responsive_web_graphql_exclude_directive_enabled" to true,
        "verified_phone_label_enabled" to false,
        "freedom_of_speech_not_reach_fetch_enabled" to true,
        "standardized_nudges_misinfo" to true,
        "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled" to true,
        "responsive_web_graphql_skip_user_profile_image_extensions_enabled" to false,
        "responsive_web_graphql_timeline_navigation_enabled" to true,
        "responsive_web_enhance_cards_enabled" to false,
    )

}
