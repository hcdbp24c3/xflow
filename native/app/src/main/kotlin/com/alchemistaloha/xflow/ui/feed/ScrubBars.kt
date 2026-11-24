package com.alchemistaloha.xflow.ui.feed

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.alchemistaloha.xflow.core.player.BottomEdgeLayout
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.core.player.DragAxis
import com.alchemistaloha.xflow.core.player.ScrubMath
import com.alchemistaloha.xflow.core.player.formatSeekTime
import com.alchemistaloha.xflow.ui.theme.AppDurations
import com.alchemistaloha.xflow.ui.theme.AppRadius
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How far a feed page reaches under the tab bar right now. Nothing in full screen: the tab bar
 * is gone and the video has the whole window.
 */
internal val tabBarOverlap: Dp
    get() = if (Fullscreen.isActive) 0.dp else BottomEdgeLayout.TAB_OVERLAP.dp

/**
 * The rows of a feed screen that lie under the top of the tab bar, painted like the tab bar
 * (its colour and its hairline), so the bar looks whole. Goes behind the pager: every page
 * leaves these rows clear except for its seek / image bar, whose thumb rides on the edge.
 */
@Composable
internal fun TabBarEdge(modifier: Modifier = Modifier) {
    if (Fullscreen.isActive) return
    val outline = AppColors.outline
    Box(
        modifier
            .fillMaxWidth()
            .height(BottomEdgeLayout.TAB_OVERLAP.dp)
            .background(AppColors.background)
            .drawBehind { drawLine(outline, Offset.Zero, Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx()) },
    )
}

private enum class ScrubEnd { TAP, SCRUB, ABANDONED }

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Follows the finger that went down on a scrub strip until it lifts.
 *
 * Nothing is consumed until the finger has clearly moved sideways, so a swipe up or down that
 * starts on the strip still turns the feed's page. From then on every move is consumed and
 * reported through [onScrub], and nothing else (the feed, the tab bar underneath) sees the
 * gesture, wherever the finger wanders.
 */
private suspend fun AwaitPointerEventScope.awaitScrub(
    down: PointerInputChange,
    slop: Float,
    onScrub: (Offset) -> Unit,
): Pair<ScrubEnd, Offset> {
    var axis = DragAxis.UNDECIDED
    var last = down.position
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return ScrubEnd.ABANDONED to last
        // Somebody else (the feed pager) already took the gesture.
        if (axis == DragAxis.UNDECIDED && change.isConsumed) return ScrubEnd.ABANDONED to last
        last = change.position
        if (!change.pressed) {
            change.consume()
            return (if (axis == DragAxis.HORIZONTAL) ScrubEnd.SCRUB else ScrubEnd.TAP) to last
        }
        if (axis == DragAxis.UNDECIDED) {
            axis = ScrubMath.dragAxis(last.x - down.position.x, last.y - down.position.y, slop)
            if (axis == DragAxis.VERTICAL) return ScrubEnd.ABANDONED to last
        }
        if (axis == DragAxis.HORIZONTAL) {
            change.consume()
            onScrub(last)
        }
    }
}

/**
 * The video seek bar, TikTok style.
 *
 * At rest: a thin line lying on the top edge of the tab bar, with a small round thumb at the
 * playing position riding on that edge. The bar's lowest rows lie under the tab bar (see
 * [BottomEdgeLayout]), so a touch that lands a little low still seeks. Touched: the line thickens and the thumb grows; dragged sideways: a
 * "current / total" label follows the thumb and the video seeks when the finger lifts.
 *
 * The position is only read while drawing, so the moving thumb redraws this one node and
 * recomposes nothing.
 *
 * [onTapAbove] gets a tap that landed in the upper part of the touch strip, away from the
 * line: that is still a tap on the video.
 */
