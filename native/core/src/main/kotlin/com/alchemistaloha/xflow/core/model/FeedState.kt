package com.alchemistaloha.xflow.core.model

data class FeedState(
    val tweets: List<Tweet>,
    val cursorBottom: String? = null,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
)

/** Loading / data / error, the three states a screen can be in. */
sealed interface Async<out T> {
    data object Loading : Async<Nothing>
    data class Data<T>(val value: T) : Async<T>
    data class Error(val error: Throwable) : Async<Nothing>

    val valueOrNull: T?
        get() = (this as? Data<T>)?.value
}
