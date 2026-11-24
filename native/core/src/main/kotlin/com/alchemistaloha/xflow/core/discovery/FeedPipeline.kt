package com.alchemistaloha.xflow.core.discovery

import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.model.Tweet
import kotlin.random.Random

/** The pure part of the main feed: what goes where when fresh and cached tweets are merged. */
object FeedPipeline {
    /**
     * How many leading items a background refresh must leave untouched: the
     * item being watched plus the next one, and never fewer than two.
     */
    fun protectedHeadLength(viewIndex: Int, currentLength: Int, resetHead: Boolean): Int {
        if (resetHead) return 0
        val wanted = if (viewIndex + 2 < 2) 2 else viewIndex + 2
        return if (wanted > currentLength) currentLength else wanted
    }

    /** The last [window] tweets: the ones whose ids/media must not be shown again. */
    fun dedupeWindow(tweets: List<Tweet>, window: Int): List<Tweet> =
        if (tweets.size > window) tweets.subList(tweets.size - window, tweets.size) else tweets

    /** First media URL of every tweet that has one. */
    fun firstMediaUrls(tweets: List<Tweet>): MutableSet<String> {
        val out = LinkedHashSet<String>()
        for (t in tweets) if (t.mediaUrls.isNotEmpty()) out.add(t.mediaUrls.first())
        return out
    }

    /**
     * Dedupes both pools against what is already on screen, mixes them by the
     * fresh ratio, then applies the unseen-subscription boost and saturation.
     * The first [protectedIndex] items of [currentTweets] keep their place.
     */
    fun runDiscoveryPipeline(
        freshPool: List<Tweet>,
        localPool: List<Tweet>,
        settings: SettingsState,
        playedByUser: Map<String, Int>,
        protectedIndex: Int = 0,
        currentTweets: List<Tweet> = emptyList(),
        random: Random = Random.Default,
    ): List<Tweet> {
        val head = currentTweets.take(protectedIndex)
        val headIds = head.map { it.id }.toSet()
        val headMedia = firstMediaUrls(head)

        // Initialize seen sets using a sliding window of already visible tweets
        val dedupeWindow = dedupeWindow(currentTweets, settings.mediaDeduplicationWindow)

        val seenIds = HashSet<String>(headIds).apply { addAll(dedupeWindow.map { it.id }) }
        val seenMediaUrls = HashSet<String>(headMedia).apply { addAll(firstMediaUrls(dedupeWindow)) }

        fun deduplicate(pool: List<Tweet>): List<Tweet> = pool.filter { t ->
            if (seenIds.contains(t.id)) return@filter false
            if (t.mediaUrls.isNotEmpty() && seenMediaUrls.contains(t.mediaUrls.first())) {
                return@filter false
            }
            seenIds.add(t.id)
            if (t.mediaUrls.isNotEmpty()) seenMediaUrls.add(t.mediaUrls.first())
            true
        }

        val uniqueFresh = deduplicate(freshPool).shuffled(random)
        val uniqueLocal = deduplicate(localPool).shuffled(random)

        val interleaved = DiscoveryEngine.interleave(uniqueFresh, uniqueLocal, settings.freshMixRatio)

        var processed: List<Tweet> = head + interleaved

        if (settings.unseenSubscriptionBoost) {
            processed = DiscoveryEngine.applyUnseenSubscriptionBoost(
                processed,
                playedByUser,
                lookahead = settings.unseenBoostLookahead,
                startIndex = head.size,
            )
        }

        processed = DiscoveryEngine.applySaturation(
            processed,
            threshold = settings.saturationThreshold,
            mediaThreshold = settings.mediaSaturationThreshold,
            windowSize = settings.saturationWindow,
            startIndex = head.size,
            maxSaturationSwaps = settings.maxSaturationSwaps,
            maxPasses = settings.maxSaturationPasses,
        )

        return processed
    }

    /** `fetchMore`: candidates whose id and first media URL have not been shown. */
    fun filterUnseen(candidates: List<Tweet>, seenIds: Set<String>, seenMedia: Set<String>): List<Tweet> =
        candidates.filter { t ->
            !seenIds.contains(t.id) &&
                (t.mediaUrls.isEmpty() || !seenMedia.contains(t.mediaUrls.first()))
        }

    /**
     * `fetchMore`: shuffles the collected candidates, keeps one batch, tags
     * untagged ones as "Mixed", appends them and applies saturation to the new tail.
     * Returns the combined list and how many tweets were added.
     */
    fun appendNewTweets(
        currentTweets: List<Tweet>,
        allNewTweets: List<Tweet>,
        settings: SettingsState,
        random: Random = Random.Default,
    ): Pair<List<Tweet>, Int> {
        // Shuffle candidates BEFORE taking the batch to improve diversity
        val finalNewTweets = allNewTweets.shuffled(random)
            .take(settings.loadBatchSize)
            .map { t -> t.copy(source = t.source ?: "Mixed") }

        var combined: List<Tweet> = currentTweets + finalNewTweets

        // Apply diversity enforcement to the new tail
        combined = DiscoveryEngine.applySaturation(
            combined,
            threshold = settings.saturationThreshold,
            mediaThreshold = settings.mediaSaturationThreshold,
            windowSize = settings.saturationWindow,
            startIndex = currentTweets.size,
            maxSaturationSwaps = settings.maxSaturationSwaps,
            maxPasses = settings.maxSaturationPasses,
        )
        return combined to finalNewTweets.size
    }
}