@Composable
internal fun TiktokSeekBar(
    player: ExoPlayer,
    active: Boolean,
    onTapAbove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = remember(player) { mutableLongStateOf(0L) }
    val duration = remember(player) { mutableLongStateOf(0L) }
    var pressed by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    val seekFraction = remember { mutableFloatStateOf(0f) }
    val grow = animateFloatAsState(if (pressed) 1f else 0f, tween(AppDurations.fast), label = "seekGrow")
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val onTapAboveNow by rememberUpdatedState(onTapAbove)

    LaunchedEffect(player, active) {
        while (true) {
            position.longValue = player.currentPosition
            duration.longValue = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
            if (!active) break
            delay(50)
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(BottomEdgeLayout.TOUCH_HEIGHT.dp)
            // A drag that starts at the screen's left or right edge is a seek, not "back".
            .systemGestureExclusion()
            .pointerInput(player) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val total = duration.longValue
                    if (total <= 0L) return@awaitEachGesture
                    val width = size.width.toFloat()
                    pressed = true
                    try {
                        val (end, last) = awaitScrub(down, touchSlop) { at ->
                            seekFraction.floatValue = ScrubMath.seekFraction(at.x, width)
                            isSeeking = true
                        }
                        val target = when (end) {
                            ScrubEnd.SCRUB -> ScrubMath.seekTargetMs(seekFraction.floatValue, total)
                            ScrubEnd.TAP ->
                                if (BottomEdgeLayout.tapSeeks((size.height - last.y) / density)) {
                                    ScrubMath.seekTargetMs(ScrubMath.seekFraction(last.x, width), total)
                                } else {
                                    onTapAboveNow()
                                    null
                                }
                            ScrubEnd.ABANDONED -> null
                        }
                        if (target != null) {
                            player.seekTo(target)
                            position.longValue = target
                        }
                    } finally {
                        pressed = false
                        isSeeking = false
                    }
                }
            }
            .drawBehind {
                val total = duration.longValue
                if (total <= 0L) return@drawBehind
                val g = grow.value
                val fraction =
                    if (isSeeking) seekFraction.floatValue else ScrubMath.playedFraction(position.longValue, total)
                val trackHeight = mix(BottomEdgeLayout.TRACK_HEIGHT, BottomEdgeLayout.TRACK_HEIGHT_ACTIVE, g).dp.toPx()
                val radius = mix(BottomEdgeLayout.THUMB_SIZE, BottomEdgeLayout.THUMB_SIZE_ACTIVE, g).dp.toPx() / 2
                val centerY = size.height - BottomEdgeLayout.LINE_FROM_BOTTOM.dp.toPx()
                val thumbX = ScrubMath.thumbCenterX(fraction, size.width, radius)
                val top = centerY - trackHeight / 2
                drawRect(Color.White.copy(alpha = 0.28f), Offset(0f, top), Size(size.width, trackHeight))
                drawRect(Color.White, Offset(0f, top), Size(thumbX, trackHeight))
                // A faint ring keeps the thumb visible on a white picture.
                drawCircle(Color.Black.copy(alpha = 0.22f), radius + 1.dp.toPx(), Offset(thumbX, centerY))
                drawCircle(Color.White, radius, Offset(thumbX, centerY))
            },
    ) {
        if (isSeeking) {
            SeekTimeLabel(
                fraction = seekFraction,
                durationMs = duration.longValue,
                barWidthPx = constraints.maxWidth.toFloat(),
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

private val SeekLabelWidth = 112.dp

/** "00:12 / 01:30" above the thumb while seeking; kept on screen at both ends. */
@Composable
private fun SeekTimeLabel(fraction: FloatState, durationMs: Long, barWidthPx: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .offset {
                val radius = BottomEdgeLayout.THUMB_SIZE_ACTIVE.dp.toPx() / 2
                val thumbX = ScrubMath.thumbCenterX(fraction.floatValue, barWidthPx, radius)
                val left = ScrubMath.labelLeft(thumbX, SeekLabelWidth.toPx(), barWidthPx, 8.dp.toPx())
                // Sits above the grown thumb.
                IntOffset(left.roundToInt(), -(BottomEdgeLayout.LINE_FROM_BOTTOM + 18f).dp.roundToPx())
            }
            .width(SeekLabelWidth)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(AppRadius.sm))
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${formatSeekTime(ScrubMath.seekTargetMs(fraction.floatValue, durationMs))} / ${formatSeekTime(durationMs)}",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Position bar of a post with several images: one segment per image, the current one bright.
 *
 * It sits where the video seek bar sits and can be used the same way: drag sideways and the
 * images slide with the finger, tap a segment to jump to that image. Swiping on the picture
 * itself keeps working. Only the bar and a little slack left and right of it take touches;
 * the rest of the bottom edge still belongs to the picture.
 */
@Composable
internal fun ImagePositionBar(pagerState: PagerState, count: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    // Where the finger wants the pager, in pages; null when it is not scrubbing.
    var scrubTarget by remember { mutableStateOf<Float?>(null) }
    val grow = animateFloatAsState(if (pressed) 1f else 0f, tween(AppDurations.fast), label = "imageBarGrow")
    val touchSlop = LocalViewConfiguration.current.touchSlop

    // One at a time and always the newest: the picture follows the finger without a queue.
    LaunchedEffect(pagerState, count) {
        snapshotFlow { scrubTarget }.collectLatest { target ->
            if (target != null) {
                val split = ScrubMath.splitPagePosition(target, count)
                pagerState.scrollToPage(split.page, split.offsetFraction)
            }
        }
    }

    BoxWithConstraints(
        modifier.fillMaxWidth().height(BottomEdgeLayout.TOUCH_HEIGHT.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val trackWidth = ScrubMath.indicatorWidth(count, maxWidth.value - 32f)
        val touchWidth = (trackWidth + ScrubMath.INDICATOR_SLACK * 2).coerceAtMost(maxWidth.value)

        Box(
            Modifier
                .width(touchWidth.dp)
                .fillMaxHeight()
                .systemGestureExclusion()
                .pointerInput(count, trackWidth) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val trackPx = trackWidth.dp.toPx()
                        val left = (size.width - trackPx) / 2
                        pressed = true
                        try {
                            val (end, last) = awaitScrub(down, touchSlop) { at ->
                                scrubTarget = ScrubMath.pagePosition(at.x - left, trackPx, count)
                                scrubbing = true
                            }
                            val page = when (end) {
                                ScrubEnd.SCRUB ->
                                    ScrubMath.splitPagePosition(
                                        ScrubMath.pagePosition(last.x - left, trackPx, count),
                                        count,
                                    ).page
                                ScrubEnd.TAP -> ScrubMath.pageIndex(last.x - left, trackPx, count)
                                ScrubEnd.ABANDONED -> null
                            }
                            scrubTarget = null
                            if (page != null) scope.launch { pagerState.animateScrollToPage(page) }
                        } finally {
                            if (scrubTarget != null) {
                                // Cancelled half-way between two images: settle on the nearer one.
                                scrubTarget = null
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
                            }
                            pressed = false
                            scrubbing = false
                        }
                    }
                }
                .drawBehind {
                    val g = grow.value
                    val height = mix(3f, 5f, g).dp.toPx()
                    val centerY = size.height - BottomEdgeLayout.LINE_FROM_BOTTOM.dp.toPx()
                    val left = (size.width - trackWidth.dp.toPx()) / 2
                    val segment = ScrubMath.segmentWidth(count, trackWidth).dp.toPx()
                    val current = pagerState.currentPage
                    val corner = CornerRadius(height / 2)
                    for (index in 0 until count) {
                        drawRoundRect(
                            color = if (index == current) Color.White else Color.White.copy(alpha = 0.38f),
                            topLeft = Offset(
                                left + ScrubMath.segmentLeft(index, count, trackWidth).dp.toPx(),
                                centerY - height / 2,
                            ),
                            size = Size(segment, height),
                            cornerRadius = corner,
                        )
                    }
                },
        )

        if (scrubbing) {
            Box(
                Modifier
                    .offset(y = -(BottomEdgeLayout.LINE_FROM_BOTTOM + 14f).dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(AppRadius.sm))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    ScrubMath.pageCounter(pagerState.currentPage, count),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
