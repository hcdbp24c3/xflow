package com.alchemistaloha.xflow.core.client

import com.alchemistaloha.xflow.core.model.TweetResponse
import com.alchemistaloha.xflow.core.util.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchDiagnosticsTest {

    @Test
    fun `a 404 says which operation was refused and why that usually happens`() {
        val outcome = FetchDiagnostics.forStatus("SearchTimeline", 404, "")
        assertEquals(FetchIssue.http, outcome.issue)
        assertTrue(outcome.isFailure)
        val notice = FetchDiagnostics.explainEmptyFeed(outcome, loggedIn = true, subscriptionCount = 120, usesSubscriptions = true)
        assertEquals("X returned an error.", notice.title)
        assertTrue(notice.detail!!.contains("HTTP 404 on SearchTimeline"))
        assertTrue(notice.detail!!.contains("transaction id"))
        assertFalse(notice.needsLogin)
    }

    @Test
    fun `a rejected session asks for a new login`() {
        for (status in listOf(401, 403)) {
            val notice = FetchDiagnostics.explainEmptyFeed(
                FetchDiagnostics.forStatus("SearchTimeline", status, """{"errors":[{"message":"Could not authenticate you","code":32}]}"""),
                loggedIn = true, subscriptionCount = 3, usesSubscriptions = true,
            )
            assertTrue(notice.needsLogin)
            assertTrue(notice.detail!!.contains("HTTP $status"))
            assertTrue(notice.detail!!.contains("Could not authenticate you"))
        }
    }

    @Test
    fun `429 is reported as a rate limit with the pause`() {
        val outcome = FetchDiagnostics.forStatus("SearchTimeline", 429, "Rate limit exceeded")
        assertEquals(FetchIssue.rateLimited, outcome.issue)
        val notice = FetchDiagnostics.explainEmptyFeed(outcome, true, 10, true, cooldownMinutes = 15)
        assertEquals("X is rate limiting this account.", notice.title)
        assertTrue(notice.detail!!.contains("15 min"))
    }

    @Test
    fun `no subscriptions is said plainly, before any request and after one`() {
        val before = FetchDiagnostics.explainEmptyFeed(null, loggedIn = true, subscriptionCount = 0, usesSubscriptions = true)
        assertEquals("No subscriptions yet.", before.title)
        val after = FetchDiagnostics.explainEmptyFeed(
            FetchOutcome("Subscriptions", FetchIssue.noSubscriptions), true, 0, true,
        )
        assertEquals("No subscriptions yet.", after.title)
        // A home timeline does not need subscriptions.
        val home = FetchDiagnostics.explainEmptyFeed(null, loggedIn = true, subscriptionCount = 0, usesSubscriptions = false)
        assertEquals("No media found.", home.title)
    }

    @Test
    fun `a failed request is not hidden behind the missing subscriptions`() {
        val notice = FetchDiagnostics.explainEmptyFeed(
            FetchDiagnostics.forStatus("Following", 404, ""), loggedIn = true, subscriptionCount = 0, usesSubscriptions = true,
        )
        assertEquals("X returned an error.", notice.title)
    }

    @Test
    fun `logged out comes first`() {
        val notice = FetchDiagnostics.explainEmptyFeed(
            FetchDiagnostics.forStatus("SearchTimeline", 404, ""), loggedIn = false, subscriptionCount = 5, usesSubscriptions = true,
        )
        assertTrue(notice.needsLogin)
        assertEquals("You are not logged in to X.", notice.detail)
    }

    @Test
    fun `an answer with no posts names the operation and the subscriptions`() {
        val notice = FetchDiagnostics.explainEmptyFeed(
            FetchOutcome("SearchTimeline", FetchIssue.empty, httpStatus = 200), true, 42, true,
        )
        assertEquals("No media found.", notice.title)
        assertTrue(notice.detail!!.contains("SearchTimeline"))
        assertTrue(notice.detail!!.contains("42 subscriptions"))
    }

    @Test
    fun `exceptions are sorted into timeout, network and unreadable`() {
        assertEquals(FetchIssue.network, FetchDiagnostics.forException("X", java.net.UnknownHostException("x.com")).issue)
        assertEquals(FetchIssue.timeout, FetchDiagnostics.forException("X", java.net.SocketTimeoutException("timeout")).issue)
        assertEquals(FetchIssue.timeout, FetchDiagnostics.forException("X", java.io.InterruptedIOException("timeout")).issue)
        assertEquals(FetchIssue.badResponse, FetchDiagnostics.forException("X", IllegalStateException("response is not a JSON object")).issue)
    }

    @Test
    fun `error bodies are shortened to one line`() {
        assertNull(FetchDiagnostics.shorten(null))
        assertNull(FetchDiagnostics.shorten("  \n "))
        assertEquals("a b c", FetchDiagnostics.shorten(" a \n b\t c "))
        val long = FetchDiagnostics.shorten("x".repeat(500))!!
        assertEquals(201, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `the log line carries operation, status and count`() {
        assertEquals(
            "FETCH SearchTimeline issue=ok status=200 items=18 detail=entries=22 bytes=90211",
            FetchOutcome("SearchTimeline", FetchIssue.ok, 200, 18, "entries=22 bytes=90211").toLogLine(),
        )
        assertEquals("FETCH Subscriptions issue=noSubscriptions items=0", FetchOutcome("Subscriptions", FetchIssue.noSubscriptions).toLogLine())
    }

    @Test
    fun `a response from the parser alone has no outcome and equals as before`() {
        assertNull(TweetResponse(tweets = emptyList()).outcome)
        assertEquals(TweetResponse(tweets = emptyList()), TweetResponse(tweets = emptyList(), cursorTop = null))
    }

    @Test
    fun `every logged line reaches the sink without the timestamp, and a broken sink is harmless`() {
        val seen = ArrayList<String>()
        val before = AppLogger.sink
        try {
            AppLogger.sink = { seen.add(it) }
            val revision = AppLogger.revision.value
            AppLogger.log("FETCH test line")
            assertEquals(listOf("FETCH test line"), seen)
            assertTrue(AppLogger.logs.last().endsWith("] FETCH test line"))
            assertEquals(revision + 1, AppLogger.revision.value)

            AppLogger.sink = { throw IllegalStateException("sink broke") }
            AppLogger.log("still logged")
            assertTrue(AppLogger.logs.last().endsWith("] still logged"))
        } finally {
            AppLogger.sink = before
        }
    }
}
