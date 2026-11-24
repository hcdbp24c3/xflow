package com.alchemistaloha.xflow.core.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.TimeZone

/**
 * The small text formatters of the profile and replies screens
 * (Dart `_formatCount` / `_formatDate` in user_details_screen.dart and
 * tweet_detail_screen.dart).
 */
object ProfileFormat {
    /** 999 -> "999", 1200 -> "1.2K", 3400000 -> "3.4M" (Dart `toStringAsFixed(1)`). */
    fun formatCount(count: Int): String {
        if (count >= 1000000) return fixed1(count / 1000000.0) + "M"
        if (count >= 1000) return fixed1(count / 1000.0) + "K"
        return count.toString()
    }

    /** `yyyy-MM-dd` in [timeZone] (the device zone by default); empty for null. */
    fun formatDate(millis: Long?, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (millis == null) return ""
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = millis
        val year = cal.get(Calendar.YEAR)
        val month = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val day = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        return "$year-$month-$day"
    }

    // Dart rounds the exact binary value half away from zero; BigDecimal(double) is that exact value.
    private fun fixed1(value: Double): String =
        BigDecimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString()
}
