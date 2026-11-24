package com.alchemistaloha.xflow.core.player

import kotlin.math.hypot

/** What the media surface should do about the taps seen so far. */
sealed interface TapEvent {
    /** Where the finger was lifted, in the listener's local coordinates. */
    val x: Float
    val y: Float

    /**
     * A tap that turned out to stand alone: nothing followed it within the double-tap window.
     * This is the moment to toggle play/pause (or the fullscreen controls).
     */
    data class Single(override val x: Float, override val y: Float) : TapEvent

    /**
     * The second or a later tap of a quick sequence: show a heart at ([x], [y]).
     * [count] is 2 for the double tap itself and grows with every further tap.
     */
    data class Heart(override val x: Float, override val y: Float, val count: Int) : TapEvent {
        /** True only for the tap that turns the sequence into a double tap: the one that likes. */
        val isFirst: Boolean get() = count == 2
    }
}

/**
 * Turns raw pointer events into single taps and quick multi-tap sequences, TikTok style.
 *
 * It is fed raw pointer events rather than a tap gesture detector, so it never competes with
 * the page scroll or the overlay buttons. Because raw events also arrive for swipes, a pointer
 * only counts as a tap when it stays within [touchSlop] and is lifted within [maxTapDurationMs].
 *
 * A first tap does nothing by itself. It is held back until the double-tap window has passed:
 *
 *  - no second touch within [doubleTapWindowMs] of the lift: [poll] reports [TapEvent.Single];
 *  - a second tap that starts within the window and lands within [doubleTapSlop] of the first:
 *    [TapEvent.Heart], and the first tap is dropped. The player is never touched, so a double
 *    tap cannot pause the video, not even for a frame;
 *  - every further tap that starts within [heartWindowMs] of the previous one is another
 *    [TapEvent.Heart], wherever it lands. Only a pause longer than that ends the sequence.
 *
 * The caller owns the clock: after [pointerUp] it reads [singleTapDeadlineMs], waits until
 * then and calls [poll]. A finger that is down again when the deadline passes postpones the
 * decision to its release, so a slow second tap still counts as a double tap.
 *
 * All times are in the time base of the pointer events. Distances are in dp.
 */
class TapSequenceDetector(
    val touchSlop: Float = 18f,
    val maxTapDurationMs: Long = 500,
    val doubleTapWindowMs: Long = DOUBLE_TAP_WINDOW_MS,
    val doubleTapSlop: Float = 100f,
    val heartWindowMs: Long = HEART_WINDOW_MS,
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

    /** A first tap that has not been reported yet. */
    private var pending = false

    /**
     * When the held-back first tap becomes a single tap, or null if there is none. Call [poll]
     * at or after this time.
     */
    val singleTapDeadlineMs: Long?
        get() = if (pending) lastTapTime + doubleTapWindowMs else null

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

    fun pointerUp(pointerId: Long, x: Float, y: Float, timeMs: Long): List<TapEvent> {
        if (activePointers > 0) activePointers--
        if (pointerId != pointer) return emptyList()
        pointer = null

        val moved = hypot(x - downX, y - downY) > touchSlop
        val tooLong = timeMs - downTime > maxTapDurationMs
        if (rejected || moved || tooLong) {
            rejected = false
            // Not a tap. A first tap that was waiting for this touch stands alone after all.
            return endSequence()
        }

        val gap = downTime - lastTapTime
        val continues = hasLastTap && if (count == 1) {
            gap <= doubleTapWindowMs && hypot(x - lastTapX, y - lastTapY) <= doubleTapSlop
        } else {
            gap <= heartWindowMs
        }

        if (continues) {
            pending = false
            count++
            remember(x, y, timeMs)
            return listOf(TapEvent.Heart(x, y, count))
        }

        // A new first tap. If an earlier first tap was still waiting (this one came too late or
        // too far away, and the caller's timer has not run yet), that one stood alone.
        val events = endSequence()
        count = 1
        pending = true
        remember(x, y, timeMs)
        return events
    }

    fun pointerCancel(pointerId: Long): List<TapEvent> {
        if (activePointers > 0) activePointers--
        if (pointerId != pointer) return emptyList()
        pointer = null
        rejected = false
        return endSequence()
    }

    /**
     * Reports the held-back first tap as [TapEvent.Single] once its window has passed. Returns
     * nothing while a finger that touched down inside the window is still down and may yet
     * become the second tap; that touch settles it in [pointerUp] or [pointerCancel].
     */
    fun poll(nowMs: Long): List<TapEvent> {
        if (!pending || nowMs < lastTapTime + doubleTapWindowMs) return emptyList()
        val secondTapUnderWay = pointer != null && !rejected &&
            downTime - lastTapTime <= doubleTapWindowMs &&
            nowMs - downTime <= maxTapDurationMs
        if (secondTapUnderWay) return emptyList()
        pending = false
        return listOf(TapEvent.Single(lastTapX, lastTapY))
    }

    /** Forgets everything, e.g. when the item scrolls off screen. A waiting tap is dropped. */
    fun reset() {
        pointer = null
        rejected = false
        activePointers = 0
        pending = false
        hasLastTap = false
        count = 0
    }

    private fun remember(x: Float, y: Float, timeMs: Long) {
        hasLastTap = true
        lastTapTime = timeMs
        lastTapX = x
        lastTapY = y
    }

    /** Ends the running sequence and hands out the first tap if it was still held back. */
    private fun endSequence(): List<TapEvent> {
        val events = if (pending) listOf(TapEvent.Single(lastTapX, lastTapY)) else emptyList()
        pending = false
        hasLastTap = false
        count = 0
        return events
    }

    companion object {
        /**
         * How long a first tap waits for a second touch, measured from lifting the finger to
         * touching down again. Android's own double-tap timeout is 300 ms; 250 ms still catches
         * an unhurried double tap (the gap between the two touches is usually 80-200 ms) and
         * keeps the delay before a single tap pauses the video below what reads as lag.
         */
        const val DOUBLE_TAP_WINDOW_MS = 250L

        /**
         * Once hearts are flying, the next tap may come this long after the previous one and
         * still be a heart rather than a pause.
         */
        const val HEART_WINDOW_MS = 500L
    }
}
