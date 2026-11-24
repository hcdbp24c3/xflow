package com.alchemistaloha.xflow.core.model

/** One post as the app shows it. [createdAtMillis] is epoch milliseconds. */
data class Tweet(
    val id: String,
    val text: String,
    val userHandle: String,
    val userAvatarUrl: String? = null,
    val mediaKey: String? = null,
    val mediaUrls: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
    val isVideo: Boolean = false,
    val createdAtMillis: Long? = null,
    /** "API", "Cache" or other metadata about where the item came from. */
    val source: String? = null,
    val isLiked: Boolean = false,
    val favoriteCount: Int = 0,
    val replyCount: Int = 0,
    val retweetCount: Int = 0,
    val isRetweeted: Boolean = false,
    val viewCount: Int = 0,
    val isBookmarked: Boolean = false,
) {
    val userAvatarUrlHighRes: String?
        get() = userAvatarUrl?.replace("_normal", "")
}

data class TweetResponse(
    val tweets: List<Tweet>,
    val cursorTop: String? = null,
    val cursorBottom: String? = null,
    /** How the request that produced this went; null for responses built by the parser alone. */
    val outcome: com.alchemistaloha.xflow.core.client.FetchOutcome? = null,
)
