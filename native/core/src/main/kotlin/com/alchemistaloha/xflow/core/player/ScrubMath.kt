package com.alchemistaloha.xflow.core.player

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Sizes (in dp) of the strip along the bottom of a feed page: the video seek bar or the image
 * position bar, and how they share the edge with the tab bar underneath.
 *
 * Like TikTok, the line lies directly on the top edge of the tab bar and its round thumb
 * rides on that edge. To make that possible without a finger aimed at the line landing on a
 * tab, a feed page reaches [TAB_OVERLAP] under the tab bar: those rows look like the top of
 * the tab bar, but they belong to the page, so a touch there seeks instead of switching tabs.
 * The touch strip grows upwards from there.
 *
 * "Edge" below is the visible top edge of the tab bar.
 */
object BottomEdgeLayout {
    /** Height of the tab bar's row of tabs. */
    const val TAB_BAR_HEIGHT = 54f

    /** How far a feed page reaches under the top of the tab bar. These rows never switch tabs. */
    const val TAB_OVERLAP = 12f

    /** Kept for the tab bar: its own top rows ignore touches wherever a page does not cover them. */
    const val TAB_GUARD = TAB_OVERLAP

    /** What is left of a tab to press, below the rows that belong to the page. */
    const val TAB_ACTIVE_HEIGHT = TAB_BAR_HEIGHT - TAB_OVERLAP

    /** Touch strip above the edge. The tap layer and the caption stop here. */
    const val TOUCH_ABOVE_EDGE = 32f

    /** Touch strip below the edge: all of the overlap. */
    const val TOUCH_BELOW_EDGE = TAB_OVERLAP

    /** Height of the strip that takes seek / image-scrub touches, from the page's bottom up. */
    const val TOUCH_HEIGHT = TOUCH_ABOVE_EDGE + TOUCH_BELOW_EDGE

    const val TRACK_HEIGHT = 2f
    const val TRACK_HEIGHT_ACTIVE = 5f
    const val THUMB_SIZE = 8f
    const val THUMB_SIZE_ACTIVE = 16f

    /** Centre of the line above the edge: the resting 2dp line sits on the edge with no gap. */
    const val LINE_ABOVE_EDGE = TRACK_HEIGHT / 2

    /** Gap between the resting line and the tab bar. */
    const val LINE_GAP = LINE_ABOVE_EDGE - TRACK_HEIGHT / 2

    /** Centre of the line above the bottom of the page (the page ends [TAB_OVERLAP] under the edge). */
    const val LINE_FROM_BOTTOM = TAB_OVERLAP + LINE_ABOVE_EDGE

    /** The caption, the buttons and the tap layer (play/pause, double tap) stop this far above the edge. */
    const val CONTENT_CLEARANCE = TOUCH_ABOVE_EDGE

    /** How far the thumb hangs over the edge, at rest and grown. Both stay inside the overlap. */
    const val THUMB_OVERHANG = THUMB_SIZE / 2 - LINE_ABOVE_EDGE
    const val THUMB_OVERHANG_ACTIVE = THUMB_SIZE_ACTIVE / 2 - LINE_ABOVE_EDGE

    /**
     * A tap seeks only when it lands on the line, under it, or up to this far above it. Higher
     * up in the strip a tap is still play/pause, so reaching for the bottom of the video does
     * not jump it.
     */
    const val TAP_SEEK_ABOVE_LINE = 16f

    /** True when a tap [yAboveBottom] dp above the bottom of the page is meant for the line. */
    fun tapSeeks(yAboveBottom: Float): Boolean = yAboveBottom <= LINE_FROM_BOTTOM + TAP_SEEK_ABOVE_LINE

    /** From the centre of the line down to the first row that switches tabs. */
    const val LINE_TO_ACTIVE_TAB = LINE_ABOVE_EDGE + TAB_OVERLAP

    /**
     * Which control a touch at [yBelowEdge] dp belongs to on a feed page (negative = above the
     * top edge of the tab bar, positive = inside it).
     */
    fun zoneAt(yBelowEdge: Float): Zone = when {
        yBelowEdge < -TOUCH_ABOVE_EDGE -> Zone.CONTENT
        yBelowEdge < TOUCH_BELOW_EDGE -> Zone.SCRUB
        yBelowEdge < TAB_BAR_HEIGHT -> Zone.TAB
        else -> Zone.BELOW
    }

    enum class Zone { CONTENT, SCRUB, TAB, BELOW }
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
