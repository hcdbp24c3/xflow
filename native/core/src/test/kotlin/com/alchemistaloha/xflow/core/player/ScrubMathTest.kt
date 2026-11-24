package com.alchemistaloha.xflow.core.player

import com.alchemistaloha.xflow.core.player.BottomEdgeLayout.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seek bar and image position bar: touch -> time, touch -> image, and who owns which rows. */
class ScrubMathTest {
    // ---- touch zones along the bottom edge ----

    @Test fun theScrubStripIsTallEnoughToHit() {
        assertTrue(BottomEdgeLayout.TOUCH_HEIGHT >= 40f)
        assertEquals(44f, BottomEdgeLayout.TOUCH_HEIGHT, 0f)
        assertEquals(BottomEdgeLayout.TOUCH_HEIGHT, BottomEdgeLayout.TOUCH_ABOVE_EDGE + BottomEdgeLayout.TOUCH_BELOW_EDGE, 0f)
    }

    @Test fun theLineSitsOnTheTabBarWithNoGap() {
        assertEquals(0f, BottomEdgeLayout.LINE_GAP, 0f)
        assertEquals(1f, BottomEdgeLayout.LINE_ABOVE_EDGE, 0f)
        // Measured from the bottom of the page, which ends under the tab bar.
        assertEquals(BottomEdgeLayout.TAB_OVERLAP + 1f, BottomEdgeLayout.LINE_FROM_BOTTOM, 0f)
    }

    @Test fun theThumbRidesOnTheEdgeAndIsNeverCutOff() {
        // It hangs over the edge, like TikTok's...
        assertTrue(BottomEdgeLayout.THUMB_OVERHANG > 0f)
        assertEquals(3f, BottomEdgeLayout.THUMB_OVERHANG, 0f)
        assertEquals(7f, BottomEdgeLayout.THUMB_OVERHANG_ACTIVE, 0f)
        // ...but the page reaches further under the tab bar than the grown thumb (and its 1dp
        // ring), so nothing clips it.
        assertTrue(BottomEdgeLayout.THUMB_OVERHANG_ACTIVE + 1f <= BottomEdgeLayout.TAB_OVERLAP)
        assertTrue(BottomEdgeLayout.TRACK_HEIGHT_ACTIVE / 2 - BottomEdgeLayout.LINE_ABOVE_EDGE < BottomEdgeLayout.TAB_OVERLAP)
    }

    @Test fun theStripGrowsUpwardsAndTakesOnlyTheTopRowsOfTheTabBar() {
        assertTrue(BottomEdgeLayout.TOUCH_ABOVE_EDGE > BottomEdgeLayout.TOUCH_BELOW_EDGE)
        assertTrue(BottomEdgeLayout.TOUCH_ABOVE_EDGE >= 28f)
        assertTrue(BottomEdgeLayout.TOUCH_BELOW_EDGE in 10f..12f)
        // Just above and just below the edge: both seek.
        assertEquals(Zone.SCRUB, BottomEdgeLayout.zoneAt(-0.5f))
        assertEquals(Zone.SCRUB, BottomEdgeLayout.zoneAt(0f))
        assertEquals(Zone.SCRUB, BottomEdgeLayout.zoneAt(11.9f))
        assertEquals(Zone.TAB, BottomEdgeLayout.zoneAt(12f))
    }

    @Test fun aTouchAimedAtTheLineStaysOutOfTheTabsEvenWhenItLandsLow() {
        val line = -BottomEdgeLayout.LINE_ABOVE_EDGE
        assertEquals(Zone.SCRUB, BottomEdgeLayout.zoneAt(line))
        // Up to 13dp too low is still a seek, not a tab (it was 1dp when the line first sat here).
        assertEquals(13f, BottomEdgeLayout.LINE_TO_ACTIVE_TAB, 0f)
        assertEquals(Zone.SCRUB, BottomEdgeLayout.zoneAt(line + 12.9f))
        assertEquals(Zone.TAB, BottomEdgeLayout.zoneAt(line + 13f))
    }

