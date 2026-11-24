package com.alchemistaloha.xflow.core.client

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.Tweet

/**
 * What a search for `(from:a OR from:b …)` is replaced with when X refuses
 * SearchTimeline: the timelines of those accounts, a few accounts per page.
 *
 * Differences from the search, which cannot be avoided:
 *  - a page covers [ACCOUNTS_PER_PAGE] accounts instead of the whole query,
 *    and "newest first" holds inside a page, not across pages;
 *  - only what the first page of each account's timeline holds is seen
 *    (about 20 posts per account);
 *  - a search without `from:` (a hashtag, trending) has no replacement.
 * Replies, reposts, the media filters and the minimum like count are
 * applied here the way the query asked for them.
 */
object AccountTimelineFallback {
    const val ACCOUNTS_PER_PAGE = 4
    private const val CURSOR_PREFIX = "accounts:"
    private val from = Regex("""(?<![A-Za-z0-9_])from:@?([A-Za-z0-9_]{1,20})""")
    private val minFaves = Regex("""min_faves:(\d+)""")

    /** What the query asks for, read back from its text. */
    data class Plan(
        val screenNames: List<String>,
        val skipReplies: Boolean,
        val skipRetweets: Boolean,
        val minFaves: Int?,
    )

    /** The accounts and switches of [query], or null when it names no account. */
    fun plan(query: String): Plan? {
        val names = from.findAll(query).map { it.groupValues[1] }.distinctBy { it.lowercase() }.toList()
        if (names.isEmpty()) return null
        return Plan(
            screenNames = names,
            skipReplies = query.contains("-filter:replies"),
            // A search leaves reposts out unless it asks for them.
            skipRetweets = !query.contains("include:nativeretweets"),
            minFaves = minFaves.find(query)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    fun isCursor(cursor: String?): Boolean = cursor != null && cursor.startsWith(CURSOR_PREFIX)

    /** Where in the account list the page for [cursor] starts. */
    fun offset(cursor: String?): Int =
        if (isCursor(cursor)) cursor!!.substring(CURSOR_PREFIX.length).toIntOrNull()?.coerceAtLeast(0) ?: 0 else 0

    /** The accounts of the page starting at [offset]. */
    fun page(screenNames: List<String>, offset: Int): List<String> =
        screenNames.drop(offset).take(ACCOUNTS_PER_PAGE)

    /** The cursor of the page after the one at [offset], or null when that was the last. */
    fun nextCursor(screenNames: List<String>, offset: Int): String? {
        val next = offset + ACCOUNTS_PER_PAGE
        return if (next < screenNames.size) "$CURSOR_PREFIX$next" else null
    }

    /** One list out of the accounts' posts: no duplicates, filtered, ordered as [sort] asks. */
    fun merge(
        timelines: List<List<Tweet>>,
        sort: FeedSort?,
        filters: Set<MediaFilter>?,
        minFaves: Int?,
    ): List<Tweet> {
        val seen = HashSet<String>()
        var tweets = timelines.flatten().filter { seen.add(it.id) }
        if (filters != null && filters.isNotEmpty()) tweets = TwitterParser.applyFilters(tweets, filters)
        if (minFaves != null) tweets = tweets.filter { it.favoriteCount >= minFaves }
        return if (sort == FeedSort.popular || sort == FeedSort.trending) {
            tweets.sortedByDescending { it.favoriteCount }
        } else {
            tweets.sortedByDescending { it.createdAtMillis ?: 0L }
        }
    }
}
