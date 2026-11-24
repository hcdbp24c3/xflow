package com.alchemistaloha.xflow.core.player

import com.alchemistaloha.xflow.core.player.MediaPagerLogic.CommitResult
import com.alchemistaloha.xflow.core.player.MediaPagerLogic.ScrollAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of feed_page_physics_test.dart (target rule) and the logic half of media_pager_test.dart. */
class FeedPagingTest {
    private val page = 800.0
    private fun target(pixels: Double, velocity: Double) =
        FeedPagePhysics.targetPixels(pixels, page, 0.0, page * 9, velocity)

    @Test fun aShortSlowDragSnapsBackToTheSamePage() {
        assertEquals(page * 2, target(page * 2 + 120, 80.0), 0.0)
        assertEquals(page * 2, target(page * 2 - 120, -80.0), 0.0)
    }

    @Test fun aSlowDragPastHalfwayGoesToTheNextPage() {
        assertEquals(page * 3, target(page * 2 + 450, 0.0), 0.0)
        assertEquals(page * 1, target(page * 2 - 450, 0.0), 0.0)
    }

    @Test fun aFlingTurnsExactlyOnePageHoweverFast() {
        assertEquals(page * 3, target(page * 2 + 40, 900.0), 0.0)
        assertEquals(page * 3, target(page * 2 + 40, 9000.0), 0.0)
        assertEquals(page * 1, target(page * 2 - 40, -900.0), 0.0)
        assertEquals(page * 1, target(page * 2 - 40, -9000.0), 0.0)
    }

    @Test fun aFlingBackTowardsTheCurrentPageReturnsToIt() {
        assertEquals(page * 2, target(page * 2 + 240, -900.0), 0.0)
    }

    @Test fun neverTargetsBeyondTheEnds() {
        assertEquals(0.0, target(10.0, -3000.0), 0.0)
        assertEquals(page * 9, target(page * 9 - 10, 3000.0), 0.0)
    }

    @Test fun targetPageMatchesThePixelRule() {
        assertEquals(3, FeedPagePhysics.targetPage(2.05, 900.0, 10))
        assertEquals(2, FeedPagePhysics.targetPage(2.3, -900.0, 10))
        assertEquals(2, FeedPagePhysics.targetPage(2.15, 80.0, 10))
        assertEquals(9, FeedPagePhysics.targetPage(8.99, 3000.0, 10))
        assertEquals(0, FeedPagePhysics.targetPage(0.3, 0.0, 0))
    }

    @Test fun composeSpringIsCriticallyDampedWithTheSameResponse() {
        assertEquals(550f, FeedPagePhysics.COMPOSE_SPRING_STIFFNESS, 0.001f)
        assertEquals(1.0, FeedPagePhysics.SPRING_DAMPING_RATIO, 0.0)
    }

    // --- MediaPagerLogic ---

    @Test fun warmsThePoolAroundTheFirstItem() {
        val logic = MediaPagerLogic()
        assertEquals(listOf(0, 1, 2, 3), logic.warmWindow(10))
    }

    @Test fun doesNothingHeavyWhileThePageIsBeingDragged() {
        val logic = MediaPagerLogic()
        // Dragging a third of the way: no pause, no pool work, still item 0.
        assertTrue(logic.onScroll(0.3) { true }.isEmpty())
        assertEquals(0, logic.currentIndex)
        // Past halfway the playing item is silenced, but the current item does not change.
        assertEquals(listOf<ScrollAction>(ScrollAction.PauseCurrent), logic.onScroll(0.6) { true })
        assertEquals(0, logic.currentIndex)
        assertTrue(logic.pausedByScroll)
        // Only one pause per drag.
        assertTrue(logic.onScroll(0.8) { true }.isEmpty())
        assertFalse(logic.isLanded(0.8))
    }