    @Test fun theTabsKeepMostOfTheirHeightAndAllOfTheirIconAndLabel() {
        assertEquals(42f, BottomEdgeLayout.TAB_ACTIVE_HEIGHT, 0f)
        assertEquals(BottomEdgeLayout.TAB_OVERLAP, BottomEdgeLayout.TAB_GUARD, 0f)
        // A tab is a 24dp icon, 3dp, and a label of about 13dp, centred in 54dp: it starts 7dp
        // down, so only the top 5dp of the icon lies on rows that do not switch tabs.
        val contentTop = (BottomEdgeLayout.TAB_BAR_HEIGHT - (24f + 3f + 13f)) / 2
        assertTrue(BottomEdgeLayout.TAB_OVERLAP - contentTop <= 5f)
        assertEquals(Zone.TAB, BottomEdgeLayout.zoneAt(BottomEdgeLayout.TAB_BAR_HEIGHT - 0.1f))
        assertEquals(Zone.BELOW, BottomEdgeLayout.zoneAt(BottomEdgeLayout.TAB_BAR_HEIGHT))
    }

    @Test fun aboveTheStripTouchesBelongToTheVideo() {
        assertEquals(Zone.CONTENT, BottomEdgeLayout.zoneAt(-BottomEdgeLayout.TOUCH_ABOVE_EDGE - 0.5f))
        assertEquals(Zone.SCRUB, BottomEdgeLayout.zoneAt(-BottomEdgeLayout.TOUCH_ABOVE_EDGE))
        assertEquals(BottomEdgeLayout.TOUCH_ABOVE_EDGE, BottomEdgeLayout.CONTENT_CLEARANCE, 0f)
    }

    @Test fun aTapSeeksNearTheLineAndIsPlayPauseHigherUp() {
        // Under the line (the rows inside the tab bar) and on it.
        assertTrue(BottomEdgeLayout.tapSeeks(0f))
        assertTrue(BottomEdgeLayout.tapSeeks(BottomEdgeLayout.LINE_FROM_BOTTOM))
        // 16dp above the line is the last row that seeks; the top 15dp of the strip is play/pause.
        assertTrue(BottomEdgeLayout.tapSeeks(29f))
        assertFalse(BottomEdgeLayout.tapSeeks(29.5f))
        assertFalse(BottomEdgeLayout.tapSeeks(BottomEdgeLayout.TOUCH_HEIGHT))
    }

    // ---- which way is the finger going ----

    @Test fun nothingIsDecidedInsideTheSlop() {
        assertEquals(DragAxis.UNDECIDED, ScrubMath.dragAxis(0f, 0f, 8f))
        assertEquals(DragAxis.UNDECIDED, ScrubMath.dragAxis(7.9f, -7.9f, 8f))
    }

    @Test fun sidewaysIsAScrub() {
        assertEquals(DragAxis.HORIZONTAL, ScrubMath.dragAxis(9f, 2f, 8f))
        assertEquals(DragAxis.HORIZONTAL, ScrubMath.dragAxis(-30f, 12f, 8f))
        // A tie goes to the strip.
        assertEquals(DragAxis.HORIZONTAL, ScrubMath.dragAxis(10f, 10f, 8f))
    }

    @Test fun upAndDownIsLeftToTheFeed() {
        assertEquals(DragAxis.VERTICAL, ScrubMath.dragAxis(2f, -9f, 8f))
        assertEquals(DragAxis.VERTICAL, ScrubMath.dragAxis(-6f, 40f, 8f))
    }

    // ---- seek bar ----

    @Test fun seekFractionFollowsTheFingerAndClamps() {
        assertEquals(0f, ScrubMath.seekFraction(0f, 1080f), 0f)
        assertEquals(0.5f, ScrubMath.seekFraction(540f, 1080f), 1e-6f)
        assertEquals(1f, ScrubMath.seekFraction(1080f, 1080f), 0f)
        assertEquals(0f, ScrubMath.seekFraction(-50f, 1080f), 0f)
        assertEquals(1f, ScrubMath.seekFraction(5000f, 1080f), 0f)
    }

    @Test fun seekFractionSurvivesABarWithNoWidth() {
        assertEquals(0f, ScrubMath.seekFraction(10f, 0f), 0f)
        assertEquals(0f, ScrubMath.seekFraction(Float.NaN, 100f), 0f)
    }

    @Test fun seekTargetIsInsideTheVideo() {
        assertEquals(0L, ScrubMath.seekTargetMs(0f, 61_000))
        assertEquals(30_500L, ScrubMath.seekTargetMs(0.5f, 61_000))
        assertEquals(61_000L, ScrubMath.seekTargetMs(1f, 61_000))
        assertEquals(61_000L, ScrubMath.seekTargetMs(1.7f, 61_000))
        assertEquals(0L, ScrubMath.seekTargetMs(-0.2f, 61_000))
        assertEquals(0L, ScrubMath.seekTargetMs(0.5f, 0))
        assertEquals(0L, ScrubMath.seekTargetMs(Float.NaN, 61_000))
    }

