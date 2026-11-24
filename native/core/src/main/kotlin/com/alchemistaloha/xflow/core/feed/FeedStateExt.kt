package com.alchemistaloha.xflow.core.feed

import com.alchemistaloha.xflow.core.model.FeedState
import com.alchemistaloha.xflow.core.model.Tweet

/**
 * Dart `FeedState.copyWith`: a null argument keeps the current value. Note
 * that this means a null [cursorBottom] never clears the stored cursor, which
 * the stores rely on (the data class `copy` would clear it).
 */
fun FeedState.copyWith(
    tweets: List<Tweet>? = null,
    cursorBottom: String? = null,
    isLoadingMore: Boolean? = null,
    isRefreshing: Boolean? = null,
): FeedState = FeedState(
    tweets = tweets ?: this.tweets,
    cursorBottom = cursorBottom ?: this.cursorBottom,
    isLoadingMore = isLoadingMore ?: this.isLoadingMore,
    isRefreshing = isRefreshing ?: this.isRefreshing,
)
