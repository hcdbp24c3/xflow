package com.alchemistaloha.xflow.ui.feed

import android.view.HapticFeedbackConstants
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.VideoEndAction
import com.alchemistaloha.xflow.core.player.TapKind
import com.alchemistaloha.xflow.core.player.TapSequenceDetector
import com.alchemistaloha.xflow.core.player.formatSeekTime
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.player.PlayerPool
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppDurations
import com.alchemistaloha.xflow.ui.theme.AppRadius
import com.alchemistaloha.xflow.ui.theme.PressableScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

private val HeartSize = 96.dp
private val EaseOutBack = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val EaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Heart scale over the 700 ms pop: in with a bounce, settle, hold, then grow out. */
internal fun heartScale(t: Float): Float = when {
    t < 0.28f -> lerp(0.3f, 1.15f, EaseOutBack.transform(t / 0.28f))
    t < 0.42f -> lerp(1.15f, 1f, EaseOut.transform((t - 0.28f) / 0.14f))
    t < 0.70f -> 1f
    else -> lerp(1f, 1.25f, EaseIn.transform((t - 0.70f) / 0.30f))
}

internal fun heartOpacity(t: Float): Float =
    if (t <= 0.62f) 1f else 1f - EaseIn.transform(((t - 0.62f) / 0.38f).coerceIn(0f, 1f))

/** Rise in dp (negative is up). */
internal fun heartRise(t: Float): Float =
    if (t <= 0.45f) 0f else -36f * EaseIn.transform(((t - 0.45f) / 0.55f).coerceIn(0f, 1f))

@Composable
internal fun ThinSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(28.dp),
        strokeWidth = 2.2.dp,
        color = Color.White.copy(alpha = 0.7f),
    )
}

/**
 * The media of one feed page: a video, an image (or a swipeable set of images), or the text
 * card for a tweet without media. Handles tap = play/pause, double tap = like, the seek bar,
 * fullscreen, retry / auto-skip on playback errors and what happens when a video ends.
 *
 * [overlay] is drawn above the media (caption and action buttons); it receives the fullscreen
 * toggle when the item is a video.
 */