    @Test fun seekTargetKeepsPrecisionOnALongVideo() {
        // Three hours: a Float product would be off by whole seconds.
        val duration = 3L * 60 * 60 * 1000
        assertEquals(duration / 4, ScrubMath.seekTargetMs(0.25f, duration))
    }

    @Test fun aTouchInTheMiddleOfAPhoneSeeksToTheMiddle() {
        val fraction = ScrubMath.seekFraction(540f, 1080f)
        assertEquals("00:30", formatSeekTime(ScrubMath.seekTargetMs(fraction, 60_000)))
    }

    @Test fun playedFraction() {
        assertEquals(0f, ScrubMath.playedFraction(5_000, 0), 0f)
        assertEquals(0.25f, ScrubMath.playedFraction(15_000, 60_000), 1e-6f)
        assertEquals(1f, ScrubMath.playedFraction(70_000, 60_000), 0f)
        assertEquals(0f, ScrubMath.playedFraction(-5, 60_000), 0f)
    }

    @Test fun theThumbNeverHangsOverTheEnds() {
        assertEquals(8f, ScrubMath.thumbCenterX(0f, 360f, 8f), 0f)
        assertEquals(180f, ScrubMath.thumbCenterX(0.5f, 360f, 8f), 0f)
        assertEquals(352f, ScrubMath.thumbCenterX(1f, 360f, 8f), 0f)
        assertEquals(5f, ScrubMath.thumbCenterX(0.9f, 10f, 8f), 0f)
    }

    @Test fun theTimeLabelStaysOnScreen() {
        assertEquals(8f, ScrubMath.labelLeft(0f, 104f, 360f, 8f), 0f)
        assertEquals(128f, ScrubMath.labelLeft(180f, 104f, 360f, 8f), 0f)
        assertEquals(248f, ScrubMath.labelLeft(360f, 104f, 360f, 8f), 0f)
        // Narrower than the label: centred, not negative.
        assertEquals(0f, ScrubMath.labelLeft(40f, 104f, 100f, 8f), 0f)
    }

    // ---- image position bar ----

    @Test fun theBarIsWideEnoughForTwoImagesAndNeverWiderThanTheScreen() {
        assertEquals(96f, ScrubMath.indicatorWidth(2, 328f), 0f)
        assertEquals(96f, ScrubMath.indicatorWidth(4, 328f), 0f)
        assertEquals(110f, ScrubMath.indicatorWidth(5, 328f), 0f)
        assertEquals(220f, ScrubMath.indicatorWidth(10, 328f), 0f)
        assertEquals(328f, ScrubMath.indicatorWidth(40, 328f), 0f)
        assertEquals(0f, ScrubMath.indicatorWidth(0, 328f), 0f)
    }

    @Test fun segmentsFillTheBarExactly() {
        for (count in listOf(2, 3, 4, 7, 10, 25, 60, 200)) {
            val width = ScrubMath.indicatorWidth(count, 328f)
            val end = ScrubMath.segmentLeft(count - 1, count, width) + ScrubMath.segmentWidth(count, width)
            assertEquals("count=$count", width, end, 0.01f)
            assertTrue("count=$count", ScrubMath.segmentWidth(count, width) > 0f)
        }
    }

    @Test fun manyImagesGetNarrowerSegmentsNotAWiderBar() {
        val width = ScrubMath.indicatorWidth(30, 328f)
        assertEquals(328f, width, 0f)
        assertEquals(3f, ScrubMath.indicatorGap(30, width), 0f)
        assertTrue(ScrubMath.segmentWidth(30, width) >= ScrubMath.INDICATOR_MIN_SEGMENT)
        // So many that the normal gap would leave no room: the gap gives way first.
        assertTrue(ScrubMath.indicatorGap(100, 328f) < 3f)
        assertTrue(ScrubMath.segmentWidth(100, 328f) >= ScrubMath.INDICATOR_MIN_SEGMENT - 0.01f)
    }

    @Test fun touchToImageIndex() {
        // Five images on a 110dp bar: 22dp each.
        assertEquals(0, ScrubMath.pageIndex(0f, 110f, 5))
        assertEquals(0, ScrubMath.pageIndex(21.9f, 110f, 5))
        assertEquals(1, ScrubMath.pageIndex(22f, 110f, 5))
        assertEquals(2, ScrubMath.pageIndex(55f, 110f, 5))
        assertEquals(4, ScrubMath.pageIndex(109f, 110f, 5))
    }

