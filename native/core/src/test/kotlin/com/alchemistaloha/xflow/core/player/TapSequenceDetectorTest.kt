package com.alchemistaloha.xflow.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of test/features/player/tap_sequence_detector_test.dart. */
class TapSequenceDetectorTest {
    private val ax = 100f
    private val ay = 300f

    /** A quick tap: down at [start], up [hold] later at the same place. */
    private fun tap(d: TapSequenceDetector, start: Long, hold: Long = 60, x: Float = ax, y: Float = ay, pointer: Long = 1): TapResult {
        d.pointerDown(pointer, x, y, start)
        return d.pointerUp(pointer, x, y, start + hold)
    }

    @Test fun aLoneTapIsReportedAtOnceAsASingleTap() {
        val r = tap(TapSequenceDetector(), 0)
        assertEquals(TapKind.SINGLE, r.kind)
        assertEquals(1, r.count)
        assertEquals(ax, r.x)
        assertEquals(ay, r.y)
    }

    @Test fun aSecondQuickTapIsADoubleTap() {
        // Regression: the first tap used to clear the tap state on release,
        // so the second tap could never be seen as a double tap.
        val d = TapSequenceDetector()
        assertEquals(TapKind.SINGLE, tap(d, 0).kind)
        val second = tap(d, 180)
        assertEquals(TapKind.MULTI, second.kind)
        assertEquals(2, second.count)
        assertTrue(second.isFirstMulti)
    }

    @Test fun furtherQuickTapsKeepProducingHeartsWithoutUndoingAgain() {
        val d = TapSequenceDetector()
        tap(d, 0)
        tap(d, 180)
        val third = tap(d, 360)
        assertEquals(TapKind.MULTI, third.kind)
        assertEquals(3, third.count)
        assertFalse(third.isFirstMulti)
        assertEquals(4, tap(d, 540).count)
    }

    @Test fun twoSlowTapsAreTwoSingleTaps() {
        val d = TapSequenceDetector()
        assertEquals(TapKind.SINGLE, tap(d, 0).kind)
        val second = tap(d, 500)
        assertEquals(TapKind.SINGLE, second.kind)
        assertEquals(1, second.count)
    }

    @Test fun twoQuickTapsFarApartAreTwoSingleTaps() {
        val d = TapSequenceDetector()
        tap(d, 0)
        assertEquals(TapKind.SINGLE, tap(d, 150, x = 100f, y = 600f).kind)
    }

    @Test fun aSwipeIsNotATap() {
        // Regression: every swipe to the next video toggled play/pause.
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerMove(1, ax, ay - 10)
        d.pointerMove(1, ax, ay - 120)
        assertEquals(TapKind.NONE, d.pointerUp(1, ax, ay - 200, 150).kind)
    }

    @Test fun aSwipeThatReturnsToItsStartIsStillNotATap() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerMove(1, ax, ay - 60)
        d.pointerMove(1, ax, ay)
        assertEquals(TapKind.NONE, d.pointerUp(1, ax, ay, 200).kind)
    }

    @Test fun smallFingerJitterIsStillATap() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerMove(1, ax + 4, ay - 6)
        assertEquals(TapKind.SINGLE, d.pointerUp(1, ax + 5, ay - 8, 70).kind)
    }

    @Test fun aLongPressIsNotATap() {
        assertEquals(TapKind.NONE, tap(TapSequenceDetector(), 0, hold = 700).kind)
    }

    @Test fun aSwipeBetweenTwoTapsBreaksTheSequence() {
        val d = TapSequenceDetector()
        tap(d, 0)
        d.pointerDown(1, ax, ay, 100)
        d.pointerMove(1, ax, ay + 80)
        d.pointerUp(1, ax, ay + 80, 160)
        assertEquals(TapKind.SINGLE, tap(d, 220).kind)
    }

    @Test fun aSecondFingerCancelsTheTap() {
        val d = TapSequenceDetector()
        d.pointerDown(1, ax, ay, 0)
        d.pointerDown(2, ax + 80, ay, 20)
        assertEquals(TapKind.NONE, d.pointerUp(2, ax + 80, ay, 60).kind)
        assertEquals(TapKind.NONE, d.pointerUp(1, ax, ay, 80).kind)
        // And the detector recovers afterwards.
        assertEquals(TapKind.SINGLE, tap(d, 1000).kind)
    }

    @Test fun aCancelledPointerDoesNotCount() {
        val d = TapSequenceDetector()
        tap(d, 0)
        d.pointerDown(1, ax, ay, 100)
        d.pointerCancel(1)
        assertEquals(TapKind.SINGLE, tap(d, 200).kind)
    }

    @Test fun resetForgetsThePreviousTap() {
        val d = TapSequenceDetector()
        tap(d, 0)
        d.reset()
        assertEquals(TapKind.SINGLE, tap(d, 100).kind)
    }
}
