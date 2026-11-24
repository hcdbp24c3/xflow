package com.alchemistaloha.xflow.ui.feed

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.format.ProfileFormat
import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.TweetResponse
import com.alchemistaloha.xflow.data.feed.FeedStore
import com.alchemistaloha.xflow.data.feed.twitterClient
import com.alchemistaloha.xflow.data.subscriptions.SubscriptionListStore
import com.alchemistaloha.xflow.ui.nav.NavigationStore
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.EmptyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The replies of one post, loaded once per post id and kept for the life of
 * the app (Dart `tweetDetailProvider`, a FutureProvider.family).
 * [invalidate] reloads one post's replies and keeps showing the old ones meanwhile.
 */
object TweetDetailStores {
    private val flows = HashMap<String, MutableStateFlow<Async<TweetResponse>>>()

    /** Call on the main thread. */
    fun get(tweetId: String): StateFlow<Async<TweetResponse>> {
        flows[tweetId]?.let { return it }
        val flow = MutableStateFlow<Async<TweetResponse>>(Async.Loading)
        flows[tweetId] = flow
        load(tweetId, flow)
        return flow
    }

    /** Call on the main thread. */
    fun invalidate(tweetId: String) {
        val flow = flows[tweetId] ?: return
        load(tweetId, flow)
    }

    private fun load(tweetId: String, flow: MutableStateFlow<Async<TweetResponse>>) {
        AppGraph.scope.launch {
            try {
                flow.value = Async.Data(twitterClient.fetchTweetDetail(tweetId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                flow.value = Async.Error(e)
            }
        }
    }
}

/** Bottom sheet with the replies to [tweet] and a box to write one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweetRepliesSheet(tweet: Tweet, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repliesFlow = remember(tweet.id) { TweetDetailStores.get(tweet.id) }
    val repliesAsync by repliesFlow.collectAsStateWithLifecycle()

    var replyText by rememberSaveable(tweet.id) { mutableStateOf("") }
    var isPosting by remember { mutableStateOf(false) }

    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    val postReply: () -> Unit = post@{
        val text = replyText.trim()
        if (text.isEmpty() || isPosting) return@post
        isPosting = true
        scope.launch {
            try {
                val success = FeedStore.postReply(tweet.id, text)
                if (success) {
                    replyText = ""
                    // Refresh replies
                    TweetDetailStores.invalidate(tweet.id)
                    Toast.makeText(context, "Đã đăng bình luận", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Không thể đăng bình luận", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isPosting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = {
            // Handle bar
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f).imePadding()) {
            // Header
            Row(
                Modifier.padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Bình luận",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = close) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = AppColors.textPrimary)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = AppColors.outline)

            // Content
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val async = repliesAsync) {
                    is Async.Data -> {
                        val replies = remember(async.value, tweet.id) {
                            async.value.tweets.filter { it.id != tweet.id }
                        }
                        if (replies.isEmpty()) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                EmptyState(
                                    icon = Icons.Outlined.ModeComment,
                                    message = "Chưa có bình luận nào",
                                    modifier = Modifier.padding(top = 32.dp),
                                )
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                itemsIndexed(replies) { _, reply ->
                                    ReplyTile(tweet = reply, onClose = close)
                                }
                            }
                        }
                    }

                    is Async.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    is Async.Error -> EmptyState(
                        icon = Icons.Rounded.ErrorOutline,
                        message = "Lỗi tải bình luận: ${async.error}",
                    )
                }
            }

            // Comment input
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                )
                BasicTextField(
                    value = replyText,
                    onValueChange = { replyText = it },
                    modifier = Modifier.weight(1f),
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                    RoundedCornerShape(20.dp),
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            if (replyText.isEmpty()) {
                                Text(
                                    "Viết bình luận...",
                                    style = textStyle.copy(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    ),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = postReply, enabled = !isPosting) {
                    if (isPosting) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyTile(tweet: Tweet, onClose: () -> Unit) {
    val subscriptions by SubscriptionListStore.state.collectAsStateWithLifecycle()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable {
                    // Deep dive into a reply's own thread?
                    // For now, let's keep it simple or allow navigation to user
                    val handle = tweet.userHandle.replaceFirst("@", "")
                    NavigationStore.selectUser(handle)
                    onClose() // Close sheet when navigating away
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val avatarUrl = tweet.userAvatarUrlHighRes
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(AppColors.surfaceHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUrl != null) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = AppColors.textPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tweet.userHandle,
                        modifier = Modifier.weight(1f, fill = false),
                        color = AppColors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subscriptions.isSubscribed(tweet.userHandle)) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = AppColors.accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(tweet.text, color = AppColors.textSecondary, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = AppColors.textTertiary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        ProfileFormat.formatCount(tweet.favoriteCount),
                        color = AppColors.textTertiary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 72.dp),
            thickness = 0.5.dp,
            color = AppColors.outline,
        )
    }
}
