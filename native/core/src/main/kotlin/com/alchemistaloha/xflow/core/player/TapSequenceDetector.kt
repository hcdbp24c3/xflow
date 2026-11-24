package com.alchemistaloha.xflow.core.player

import kotlin.math.hypot

/** What a completed pointer sequence means for the media surface. */
enum class TapKind {
    /** Not a tap: the finger moved (a swipe), was held too long, or another finger was down. */
    NONE,

    /** First tap of a sequence. The player toggles play/pause right away. */
    SINGLE,

    /** Second or later tap of a quick sequence. Shows a heart and likes. */
    MULTI,
}

data class TapResult(
    val kind: TapKind,
    /** Where the finger was lifted, in the listener's local coordinates. */
    val x: Float,
    val y: Float,
    /** 1 for a single tap, 2 for a double tap, 3+ for further quick taps. */
    val count: Int,
) {
    /**
     * True only for the second tap of a sequence. The first tap already toggled play/pause,
     * so this is the moment to undo that toggle.
     */
    val isFirstMulti: Boolean get() = kind == TapKind.MULTI && count == 2

    companion object {
        val NONE = TapResult(TapKind.NONE, 0f, 0f, 0)
    }
}

/**
 * Turns raw pointer events into single taps and quick multi-tap sequences.
 *
 * It is fed raw pointer events rather than a tap gesture detector, so it never competes with
 * the page scroll or the overlay buttons. Because raw events also arrive for swipes, a pointer
 * only counts as a tap when it stays within [touchSlop] and is lifted within [maxTapDurationMs].
 *
 * The first tap is reported immediately (no waiting to see whether a second one follows), which
 * keeps play/pause instant. A second tap that lands within [multiTapWindowMs] and [multiTapSlop]
 * of the previous one is reported as [TapKind.MULTI]; the caller undoes the play/pause toggle
 * on [TapResult.isFirstMulti].
 *
 * Distances are in the same unit the Flutter app used (logical pixels, i.e. dp).
 */
class TapSequenceDetector(
    val touchSlop: Float = 18f,
    val maxTapDurationMs: Long = 500,
    val multiTapWindowMs: Long = 300,
    val multiTapSlop: Float = 100f,
) {
    private var pointer: Long? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var rejected = false
    private var activePointers = 0

    private var hasLastTap = false
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapTime = 0L
    private var count = 0

    /** [timeMs] is the event time stamp. */
    fun pointerDown(pointerId: Long, x: Float, y: Float, timeMs: Long) {
        activePointers++
        if (activePointers > 1) {
            // Second finger: this is a pinch or a stray touch, not a tap.
            rejected = true
            return
        }
        pointer = pointerId
        downX = x
        downY = y
        downTime = timeMs
        rejected = false
    }

    fun pointerMove(pointerId: Long, x: Float, y: Float) {
        if (pointerId != pointer || rejected) return
        if (hypot(x - downX, y - downY) > touchSlop) rejected = true
    }

    fun pointerUp(pointerId: Long, x: Float, y: Float, timeMs: Long): TapResult {
        if (activePointers > 0) activePointers--
        if (pointerId != pointer) return TapResult.NONE
        pointer = null

        val moved = hypot(x - downX, y - downY) > touchSlop
        val tooLong = timeMs - downTime > maxTapDurationMs
        if (rejected || moved || tooLong) {
            rejected = false
            breakSequence()
            return TapResult.NONE
        }

        val continues = hasLastTap &&
            downTime - lastTapTime <= multiTapWindowMs &&
            hypot(x - lastTapX, y - lastTapY) <= multiTapSlop

        count = if (continues) count + 1 else 1
        hasLastTap = true
        lastTapTime = timeMs
        lastTapX = x
        lastTapY = y

        return TapResult(if (count == 1) TapKind.SINGLE else TapKind.MULTI, x, y, count)
    }

    fun pointerCancel(pointerId: Long) {
        if (activePointers > 0) activePointers--
        if (pointerId != pointer) return
        pointer = null
        rejected = false
        breakSequence()
    }

    /** Forgets everything, e.g. when the item scrolls off screen. */
    fun reset() {
        pointer = null
        rejected = false
        activePointers = 0
        breakSequence()
    }

    private fun breakSequence() {
        hasLastTap = false
        count = 0
    }
}
