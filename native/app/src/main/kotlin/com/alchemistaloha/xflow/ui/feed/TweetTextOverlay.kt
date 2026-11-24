package com.alchemistaloha.xflow.ui.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.player.CaptionSpan
import com.alchemistaloha.xflow.core.player.formatCount
import com.alchemistaloha.xflow.core.player.parseCaption
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.feed.BookmarkStore
import com.alchemistaloha.xflow.data.subscriptions.SubscriptionListStore
import com.alchemistaloha.xflow.ui.nav.NavigationStore
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppRadius
import com.alchemistaloha.xflow.ui.theme.PressableScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ActionColumnWidth = 56.dp
private val TextShadow = Shadow(color = Color.Black.copy(alpha = 0.54f), offset = Offset(0f, 1f), blurRadius = 3f)

// Lighter than the accent so links stay readable on top of video.
private val LinkColor = Color(0xFF9CC2FF)

internal fun shareTweet(context: Context, tweet: Tweet) {
    val handle = tweet.userHandle.replaceFirst("@", "")
    val url = "https://x.com/$handle/status/${tweet.id}"
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, "Xem tweet này")
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        AppLogger.log("XFLOW: Error sharing tweet: $e")
    }
}

internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        AppLogger.log("XFLOW: Error opening link: $e")
    }
}

/** Caption (bottom left) and the action column (right) drawn over a feed item. */
@Composable
fun TweetTextOverlay(
    tweet: Tweet,
    modifier: Modifier = Modifier,
    onFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
) {
    val bottomPadding = if (isFullscreen) 48.dp else 36.dp
    val rightPadding = if (isFullscreen) 24.dp else 6.dp

    Box(modifier.fillMaxSize()) {
        // Scrim behind the caption. It takes no touches, so tapping and double-tapping the
        // media still work down here.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = 0.28f),
                        1f to Color.Black.copy(alpha = 0.62f),
                    ),
                ),
        )
        // Caption (bottom left). The right inset keeps it clear of the action column.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = ActionColumnWidth + rightPadding + 8.dp, bottom = bottomPadding),
        ) {
            UserHeader(tweet)
            // A text-only tweet already shows its text as the page.
            if (tweet.mediaUrls.isNotEmpty() && tweet.text.trim().isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                TweetText(tweet.text)
            }
        }
        // Action buttons (right side)
        Box(Modifier.align(Alignment.BottomEnd).padding(end = rightPadding, bottom = bottomPadding - 6.dp)) {
            ActionButtons(tweet, onFullscreen, isFullscreen)
        }
    }
}

@Composable
private fun ActionButtons(original: Tweet, onFullscreen: (() -> Unit)?, isFullscreen: Boolean) {
    val context = LocalContext.current
    // Live tweet state so buttons update immediately after a toggle
    val tweet = rememberLiveTweet(original)
    // The saved state comes from the bookmark list: a tweet in a feed never has isBookmarked
    // set, so the icon would stay off after saving.
    val bookmarks by BookmarkStore.state.collectAsState()
    val isBookmarked = bookmarks.valueOrNull?.any { it.id == tweet.id } ?: tweet.isBookmarked
    var showReplies by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ActionButton(
            icon = if (tweet.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            color = if (tweet.isLiked) AppColors.like else Color.White,
            active = tweet.isLiked,
            label = formatCount(tweet.favoriteCount),
            onTap = { toggleLike(original) },
        )
        Spacer(Modifier.height(14.dp))
        ActionButton(
            icon = Icons.Outlined.ModeComment,
            label = formatCount(tweet.replyCount),
            onTap = { showReplies = true },
        )
        Spacer(Modifier.height(14.dp))
        ActionButton(
            icon = Icons.Rounded.Repeat,
            color = if (tweet.isRetweeted) AppColors.retweet else Color.White,
            active = tweet.isRetweeted,
            label = formatCount(tweet.retweetCount),
            onTap = { toggleRetweet(original) },
        )
        Spacer(Modifier.height(14.dp))
        ActionButton(
            icon = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            color = if (isBookmarked) AppColors.bookmark else Color.White,
            active = isBookmarked,
            label = "Lưu",
            onTap = { toggleBookmark(tweet.copy(isBookmarked = isBookmarked)) },
        )
        Spacer(Modifier.height(14.dp))
        ActionButton(
            icon = Icons.Rounded.IosShare,
            label = "Chia sẻ",
            onTap = { shareTweet(context, tweet) },
        )
        if (onFullscreen != null && tweet.isVideo) {
            Spacer(Modifier.height(14.dp))
            ActionButton(
                icon = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                label = if (isFullscreen) "Exit" else "Full",
                onTap = onFullscreen,
            )
        }
    }

    if (showReplies) {
        TweetRepliesSheet(tweet = original, onDismiss = { showReplies = false })
    }
}

