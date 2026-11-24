package com.alchemistaloha.xflow.core.client

/** Why a request to X gave no posts (or [ok] when it gave some). */
enum class FetchIssue {
    ok,
    /** HTTP 200 and a readable timeline, but nothing in it. */
    empty,
    notLoggedIn,
    noSubscriptions,
    rateLimited,
    /** Any other HTTP status; see [FetchOutcome.httpStatus]. */
    http,
    timeout,
    network,
    /** HTTP 200 but the body was not the expected shape. */
    badResponse,
}

/**
 * The result of one call to X, kept so an empty feed can say why it is empty.
 * It never holds cookies, tokens or request headers: only the operation name,
 * the status, a count, and a short text taken from X's error body.
 */
data class FetchOutcome(
    val operation: String,
    val issue: FetchIssue,
    val httpStatus: Int? = null,
    val items: Int = 0,
    val detail: String? = null,
    val atMillis: Long = System.currentTimeMillis(),
) {
    val isFailure: Boolean get() = issue != FetchIssue.ok && issue != FetchIssue.empty

    /** One line for the log: `FETCH SearchTimeline issue=http status=404 items=0 detail=...`. */
    fun toLogLine(): String = buildString {
        append("FETCH ").append(operation)
        append(" issue=").append(issue.name)
        if (httpStatus != null) append(" status=").append(httpStatus)
        append(" items=").append(items)
        if (!detail.isNullOrEmpty()) append(" detail=").append(detail)
    }
}

/** What an empty screen shows: a headline and, when known, the reason under it. */
data class FeedNotice(val title: String, val detail: String? = null, val needsLogin: Boolean = false)

object FetchDiagnostics {
    private const val MAX_DETAIL = 200

    /** Shortens an error body for the log and the screen; whitespace runs become one space. */
    fun shorten(text: String?, maxLength: Int = MAX_DETAIL): String? {
        if (text == null) return null
        val compact = text.trim().replace(Regex("\\s+"), " ")
        if (compact.isEmpty()) return null
        return if (compact.length <= maxLength) compact else compact.substring(0, maxLength) + "…"
    }

    fun forStatus(operation: String, status: Int, body: String?): FetchOutcome = when (status) {
        429 -> FetchOutcome(operation, FetchIssue.rateLimited, status, detail = shorten(body))
        else -> FetchOutcome(operation, FetchIssue.http, status, detail = shorten(body))
    }

    fun forException(operation: String, e: Throwable): FetchOutcome {
        val name = e::class.simpleName ?: "Exception"
        val isTimeout = name.contains("Timeout", ignoreCase = true) ||
            (e.message?.contains("timeout", ignoreCase = true) == true)
        val isNetwork = e is java.io.IOException
        val issue = when {
            isTimeout -> FetchIssue.timeout
            isNetwork -> FetchIssue.network
            else -> FetchIssue.badResponse
        }
        return FetchOutcome(operation, issue, detail = shorten("$name: ${e.message ?: ""}"))
    }

    /**
     * The text for a feed with nothing to show. [outcome] is the last request
     * the feed made (null when it has not made one), [subscriptionCount] is
     * null when unknown, [usesSubscriptions] is false for the home timelines.
     */
    fun explainEmptyFeed(
        outcome: FetchOutcome?,
        loggedIn: Boolean,
        subscriptionCount: Int?,
        usesSubscriptions: Boolean,
        cooldownMinutes: Int? = null,
    ): FeedNotice {
        if (!loggedIn) {
            return FeedNotice("No media found.", "You are not logged in to X.", needsLogin = true)
        }
        if (usesSubscriptions && subscriptionCount == 0 &&
            (outcome == null || outcome.issue == FetchIssue.noSubscriptions || !outcome.isFailure)
        ) {
            return FeedNotice(
                "No subscriptions yet.",
                "Import the accounts you follow (Subscriptions tab) or switch the feed source in Settings.",
            )
        }
        if (outcome == null) {
            return FeedNotice("No media found.", "No request to X has finished yet. Tap Retry.")
        }
        val op = outcome.operation
        return when (outcome.issue) {
            FetchIssue.ok, FetchIssue.empty -> FeedNotice(
                "No media found.",
                "X answered $op with no matching posts" +
                    (if (usesSubscriptions && subscriptionCount != null) " for $subscriptionCount subscriptions" else "") +
                    ". Try again, or relax the filters / minimum likes in Settings.",
            )
            FetchIssue.notLoggedIn -> FeedNotice("No media found.", "You are not logged in to X.", needsLogin = true)
            FetchIssue.noSubscriptions -> FeedNotice(
                "No subscriptions yet.",
                "Import the accounts you follow (Subscriptions tab) or switch the feed source in Settings.",
            )
            FetchIssue.rateLimited -> FeedNotice(
                "X is rate limiting this account.",
                "HTTP 429 on $op." +
                    (if (cooldownMinutes != null) " Requests are paused for $cooldownMinutes min." else "") +
                    " Retry later.",
            )
            FetchIssue.http -> {
                val status = outcome.httpStatus
                val hint = when (status) {
                    401, 403 -> " The session was rejected: log in again."
                    404 -> " X did not accept the request (operation id or transaction id)."
                    else -> ""
                }
                FeedNotice(
                    "X returned an error.",
                    "HTTP $status on $op.$hint" + (outcome.detail?.let { "\n$it" } ?: ""),
                    needsLogin = status == 401 || status == 403,
                )
            }
            FetchIssue.timeout -> FeedNotice("The request timed out.", "$op got no answer in time. Check the connection and retry.")
            FetchIssue.network -> FeedNotice(
                "Network error.",
                "$op could not reach X." + (outcome.detail?.let { "\n$it" } ?: ""),
            )
            FetchIssue.badResponse -> FeedNotice(
                "Unexpected answer from X.",
                "$op: " + (outcome.detail ?: "the response could not be read."),
            )
        }
    }
}