    @Test fun touchesPastTheEndsClampToFirstAndLast() {
        assertEquals(0, ScrubMath.pageIndex(-20f, 110f, 5))
        assertEquals(4, ScrubMath.pageIndex(110f, 110f, 5))
        assertEquals(4, ScrubMath.pageIndex(400f, 110f, 5))
        assertEquals(0, ScrubMath.pageIndex(50f, 0f, 5))
        assertEquals(0, ScrubMath.pageIndex(50f, 110f, 0))
    }

    @Test fun everySegmentMapsBackToItsOwnImage() {
        for (count in listOf(2, 4, 9, 30)) {
            val width = ScrubMath.indicatorWidth(count, 328f)
            for (i in 0 until count) {
                val middle = ScrubMath.segmentLeft(i, count, width) + ScrubMath.segmentWidth(count, width) / 2
                assertEquals("count=$count i=$i", i, ScrubMath.pageIndex(middle, width, count))
            }
        }
    }

    @Test fun theImageSlidesWithTheFinger() {
        // Middle of a segment is exactly that image.
        assertEquals(0f, ScrubMath.pagePosition(11f, 110f, 5), 1e-5f)
        assertEquals(2f, ScrubMath.pagePosition(55f, 110f, 5), 1e-5f)
        assertEquals(4f, ScrubMath.pagePosition(99f, 110f, 5), 1e-5f)
        // On the border between two segments: half-way between the two images.
        assertEquals(0.5f, ScrubMath.pagePosition(22f, 110f, 5), 1e-5f)
        // Clamped at both ends, and with one image there is nowhere to go.
        assertEquals(0f, ScrubMath.pagePosition(-50f, 110f, 5), 0f)
        assertEquals(4f, ScrubMath.pagePosition(500f, 110f, 5), 0f)
        assertEquals(0f, ScrubMath.pagePosition(50f, 110f, 1), 0f)
    }

    @Test fun thePositionIsMonotonicAcrossTheBar() {
        var last = -1f
        var x = -10f
        while (x <= 120f) {
            val p = ScrubMath.pagePosition(x, 110f, 5)
            assertTrue(p >= last)
            last = p
            x += 0.5f
        }
        assertEquals(4f, last, 0f)
    }

    @Test fun pagePositionSplitsIntoPageAndOffset() {
        assertEquals(PagePosition(2, 0f), ScrubMath.splitPagePosition(2f, 5))
        val a = ScrubMath.splitPagePosition(2.3f, 5)
        assertEquals(2, a.page)
        assertEquals(0.3f, a.offsetFraction, 1e-5f)
        val b = ScrubMath.splitPagePosition(2.7f, 5)
        assertEquals(3, b.page)
        assertEquals(-0.3f, b.offsetFraction, 1e-5f)
        assertEquals(PagePosition(4, 0f), ScrubMath.splitPagePosition(9f, 5))
        assertEquals(PagePosition(0, 0f), ScrubMath.splitPagePosition(-3f, 5))
        assertEquals(PagePosition(0, 0f), ScrubMath.splitPagePosition(1f, 0))
    }

    @Test fun theOffsetIsAlwaysWithinHalfAPage() {
        var p = 0f
        while (p <= 4f) {
            val split = ScrubMath.splitPagePosition(p, 5)
            assertTrue(split.offsetFraction >= -0.5f && split.offsetFraction <= 0.5f)
            assertEquals(p, split.page + split.offsetFraction, 1e-4f)
            p += 0.05f
        }
    }

    @Test fun theBarOwnsItsSlackAndNothingElse() {
        assertTrue(ScrubMath.hitsIndicator(0f, 110f))
        assertTrue(ScrubMath.hitsIndicator(-24f, 110f))
        assertTrue(ScrubMath.hitsIndicator(134f, 110f))
        assertFalse(ScrubMath.hitsIndicator(-24.5f, 110f))
        assertFalse(ScrubMath.hitsIndicator(135f, 110f))
    }

    @Test fun pageCounter() {
        assertEquals("1/5", ScrubMath.pageCounter(0, 5))
        assertEquals("2/5", ScrubMath.pageCounter(1, 5))
        assertEquals("5/5", ScrubMath.pageCounter(9, 5))
        assertEquals("1/5", ScrubMath.pageCounter(-1, 5))
    }
}
