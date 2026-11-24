package com.alchemistaloha.xflow.ui.feed

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.player.FeedPagePhysics
import com.alchemistaloha.xflow.core.player.MediaPagerLogic
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.player.PlayerPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Flutter `Curves.easeOutCubic`. */
internal val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
internal val EaseInCubic = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)

/** Handle on a [MediaPager]: the Compose counterpart of the Dart PageController + pager state. */
@Stable
class MediaPagerController internal constructor(internal val initialIndex: Int) {
    internal var pagerState: PagerState? = null
    internal var scope: CoroutineScope? = null
    internal var onJump: ((Int) -> Unit)? = null

    /** The item that has landed (not the one a drag is merely passing). */
    var currentIndex: Int by mutableIntStateOf(initialIndex)
        internal set

    fun jumpToPage(index: Int) {
        val state = pagerState ?: return
        scope?.launch {
            state.scrollToPage(index.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
            onJump?.invoke(state.currentPage)
        }
    }

    /** Animated advance to the next item (auto-skip and "play next"). */
    fun nextPage() {
        val state = pagerState ?: return
        if (state.currentPage >= state.pageCount - 1) return
        scope?.launch {
            state.animateScrollToPage(state.currentPage + 1, animationSpec = tween(300, easing = EaseOutCubic))
        }
    }
}

@Composable
fun rememberMediaPagerController(initialIndex: Int = 0): MediaPagerController =
    remember { MediaPagerController(initialIndex) }

/**
 * The vertical, one-item-per-page media list shared by the main feed, the hashtag feed and the
 * profile feed.
 *
 * It decides which item is "current" and keeps the player pool warm around it. Both are done
 * when a swipe has landed, not while the page is still moving (see [MediaPagerLogic]).
 *
 * [isActive] is false while another screen covers this feed. An inactive pager plays nothing
 * and leaves the shared player pool alone. [onIndexChanged] is called once per landed change.
 */
@Composable
fun MediaPager(
    tweets: List<Tweet>,
    controller: MediaPagerController,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    onIndexChanged: ((Int) -> Unit)? = null,
    precacheImages: Boolean = true,
    itemContent: @Composable (tweet: Tweet, isVisible: Boolean, onPlaybackError: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = controller.initialIndex.coerceIn(0, (tweets.size - 1).coerceAtLeast(0)),
    ) { tweets.size }
    val logic = remember { MediaPagerLogic(controller.initialIndex) }
    val keys = remember(tweets) { MediaPagerLogic.uniqueKeys(tweets.map { it.id }) }

    val tweetsNow by rememberUpdatedState(tweets)
    val activeNow by rememberUpdatedState(isActive)
    val onIndexChangedNow by rememberUpdatedState(onIndexChanged)
    val precacheNow by rememberUpdatedState(precacheImages)

    fun playerAt(index: Int) = tweetsNow.getOrNull(index)?.let { PlayerPool[it.id] }

    fun managePool(center: Int? = null) {
        if (!activeNow) return
        try {
            val list = tweetsNow
            val activeIds = HashSet<String>()
            for (i in logic.warmWindow(list.size, center)) {
                val tweet = list[i]
                activeIds.add(tweet.id)
                // The current item is always kept, but only the window around it is warmed.
                if (tweet.isVideo && tweet.mediaUrls.isNotEmpty()) {
                    PlayerPool.warmup(tweet.id, tweet.mediaUrls.first())
                } else if (precacheNow && tweet.mediaUrls.isNotEmpty()) {
                    val loader = SingletonImageLoader.get(context)
                    for (url in tweet.mediaUrls) loader.enqueue(ImageRequest.Builder(context).data(url).build())
                }
            }
            PlayerPool.cleanupExcept(activeIds)
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Error managing player pool: $e")
        }
    }

    val poolJob = remember { arrayOfNulls<Job>(1) }

    fun commit(index: Int) {
        when (val result = logic.commit(index, tweetsNow.size)) {
            MediaPagerLogic.CommitResult.Nothing -> Unit
            MediaPagerLogic.CommitResult.ResumeCurrent -> if (activeNow) playerAt(index)?.play()
            is MediaPagerLogic.CommitResult.Changed -> {
                controller.currentIndex = result.index
                onIndexChangedNow?.invoke(result.index)
                // Pool work waits so it never shares a frame with the end of the snap animation.
                poolJob[0]?.cancel()
                poolJob[0] = scope.launch {
                    delay(MediaPagerLogic.POOL_DELAY_MS)
                    managePool()
                }
            }
        }
    }

    DisposableEffect(controller, pagerState) {
        controller.pagerState = pagerState
        controller.scope = scope
        controller.onJump = { commit(it) }
        onDispose {
            controller.pagerState = null
            controller.scope = null
            controller.onJump = null
        }
    }

    // First composition, a new list, or the screen becoming active again.
    LaunchedEffect(tweets, isActive) { managePool() }

    // Every scroll tick: only the two cheap things that cannot wait for landing.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction.toDouble() }
            .collect { page ->
                for (action in logic.onScroll(page) { playerAt(logic.currentIndex)?.isPlaying == true }) {
                    when (action) {
                        MediaPagerLogic.ScrollAction.PauseCurrent -> playerAt(logic.currentIndex)?.pause()
                        is MediaPagerLogic.ScrollAction.CatchUpPool -> managePool(center = action.center)
                    }
                }
            }
    }

