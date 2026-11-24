package com.alchemistaloha.xflow

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PeopleOutline
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.client.TransactionIdWebViewHost
import com.alchemistaloha.xflow.data.client.TwitterClient
import com.alchemistaloha.xflow.data.feed.twitterClient
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.data.subscriptions.SubscriptionListStore
import com.alchemistaloha.xflow.data.feed.FeedStore
import com.alchemistaloha.xflow.data.sync.BackgroundSync
import com.alchemistaloha.xflow.player.MediaCache
import com.alchemistaloha.xflow.player.PlayerPool
import com.alchemistaloha.xflow.ui.feed.EaseInCubic
import com.alchemistaloha.xflow.core.player.BottomEdgeLayout
import com.alchemistaloha.xflow.ui.feed.EaseOutCubic
import com.alchemistaloha.xflow.ui.feed.Fullscreen
import com.alchemistaloha.xflow.ui.feed.HashtagListScreen
import com.alchemistaloha.xflow.ui.feed.HashtagMediaFeedScreen
import com.alchemistaloha.xflow.ui.feed.TiktokFeedScreen
import com.alchemistaloha.xflow.ui.nav.MainTab
import com.alchemistaloha.xflow.ui.nav.NavigationStore
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.profile.UserDetailsScreen
import com.alchemistaloha.xflow.ui.profile.UserMediaFeedScreen
import com.alchemistaloha.xflow.ui.subscriptions.SubscriptionListScreen
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppDurations
import com.alchemistaloha.xflow.ui.theme.PressableScale
import com.alchemistaloha.xflow.ui.theme.XFlowTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            XFlowTheme { XFlowRoot() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            Fullscreen.exit(this)
            PlayerPool.releaseAll()
        }
    }
}

/** What restarts the background sync when it changes (as in the Flutter app). */
private data class SyncKey(val interval: Int, val batch: Int, val prune: Int)

private fun SettingsState.syncKey() = SyncKey(syncInterval, syncBatchSize, pruneThreshold)

@Composable
private fun XFlowRoot() {
    val ready by XFlowApp.ready.collectAsState()

    // Start the background sync once storage is ready; restart it when its settings change.
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        MediaCache.applyMaxSize(SettingsStore.state.value.mediaCacheSizeMB)
        BackgroundSync.start(twitterClient, SettingsStore.state.value)
        SettingsStore.state.map { it.syncKey() }.distinctUntilChanged().drop(1).collect {
            BackgroundSync.restart(twitterClient, SettingsStore.state.value)
        }
    }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        SettingsStore.state.map { it.mediaCacheSizeMB }.distinctUntilChanged().drop(1).collect {
            MediaCache.applyMaxSize(it)
        }
    }

    // Back in the foreground: make sure requests and the sync are running again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (XFlowApp.ready.value) {
            AppLogger.log("XFLOW: App resumed. Ensuring BackgroundSync is active.")
            // The Flutter app reset its request queue here because a failed request
            // never gave its turn back. This client always gives the turn back, and a
            // reset would fail every request still waiting (a feed refresh queued
            // behind the sync came back empty), so the queue is left alone.
            BackgroundSync.restart(twitterClient, SettingsStore.state.value)
            FeedStore.retryIfEmpty()
        }
    }

    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        if (ready) MainScaffold()
    }
}

