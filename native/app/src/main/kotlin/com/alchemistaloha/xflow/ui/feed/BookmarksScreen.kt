package com.alchemistaloha.xflow.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.feed.BookmarkStore
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppTopBar
import com.alchemistaloha.xflow.ui.theme.EmptyState
import com.alchemistaloha.xflow.ui.theme.HSpace
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun launchBookmarkCall(block: suspend () -> Unit) {
    AppGraph.scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Bookmark update failed: $e")
        }
    }
}

/** The saved posts; swipe a row to the left to remove it, with undo. */
@Composable
fun BookmarksScreen() {
    val bookmarksAsync by BookmarkStore.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The Dart provider is auto-disposed, so the list is read again each time
    // the screen opens.
    LaunchedEffect(Unit) { BookmarkStore.loadBookmarks() }

    Box(Modifier.fillMaxSize().background(AppColors.background).navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title = "Đã lưu", onBack = { Navigator.pop() })
            when (val async = bookmarksAsync) {
                is Async.Data -> {
                    val bookmarks = async.value
                    if (bookmarks.isEmpty()) {
                        EmptyState(
                            icon = Icons.Rounded.BookmarkBorder,
                            message = "Chưa có bookmark nào",
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(bookmarks, key = { it.id }) { tweet ->
                                SwipeToDeleteRow(
                                    modifier = Modifier.animateItem(),
                                    onDismissed = {
                                        launchBookmarkCall {
                                            BookmarkStore.toggleBookmark(tweet.copy(isBookmarked = true))
                                        }
                                        scope.launch {
                                            val result = snackbar.showSnackbar(
                                                message = "Đã xoá bookmark",
                                                actionLabel = "Hoàn tác",
                                                duration = SnackbarDuration.Short,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                launchBookmarkCall { BookmarkStore.addBookmark(tweet) }
                                            }
                                        }
                                    },
                                ) { BookmarkRow(tweet) }
                            }
                        }
                    }
                }
                Async.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is Async.Error -> EmptyState(
                    icon = Icons.Rounded.ErrorOutline,
                    message = "Lỗi: ${async.error}",
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BookmarkRow(tweet: Tweet) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(AppColors.surfaceHighest),
            contentAlignment = Alignment.Center,
        ) {
            val avatar = tweet.userAvatarUrlHighRes
            if (avatar != null) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = AppColors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        HSpace(16.dp)
        Column(Modifier.weight(1f)) {
            Text(
                tweet.userHandle,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                ),
            )
            Text(
                tweet.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    color = AppColors.textSecondary,
                ),
            )
        }
        HSpace(16.dp)
        Icon(
            Icons.Rounded.Bookmark,
            contentDescription = null,
            tint = AppColors.bookmark,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A row that can be dragged to the left over a red "delete" background.
 * Let go past 40% of the width, or with a fast leftward fling, and it slides
 * out and [onDismissed] runs; otherwise it springs back.
 */
@Composable
private fun SwipeToDeleteRow(
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    val flingVelocity = with(LocalDensity.current) { 700.dp.toPx() }

    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        Box(
            Modifier.matchParentSize().background(AppColors.danger).padding(end = 24.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color.White)
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(AppColors.background)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceAtMost(0f)) }
                    },
                    onDragStopped = { velocity ->
                        val full = width.toFloat()
                        val dismiss = full > 0f && offset.value < 0f &&
                            (offset.value < -full * 0.4f || velocity < -flingVelocity)
                        if (dismiss) {
                            offset.animateTo(-full, tween(200))
                            currentOnDismissed()
                        } else {
                            offset.animateTo(0f, tween(200))
                        }
                    },
                ),
        ) { content() }
    }
}