@OptIn(UnstableApi::class)
@Composable
fun TiktokMediaContainer(
    tweet: Tweet,
    isVisible: Boolean,
    modifier: Modifier = Modifier,
    autoFullscreen: Boolean = false,
    onPlaybackError: (() -> Unit)? = null,
    overlay: (@Composable (onFullscreen: (() -> Unit)?, isFullscreen: Boolean) -> Unit)? = null,
) {
    val density = LocalDensity.current.density
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()

    val taps = remember { TapSequenceDetector() }
    // Play state before the first tap of a sequence, restored on double tap.
    val playingBeforeTap = remember { arrayOfNulls<Boolean>(1) }
    var isPlaying by remember { mutableStateOf(false) }
    // True only when the user paused by tapping; drives the centre play icon, so it does not
    // flash while loading or while a swipe pauses the video.
    var userPaused by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var retryCount by remember(tweet.id) { mutableIntStateOf(0) }
    var failure by remember(tweet.id) { mutableStateOf<String?>(null) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var controlsVisible by remember { mutableStateOf(true) }
    var autoFullscreenDone by remember { mutableStateOf(false) }

    // Double-tap heart
    val heartProgress = remember { Animatable(1f) }
    var heartPosition by remember { mutableStateOf(Offset.Zero) }
    var heartAngle by remember { mutableFloatStateOf(0f) }

    val visibleNow by rememberUpdatedState(isVisible)
    val tweetNow by rememberUpdatedState(tweet)
    val onPlaybackErrorNow by rememberUpdatedState(onPlaybackError)

    val isFullscreen = Fullscreen.tweetId == tweet.id
    val player: ExoPlayer? = if (tweet.isVideo) PlayerPool[tweet.id] else null

    fun currentPlayer(): ExoPlayer? = if (tweetNow.isVideo) PlayerPool[tweetNow.id] else null

    /** Instant play/pause on the first tap, no waiting for a second tap. */
    fun togglePlayPause() {
        val p = currentPlayer() ?: return
        val wasPlaying = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        playingBeforeTap[0] = wasPlaying
        if (wasPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
        userPaused = wasPlaying
    }

    /** The first tap of a double tap already toggled playback; put it back so liking never pauses. */
    fun undoPlayPauseToggle() {
        val before = playingBeforeTap[0]
        playingBeforeTap[0] = null
        val p = currentPlayer() ?: return
        if (before == null) return
        if (before) p.play() else p.pause()
        userPaused = !before
    }

    fun doubleTapAt(position: Offset) {
        // Only like if NOT already liked (but always show hearts, TikTok style). The tweet is
        // passed along because hashtag and profile feeds show tweets that are not in the main
        // feed's list.
        if (!liveTweetNow(tweetNow).isLiked) toggleLike(tweetNow)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        heartPosition = position
        heartAngle = (Random.nextFloat() - 0.5f) * 0.5f
        scope.launch {
            heartProgress.snapTo(0f)
            heartProgress.animateTo(1f, tween(700, easing = LinearEasing))
        }
    }

    val tapModifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Raw events, never consumed: this does not compete with the page scroll.
                // Swipes also arrive here; TapSequenceDetector rejects them by distance and time.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                for (change in event.changes) {
                    val id = change.id.value
                    val x = change.position.x / density
                    val y = change.position.y / density
                    when {
                        change.changedToDown() -> taps.pointerDown(id, x, y, change.uptimeMillis)
                        change.changedToUp() -> {
                            val tap = taps.pointerUp(id, x, y, change.uptimeMillis)
                            // A touch on a page that is still sliding in is not for this item.
                            if (visibleNow) {
                                when (tap.kind) {
                                    TapKind.NONE -> Unit
                                    TapKind.SINGLE ->
                                        if (Fullscreen.tweetId == tweetNow.id) controlsVisible = !controlsVisible
                                        else togglePlayPause()
                                    TapKind.MULTI -> {
                                        if (tap.isFirstMulti) {
                                            if (Fullscreen.tweetId == tweetNow.id) controlsVisible = !controlsVisible
                                            else undoPlayPauseToggle()
                                        }
                                        doubleTapAt(change.position)
                                    }
                                }
                            }
                        }
                        change.pressed && change.positionChanged() -> taps.pointerMove(id, x, y)
                    }
                }
            }
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            autoFullscreenDone = false
        } else {
            taps.reset()
            playingBeforeTap[0] = null
            userPaused = false
        }
    }

    // ---- text only ----
    if (tweet.mediaUrls.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            TextTweetCard(text = tweet.text)
            overlay?.invoke(null, false)
        }
        return
    }

    // ---- images ----
    if (!tweet.isVideo) {
        Box(modifier.fillMaxSize()) {
            val pagerState = rememberPagerState { tweet.mediaUrls.size }
            Box(Modifier.fillMaxSize().then(tapModifier)) {
                if (tweet.mediaUrls.size == 1) {
                    FeedImage(tweet.mediaUrls.first())
                } else {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                        FeedImage(tweet.mediaUrls[index])
                    }
                }
            }
            LikeHeart(heartProgress.value, heartPosition, heartAngle)
            if (tweet.mediaUrls.size > 1) {
                Row(
                    // Above the text overlay
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 132.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(tweet.mediaUrls.size) { index ->
                        val active = pagerState.currentPage == index
                        val width by animateDpAsState(
                            if (active) 14.dp else 5.dp,
                            tween(AppDurations.normal, easing = EaseOut),
                            label = "dot",
                        )
                        Box(
                            Modifier
                                .padding(horizontal = 2.5.dp)
                                .size(width, 5.dp)
                                .background(
                                    if (active) Color.White else Color.White.copy(alpha = 0.4f),
                                    RoundedCornerShape(3.dp),
                                ),
                        )
                    }
                }
            }
            overlay?.invoke(null, false)
        }
        return
    }

    // ---- video ----
    if (player == null) {
        // No player yet: show the poster and the overlay rather than a bare spinner, so the
        // page already looks like itself while swiping in.
        Box(modifier.fillMaxSize()) {
            Thumbnail(tweet.thumbnailUrl)
            ThinSpinner(Modifier.align(Alignment.Center))
            overlay?.invoke(null, false)
        }
        return
    }

    fun exitFullscreen() = Fullscreen.exit(activity)

    fun toggleFullscreen() {
        if (Fullscreen.tweetId == tweetNow.id) {
            exitFullscreen()
        } else {
            val size = currentPlayer()?.videoSize ?: VideoSize.UNKNOWN
            controlsVisible = true
            Fullscreen.enter(
                activity,
                tweetNow.id,
                (size.width * size.pixelWidthHeightRatio).roundToInt(),
                size.height,
            )
        }
    }

    // Listen to this tweet's player. If the pool hands this tweet a new player (the old one
    // was released and warmed up again), the listener moves over to it.
    DisposableEffect(player) {
        isPlaying = player.isPlaying
        buffering = player.playbackState == Player.STATE_BUFFERING
        videoSize = player.videoSize
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) userPaused = false
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    if (!visibleNow) return
                    // Exit fullscreen if active when the video completes
                    if (Fullscreen.tweetId == tweetNow.id) exitFullscreen()
                    when (SettingsStore.state.value.videoEndAction) {
                        VideoEndAction.pause -> Unit // Already stopped at the end
                        VideoEndAction.replay -> {
                            player.seekTo(0)
                            player.play()
                        }
                        // Re-use the same callback for auto-advance
                        VideoEndAction.playNext -> onPlaybackErrorNow?.invoke()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val settings = SettingsStore.state.value
                if (Fullscreen.tweetId == tweetNow.id) exitFullscreen()
                val description = "${error.errorCodeName}: ${error.message ?: error.cause?.message ?: ""}"
                if (retryCount < settings.playbackRetryLimit) {
                    retryCount++
                    AppLogger.log(
                        "XFLOW: Video playback error. Retrying ($retryCount/${settings.playbackRetryLimit})... Error: $description",
                    )
                    // Re-open media to retry
                    PlayerPool.reopen(tweetNow.id, tweetNow.mediaUrls.first(), play = visibleNow)
                } else {
                    AppLogger.log("XFLOW: Video playback failed after retry. Skipping item.")
                    failure = description
                    onPlaybackErrorNow?.invoke()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Play when this page becomes the current one, pause when it stops being it.
    LaunchedEffect(isVisible, player) {
        if (isVisible) {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        } else {
            player.pause()
        }
    }

    // Nothing plays while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (visibleNow && !userPaused && player.playbackState != Player.STATE_ENDED) player.play()
    }

    // Leaving the page (or the screen) while fullscreen puts the window back.
    DisposableEffect(tweet.id) {
        onDispose { if (Fullscreen.tweetId == tweet.id) Fullscreen.exit(activity) }
    }
    LaunchedEffect(isVisible) { if (!isVisible && Fullscreen.tweetId == tweet.id) exitFullscreen() }

    // Handle auto-fullscreen if requested
    LaunchedEffect(isVisible, autoFullscreen, player) {
        if (isVisible && autoFullscreen && !autoFullscreenDone) {
            autoFullscreenDone = true
            // Wait (briefly) for the video size so the orientation is right.
            snapshotFlow { videoSize }.collect { size ->
                if (size.width > 0 && size.height > 0) {
                    if (Fullscreen.tweetId != tweetNow.id) toggleFullscreen()
                    return@collect
                }
            }
        }
    }

    // Fullscreen controls hide themselves after a moment.
    LaunchedEffect(isFullscreen, controlsVisible, isPlaying) {
        if (isFullscreen && controlsVisible && isPlaying) {
            delay(3000)
            controlsVisible = false
        }
    }

    BackHandler(enabled = isFullscreen) { exitFullscreen() }

    val failureText = failure
    if (failureText != null && retryCount >= SettingsStore.state.value.playbackRetryLimit) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = AppColors.textTertiary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(16.dp))
                Text("Playback failed. Moving to next...", color = AppColors.textSecondary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Error: $failureText",
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    color = AppColors.textTertiary,
                    fontSize = 12.sp,
                )
            }
        }
        return
    }

    Box(modifier.fillMaxSize()) {
        // Thumbnail placeholder, shows while the video is loading
        Thumbnail(tweet.thumbnailUrl)

        VideoSurface(player, videoSize)

        // Play/pause + double-tap from raw pointer events. Stops short of the seek bar's
        // touch strip.
        Box(Modifier.fillMaxSize().padding(bottom = 28.dp).then(tapModifier))

        // Buffering: a thin spinner, only for the item on screen.
        if (isVisible) {
            val spinnerAlpha by animateFloatAsState(
                if (buffering && !userPaused) 1f else 0f,
                tween(AppDurations.normal),
                label = "buffering",
            )
            if (spinnerAlpha > 0f) {
                ThinSpinner(Modifier.align(Alignment.Center).alpha(spinnerAlpha))
            }
        }

        // Paused: a single translucent play glyph, no disc behind it.
        if (!isFullscreen) {
            val showPlay = userPaused && !isPlaying
            val playAlpha by animateFloatAsState(if (showPlay) 1f else 0f, tween(AppDurations.fast), label = "play")
            val playScale by animateFloatAsState(
                if (showPlay) 1f else 1.35f,
                tween(AppDurations.normal, easing = EaseOutCubic),
                label = "playScale",
            )
            if (playAlpha > 0f) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(84.dp)
                        .graphicsLayer {
                            alpha = playAlpha
                            scaleX = playScale
                            scaleY = playScale
                        },
                )
            }
        }

        // Double-tap heart
        LikeHeart(heartProgress.value, heartPosition, heartAngle)

        if (isFullscreen) {
            FullscreenControls(
                tweet = tweet,
                player = player,
                visible = controlsVisible,
                isPlaying = isPlaying,
                onPlayPause = { togglePlayPause() },
                onExit = { exitFullscreen() },
            )
        } else {
            // Caption and buttons, above the tap layer so their touches are their own.
            overlay?.invoke({ toggleFullscreen() }, false)
            // Seek bar at the very bottom (thin line)
            TiktokSeekBar(player, active = isVisible, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Thumbnail(url: String?) {
    if (url == null) return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun FeedImage(url: String) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ThinSpinner() } },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.BrokenImage, null, tint = AppColors.textTertiary, modifier = Modifier.size(40.dp))
            }
        },
    )
}