@Composable
private fun MainScaffold() {
    val nav by NavigationStore.state.collectAsState()
    val hasOverlay = nav.selectedUser != null || nav.selectedHashtag != null
    val stateHolder = rememberSaveableStateHolder()

    // Pushed screens first, then the profile / hashtag overlay.
    BackHandler(enabled = Navigator.canPop || hasOverlay) {
        if (Navigator.canPop) Navigator.pop() else NavigationStore.back()
    }

    val overlayKey = when {
        nav.selectedHashtag != null -> "hashtag:${nav.selectedHashtag}"
        nav.selectedUser != null && nav.userMediaInitialIndex != null -> "media:${nav.selectedUser}"
        nav.selectedUser != null -> "user:${nav.selectedUser}"
        else -> ""
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The three tabs stay alive (like an IndexedStack); only the current one shows.
                // They are hidden, not removed, while an overlay covers them.
                for (tab in MainTab.entries) {
                    val selected = tab == nav.currentTab
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(if (selected) 1f else 0f)
                            .alpha(if (selected && !hasOverlay) 1f else 0f),
                    ) {
                        stateHolder.SaveableStateProvider(tab.name) {
                            when (tab) {
                                MainTab.media -> TiktokFeedScreen()
                                MainTab.subscriptions -> SubscriptionListScreen(isStandalone = false)
                                MainTab.trending -> HashtagListScreen()
                            }
                        }
                    }
                }

                // Hidden WebView that computes request signatures.
                Box(Modifier.size(1.dp).alpha(0f)) { TransactionIdWebViewHost() }

                // Profile and hashtag screens fade in over the tabs and out again.
                AnimatedContent(
                    targetState = overlayKey,
                    modifier = Modifier.fillMaxSize().zIndex(2f),
                    transitionSpec = {
                        fadeIn(tween(AppDurations.normal, easing = EaseOutCubic)) togetherWith
                            fadeOut(tween(AppDurations.normal, easing = EaseInCubic))
                    },
                    label = "overlay",
                ) { key ->
                    // The content is built from the key it was created for, so the screen
                    // that is fading out keeps showing its own user or hashtag.
                    val value = key.substringAfter(':', "")
                    when {
                        key.startsWith("hashtag:") -> OverlayBox { HashtagMediaFeedScreen(hashtag = value) }
                        key.startsWith("media:") -> OverlayBox {
                            UserMediaFeedScreen(
                                screenName = value,
                                initialIndex = nav.userMediaInitialIndex ?: 0,
                                initialTweetId = nav.userMediaInitialTweetId,
                            )
                        }
                        key.startsWith("user:") -> OverlayBox { UserDetailsScreen(screenName = value) }
                        else -> Box(Modifier)
                    }
                }
            }
            if (!Fullscreen.isActive) {
                BottomBar(
                    current = nav.currentTab,
                    onSelected = { tab ->
                        NavigationStore.setTab(tab)
                        if (tab == MainTab.subscriptions) SubscriptionListStore.invalidate()
                    },
                )
            }
        }

        // Pushed full screens (Settings, Bookmarks, Login, ...) above everything.
        for (entry in Navigator.stack) {
            androidx.compose.runtime.key(entry.id) {
                PushedScreen { entry.content() }
            }
        }
    }
}

@Composable
private fun OverlayBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(AppColors.background).blockTouches()) { content() }
}

/** Touches on empty parts of a covering screen must not reach what is underneath. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {}

/** A pushed screen slides a little and fades in, like a Material page route. */
@Composable
private fun PushedScreen(content: @Composable () -> Unit) {
    val visible = androidx.compose.runtime.remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(AppDurations.normal, easing = EaseOutCubic)) +
            slideInHorizontally(tween(AppDurations.normal, easing = EaseOutCubic)) { it / 8 },
        exit = fadeOut(tween(AppDurations.fast)) + slideOutHorizontally(tween(AppDurations.fast)) { it / 8 },
    ) {
        Box(Modifier.fillMaxSize().background(AppColors.background).blockTouches()) { content() }
    }
}

private class BottomItem(val tab: MainTab, val icon: ImageVector, val selectedIcon: ImageVector, val label: String)

private val bottomItems = listOf(
    BottomItem(MainTab.media, Icons.Rounded.PlayCircleOutline, Icons.Rounded.PlayCircle, "Media"),
    BottomItem(MainTab.subscriptions, Icons.Rounded.PeopleOutline, Icons.Rounded.People, "Subscriptions"),
    BottomItem(MainTab.trending, Icons.Rounded.Tag, Icons.Rounded.Tag, "Hashtags"),
)

/** A slim, flat tab bar: icon over a small label, no pill indicator. */
@Composable
private fun BottomBar(current: MainTab, onSelected: (MainTab) -> Unit) {
    val outline = AppColors.outline
    Box(
        Modifier
            .fillMaxWidth()
            .background(AppColors.background)
            .drawBehind { drawLine(outline, Offset.Zero, Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx()) }
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(54.dp)) {
            for (item in bottomItems) {
                val selected = item.tab == current
                val color = if (selected) AppColors.textPrimary else AppColors.textTertiary
                PressableScale(
                    onTap = { onSelected(item.tab) },
                    modifier = Modifier.weight(1f).height(54.dp),
                    pressedScale = 0.92f,
                    tooltip = item.label,
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Crossfade(selected, animationSpec = tween(AppDurations.fast), label = "tabIcon") { on ->
                            Icon(
                                if (on) item.selectedIcon else item.icon,
                                contentDescription = null,
                                tint = if (on) AppColors.textPrimary else AppColors.textTertiary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            item.label,
                            color = color,
                            fontSize = 10.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            letterSpacing = 0.1.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // The feed's seek bar and image bar end right above this bar. A finger aimed at them
        // that lands a little low hits these rows, which do nothing, instead of a tab.
        Box(Modifier.fillMaxWidth().height(BottomEdgeLayout.TAB_GUARD.dp).blockTouches())
    }
}
