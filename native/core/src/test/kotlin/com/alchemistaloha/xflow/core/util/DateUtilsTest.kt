package com.alchemistaloha.xflow.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DateUtilsTest {
    @Test
    fun parsesTheTwitterFormat() {
        assertEquals(1539202764000L, parseTwitterDateTime("Wed Oct 10 20:19:24 +0000 2018"))
        // An offset is applied: 20:19:24 at +07:00 is 13:19:24 UTC.
        assertEquals(1539202764000L - 7 * 3600_000L, parseTwitterDateTime("Wed Oct 10 20:19:24 +0700 2018"))
    }

    @Test
    fun fallsBackToIso8601() {
        assertEquals(1539202764000L, parseTwitterDateTime("2018-10-10T20:19:24Z"))
        assertEquals(1539202764123L, parseTwitterDateTime("2018-10-10T20:19:24.123Z"))
        assertEquals(1539202764000L, parseTwitterDateTime("2018-10-10 20:19:24+00:00"))
        assertEquals(1539202764000L, parseTwitterDateTime("2018-10-11T03:19:24+0700"))
        assertEquals(1539129600000L, parseTwitterDateTime("20181010T000000Z"))
    }

    @Test
    fun returnsNullForNothingOrNonsense() {
        assertNull(parseTwitterDateTime(null))
        assertNull(parseTwitterDateTime(""))
        assertNull(parseTwitterDateTime("yesterday"))
        assertNull(parseTwitterDateTime("Wed Oct 10 20:19:24 +0000 2018 trailing"))
        assertNull(parseTwitterDateTime("2018-13-40T00:00:00Z"))
    }
}