/** The video picture, letterboxed to the video's own shape (fit: contain). */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: ExoPlayer, videoSize: VideoSize) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val videoWidth = videoSize.width * videoSize.pixelWidthHeightRatio
        val aspect = if (videoWidth > 0 && videoSize.height > 0) videoWidth / videoSize.height else 0f
        val boxAspect = if (maxHeight.value > 0) maxWidth.value / maxHeight.value else 0f
        val sized = when {
            aspect <= 0f || boxAspect <= 0f -> Modifier.fillMaxSize()
            aspect >= boxAspect -> Modifier.size(maxWidth, maxWidth / aspect)
            else -> Modifier.size(maxHeight * aspect, maxHeight)
        }
        // One view per player: a re-created player gets a fresh surface.
        key(player) {
            AndroidView(
                modifier = sized,
                factory = { context ->
                    TextureView(context).also { player.setVideoTextureView(it) }
                },
                onRelease = { player.clearVideoTextureView(it) },
            )
        }
    }
}

/** The heart that pops where the user double-tapped. */
@Composable
private fun LikeHeart(progress: Float, position: Offset, angle: Float) {
    if (progress >= 1f) return
    val density = LocalDensity.current
    val half = with(density) { (HeartSize / 2).toPx() }
    val rise = with(density) { heartRise(progress).dp.toPx() }
    Icon(
        Icons.Rounded.Favorite,
        contentDescription = null,
        tint = AppColors.like,
        modifier = Modifier
            .offset { IntOffset((position.x - half).roundToInt(), (position.y - half + rise).roundToInt()) }
            .size(HeartSize)
            .graphicsLayer {
                alpha = heartOpacity(progress)
                rotationZ = Math.toDegrees(angle.toDouble()).toFloat()
                val s = heartScale(progress)
                scaleX = s
                scaleY = s
            },
    )
}

