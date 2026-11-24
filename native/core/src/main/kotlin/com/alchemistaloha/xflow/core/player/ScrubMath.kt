package com.alchemistaloha.xflow.core.player

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Sizes (in dp) of the strip along the bottom edge of a feed page: the video seek bar or the
 * image position bar, and how far both stay away from the tab bar underneath.
 *
 * The feed page ends exactly where the tab bar begins. The line used to sit on that edge with
 * a 24dp touch strip above it, so a finger aimed at the line landed on a tab half of the time.
 * Now the line is lifted, the touch strip is taller and grows upwards, and the top of the tab
 * bar ignores touches.
 */
object BottomEdgeLayout {
    /** Height of the strip that takes seek / image-scrub touches, from the page's bottom edge up. */
    const val TOUCH_HEIGHT = 44f

    /** Distance from the page's bottom edge (the top of the tab bar) to the centre of the line. */
    const val LINE_FROM_BOTTOM = 16f

    /** Rows at the top of the tab bar that do not react, so a low touch on the line is not a tab. */
    const val TAB_GUARD = 8f

    /** The tap layer (play/pause, double tap) and the caption stop this far above the bottom edge. */
    const val CONTENT_CLEARANCE = TOUCH_HEIGHT

    const val TRACK_HEIGHT = 2f
    const val TRACK_HEIGHT_ACTIVE = 5f
    const val THUMB_SIZE = 8f
    const val THUMB_SIZE_ACTIVE = 16f

    /** Touch strip above the line and below it (down to the tab bar). */
    const val TOUCH_ABOVE_LINE = TOUCH_HEIGHT - LINE_FROM_BOTTOM
    const val TOUCH_BELOW_LINE = LINE_FROM_BOTTOM

    /**
     * A tap seeks only when it lands on the line or up to this far above it. Higher up in the
     * strip a tap is still play/pause, so reaching for the bottom of the video does not jump it.
     */
    const val TAP_SEEK_ABOVE_LINE = 14f

    /** True when a tap [yAboveBottom] dp above the page's bottom edge is meant for the line. */
    fun tapSeeks(yAboveBottom: Float): Boolean = yAboveBottom <= LINE_FROM_BOTTOM + TAP_SEEK_ABOVE_LINE

    /** From the centre of the line down to the first row of the tab bar that reacts. */
    const val LINE_TO_ACTIVE_TAB = LINE_FROM_BOTTOM + TAB_GUARD

    /**
     * Which control a touch at [yBelowPageBottom] dp belongs to (negative = above the page's
     * bottom edge, positive = inside the tab bar).
     */
    fun zoneAt(yBelowPageBottom: Float): Zone = when {
        yBelowPageBottom < -TOUCH_HEIGHT -> Zone.CONTENT
        yBelowPageBottom < 0f -> Zone.SCRUB
        yBelowPageBottom < TAB_GUARD -> Zone.GUARD
        else -> Zone.TAB
    }

    enum class Zone { CONTENT, SCRUB, GUARD, TAB }
}

/** What a finger that went down on a scrub strip is doing. */
enum class DragAxis { UNDECIDED, HORIZONTAL, VERTICAL }

