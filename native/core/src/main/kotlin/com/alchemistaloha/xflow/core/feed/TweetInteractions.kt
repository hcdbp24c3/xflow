package com.alchemistaloha.xflow.core.feed

import com.alchemistaloha.xflow.core.model.FeedState
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.util.AppLogger
import kotlinx.coroutines.CancellationException

/** Like / retweet state changes and the optimistic update-then-revert flow around them. */
object TweetInteractions {
    /** The tweet after tapping like: flag flipped, count moved by one and never below zero. */
    fun applyLikeToggle(t: Tweet): Tweet {
        val liked = !t.isLiked
        val count = t.favoriteCount + (if (liked) 1 else -1)
        return t.copy(isLiked = liked, favoriteCount = if (count >= 0) count else 0)
    }

    /** The tweet after tapping retweet. */
    fun applyRetweetToggle(t: Tweet): Tweet {
        val retweeted = !t.isRetweeted
        val count = t.retweetCount + (if (retweeted) 1 else -1)
        return t.copy(isRetweeted = retweeted, retweetCount = if (count >= 0) count else 0)
    }

    /**
     * The freshest copy of [tweet]: the one in the main feed if it is there,
     * otherwise the locally updated copy, otherwise [tweet] itself.
     */
    fun resolveLiveTweet(feedTweets: List<Tweet>?, overrides: Map<String, Tweet>, tweet: Tweet): Tweet {
        if (feedTweets != null) {
            for (t in feedTweets) {
                if (t.id == tweet.id) return t
            }
        }
        return overrides[tweet.id] ?: tweet
    }
}

/**
 * Optimistic toggle for a tweet that lives either in the main feed or, for
 * hashtag and profile feeds, in the overrides map. Writes the new state at
 * once, sends the request, and writes the original back if it fails.
 */
class InteractionToggler(
    private val readFeed: () -> FeedState?,
    private val writeFeed: (FeedState) -> Unit,
    private val readOverride: (String) -> Tweet?,
    private val putOverride: (Tweet) -> Unit,
) {
    suspend fun toggle(
        tweetId: String,
        fallback: Tweet?,
        label: String,
        apply: (Tweet) -> Tweet,
        send: suspend (Tweet) -> Boolean,
    ) {
        val currentState = readFeed()
        val tweetIndex = currentState?.tweets?.indexOfFirst { it.id == tweetId } ?: -1
        val inFeed = currentState != null && tweetIndex != -1

        val original: Tweet = if (inFeed) {
            currentState!!.tweets[tweetIndex]
        } else {
            readOverride(tweetId) ?: fallback ?: return
        }
        val updated = apply(original)

        // Optimistic UI update
        fun write(value: Tweet) {
            val latest = readFeed()
            val idx = latest?.tweets?.indexOfFirst { it.id == tweetId } ?: -1
            if (latest != null && idx != -1) {
                val tweets = latest.tweets.toMutableList()
                tweets[idx] = value
                writeFeed(latest.copyWith(tweets = tweets))
            } else if (!inFeed) {
                putOverride(value)
            }
        }

        write(updated)

        val success = try {
            send(updated)
        } catch (e: CancellationException) {
            write(original)
            throw e
        } catch (e: Exception) {
            AppLogger.log("XFLOW: toggle $label for $tweetId threw: $e")
            false
        }

        if (!success) {
            write(original) // Revert on failure
            AppLogger.log("XFLOW: Failed to toggle $label for $tweetId, reverted.")
        } else {
            AppLogger.log("XFLOW: Successfully toggled $label for $tweetId")
        }
    }
}
