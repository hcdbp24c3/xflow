package com.alchemistaloha.xflow.core.format

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileFormatTest {
    @Test
    fun countsBelowThousandArePlain() {
        assertEquals("0", ProfileFormat.formatCount(0))
        assertEquals("7", ProfileFormat.formatCount(7))
        assertEquals("999", ProfileFormat.formatCount(999))
        assertEquals("-5", ProfileFormat.formatCount(-5))
    }

    @Test
    fun thousandsUseOneDecimalAndK() {
        assertEquals("1.0K", ProfileFormat.formatCount(1000))
        assertEquals("1.2K", ProfileFormat.formatCount(1234))
        assertEquals("1.3K", ProfileFormat.formatCount(1250))
        assertEquals("12.5K", ProfileFormat.formatCount(12500))
        assertEquals("999.9K", ProfileFormat.formatCount(999949))
        // Same as Dart: no carry into "M" just below a million.
        assertEquals("1000.0K", ProfileFormat.formatCount(999999))
    }

    @Test
    fun millionsUseOneDecimalAndM() {
        assertEquals("1.0M", ProfileFormat.formatCount(1000000))
        assertEquals("3.4M", ProfileFormat.formatCount(3400000))
        assertEquals("2147.5M", ProfileFormat.formatCount(Int.MAX_VALUE))
    }

    @Test
    fun dateIsZeroPaddedYearMonthDay() {
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("", ProfileFormat.formatDate(null, utc))
        assertEquals("1970-01-01", ProfileFormat.formatDate(0L, utc))
        // 2023-01-02T03:04:05Z
        assertEquals("2023-01-02", ProfileFormat.formatDate(1672628645000L, utc))
    }

    @Test
    fun dateUsesTheGivenZone() {
        // 2023-01-01T20:00:00Z is already 2 January in UTC+7.
        val millis = 1672603200000L
        assertEquals("2023-01-01", ProfileFormat.formatDate(millis, TimeZone.getTimeZone("UTC")))
        assertEquals("2023-01-02", ProfileFormat.formatDate(millis, TimeZone.getTimeZone("Asia/Ho_Chi_Minh")))
    }
}