    // Landing: the pager has stopped on a page.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .collect { (settled, scrolling) ->
                if (!scrolling && abs(pagerState.currentPageOffsetFraction) <= MediaPagerLogic.LANDED_EPSILON) {
                    commit(settled)
                }
            }
    }

    val fling = remember(pagerState, density) { FeedPageFlingBehavior(pagerState, density) }

    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        flingBehavior = fling,
        // Fullscreen is one video, like the separate fullscreen route in Flutter: no
        // swiping to the neighbours until it is left.
        userScrollEnabled = !Fullscreen.isActive,
        // The neighbours are laid out ahead of the swipe, so a video surface is never
        // created in the middle of a drag.
        beyondViewportPageCount = 1,
        key = { index -> keys.getOrElse(index) { "#$index" } },
    ) { index ->
        val tweet = tweets.getOrNull(index)
        if (tweet != null) {
            Box(Modifier.fillMaxSize()) {
                itemContent(tweet, index == controller.currentIndex && isActive) {
                    // Playback error (or "play next"): move on after the configured delay,
                    // if the user is still on this item.
                    if (index == controller.currentIndex) {
                        val delaySeconds = SettingsStore.state.value.autoSkipDelaySeconds
                        scope.launch {
                            delay(delaySeconds * 1000L)
                            if (controller.currentIndex == index) controller.nextPage()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Page snapping for the vertical media feed: one page per swipe, a tight critically damped
 * spring (lands quickly, no overshoot), and a release slower than
 * [FeedPagePhysics.FLING_VELOCITY] snaps to the nearest page instead of always advancing.
 */
private class FeedPageFlingBehavior(
    private val state: PagerState,
    private val density: Float,
) : TargetedFlingBehavior {
    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ): Float {
        val pageSize = state.layoutInfo.pageSize + state.layoutInfo.pageSpacing
        if (pageSize <= 0 || state.pageCount == 0) return initialVelocity
        val page = state.currentPage + state.currentPageOffsetFraction.toDouble()
        val target = FeedPagePhysics.targetPage(page, initialVelocity / density.toDouble(), state.pageCount)
        val distance = ((target - page) * pageSize).toFloat()
        if (abs(distance) < 0.5f) return 0f

        var travelled = 0f
        AnimationState(initialValue = 0f, initialVelocity = initialVelocity).animateTo(
            targetValue = distance,
            animationSpec = spring(
                dampingRatio = FeedPagePhysics.SPRING_DAMPING_RATIO.toFloat(),
                stiffness = FeedPagePhysics.COMPOSE_SPRING_STIFFNESS,
                visibilityThreshold = 0.5f,
            ),
        ) {
            // Never run past the page and come back, however hard the fling.
            val wanted = if (distance >= 0) value.coerceAtMost(distance) else value.coerceAtLeast(distance)
            val delta = wanted - travelled
            val consumed = scrollBy(delta)
            travelled += consumed
            onRemainingDistanceUpdated(distance - travelled)
            if (abs(delta - consumed) > 0.5f || wanted == distance) cancelAnimation()
        }
        // Close the last sub-pixel so the pager rests exactly on the page.
        val rest = distance - travelled
        if (abs(rest) in 0.01f..2f) scrollBy(rest)
        return 0f
    }
}
