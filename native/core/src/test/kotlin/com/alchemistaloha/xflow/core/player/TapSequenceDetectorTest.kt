package com.alchemistaloha.xflow.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Started as a port of test/features/player/tap_sequence_detector_test.dart. The Kotlin
 * detector has since moved on: a first tap waits for the double-tap window instead of acting
 * at once, so a double tap never touches the player.
 */
class TapSequenceDetectorTest {
    private val ax = 100f
    private val ay = 300f
    private val window = TapSequenceDetector.DOUBLE_TAP_WINDOW_MS

    /** Everything a surface would have done, in order. */
    private class Surface(val d: TapSequenceDetector = TapSequenceDetector()) {
        val events = mutableListOf<TapEvent>()
        val singles get() = events.filterIsInstance<TapEvent.Single>()
        val hearts get() = events.filterIsInstance<TapEvent.Heart>()

        /** A quick tap: down at [start], up [hold] later at the same place. */
        fun tap(start: Long, hold: Long = 60, x: Float = 100f, y: Float = 300f, pointer: Long = 1) {
            // The surface's timer runs before a later touch is delivered.
            timer(start)
            d.pointerDown(pointer, x, y, start)
            events += d.pointerUp(pointer, x, y, start + hold)
        }

        /** The caller's timer: fires once the deadline has been reached. */
        fun timer(now: Long) {
            val deadline = d.singleTapDeadlineMs ?: return
            if (now >= deadline) events += d.poll(now)
        }
    }

    // ---- a lone tap ----

    @Test fun aLoneTapDoesNothingUntilTheWindowHasPassed() {
        val s = Surface()
        s.tap(0)
        assertTrue(s.events.isEmpty())
        assertEquals(60 + window, s.d.singleTapDeadlineMs)

        s.timer(60 + window - 1)
        assertTrue(s.events.isEmpty())
    }

    @Test fun aLoneTapBecomesExactlyOneSingleTapAfterTheWindow() {
        val s = Surface()
        s.tap(0)
        s.timer(60 + window)
        assertEquals(listOf<TapEvent>(TapEvent.Single(ax, ay)), s.events)
        assertNull(s.d.singleTapDeadlineMs)

        // Polling again reports nothing more.
        s.timer(2_000)
        assertTrue(s.d.poll(2_000).isEmpty())
        assertEquals(1, s.events.size)
    }

