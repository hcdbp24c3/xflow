package com.alchemistaloha.xflow.core.settings

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.Tweet
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The text and number rules of the Settings screens and the debug overlay,
 * kept free of Android so they can be unit-tested on the JVM.
 * Each function mirrors one expression of the Flutter settings_screen.dart.
 */
object SettingsFormat {

    /** Dart `PlaybackSettingsPage._getFeedSortLabel`. */
    fun feedSortLabel(sort: FeedSort): String = when (sort) {
        FeedSort.latest -> "Subs: Latest"
        FeedSort.popular -> "Subs: Popular"
        FeedSort.trending -> "Subs: Trending"
        FeedSort.algorithmic -> "For You (X)"
        FeedSort.chronological -> "Following (X)"
        FeedSort.videomixer -> "Video Mixer (X)"
    }

    /** Dart `enum.name.toUpperCase()` (e.g. `playNext` -> `PLAYNEXT`). */
    fun enumLabel(name: String): String = name.uppercase(Locale.ROOT)

    /** Dart `sizeBytes / (1024 * 1024)`. */
    fun bytesToMB(sizeBytes: Long): Double = sizeBytes / (1024.0 * 1024.0)

    /** Dart `double.toStringAsFixed(1)`: exact decimal value, ties away from zero. */
    fun fixed1(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        val rounded = BigDecimal(value).setScale(1, RoundingMode.HALF_UP)
        // Dart prints "-0.0" for small negatives; BigDecimal prints "0.0".
        return if (rounded.signum() == 0 && (value < 0 || 1.0 / value < 0)) "-0.0" else rounded.toPlainString()
    }

    /** Subtitle of the "Storage & Cache" row on the main Settings screen. */
    fun storageSummary(cacheSizeMB: Double, metadataCount: Int): String =
        "${fixed1(cacheSizeMB)} MB used • $metadataCount items"

    /** Subtitle of "Local Media Cache" on the Storage page. */
    fun cacheUsage(cacheSizeMB: Double, limitMB: Int): String =
        "${fixed1(cacheSizeMB)} MB used / $limitMB MB limit"

    /** Bubble label of the cache-size slider. */
    fun cacheLimitLabel(limitMB: Int): String = "$limitMB MB"

    /**
     * Subtitle of "New vs. Cached Mix". Ported expression for expression:
     * both halves truncate (0.29 prints "28% Fresh / 71% Saved").
     */
    fun freshMixLabel(freshMixRatio: Double): String =
        "${(freshMixRatio * 100).toInt()}% Fresh / ${(100 - freshMixRatio * 100).toInt()}% Saved"

    fun contentStrategySubtitle(sort: FeedSort): String = "Current: ${feedSortLabel(sort)}"

    fun videoEndActionSubtitle(actionName: String): String = "Action: ${enumLabel(actionName)}"

    /** Number shown at the right of a slider row: Dart `value.toInt().toString()`. */
    fun sliderValueLabel(value: Double): String = value.toInt().toString()

    /** Dart `_SliderSetting`: `divisions ?? (max - min).toInt()`. */
    fun sliderDivisions(min: Double, max: Double, divisions: Int? = null): Int =
        divisions ?: (max - min).toInt()

    /** Dart `value.clamp(min, max)`. */
    fun clamp(value: Double, min: Double, max: Double): Double =
        if (value < min) min else if (value > max) max else value

    /**
     * The value a Flutter slider with [divisions] reports for a raw position:
     * the nearest of the `divisions + 1` evenly spaced stops.
     */
    fun snapToDivision(raw: Double, min: Double, max: Double, divisions: Int): Double {
        val clamped = clamp(raw, min, max)
        if (divisions <= 0 || max <= min) return clamped
        val step = Math.round((clamped - min) / (max - min) * divisions)
        return min + step * (max - min) / divisions
    }

    /** Position of [value] on the track, 0..1. */
    fun sliderFraction(value: Double, min: Double, max: Double): Double =
        if (max <= min) 0.0 else (clamp(value, min, max) - min) / (max - min)

    // --- Debug overlay (Dart DiscoveryDebugOverlay) ---

    /** Dart `tweet.id.substring(tweet.id.length - 8)`; shorter ids are shown whole. */
    fun debugIdTail(id: String): String = if (id.length > 8) id.substring(id.length - 8) else id

    /** Dart `createdAt.toLocal().toString().split(' ').last.split('.').first`: local `HH:mm:ss`. */
    fun debugTimestamp(createdAtMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val format = SimpleDateFormat("HH:mm:ss", Locale.US)
        format.timeZone = timeZone
        return format.format(Date(createdAtMillis))
    }

    /** The overlay's label/value lines, in the order the Dart widget draws them. */
    fun debugLines(
        tweet: Tweet,
        mediaPlayedCount: Int,
        userPlayedCount: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<Pair<String, String>> {
        val lines = mutableListOf(
            "TYPE" to if (tweet.isVideo) "VIDEO" else "IMAGE",
            "SOURCE" to (tweet.source ?: "UNKNOWN"),
            "ID" to debugIdTail(tweet.id),
            "MEDIA" to "${tweet.mediaUrls.size} urls",
            "SEEN" to "$mediaPlayedCount times",
            "ACCT_SEEN" to "$userPlayedCount times",
        )
        val created = tweet.createdAtMillis
        if (created != null) lines.add("TS" to debugTimestamp(created, timeZone))
        return lines
    }
}
