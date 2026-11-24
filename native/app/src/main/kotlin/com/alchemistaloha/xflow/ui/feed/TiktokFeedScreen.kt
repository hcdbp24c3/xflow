package com.alchemistaloha.xflow.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.data.client.AccountStore
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.feed.FeedStore
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.ui.auth.LoginScreen
import com.alchemistaloha.xflow.ui.nav.MainTab
import com.alchemistaloha.xflow.ui.nav.NavigationStore
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.settings.DiscoveryDebugOverlay
import com.alchemistaloha.xflow.ui.settings.SettingsScreen
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppRadius
import com.alchemistaloha.xflow.ui.theme.EmptyState
import com.alchemistaloha.xflow.ui.theme.FeedTopBar
import com.alchemistaloha.xflow.ui.theme.FloatingIconButton
import com.alchemistaloha.xflow.ui.theme.PressableScale
import com.alchemistaloha.xflow.ui.theme.TopProgressLine
import kotlinx.coroutines.launch

private fun goToLogin() {
    Navigator.push {
        LoginScreen { success -> if (success) FeedStore.invalidate() }
    }
}

/** The main tab: the full-bleed vertical media feed with floating buttons on top. */
@Composable
fun TiktokFeedScreen() {
    val account by AccountStore.state.collectAsState()
    val pager = rememberMediaPagerController()

    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        MediaFeed(pager)

        if (!Fullscreen.isActive) {
            FeedTopBar(
                modifier = Modifier.align(Alignment.TopCenter),
                leading = {
                    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "XFlow",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.4).sp,
                                shadow = Shadow(Color.Black.copy(alpha = 0.54f), Offset.Zero, 6f),
                            ),
                        )
                    }
                },
                actions = {
                    if (account == null) {
                        PressableScale(onTap = { goToLogin() }, modifier = Modifier.padding(end = 4.dp)) {
                            Box(
                                Modifier
                                    .height(34.dp)
                                    .background(Color.White, RoundedCornerShape(AppRadius.xl))
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Login", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    FloatingIconButton(
                        icon = Icons.Rounded.Refresh,
                        tooltip = "Refresh",
                        onTap = {
                            pager.jumpToPage(0)
                            FeedStore.reportViewIndex(0)
                            AppGraph.scope.launch { FeedStore.refresh() }
                        },
                    )
                    FloatingIconButton(
                        icon = Icons.Rounded.BookmarkBorder,
                        tooltip = "Bookmarks",
                        onTap = { Navigator.push { BookmarksScreen() } },
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

@Composable
private fun MediaFeed(pager: MediaPagerController) {
    val feed by FeedStore.state.collectAsState()
    val nav by NavigationStore.state.collectAsState()
    val isScreenActive = nav.selectedUser == null &&
        nav.selectedHashtag == null &&
        nav.currentTab == MainTab.media &&
        !Navigator.canPop

    when (val async = feed) {
        is Async.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
        }

        is Async.Error -> EmptyState(
            icon = Icons.Rounded.CloudOff,
            message = "Error: ${async.error}",
            actions = {
                FilledTonalButton(onClick = { goToLogin() }) { Text("Login to X") }
                TextButton(onClick = { FeedStore.invalidate() }) { Text("Retry") }
            },
        )

        is Async.Data -> {
            val state = async.value
            val tweets = state.tweets
            if (tweets.isEmpty()) {
                if (state.isRefreshing) {
                    EmptyState(loading = true, message = "Fetching latest media...")
                } else {
                    EmptyState(
                        icon = Icons.Rounded.PlayCircleOutline,
                        message = "No media found.",
                        actions = { FilledTonalButton(onClick = { goToLogin() }) { Text("Login to X") } },
                    )
                }
                return
            }
            Box(Modifier.fillMaxSize()) {
                MediaPager(
                    tweets = tweets,
                    controller = pager,
                    isActive = isScreenActive,
                    // Runs once per landed page, not on every scroll tick.
                    onIndexChanged = { index -> onFeedIndexChanged(index) },
                ) { tweet, isVisible, onPlaybackError ->
                    TiktokFeedItem(tweet = tweet, isVisible = isVisible, onPlaybackError = onPlaybackError)
                }
                if (state.isRefreshing && !Fullscreen.isActive) {
                    TopProgressLine(Modifier.align(Alignment.TopCenter).statusBarsPadding())
                }
            }
        }
    }
}

private fun onFeedIndexChanged(index: Int) {
    FeedStore.reportViewIndex(index)
    val state = (FeedStore.state.value as? Async.Data)?.value ?: return
    val tweets = state.tweets
    if (index < tweets.size) {
        val id = tweets[index].id
        AppGraph.scope.launch { Repository.markMediaAsPlayed(id) }
    }
    val settings = SettingsStore.state.value
    if (index >= tweets.size - settings.lazyLoadThreshold && !state.isRefreshing) {
        AppGraph.scope.launch { FeedStore.fetchMore() }
    }
}

/** One full-screen feed page: media + caption/action overlay (+ debug card when enabled). */
@Composable
fun TiktokFeedItem(
    tweet: Tweet,
    isVisible: Boolean,
    autoFullscreen: Boolean = false,
    onPlaybackError: (() -> Unit)? = null,
) {
    val settings by SettingsStore.state.collectAsState()
    Box(Modifier.fillMaxSize()) {
        TiktokMediaContainer(
            tweet = tweet,
            isVisible = isVisible,
            autoFullscreen = autoFullscreen,
            onPlaybackError = onPlaybackError,
            overlay = { onFullscreen, isFullscreen ->
                TweetTextOverlay(tweet = tweet, onFullscreen = onFullscreen, isFullscreen = isFullscreen)
            },
        )
        if (settings.showDebugInfo && !Fullscreen.isActive) {
            DiscoveryDebugOverlay(
                tweet = tweet,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 10.dp, top = 60.dp),
            )
        }
    }
}
