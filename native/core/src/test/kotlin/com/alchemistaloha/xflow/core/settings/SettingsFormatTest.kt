package com.alchemistaloha.xflow.core.settings

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.model.VideoEndAction
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Dart settings_screen_test.dart is a widget test (it looks for row texts
 * on screen). The texts built from values, and the slider arithmetic behind
 * them, are checked here.
 */
class SettingsFormatTest {

    @Test
    fun feedSortLabelsMatchDart() {
        assertEquals("Subs: Latest", SettingsFormat.feedSortLabel(FeedSort.latest))
        assertEquals("Subs: Popular", SettingsFormat.feedSortLabel(FeedSort.popular))
        assertEquals("Subs: Trending", SettingsFormat.feedSortLabel(FeedSort.trending))
        assertEquals("For You (X)", SettingsFormat.feedSortLabel(FeedSort.algorithmic))
        assertEquals("Following (X)", SettingsFormat.feedSortLabel(FeedSort.chronological))
        assertEquals("Video Mixer (X)", SettingsFormat.feedSortLabel(FeedSort.videomixer))
        assertEquals("Current: Subs: Latest", SettingsFormat.contentStrategySubtitle(FeedSort.latest))
    }

    @Test
    fun enumLabelsAreUpperCasedNames() {
        assertEquals("PLAYNEXT", SettingsFormat.enumLabel(VideoEndAction.playNext.name))
        assertEquals("Action: REPLAY", SettingsFormat.videoEndActionSubtitle(VideoEndAction.replay.name))
        assertEquals("VIDEO", SettingsFormat.enumLabel("video"))
    }

    @Test
    fun fixed1RoundsLikeDartToStringAsFixed() {
        assertEquals("0.0", SettingsFormat.fixed1(0.0))
        assertEquals("12.3", SettingsFormat.fixed1(12.34))
        assertEquals("12.4", SettingsFormat.fixed1(12.36))
        assertEquals("0.3", SettingsFormat.fixed1(0.25)) // exact tie rounds away from zero
        assertEquals("0.1", SettingsFormat.fixed1(0.15)) // 0.15 is stored just below the tie
        assertEquals("1024.0", SettingsFormat.fixed1(1024.0))
        assertEquals("-0.0", SettingsFormat.fixed1(-0.01))
    }

    @Test
    fun storageTexts() {
        // Freshly opened screen, before stats load (Dart initial state).
        assertEquals("0.0 MB used • 0 items", SettingsFormat.storageSummary(0.0, 0))
        val mb = SettingsFormat.bytesToMB(157_286_400L) // 150 MB
        assertEquals(150.0, mb, 0.0)
        assertEquals("150.0 MB used • 4321 items", SettingsFormat.storageSummary(mb, 4321))
        assertEquals("1.5 MB used / 500 MB limit", SettingsFormat.cacheUsage(SettingsFormat.bytesToMB(1_572_864L), 500))
        assertEquals("500 MB", SettingsFormat.cacheLimitLabel(SettingsState().mediaCacheSizeMB))
    }

    @Test
    fun freshMixLabelKeepsDartTruncation() {
        assertEquals("0% Fresh / 100% Saved", SettingsFormat.freshMixLabel(0.0))
        assertEquals("50% Fresh / 50% Saved", SettingsFormat.freshMixLabel(0.5))
        assertEquals("100% Fresh / 0% Saved", SettingsFormat.freshMixLabel(1.0))
        assertEquals("30% Fresh / 70% Saved", SettingsFormat.freshMixLabel(SettingsState().freshMixRatio))
        // Both halves truncate, as in Dart: 0.29 * 100 is 28.999999999999996.
        assertEquals("28% Fresh / 71% Saved", SettingsFormat.freshMixLabel(0.29))
        // Every slider stop (0.0, 0.1 ... 1.0) adds up to 100.
        for (i in 0..10) {
            val ratio = SettingsFormat.snapToDivision(i / 10.0, 0.0, 1.0, 10)
            assertEquals("${i * 10}% Fresh / ${100 - i * 10}% Saved", SettingsFormat.freshMixLabel(ratio))
        }
    }