    @Test fun aSmallSlowDragReturnsToTheSameItem() {
        val logic = MediaPagerLogic()
        logic.onScroll(0.6) { true }
        assertTrue(logic.isLanded(0.01))
        assertEquals(CommitResult.ResumeCurrent, logic.commit(0, 10))
        assertFalse(logic.pausedByScroll)
        assertEquals(CommitResult.Nothing, logic.commit(0, 10))
    }

    @Test fun aNotPlayingItemIsNotMarkedPausedByScroll() {
        val logic = MediaPagerLogic()
        assertTrue(logic.onScroll(0.7) { false }.isEmpty())
        assertEquals(CommitResult.Nothing, logic.commit(0, 10))
    }

    @Test fun aFlingMovesExactlyOneItem() {
        val logic = MediaPagerLogic()
        logic.onScroll(0.9) { true }
        assertEquals(CommitResult.Changed(1), logic.commit(1, 10))
        assertEquals(1, logic.currentIndex)
        assertEquals(listOf(1, 0, 2, 3, 4), logic.warmWindow(10))
        assertEquals(1, logic.poolCenter)
    }

    @Test fun jumpToPageIsPickedUpAsTheCurrentItem() {
        val logic = MediaPagerLogic()
        assertEquals(CommitResult.Changed(5), logic.commit(5, 10))
        assertEquals(listOf(5, 4, 6, 7, 8), logic.warmWindow(10))
    }

    @Test fun catchesUpWhenSwipedSeveralPagesWithoutLanding() {
        val logic = MediaPagerLogic()
        logic.warmWindow(10)
        val actions = logic.onScroll(2.4) { false }
        assertEquals(listOf<ScrollAction>(ScrollAction.CatchUpPool(2)), actions)
        // The current item stays warm even though the window moved on.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), logic.warmWindow(10, center = 2))
        assertEquals(2, logic.poolCenter)
    }

    @Test fun commitClampsAndIgnoresAnEmptyFeed() {
        val logic = MediaPagerLogic()
        assertEquals(CommitResult.Nothing, logic.commit(3, 0))
        assertEquals(CommitResult.Changed(4), logic.commit(99, 5))
        assertEquals(listOf(4, 3), logic.warmWindow(5))
    }

    @Test fun survivesAFeedThatContainsTheSameTweetTwice() {
        assertEquals(listOf("a", "b", "a#2", "c", "b#4"), MediaPagerLogic.uniqueKeys(listOf("a", "b", "a", "c", "b")))
    }

    // --- formatters ---

    @Test fun formatCountMatchesTheDartHelper() {
        assertEquals("999", formatCount(999))
        assertEquals("1.0K", formatCount(1000))
        assertEquals("1.5K", formatCount(1549))
        assertEquals("12.3K", formatCount(12345))
        assertEquals("1.0M", formatCount(1_000_000))
        assertEquals("2.5M", formatCount(2_540_000))
    }

    @Test fun formatSeekTime() {
        assertEquals("00:00", formatSeekTime(0))
        assertEquals("01:05", formatSeekTime(65_000))
        assertEquals("00:01", formatSeekTime(3_601_000))
    }

    @Test fun parseCaptionSplitsHashtagsMentionsAndLinks() {
        val spans = parseCaption("hi #tag @bob https://x.com/a). #")
        assertEquals(
            listOf(
                CaptionSpan("hi", CaptionSpan.Kind.TEXT),
                CaptionSpan(" ", CaptionSpan.Kind.TEXT),
                CaptionSpan("#tag", CaptionSpan.Kind.HASHTAG),
                CaptionSpan(" ", CaptionSpan.Kind.TEXT),
                CaptionSpan("@bob", CaptionSpan.Kind.MENTION, "bob"),
                CaptionSpan(" ", CaptionSpan.Kind.TEXT),
                CaptionSpan("https://x.com/a", CaptionSpan.Kind.URL),
                CaptionSpan(" ", CaptionSpan.Kind.TEXT),
                CaptionSpan("#", CaptionSpan.Kind.TEXT),
            ),
            spans,
        )
    }
}
