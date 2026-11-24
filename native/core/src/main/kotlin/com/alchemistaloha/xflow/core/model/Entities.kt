package com.alchemistaloha.xflow.core.model

/** A logged-in X account. [authHeader] is a JSON object of request headers. */
data class Account(
    val id: String,
    val screenName: String,
    val restId: String,
    val authHeader: String,
)

data class Subscription(
    val id: String,
    val screenName: String,
    val name: String,
    val profileImageUrl: String? = null,
    val description: String? = null,
    val followersCount: Int? = null,
    val followingCount: Int? = null,
) {
    val profileImageUrlHighRes: String?
        get() = profileImageUrl?.replace("_normal", "")
}
