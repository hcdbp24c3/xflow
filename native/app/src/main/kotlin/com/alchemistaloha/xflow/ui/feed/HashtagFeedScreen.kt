package com.alchemistaloha.xflow.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.feed.HashtagListStore
import com.alchemistaloha.xflow.data.feed.HashtagMediaStores
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.ui.nav.NavigationStore
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.settings.SettingsScreen
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppIconButton
import com.alchemistaloha.xflow.ui.theme.AppRadius
import com.alchemistaloha.xflow.ui.theme.AppTopBar
import com.alchemistaloha.xflow.ui.theme.EmptyState
import com.alchemistaloha.xflow.ui.theme.FeedTopBar
import com.alchemistaloha.xflow.ui.theme.FloatingIconButton
import com.alchemistaloha.xflow.ui.theme.HSpace
import com.alchemistaloha.xflow.ui.theme.TopProgressLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Runs a store call that must finish even if the screen goes away. */
private fun launchStoreCall(what: String, block: suspend () -> Unit) {
    AppGraph.scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("XFLOW: $what failed: $e")
        }
    }
}

/** The Hashtags tab: the saved hashtags, each opening its media feed. */
@Composable
fun HashtagListScreen() {
    val hashtagsAsync by HashtagListStore.state.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var tagToDelete by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(AppColors.background)) {
        AppTopBar(
            title = "Hashtags",
            actions = {
                AppIconButton(icon = Icons.Filled.Add, onTap = { showAddDialog = true })
                AppIconButton(
                    icon = Icons.Outlined.Settings,
                    onTap = { Navigator.push { SettingsScreen() } },
                )
            },
        )
        when (val async = hashtagsAsync) {
            is Async.Data -> {
                val tags = async.value
                if (tags.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.Tag,
                        message = "No hashtags added yet",
                        actions = {
                            FilledTonalButton(onClick = { showAddDialog = true }) {
                                Text("Add your first hashtag")
                            }
                        },
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(tags, key = { it }) { tag ->
                            HashtagRow(
                                tag = tag,
                                onTap = { NavigationStore.selectHashtag(tag) },
                                onDelete = { tagToDelete = tag },
                            )
                        }
                    }
                }
            }
            Async.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is Async.Error -> EmptyState(
                icon = Icons.Rounded.ErrorOutline,
                message = "Error: ${async.error}",
            )
        }
    }

    if (showAddDialog) {
        AddHashtagDialog(onDismiss = { showAddDialog = false })
    }
    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagToDelete = null },
            title = { Text("Remove Hashtag") },
            text = { Text("Are you sure you want to remove $tag?") },
            dismissButton = {
                TextButton(onClick = { tagToDelete = null }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        launchStoreCall("removeHashtag") { HashtagListStore.removeHashtag(tag) }
                        tagToDelete = null
                    },
                ) { Text("Remove", color = AppColors.danger) }
            },
        )
    }
}

@Composable
private fun HashtagRow(tag: String, onTap: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(AppRadius.md))
                .background(AppColors.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Tag,
                contentDescription = null,
                tint = AppColors.accent,
                modifier = Modifier.size(18.dp),
            )
        }
        HSpace(16.dp)
        Text(
            tag,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )
        AppIconButton(
            icon = Icons.Rounded.DeleteOutline,
            onTap = onDelete,
            tint = AppColors.textTertiary,
        )
    }
}

@Composable
private fun AddHashtagDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Hashtag") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                placeholder = { Text("e.g. #nature or nature") },
                prefix = { Text("#") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val tag = text.trim()
                    if (tag.isNotEmpty()) {
                        launchStoreCall("addHashtag") { HashtagListStore.addHashtag(tag) }
                    }
                    onDismiss()
                },
            ) { Text("Add") }
        },
    )
}

/** Full-bleed, swipeable media feed of one hashtag, shown as an overlay above the tabs. */
@Composable
fun HashtagMediaFeedScreen(hashtag: String) {
    val store = remember(hashtag) { HashtagMediaStores.get(hashtag) }
    val feedAsync by store.state.collectAsStateWithLifecycle()
    val pagerController = rememberMediaPagerController()

    val onIndexChanged: (Int) -> Unit = { index ->
        val feed = store.state.value.valueOrNull
        if (feed != null) {
            val settings = SettingsStore.state.value
            if (index >= feed.tweets.size - settings.lazyLoadThreshold && !feed.isLoadingMore) {
                launchStoreCall("hashtag fetchMore") { store.fetchMore() }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        // This screen reaches a little under the tab bar; these rows are painted like the bar.
        TabBarEdge(Modifier.align(Alignment.BottomCenter))
        when (val async = feedAsync) {
            is Async.Data -> {
                val state = async.value
                val tweets = state.tweets
                if (tweets.isEmpty()) {
                    EmptyState(icon = Icons.Rounded.Tag, message = "No media found")
                } else {
                    MediaPager(
                        tweets = tweets,
                        controller = pagerController,
                        modifier = Modifier.fillMaxSize(),
                        // Nothing plays underneath a pushed screen (Settings).
                        isActive = !Navigator.canPop,
                        onIndexChanged = onIndexChanged,
                        // The hashtag feed never precached images.
                        precacheImages = false,
                    ) { tweet, isVisible, onPlaybackError ->
                        HashtagFeedItem(
                            tweet = tweet,
                            isVisible = isVisible,
                            onPlaybackError = onPlaybackError,
                        )
                    }
                    if ((state.isLoadingMore || state.isRefreshing) && !Fullscreen.isActive) {
                        TopProgressLine(Modifier.align(Alignment.TopCenter))
                    }
                }
            }
            Async.Loading -> EmptyState(loading = true, message = "")
            is Async.Error -> EmptyState(
                icon = Icons.Rounded.CloudOff,
                message = "Error: ${async.error}",
                actions = {
                    FilledTonalButton(
                        onClick = { launchStoreCall("hashtag refresh") { store.refresh() } },
                    ) { Text("Retry") }
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
                title = { Text(hashtag, maxLines = 1) },
                actions = {
                    FloatingIconButton(
                        icon = Icons.Rounded.Refresh,
                        tooltip = "Refresh",
                        onTap = {
                            pagerController.jumpToPage(0)
                            launchStoreCall("hashtag refresh") { store.refresh() }
                        },
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

/**
 * One page of the hashtag feed: the media with the caption / action overlay.
 * Unlike TiktokFeedItem it never shows the discovery debug card.
 */
@Composable
fun HashtagFeedItem(
    tweet: Tweet,
    isVisible: Boolean,
    autoFullscreen: Boolean = false,
    onPlaybackError: (() -> Unit)? = null,
) {
    TiktokMediaContainer(
        tweet = tweet,
        isVisible = isVisible,
        autoFullscreen = autoFullscreen,
        onPlaybackError = onPlaybackError,
        overlay = { onFullscreen, isFullscreen ->
            TweetTextOverlay(tweet = tweet, onFullscreen = onFullscreen, isFullscreen = isFullscreen)
        },
    )
}
