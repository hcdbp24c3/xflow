package com.alchemistaloha.xflow.core.discovery

import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.util.AppLogger
import kotlin.math.floor

/**
 * The core intelligence of XFlow's feed.
 *
 * Handles interleaving fresh/cached content, promoting new accounts,
 * and enforcing diversity via saturation thresholds for handles and media.
 */
object DiscoveryEngine {
    /**
     * Interleaves fresh API items and cached items based on a ratio (0.0 to 1.0).
     *
     * [ratio] represents the target percentage of fresh items in the result.
     * Logic ensures that if we have a 0.3 ratio, roughly 3 out of every 10 items
     * are from the [fresh] pool, while maintaining relative order within each pool.
     */
    fun interleave(fresh: List<Tweet>, cached: List<Tweet>, ratio: Double): List<Tweet> {
        AppLogger.log(
            "Discovery: Interleaving ${fresh.size} fresh and ${cached.size} cached items with ratio $ratio"
        )
        val result = ArrayList<Tweet>()
        var freshIdx = 0
        var cacheIdx = 0

        var currentFreshCount = 0

        while (freshIdx < fresh.size || cacheIdx < cached.size) {
            val nextCount = result.size + 1
            val targetFreshCount = floor(nextCount * ratio).toInt()

            // Determine if the next slot should be fresh based on the target ratio
            val shouldPickFresh = freshIdx < fresh.size &&
                (currentFreshCount < targetFreshCount || cacheIdx >= cached.size)

            if (shouldPickFresh) {
                val item = fresh[freshIdx++]
                result.add(item)
                currentFreshCount++
            } else if (cacheIdx < cached.size) {
                result.add(cached[cacheIdx++])
            } else {
                // Fallback for trailing items
                if (freshIdx < fresh.size) {
                    result.add(fresh[freshIdx++])
                    currentFreshCount++
                } else {
                    break
                }
            }
        }
        AppLogger.log("Discovery: Interleaving complete. Total: ${result.size}")
        return result
    }

    /** Ensures handles are compared consistently regardless of '@' prefix or case. */
    private fun normalizeHandle(handle: String): String {
        val trimmed = handle.trim()
        if (trimmed.startsWith("@")) {
            return trimmed.substring(1).lowercase()
        }
        return trimmed.lowercase()
    }

    /**
     * Promotes tweets from accounts the user has interacted with the least.
     *
     * [playedCountByUser] maps handles to total views.
     * [lookahead] defines how far down the list we search for a "better" candidate
     * to swap into the current position.
     *
     * Should be run BEFORE saturation so saturation can "fix" any clumps
     * created by the boost.
     */
    fun applyUnseenSubscriptionBoost(
        tweets: List<Tweet>,
        playedCountByUser: Map<String, Int>,
        lookahead: Int = 6,
        startIndex: Int = 0,
    ): List<Tweet> {
        if (tweets.size < 2 || playedCountByUser.isEmpty()) return tweets

        val result = ArrayList(tweets)
        var boosts = 0

        for (i in startIndex until result.size - 1) {
            val end = (i + lookahead).coerceIn(i + 1, result.size)
            var bestIdx = i
            var bestScore = playedCountByUser[normalizeHandle(result[i].userHandle)] ?: 0

            val prevHandle = if (i > 0) normalizeHandle(result[i - 1].userHandle) else null

            // Find the account in the lookahead window with the lowest view count
            for (j in i + 1 until end) {
                val candHandle = normalizeHandle(result[j].userHandle)

                // Safety: Don't pull an item up if it would create a consecutive duplicate
                if (candHandle == prevHandle) continue

                val candScore = playedCountByUser[candHandle] ?: 0
                if (candScore < bestScore) {
                    bestScore = candScore
                    bestIdx = j
                }
            }

            // Swap the best candidate into the current position
            if (bestIdx != i) {
                val temp = result[i]
                result[i] = result[bestIdx]
                result[bestIdx] = temp
                boosts++
            }
        }

        if (boosts > 0) {
            AppLogger.log(
                "Discovery: Applied $boosts unseen subscription boosts (Lookahead: $lookahead, StartIndex: $startIndex)"
            )
        }
        return result
    }