    @Test
    fun sliderDivisionsDefaultToWholeSteps() {
        assertEquals(5, SettingsFormat.sliderDivisions(0.0, 5.0))
        assertEquals(119, SettingsFormat.sliderDivisions(1.0, 120.0))
        assertEquals(99, SettingsFormat.sliderDivisions(1000.0, 100000.0, 99))
        assertEquals("15", SettingsFormat.sliderValueLabel(15.0))
        assertEquals("15", SettingsFormat.sliderValueLabel(15.9))
    }

    @Test
    fun snapToDivisionLandsOnStops() {
        assertEquals(7.0, SettingsFormat.snapToDivision(6.6, 1.0, 120.0, 119), 0.0)
        assertEquals(7.0, SettingsFormat.snapToDivision(7.4, 1.0, 120.0, 119), 0.0)
        assertEquals(51000.0, SettingsFormat.snapToDivision(50600.0, 1000.0, 100000.0, 99), 0.0)
        assertEquals(500.0, SettingsFormat.snapToDivision(520.0, 100.0, 2000.0, 19), 0.0)
        assertEquals(450.0, SettingsFormat.snapToDivision(470.0, 100.0, 1000.0, 18), 0.0)
        assertEquals(0.3, SettingsFormat.snapToDivision(0.31, 0.0, 1.0, 10), 0.0)
        // Out of range positions clamp to the ends.
        assertEquals(1.0, SettingsFormat.snapToDivision(-5.0, 1.0, 10.0, 9), 0.0)
        assertEquals(10.0, SettingsFormat.snapToDivision(50.0, 1.0, 10.0, 9), 0.0)
        // Every stop of every integer slider is a whole number.
        for (i in 0..49) {
            val v = SettingsFormat.snapToDivision(100.0 + i * 100.0 + 3, 100.0, 5000.0, 49)
            assertEquals(100.0 + i * 100.0, v, 0.0)
        }
    }

    @Test
    fun clampAndFraction() {
        assertEquals(480.0, SettingsFormat.clamp(480.0, 100.0, 1000.0), 0.0)
        assertEquals(100.0, SettingsFormat.clamp(5.0, 100.0, 1000.0), 0.0)
        assertEquals(0.5, SettingsFormat.sliderFraction(5.0, 0.0, 10.0), 0.0)
        assertEquals(1.0, SettingsFormat.sliderFraction(50.0, 0.0, 10.0), 0.0)
    }

    @Test
    fun debugOverlayLines() {
        val utc = TimeZone.getTimeZone("UTC")
        val tweet = Tweet(
            id = "1234567890123456789",
            text = "t",
            userHandle = "someone",
            mediaUrls = listOf("a", "b"),
            isVideo = true,
            createdAtMillis = 1_700_000_000_000L, // 2023-11-14 22:13:20 UTC
            source = "API",
        )
        assertEquals(
            listOf(
                "TYPE" to "VIDEO",
                "SOURCE" to "API",
                "ID" to "23456789",
                "MEDIA" to "2 urls",
                "SEEN" to "3 times",
                "ACCT_SEEN" to "7 times",
                "TS" to "22:13:20",
            ),
            SettingsFormat.debugLines(tweet, 3, 7, utc),
        )
        val bare = Tweet(id = "42", text = "", userHandle = "x")
        assertEquals(
            listOf(
                "TYPE" to "IMAGE",
                "SOURCE" to "UNKNOWN",
                "ID" to "42",
                "MEDIA" to "0 urls",
                "SEEN" to "0 times",
                "ACCT_SEEN" to "0 times",
            ),
            SettingsFormat.debugLines(bare, 0, 0, utc),
        )
        assertEquals("05:13:20", SettingsFormat.debugTimestamp(1_700_000_000_000L, TimeZone.getTimeZone("Asia/Ho_Chi_Minh")))
    }
}
