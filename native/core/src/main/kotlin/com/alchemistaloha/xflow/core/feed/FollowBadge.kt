package com.alchemistaloha.xflow.core.feed

/** What the small badge on the author's avatar in a feed shows. */
enum class FollowBadge {
    /** Not followed: a "+" that follows on tap. */
    Plus,

    /** Followed a moment ago from this badge: a tick that then fades away. */
    Confirmed,

    /** Followed already (or nothing to follow): no badge. */
    Hidden,
}

object FollowBadgeLogic {
    /**
     * [isSubscribed] is the stored state, [followedHere] says the user tapped this badge while
     * it showed "+". A follow that was undone (the store rolled it back) shows "+" again.
     */
    fun badgeFor(isSubscribed: Boolean, followedHere: Boolean, isOwnAccount: Boolean = false): FollowBadge = when {
        isOwnAccount -> FollowBadge.Hidden
        !isSubscribed -> FollowBadge.Plus
        followedHere -> FollowBadge.Confirmed
        else -> FollowBadge.Hidden
    }

    /** True when [handle] ("@name" or "name") is the signed-in account [ownScreenName]. */
    fun isOwnAccount(handle: String, ownScreenName: String?): Boolean {
        if (ownScreenName.isNullOrBlank()) return false
        return handle.removePrefix("@").equals(ownScreenName.removePrefix("@"), ignoreCase = true)
    }
}