    /**
     * Enforces feed diversity by separating clumps of the same user or media.
     *
     * [threshold] - Max times a handle can appear in [windowSize].
     * [mediaThreshold] - Max times a specific media URL can appear in [windowSize].
     * [maxPasses] - Enables multiple sweeps of the list. Essential because
     * a swap made to fix index 5 might create a new clump at index 8.
     *
     * The algorithm lookahead search ensures that the item we pull UP to break
     * a clump is itself a "valid" fit for the new position.
     */
    fun applySaturation(
        tweets: List<Tweet>,
        threshold: Int = 2,
        mediaThreshold: Int = 1,
        windowSize: Int = 10,
        startIndex: Int = 0,
        maxSaturationSwaps: Int = 1000,
        maxPasses: Int = 3,
    ): List<Tweet> {
        if (tweets.isEmpty()) return tweets
        val result = ArrayList(tweets)

        var totalSwaps = 0

        // Multi-pass sweep: Subsequent passes resolve clumps created by previous swaps.
        for (pass in 0 until maxPasses) {
            var passSwaps = 0
            var i = startIndex
            while (i < result.size && totalSwaps < maxSaturationSwaps) {
                val handle = normalizeHandle(result[i].userHandle)
                val mediaUrl = result[i].mediaUrls.firstOrNull()

                // Define the sliding window of items preceding the current index
                val start = (i - windowSize).coerceIn(0, result.size)
                val window = result.subList(start, i)

                // Count occurrences of current item's identity in the preceding window
                val handleCount = window.count { normalizeHandle(it.userHandle) == handle }
                val mediaCount = if (mediaUrl != null) {
                    window.count { it.mediaUrls.isNotEmpty() && it.mediaUrls.first() == mediaUrl }
                } else {
                    0
                }

                // Hard rule: No consecutive duplicates (even if threshold > 1)
                val isConsecutive = i > 0 && normalizeHandle(result[i - 1].userHandle) == handle
                val isMediaConsecutive = i > 0 &&
                    mediaUrl != null &&
                    result[i - 1].mediaUrls.isNotEmpty() &&
                    result[i - 1].mediaUrls.first() == mediaUrl

                // If any diversity rule is violated, search forward for a valid swap candidate
                if (handleCount >= threshold ||
                    isConsecutive ||
                    mediaCount >= mediaThreshold ||
                    isMediaConsecutive
                ) {
                    var swapIdx = -1

                    // First pass: lookahead search for a perfect candidate
                    val lookahead = windowSize + 10
                    var j = i + 1
                    while (j < result.size && j < i + lookahead) {
                        if (isValidSwap(result, i, j, threshold, mediaThreshold, windowSize)) {
                            swapIdx = j
                            break
                        }
                        j++
                    }

                    // Second pass: if no perfect candidate in lookahead, search the entire remaining list
                    if (swapIdx == -1) {
                        j = i + 1
                        while (j < result.size) {
                            if (isValidSwap(result, i, j, threshold, mediaThreshold, windowSize)) {
                                swapIdx = j
                                break
                            }
                            j++
                        }
                    }

                    if (swapIdx != -1) {
                        val temp = result[i]
                        result[i] = result[swapIdx]
                        result[swapIdx] = temp
                        passSwaps++
                        totalSwaps++
                    }
                }
                i++
            }
            // If a full pass resulted in zero swaps, the list is perfectly diverse.
            if (passSwaps == 0) break
        }

        if (totalSwaps > 0) {
            AppLogger.log(
                "Discovery: Applied $totalSwaps saturation swaps (Threshold: $threshold, MediaThreshold: $mediaThreshold, StartIndex: $startIndex)"
            )
        }
        return result
    }

    private fun isValidSwap(
        result: List<Tweet>,
        i: Int,
        j: Int,
        threshold: Int,
        mediaThreshold: Int,
        windowSize: Int,
    ): Boolean {
        val candHandle = normalizeHandle(result[j].userHandle)
        val candMedia = result[j].mediaUrls.firstOrNull()
        val prevHandle = if (i > 0) normalizeHandle(result[i - 1].userHandle) else null
        val prevMedia = if (i > 0) result[i - 1].mediaUrls.firstOrNull() else null

        // Must not create a consecutive duplicate
        if (candHandle == prevHandle) return false
        if (candMedia != null && candMedia == prevMedia) return false

        // Must not violate saturation rules in its new window at position i
        val candStart = (i - windowSize).coerceIn(0, result.size)
        val candWindow = result.subList(candStart, i)

        val candHandleCount = candWindow.count { normalizeHandle(it.userHandle) == candHandle }
        val candMediaCount = if (candMedia != null) {
            candWindow.count { it.mediaUrls.isNotEmpty() && it.mediaUrls.first() == candMedia }
        } else {
            0
        }

        return candHandleCount < threshold && candMediaCount < mediaThreshold
    }
}
