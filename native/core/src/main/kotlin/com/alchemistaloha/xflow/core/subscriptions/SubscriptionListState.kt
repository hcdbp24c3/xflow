package com.alchemistaloha.xflow.core.subscriptions

import com.alchemistaloha.xflow.core.model.Subscription
import java.util.Locale

/** Entries are capitalised because a Kotlin enum entry cannot be called `name`. */
enum class SubscriptionSort { Name, Handle, Followers, Views }

/** What the Subscriptions screen shows: the list plus the search / sort choices. */
data class SubscriptionListState(
    val allSubscriptions: List<Subscription> = emptyList(),
    /** Total plays per lower-cased handle (without "@"). */
    val userViews: Map<String, Int> = emptyMap(),
    val searchQuery: String = "",
    val sort: SubscriptionSort = SubscriptionSort.Name,
    val isAscending: Boolean = true,
    val isLoading: Boolean = true,
) {
    val filteredSubscriptions: List<Subscription>
        get() {
            var filtered: List<Subscription> = allSubscriptions.toList()
            if (searchQuery.isNotEmpty()) {
                val query = searchQuery.lowercase()
                filtered = filtered.filter { sub ->
                    sub.name.lowercase().contains(query) || sub.screenName.lowercase().contains(query)
                }
            }

            val multiplier = if (isAscending) 1 else -1

            val comparator: Comparator<Subscription> = when (sort) {
                SubscriptionSort.Name -> Comparator { a, b ->
                    a.name.lowercase().compareTo(b.name.lowercase()) * multiplier
                }
                SubscriptionSort.Handle -> Comparator { a, b ->
                    a.screenName.lowercase().compareTo(b.screenName.lowercase()) * multiplier
                }
                SubscriptionSort.Followers -> Comparator { a, b ->
                    (a.followersCount ?: 0).compareTo(b.followersCount ?: 0) * multiplier
                }
                SubscriptionSort.Views -> Comparator { a, b ->
                    val viewsA = userViews[a.screenName.lowercase()] ?: 0
                    val viewsB = userViews[b.screenName.lowercase()] ?: 0
                    viewsA.compareTo(viewsB) * multiplier
                }
            }
            return filtered.sortedWith(comparator)
        }

    fun isSubscribed(screenName: String): Boolean {
        val normalized = if (screenName.startsWith("@")) screenName.substring(1) else screenName
        val lower = normalized.lowercase()
        return allSubscriptions.any { it.screenName.lowercase() == lower }
    }

    fun copyWith(
        allSubscriptions: List<Subscription>? = null,
        userViews: Map<String, Int>? = null,
        searchQuery: String? = null,
        sort: SubscriptionSort? = null,
        isAscending: Boolean? = null,
        isLoading: Boolean? = null,
    ) = SubscriptionListState(
        allSubscriptions = allSubscriptions ?: this.allSubscriptions,
        userViews = userViews ?: this.userViews,
        searchQuery = searchQuery ?: this.searchQuery,
        sort = sort ?: this.sort,
        isAscending = isAscending ?: this.isAscending,
        isLoading = isLoading ?: this.isLoading,
    )

    /** The list after an optimistic follow / unfollow of [sub]. */
    fun withToggled(sub: Subscription): List<Subscription> {
        val current = allSubscriptions.toMutableList()
        if (isSubscribed(sub.screenName)) {
            current.removeAll { it.screenName.lowercase() == sub.screenName.lowercase() }
        } else {
            current.add(sub)
        }
        return current
    }
}

/** 1234 -> "1.2K", 2500000 -> "2.5M". */
fun formatSubscriptionCount(count: Int): String = when {
    count >= 1000000 -> String.format(Locale.US, "%.1f", count / 1000000.0) + "M"
    count >= 1000 -> String.format(Locale.US, "%.1f", count / 1000.0) + "K"
    else -> count.toString()
}

/** Second line of a subscription row: "@handle • 1.2K followers • 3 views". */
fun subscriptionSubtitle(sub: Subscription, views: Int): String {
    val followers = sub.followersCount
    return "@${sub.screenName}" +
        (if (followers != null) " • ${formatSubscriptionCount(followers)} followers" else "") +
        (if (views > 0) " • $views views" else "")
}