    @Test fun pollBeforeTheDeadlineKeepsTheTapWaiting() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerUp(1, ax, ay, 60)
        assertTrue(d.poll(100).isEmpty())
        assertEquals(1, d.poll(60 + window).size)
    }

    // ---- double tap ----

    @Test fun aDoubleTapIsOneHeartAndNeverASingleTap() {
        val s = Surface()
        s.tap(0)
        s.tap(180)
        s.timer(5_000)

        assertTrue("a double tap must not reach play/pause", s.singles.isEmpty())
        assertEquals(1, s.hearts.size)
        val heart = s.hearts.single()
        assertEquals(2, heart.count)
        assertTrue(heart.isFirst)
        assertEquals(ax, heart.x)
        assertEquals(ay, heart.y)
    }

    @Test fun theHeartIsReportedAtTheSecondTapWithoutWaiting() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        assertTrue(d.pointerUp(1, ax, ay, 60).isEmpty())
        d.pointerDown(1, ax + 10, ay + 5, 200)
        val events = d.pointerUp(1, ax + 10, ay + 5, 250)
        assertEquals(listOf<TapEvent>(TapEvent.Heart(ax + 10, ay + 5, 2)), events)
        assertNull(d.singleTapDeadlineMs)
    }

    @Test fun aSecondTapThatStartsInTheWindowButEndsAfterItIsStillADoubleTap() {
        // First tap lifted at 60, so the deadline is 60 + window. The second finger is down
        // just before it and lifted after it; the timer fires in between.
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerUp(1, ax, ay, 60)
        d.pointerDown(1, ax, ay, 60 + window - 10)
        assertTrue("the decision waits for the finger", d.poll(60 + window).isEmpty())
        val events = d.pointerUp(1, ax, ay, 60 + window + 70)
        assertEquals(listOf<TapEvent>(TapEvent.Heart(ax, ay, 2)), events)
        assertTrue(d.poll(10_000).isEmpty())
    }

    // ---- many taps ----

    @Test fun fiveQuickTapsAreFourHeartsAndNoSingleTap() {
        val s = Surface()
        for (i in 0 until 5) s.tap(i * 170L)
        s.timer(10_000)

        assertTrue(s.singles.isEmpty())
        assertEquals(listOf(2, 3, 4, 5), s.hearts.map { it.count })
        assertEquals(listOf(true, false, false, false), s.hearts.map { it.isFirst })
    }

    @Test fun everyHeartCarriesItsOwnPosition() {
        val s = Surface()
        s.tap(0, x = 100f, y = 300f)
        s.tap(170, x = 120f, y = 310f)
        s.tap(340, x = 300f, y = 700f) // far away: still a heart once hearts are flying
        s.tap(510, x = 40f, y = 90f)
        s.timer(10_000)

        assertTrue(s.singles.isEmpty())
        assertEquals(listOf(120f to 310f, 300f to 700f, 40f to 90f), s.hearts.map { it.x to it.y })
    }

    @Test fun heartsKeepComingAtASlowerPaceThanTheDoubleTapItself() {
        // 400 ms between taps: too slow for a double tap, fine once hearts are flying.
        val s = Surface()
        s.tap(0)
        s.tap(150)
        s.tap(150 + 60 + 400)
        s.tap(150 + 60 + 400 + 60 + 400)
        s.timer(10_000)
        assertTrue(s.singles.isEmpty())
        assertEquals(3, s.hearts.size)
    }

    @Test fun aPauseAfterHeartsStartsOver() {
        val s = Surface()
        s.tap(0)
        s.tap(170)
        s.tap(340)
        assertEquals(2, s.hearts.size)

        // Long after the last heart: a plain tap again.
        s.tap(2_000)
        assertEquals(2, s.hearts.size)
        assertTrue(s.singles.isEmpty())
        s.timer(2_060 + window)
        assertEquals(1, s.singles.size)

        // And a fresh double tap likes again.
        s.tap(5_000)
        s.tap(5_170)
        assertEquals(3, s.hearts.size)
        assertTrue(s.hearts.last().isFirst)
        assertEquals(1, s.singles.size)
    }

    // ---- two single taps ----

    @Test fun tapWaitTapIsTwoSingleTaps() {
        val s = Surface()
        s.tap(0)
        s.timer(60 + window)
        assertEquals(1, s.singles.size)

        s.tap(800)
        s.timer(860 + window)
        assertEquals(2, s.singles.size)
        assertTrue(s.hearts.isEmpty())
    }

    @Test fun aSecondTapJustAfterTheWindowIsASingleTapEvenIfTheTimerIsLate() {
        // The timer never runs between the two taps. The first tap must still come out as a
        // single tap, before the second one starts waiting.
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerUp(1, ax, ay, 60)
        d.pointerDown(1, ax, ay, 60 + window + 1)
        val events = d.pointerUp(1, ax, ay, 60 + window + 50)
        assertEquals(listOf<TapEvent>(TapEvent.Single(ax, ay)), events)
        assertEquals(60 + window + 50 + window, d.singleTapDeadlineMs)
        assertEquals(1, d.poll(60 + window + 50 + window).size)
    }

    @Test fun aFingerThatComesDownAfterTheWindowDoesNotHoldTheSingleTapBack() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerUp(1, ax, ay, 60)
        d.pointerDown(1, ax, ay, 60 + window + 5)
        assertEquals(listOf<TapEvent>(TapEvent.Single(ax, ay)), d.poll(60 + window + 6))
    }

    @Test fun twoQuickTapsFarApartAreTwoSingleTaps() {
        val s = Surface()
        s.tap(0)
        s.tap(150, x = 100f, y = 600f)
        assertEquals(listOf<TapEvent>(TapEvent.Single(ax, ay)), s.events)
        s.timer(210 + window)
        assertEquals(listOf<TapEvent>(TapEvent.Single(ax, ay), TapEvent.Single(100f, 600f)), s.events)
    }

    // ---- not taps ----

    @Test fun aSwipeIsNotATap() {
        // Regression: every swipe to the next video toggled play/pause.
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerMove(1, ax, ay - 10)
        d.pointerMove(1, ax, ay - 120)
        assertTrue(d.pointerUp(1, ax, ay - 200, 150).isEmpty())
        assertNull(d.singleTapDeadlineMs)
        assertTrue(d.poll(10_000).isEmpty())
    }

    @Test fun aSwipeThatReturnsToItsStartIsStillNotATap() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerMove(1, ax, ay - 60)
        d.pointerMove(1, ax, ay)
        assertTrue(d.pointerUp(1, ax, ay, 200).isEmpty())
        assertTrue(d.poll(10_000).isEmpty())
    }

    @Test fun smallFingerJitterIsStillATap() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerMove(1, ax + 4, ay - 6)
        assertTrue(d.pointerUp(1, ax + 5, ay - 8, 70).isEmpty())
        assertEquals(listOf<TapEvent>(TapEvent.Single(ax + 5, ay - 8)), d.poll(70 + window))
    }

    @Test fun aLongPressIsNotATap() {
        val s = Surface()
        s.tap(0, hold = 700)
        s.timer(10_000)
        assertTrue(s.events.isEmpty())
    }

    @Test fun aSwipeRightAfterATapLeavesOneSingleTapAndNoHeart() {
        val s = Surface()
        s.tap(0)
        s.d.pointerDown(1, ax, ay, 100)
        s.d.pointerMove(1, ax, ay + 80)
        // The first tap was waiting for this touch; it was not a tap, so the first stands alone.
        s.events += s.d.pointerUp(1, ax, ay + 80, 160)
        assertEquals(listOf<TapEvent>(TapEvent.Single(ax, ay)), s.events)

        // The swipe broke the sequence: the next tap is a first tap again.
        s.tap(220)
        assertEquals(1, s.events.size)
        s.timer(280 + window)
        assertEquals(2, s.singles.size)
        assertTrue(s.hearts.isEmpty())
    }

    @Test fun aSwipeDuringHeartsEndsTheSequenceWithoutASingleTap() {
        val s = Surface()
        s.tap(0)
        s.tap(170)
        s.d.pointerDown(1, ax, ay, 300)
        s.d.pointerMove(1, ax, ay - 200)
        s.events += s.d.pointerUp(1, ax, ay - 200, 380)
        assertTrue(s.singles.isEmpty())
        assertEquals(1, s.hearts.size)

        s.tap(450)
        s.timer(10_000)
        assertEquals(1, s.singles.size)
        assertEquals(1, s.hearts.size)
    }

    @Test fun aSecondFingerCancelsTheTap() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerDown(2, ax + 80, ay, 20)
        assertTrue(d.pointerUp(2, ax + 80, ay, 60).isEmpty())
        assertTrue(d.pointerUp(1, ax, ay, 80).isEmpty())
        assertTrue(d.poll(10_000).isEmpty())
        // And the detector recovers afterwards.
        d.pointerDown(1, ax, ay, 20_000)
        d.pointerUp(1, ax, ay, 20_050)
        assertEquals(1, d.poll(20_050 + window).size)
    }

    @Test fun aCancelledSecondTouchLeavesTheFirstTapAsASingleTap() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerUp(1, ax, ay, 60)
        d.pointerDown(1, ax, ay, 100)
        assertEquals(listOf<TapEvent>(TapEvent.Single(ax, ay)), d.pointerCancel(1))
        assertTrue(d.poll(10_000).isEmpty())
    }

    @Test fun aSecondTouchHeldTooLongNoLongerHoldsTheSingleTapBack() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerUp(1, ax, ay, 60)
        d.pointerDown(1, ax, ay, 100)
        assertTrue(d.poll(60 + window).isEmpty())
        // Held past the longest tap: it can no longer be the second tap.
        assertEquals(1, d.poll(100 + 501).size)
        assertTrue(d.pointerUp(1, ax, ay, 900).isEmpty())
    }

    @Test fun resetDropsAWaitingTap() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerUp(1, ax, ay, 60)
        d.reset()
        assertNull(d.singleTapDeadlineMs)
        assertTrue(d.poll(10_000).isEmpty())
    }

    @Test fun resetForgetsThePreviousTap() {
        val s = Surface()
        s.tap(0)
        s.d.reset()
        s.tap(100)
        assertTrue(s.events.isEmpty())
        s.timer(160 + window)
        assertEquals(1, s.singles.size)
        assertFalse(s.hearts.isNotEmpty())
    }

    @Test fun theWindowIsShortEnoughToFeelInstantAndLongEnoughForADoubleTap() {
        assertTrue(window in 200L..250L)
        assertTrue(TapSequenceDetector.HEART_WINDOW_MS > window)
    }
}
