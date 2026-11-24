package com.alchemistaloha.xflow.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.data.feed.FeedStore
import com.alchemistaloha.xflow.data.feed.currentTweet
import com.alchemistaloha.xflow.data.feed.liveTweetFlow
import kotlinx.coroutines.launch

// The feed widgets' one door to the stores, so every feed (main, hashtag, profile, bookmarks)
// likes and retweets the same way.

/** [tweet] with the latest like / retweet state applied; recomposes when that changes. */
@Composable
internal fun rememberLiveTweet(tweet: Tweet): Tweet {
    val flow = remember(tweet) { liveTweetFlow(tweet) }
    val live by flow.collectAsState(initial = currentTweet(tweet))
    return live
}

internal fun liveTweetNow(tweet: Tweet): Tweet = currentTweet(tweet)

internal fun toggleLike(tweet: Tweet) {
    AppGraph.scope.launch { FeedStore.toggleLike(tweet.id, tweet = tweet) }
}

internal fun toggleRetweet(tweet: Tweet) {
    AppGraph.scope.launch { FeedStore.toggleRetweet(tweet.id, tweet = tweet) }
}

internal fun toggleBookmark(tweet: Tweet) {
    AppGraph.scope.launch { com.alchemistaloha.xflow.data.feed.BookmarkStore.toggleBookmark(tweet) }
}