@Composable
private fun UserHeader(tweet: Tweet) {
    val subscriptions by SubscriptionListStore.state.collectAsState()
    val isSubscribed = subscriptions.isSubscribed(tweet.userHandle)
    val handle = tweet.userHandle.replaceFirst("@", "")
    val noRipple = remember { MutableInteractionSource() }

    val dateStr = remember(tweet.createdAtMillis) {
        val millis = tweet.createdAtMillis ?: return@remember ""
        try {
            SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(millis))
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Error formatting date: $e")
            "ERR"
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(AppColors.surfaceHighest)
                .clickable(interactionSource = noRipple, indication = null) { NavigationStore.selectUser(handle) },
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
                Icon(Icons.Rounded.Person, null, tint = AppColors.textSecondary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tweet.userHandle,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable(interactionSource = noRipple, indication = null) { NavigationStore.selectUser(handle) },
                    style = TextStyle(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = (-0.1).sp,
                        shadow = TextShadow,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isSubscribed) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.CheckCircle, null, tint = AppColors.accent, modifier = Modifier.size(15.dp))
                } else {
                    Spacer(Modifier.width(8.dp))
                    PressableScale(
                        onTap = {
                            SubscriptionListStore.toggleSubscription(
                                Subscription(
                                    id = tweet.userHandle,
                                    screenName = handle,
                                    name = tweet.userHandle,
                                    profileImageUrl = tweet.userAvatarUrl,
                                ),
                            )
                        },
                    ) {
                        Box(
                            Modifier
                                .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(AppRadius.xl))
                                .border(
                                    BorderStroke(1.dp, Color.White.copy(alpha = 0.85f)),
                                    RoundedCornerShape(AppRadius.xl),
                                )
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Follow", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (dateStr.isNotEmpty()) {
                Text(
                    dateStr,
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.5.sp,
                        shadow = TextShadow,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TweetText(text: String) {
    val context = LocalContext.current
    var isExpanded by remember(text) { mutableStateOf(false) }

    val annotated = remember(text) {
        buildAnnotatedString {
            for (span in parseCaption(text)) {
                when (span.kind) {
                    CaptionSpan.Kind.TEXT -> append(span.text)
                    CaptionSpan.Kind.HASHTAG -> withLink(
                        LinkAnnotation.Clickable(
                            tag = "hashtag",
                            styles = TextLinkStyles(SpanStyle(color = LinkColor, fontWeight = FontWeight.SemiBold)),
                        ) { NavigationStore.selectHashtag(span.target) },
                    ) { append(span.text) }
                    CaptionSpan.Kind.MENTION -> withLink(
                        LinkAnnotation.Clickable(
                            tag = "mention",
                            styles = TextLinkStyles(SpanStyle(color = LinkColor, fontWeight = FontWeight.Medium)),
                        ) { NavigationStore.selectUser(span.target) },
                    ) { append(span.text) }
                    CaptionSpan.Kind.URL -> withLink(
                        LinkAnnotation.Clickable(
                            tag = "url",
                            styles = TextLinkStyles(
                                SpanStyle(
                                    color = LinkColor,
                                    fontWeight = FontWeight.Medium,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ) { openUrl(context, span.target) },
                    ) { append(span.text) }
                }
            }
        }
    }

    Column {
        Text(
            annotated,
            style = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 1.35.em, shadow = TextShadow),
            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
            overflow = if (isExpanded) TextOverflow.Visible else TextOverflow.Ellipsis,
        )
        if (!isExpanded && text.length > 100) {
            Text(
                "Xem thêm",
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        isExpanded = true
                    },
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp,
                    shadow = TextShadow,
                ),
            )
        }
    }
}

/**
 * One button of the action column. [active] says whether the action is on (liked, retweeted,
 * saved): the icon pops when it turns on, wherever the change came from, so a double-tap like
 * also plays here.
 */
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onTap: (() -> Unit)?,
    color: Color = Color.White,
    active: Boolean = false,
) {
    val pop = remember { Animatable(1f) }
    var wasActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !wasActive) {
            pop.snapTo(1f)
            pop.animateTo(1.3f, tween(112, easing = EaseOutCubic))
            pop.animateTo(1f, tween(168, easing = EaseOutCubic))
        }
        wasActive = active
    }

    PressableScale(onTap = onTap, tooltip = label) {
        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
            ) {
                // A dark copy one dp lower stands in for the Flutter icon shadow, so the
                // white glyph keeps an edge on bright video. No blur.
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.size(29.dp).graphicsLayer { translationY = 1.dp.toPx() },
                )
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(29.dp))
            }
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    shadow = TextShadow,
                ),
            )
        }
    }
}
