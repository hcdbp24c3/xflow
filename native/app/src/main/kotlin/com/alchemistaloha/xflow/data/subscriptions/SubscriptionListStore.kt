package com.alchemistaloha.xflow.data.subscriptions

import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.subscriptions.SubscriptionListState
import com.alchemistaloha.xflow.core.subscriptions.SubscriptionSort
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.db.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The followed accounts plus the search / sort choices of the Subscriptions
 * screen (Dart subscriptionListProvider). Loads itself on first use.
 */
object SubscriptionListStore {
    private val _state = MutableStateFlow(SubscriptionListState())
    val state: StateFlow<SubscriptionListState> = _state.asStateFlow()

    init {
        AppGraph.scope.launch { load() }
    }

    private suspend fun load() {
        try {
            val (subscriptions, views) = coroutineScope {
                val subs = async { Repository.getSubscriptions() }
                val played = async { Repository.getPlayedCountsByUser() }
                subs.await() to played.await()
            }
            _state.update {
                it.copyWith(allSubscriptions = subscriptions, userViews = views, isLoading = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The Dart future would fail unhandled and leave the spinner up;
            // here the screen is released so it stays usable.
            AppLogger.log("XFLOW: Error loading subscriptions: $e")
            _state.update { it.copyWith(isLoading = false) }
        }
    }

    fun setSearchQuery(query: String) = _state.update { it.copyWith(searchQuery = query) }

    fun setSort(sort: SubscriptionSort) = _state.update { it.copyWith(sort = sort) }

    fun toggleOrder() = _state.update { it.copyWith(isAscending = !it.isAscending) }

    suspend fun refresh() {
        _state.update { it.copyWith(isLoading = true) }
        load()
    }

    /** Riverpod `ref.invalidate`: back to the initial state, then load again. */
    fun invalidate() {
        _state.value = SubscriptionListState()
        AppGraph.scope.launch { load() }
    }

    /** Follows [sub] if it is not in the list, otherwise unfollows it. Returns at once. */
    fun toggleSubscription(sub: Subscription) {
        val before = _state.value
        val exists = before.isSubscribed(sub.screenName)

        // Optimistic update for immediate UI feedback
        _state.update { it.copyWith(allSubscriptions = it.withToggled(sub)) }

        AppGraph.scope.launch {
            try {
                if (exists) {
                    Repository.deleteSubscriptionByScreenName(sub.screenName)
                } else {
                    Repository.insertSubscription(sub)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("XFLOW: Error toggling subscription: $e")
                // Rollback on error
                load()
                return@launch
            }

            // Refresh to ensure everything (including views) is in sync
            load()
        }
    }

    fun isSubscribed(screenName: String): Boolean = _state.value.isSubscribed(screenName)
}