/**
 * Standalone seek bar: only it recomposes as the position moves.
 *
 * At rest it is a hairline along the bottom edge. While the finger is on it, the line
 * thickens, a thumb appears and a time label follows the finger.
 */
@Composable
private fun TiktokSeekBar(player: ExoPlayer, active: Boolean, modifier: Modifier = Modifier) {
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(player, active) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.let { if (it == androidx.media3.common.C.TIME_UNSET) 0L else it }
            if (!active) break
            delay(100)
        }
    }

    if (duration <= 0L) return

    val progress = (if (isSeeking) seekPosition else position.toFloat() / duration).coerceIn(0f, 1f)
    val barHeight by animateDpAsState(if (isSeeking) 4.dp else 2.dp, tween(AppDurations.fast), label = "bar")
    val thumbSize by animateDpAsState(if (isSeeking) 12.dp else 0.dp, tween(AppDurations.fast), label = "thumb")
    val labelWidth = 104.dp

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(player, duration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    isSeeking = true
                    seekPosition = (down.position.x / width).coerceIn(0f, 1f)
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            cancelled = true
                            break
                        }
                        change.consume()
                        if (!change.pressed) break
                        seekPosition = (change.position.x / width).coerceIn(0f, 1f)
                    }
                    if (!cancelled) {
                        val target = (seekPosition * duration).roundToInt().toLong()
                        player.seekTo(target)
                        position = target
                    }
                    isSeeking = false
                }
            },
    ) {
        val totalWidth = maxWidth
        // Background bar
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(barHeight)
                .background(Color.White.copy(alpha = 0.22f)),
        )
        // Progress bar
        Box(
            Modifier.align(Alignment.BottomStart).width(totalWidth * progress).height(barHeight)
                .background(Color.White),
        )
        // Thumb, only while seeking
        if (thumbSize > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = totalWidth * progress - thumbSize / 2, y = -(barHeight / 2 - thumbSize / 2))
                    .size(thumbSize)
                    .background(Color.White, CircleShape),
            )
        }
        // Time label when seeking
        if (isSeeking) {
            val left = (totalWidth * progress - labelWidth / 2)
                .coerceIn(8.dp, (totalWidth - labelWidth - 8.dp).coerceAtLeast(8.dp))
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = left, y = (-30).dp)
                    .width(labelWidth)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(AppRadius.sm))
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${formatSeekTime((progress * duration).toLong())} / ${formatSeekTime(duration)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Controls shown over a fullscreen video: play/pause, seek bar, time, like and exit. */
@Composable
private fun FullscreenControls(
    tweet: Tweet,
    player: ExoPlayer,
    visible: Boolean,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onExit: () -> Unit,
) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(AppDurations.normal), label = "controls")
    if (alpha <= 0f) return

    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.let { if (it == androidx.media3.common.C.TIME_UNSET) 0L else it }
            delay(200)
        }
    }
    val live = rememberLiveTweet(tweet)

    Box(Modifier.fillMaxSize().alpha(alpha)) {
        PressableScale(onTap = onPlayPause, modifier = Modifier.align(Alignment.Center)) {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(64.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Slider(
                value = fraction,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatSeekTime((fraction * duration).toLong())} / ${formatSeekTime(duration)}",
                    color = Color.White,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                PressableScale(onTap = { toggleLike(tweet) }, modifier = Modifier.padding(8.dp)) {
                    Icon(
                        if (live.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = if (live.isLiked) AppColors.like else Color.White,
                    )
                }
                PressableScale(onTap = onExit, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.Rounded.FullscreenExit, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}
