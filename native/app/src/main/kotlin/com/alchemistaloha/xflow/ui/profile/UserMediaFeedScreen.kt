package com.alchemistaloha.xflow.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.data.profile.UserMediaStores
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.ui.feed.Fullscreen
import com.alchemistaloha.xflow.ui.feed.MediaPager
import com.alchemistaloha.xflow.ui.feed.TabBarEdge
import com.alchemistaloha.xflow.ui.feed.TiktokFeedItem
import com.alchemistaloha.xflow.ui.feed.rememberMediaPagerController
import com.alchemistaloha.xflow.ui.nav.NavigationStore
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.settings.SettingsScreen
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.EmptyState
import com.alchemistaloha.xflow.ui.theme.FeedTopBar
import com.alchemistaloha.xflow.ui.theme.FloatingIconButton
import com.alchemistaloha.xflow.ui.theme.TopProgressLine
import kotlinx.coroutines.launch

/** One user's media as a full-screen swipe feed, opened from a cell of the profile grid. */
@Composable
fun UserMediaFeedScreen(screenName: String, initialIndex: Int, initialTweetId: String?) {
    val store = remember(screenName) { UserMediaStores.get(screenName) }
    val feedAsync by store.state.collectAsStateWithLifecycle()
    val controller = rememberMediaPagerController(initialIndex)
    val scope = rememberCoroutineScope()

    // When the list changes for the first time after opening (the fresh fetch
    // landing), the tapped post may have moved: find it again by id and jump there.
    LaunchedEffect(store) {
        var initialized = false
        var previous = store.state.value
        store.state.collect { next ->
            if (next === previous) return@collect
            // Dart only jumps when the pager is already on screen (`hasClients`).
            val pagerShowing = previous.valueOrNull?.tweets?.isNotEmpty() == true
            previous = next
            if (next is Async.Data && !initialized) {
                val tweets = next.value.tweets
                var targetIndex = initialIndex
                if (initialTweetId != null) {
                    val foundIndex = tweets.indexOfFirst { it.id == initialTweetId }
                    if (foundIndex != -1) targetIndex = foundIndex
                }
                if (targetIndex < tweets.size && pagerShowing) {
                    controller.jumpToPage(targetIndex)
                }
                initialized = true
            }
        }
    }

    val onIndexChanged: (Int) -> Unit = { index ->
        val current = store.state.value
        if (current is Async.Data) {
            val tweets = current.value.tweets
            val settings = SettingsStore.state.value
            if (index >= tweets.size - settings.lazyLoadThreshold && !current.value.isLoadingMore) {
                scope.launch { store.fetchMore() }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        // This screen reaches a little under the tab bar; these rows are painted like the bar.
        TabBarEdge(Modifier.align(Alignment.BottomCenter))
        when (val feed = feedAsync) {
            is Async.Data -> {
                val state = feed.value
                val tweets = state.tweets
                if (tweets.isEmpty()) {
                    if (state.isRefreshing) {
                        EmptyState(loading = true, message = "")
                    } else {
                        EmptyState(
                            icon = Icons.Rounded.PlayCircleOutline,
                            message = "No media found.",
                            actions = {
                                FilledTonalButton(onClick = { scope.launch { store.refresh() } }) {
                                    Text("Refresh")
                                }
                            },
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                        MediaPager(
                            tweets = tweets,
                            controller = controller,
                            // Nothing plays underneath a pushed screen (Settings).
                            isActive = !Navigator.canPop,
                            onIndexChanged = onIndexChanged,
                        ) { tweet, isVisible, onPlaybackError ->
                            UserMediaFeedItem(
                                tweet = tweet,
                                isVisible = isVisible,
                                onPlaybackError = onPlaybackError,
                            )
                        }
                        if (state.isRefreshing && !Fullscreen.isActive) TopProgressLine(Modifier.align(Alignment.TopCenter))
                    }
                }
            }

            is Async.Loading -> EmptyState(loading = true, message = "")

            is Async.Error -> EmptyState(
                icon = Icons.Rounded.CloudOff,
                message = "Error: ${feed.error}",
                actions = {
                    FilledTonalButton(onClick = { scope.launch { store.refresh() } }) {
                        Text("Retry")
                    }
                },
            )
        }

        // A fullscreen video has the whole window to itself.
        if (!Fullscreen.isActive) {
            FeedTopBar(
                modifier = Modifier.align(Alignment.TopCenter),
                leading = {
                    FloatingIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        tooltip = "Back",
                        onTap = { NavigationStore.back() },
                    )
                },
                title = { Text("@$screenName") },
                actions = {
                    FloatingIconButton(
                        icon = Icons.Rounded.Refresh,
                        tooltip = "Refresh",
                        onTap = { scope.launch { store.refresh() } },
                    )
                    FloatingIconButton(
                        icon = Icons.Outlined.Settings,
                        tooltip = "Settings",
                        onTap = { Navigator.push { SettingsScreen() } },
                    )
                },
            )
        }
    }
}

/** One page of the user feed: media with the caption and action overlay. */
@Composable
fun UserMediaFeedItem(
    tweet: Tweet,
    isVisible: Boolean,
    autoFullscreen: Boolean = false,
    onPlaybackError: (() -> Unit)? = null,
) {
    TiktokFeedItem(
        tweet = tweet,
        isVisible = isVisible,
        autoFullscreen = autoFullscreen,
        onPlaybackError = onPlaybackError,
    )
}
