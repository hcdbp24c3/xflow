package com.alchemistaloha.xflow.ui.feed

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
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
import com.alchemistaloha.xflow.core.feed.FollowBadge
import com.alchemistaloha.xflow.core.feed.FollowBadgeLogic
import com.alchemistaloha.xflow.core.model.Subscription
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.player.BottomEdgeLayout
import com.alchemistaloha.xflow.core.player.CaptionSpan
import com.alchemistaloha.xflow.core.player.formatCount
import com.alchemistaloha.xflow.core.player.parseCaption
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.client.TwitterAccount
import com.alchemistaloha.xflow.data.feed.BookmarkStore
import com.alchemistaloha.xflow.data.subscriptions.SubscriptionListStore
import com.alchemistaloha.xflow.ui.browser.openLink
import com.alchemistaloha.xflow.ui.nav.NavigationStore
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.PressableScale
import kotlinx.coroutines.delay
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

/** Caption (bottom left) and the action column (right) drawn over a feed item. */
@Composable
fun TweetTextOverlay(
    tweet: Tweet,
    modifier: Modifier = Modifier,
    onFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
) {
    // Clear of the seek / image bar's touch strip along the bottom edge.
    val bottomPadding = if (isFullscreen) 48.dp else (BottomEdgeLayout.TOUCH_HEIGHT + 6f).dp
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
        // A fullscreen video in landscape has no room for it above the buttons.
        if (!isFullscreen) {
            AuthorAvatarButton(tweet)
            Spacer(Modifier.height(10.dp))
        }
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

    // The avatar and the follow badge live at the top of the action column.
    Column {
        Text(
            tweet.userHandle,
            modifier = Modifier
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

private val AvatarSize = 44.dp
private val BadgeSize = 20.dp
private val BadgeTouchSize = 40.dp

/**
 * The author's avatar at the top of the action column. Tapping it opens the profile. While the
 * author is not followed a small "+" badge sits on its lower edge; tapping the badge follows,
 * the badge turns into a tick and fades away. An author who is already followed has no badge.
 */
@Composable
private fun AuthorAvatarButton(tweet: Tweet) {
    val subscriptions by SubscriptionListStore.state.collectAsState()
    val handle = tweet.userHandle.replaceFirst("@", "")
    val isSubscribed = subscriptions.isSubscribed(tweet.userHandle)
    // True from the tap on "+" until the tick has faded.
    var followedHere by remember(tweet.userHandle) { mutableStateOf(false) }
    val isOwn = FollowBadgeLogic.isOwnAccount(tweet.userHandle, TwitterAccount.currentAccount?.screenName)
    val badge = FollowBadgeLogic.badgeFor(isSubscribed, followedHere, isOwn)

    val badgeAlpha = remember { Animatable(1f) }
    val badgeScale = remember { Animatable(1f) }
    LaunchedEffect(badge) {
        when (badge) {
            FollowBadge.Confirmed -> {
                badgeAlpha.snapTo(1f)
                badgeScale.snapTo(0.6f)
                badgeScale.animateTo(1.15f, tween(140, easing = EaseOutCubic))
                badgeScale.animateTo(1f, tween(120, easing = EaseOutCubic))
                delay(700)
                badgeAlpha.animateTo(0f, tween(260))
                followedHere = false
            }
            else -> {
                badgeAlpha.snapTo(1f)
                badgeScale.snapTo(1f)
            }
        }
    }

    // Tall enough to hold the badge's touch area, which reaches below the avatar.
    Box(Modifier.width(ActionColumnWidth).height(AvatarSize + BadgeTouchSize / 2)) {
        PressableScale(
            onTap = { NavigationStore.selectUser(handle) },
            tooltip = "Profile of ${tweet.userHandle}",
            modifier = Modifier.align(Alignment.TopCenter),
            pressedScale = 0.92f,
        ) {
            Box(
                Modifier
                    .size(AvatarSize)
                    .border(1.5.dp, Color.White, CircleShape)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(AppColors.surfaceHighest),
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
                    Icon(Icons.Rounded.Person, null, tint = AppColors.textSecondary, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (badge != FollowBadge.Hidden) {
            // The badge is centred on the avatar's lower edge; its touch area is larger than it looks.
            PressableScale(
                onTap = if (badge == FollowBadge.Plus) {
                    {
                        followedHere = true
                        SubscriptionListStore.toggleSubscription(
                            Subscription(
                                id = tweet.userHandle,
                                screenName = handle,
                                name = tweet.userHandle,
                                profileImageUrl = tweet.userAvatarUrl,
                            ),
                        )
                    }
                } else {
                    null
                },
                tooltip = if (badge == FollowBadge.Plus) "Follow ${tweet.userHandle}" else "Following",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = AvatarSize - BadgeTouchSize / 2)
                    .size(BadgeTouchSize),
            ) {
                Box(
                    Modifier
                        .size(BadgeSize)
                        .graphicsLayer {
                            alpha = badgeAlpha.value
                            scaleX = badgeScale.value
                            scaleY = badgeScale.value
                        }
                        .background(Color.Black, CircleShape)
                        .padding(1.5.dp)
                        .background(AppColors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (badge == FollowBadge.Plus) Icons.Rounded.Add else Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                }
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
                        ) { openLink(context, span.target) },
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