/** Touch position -> seek time / image index. Pure, so it can be tested without a device. */
object ScrubMath {
    /**
     * Decides once the finger left the slop circle: sideways is a scrub, up/down is the feed's
     * page swipe (which must keep working when it starts on the strip). A tie goes sideways,
     * the strip is there to be scrubbed.
     */
    fun dragAxis(dx: Float, dy: Float, slop: Float): DragAxis {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ax < slop && ay < slop) return DragAxis.UNDECIDED
        return if (ay > ax) DragAxis.VERTICAL else DragAxis.HORIZONTAL
    }

    /** Fraction of the video for a touch at [x] on a bar [width] wide; 0 for a bar with no width. */
    fun seekFraction(x: Float, width: Float): Float {
        if (!(width > 0f) || x.isNaN()) return 0f
        return (x / width).coerceIn(0f, 1f)
    }

    /** Position in ms for [fraction] of [durationMs]; never outside the video. */
    fun seekTargetMs(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L || fraction.isNaN()) return 0L
        return (fraction.coerceIn(0f, 1f).toDouble() * durationMs).roundToLong().coerceIn(0L, durationMs)
    }

    /** Played fraction, 0 while the duration is unknown. */
    fun playedFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
    }

    /** Centre of the thumb: follows the progress but never hangs over either end of the bar. */
    fun thumbCenterX(fraction: Float, width: Float, thumbRadius: Float): Float {
        val x = fraction.coerceIn(0f, 1f) * width
        if (width <= thumbRadius * 2) return width / 2
        return x.coerceIn(thumbRadius, width - thumbRadius)
    }

    /** Left edge of a label [labelWidth] wide centred on [centerX], kept [margin] inside the bar. */
    fun labelLeft(centerX: Float, labelWidth: Float, width: Float, margin: Float): Float {
        val max = width - labelWidth - margin
        if (max <= margin) return ((width - labelWidth) / 2).coerceAtLeast(0f)
        return (centerX - labelWidth / 2).coerceIn(margin, max)
    }

    // ---- image position bar ----

    const val INDICATOR_PER_ITEM = 22f
    const val INDICATOR_MIN_WIDTH = 96f
    const val INDICATOR_GAP = 3f
    const val INDICATOR_MIN_SEGMENT = 2f

    /** Touches this far left / right of the bar still belong to it. */
    const val INDICATOR_SLACK = 24f

    /**
     * Width of the segmented bar for [count] images: 22dp per image, at least 96dp so two
     * images are still easy to scrub, at most [available]. With many images the segments get
     * narrower instead of the bar getting wider than the screen.
     */
    fun indicatorWidth(count: Int, available: Float): Float {
        if (count <= 0 || available <= 0f) return 0f
        return (count * INDICATOR_PER_ITEM).coerceAtLeast(INDICATOR_MIN_WIDTH).coerceAtMost(available)
    }

    /** Gap between segments; shrinks when [count] segments of the minimum width would not fit. */
    fun indicatorGap(count: Int, trackWidth: Float): Float {
        if (count <= 1) return 0f
        val fits = (trackWidth - count * INDICATOR_MIN_SEGMENT) / (count - 1)
        return INDICATOR_GAP.coerceAtMost(fits).coerceAtLeast(0f)
    }

    /** Width of one segment. */
    fun segmentWidth(count: Int, trackWidth: Float): Float {
        if (count <= 0) return 0f
        return ((trackWidth - indicatorGap(count, trackWidth) * (count - 1)) / count).coerceAtLeast(0f)
    }

    /** Left edge of segment [index] inside the bar. */
    fun segmentLeft(index: Int, count: Int, trackWidth: Float): Float =
        index * (segmentWidth(count, trackWidth) + indicatorGap(count, trackWidth))

    /** Image index under a touch at [x] (relative to the bar's left edge); the ends clamp. */
    fun pageIndex(x: Float, trackWidth: Float, count: Int): Int {
        if (count <= 0) return 0
        if (!(trackWidth > 0f) || x.isNaN()) return 0
        return floor(x / trackWidth * count).toInt().coerceIn(0, count - 1)
    }

    /**
     * Continuous pager position for a touch at [x]: the middle of segment i is exactly page i,
     * in between the two images are part-way through the slide, so the picture follows the
     * finger. Clamped to the first and last image.
     */
    fun pagePosition(x: Float, trackWidth: Float, count: Int): Float {
        if (count <= 1) return 0f
        if (!(trackWidth > 0f) || x.isNaN()) return 0f
        return (x / trackWidth * count - 0.5f).coerceIn(0f, (count - 1).toFloat())
    }

    /** [position] as the pager wants it: nearest page and an offset in -0.5..0.5 from it. */
    fun splitPagePosition(position: Float, count: Int): PagePosition {
        if (count <= 0) return PagePosition(0, 0f)
        val clamped = position.coerceIn(0f, (count - 1).toFloat())
        val page = clamped.roundToInt().coerceIn(0, count - 1)
        return PagePosition(page, (clamped - page).coerceIn(-0.5f, 0.5f))
    }

    /** True when a touch at [x] (relative to the bar's left edge) is on the bar or its slack. */
    fun hitsIndicator(x: Float, trackWidth: Float, slack: Float = INDICATOR_SLACK): Boolean =
        x >= -slack && x <= trackWidth + slack

    /** "2/5" */
    fun pageCounter(index: Int, count: Int): String = "${index.coerceIn(0, (count - 1).coerceAtLeast(0)) + 1}/$count"
}

data class PagePosition(val page: Int, val offsetFraction: Float)
