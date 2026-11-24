package com.alchemistaloha.xflow.core.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How requests are written to the in-app log. Kept in the same text shape as
 * the Flutter app, which printed Dart maps and lists (`{key: value}`,
 * `[a, b]`), so logs from both apps read alike.
 */
object TwitterLogFormat {
    private val whitespace =
        Regex("[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

    /** Text of [value] the way Dart's `toString()` prints maps, lists and JSON values. */
    fun dartToString(value: Any?): String = when (value) {
        null, is JsonNull -> "null"
        is JsonPrimitive -> value.content
        is JsonObject -> value.entries.joinToString(", ", "{", "}") { "${it.key}: ${dartToString(it.value)}" }
        is JsonArray -> value.joinToString(", ", "[", "]") { dartToString(it) }
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") {
            "${dartToString(it.key)}: ${dartToString(it.value)}"
        }
        is Set<*> -> value.joinToString(", ", "{", "}") { dartToString(it) }
        is Iterable<*> -> value.joinToString(", ", "[", "]") { dartToString(it) }
        is Enum<*> -> value.name
        else -> value.toString()
    }

    fun compactForLog(value: Any?, maxLength: Int? = null): String {
        if (value == null) return "null"
        val flattened = dartToString(value).replace(whitespace, " ").trim()
        if (maxLength == null || flattened.length <= maxLength) {
            return flattened
        }
        return "${flattened.substring(0, maxLength).trimEnd()}..."
    }

    /**
     * The Flutter app decoded the (already decoded) value once more here, and
     * `Uri.decodeQueryComponent` threw on any non-ASCII character and on a
     * '%' that does not start an escape. That exception left
     * `formatUriForLog` and the request was never sent: a search for
     * "tiếng Việt" or "100% real" silently returned nothing. A log line must
     * not decide whether a request goes out, so text that cannot be decoded
     * is logged as it is.
     */
    private fun decodeLogValue(value: String): Any? {
        val decoded = try {
            XUri.decodeQueryComponent(value)
        } catch (_: IllegalArgumentException) {
            value
        }
        return try {
            Json.parseToJsonElement(decoded) as JsonElement
        } catch (_: Exception) {
            decoded
        }
    }

    fun formatUriForLog(uri: XUri): String {
        if (uri.query.isEmpty()) return uri.path
        val params = LinkedHashMap<String, Any?>()
        for (entry in uri.queryParameters.entries) {
            params[entry.key] = decodeLogValue(entry.value)
        }
        return "${uri.path} params=${compactForLog(params)}"
    }

    fun formatTransactionIdForLog(transactionId: String?): String {
        if (transactionId.isNullOrEmpty()) {
            return "missing"
        }
        return compactForLog(transactionId)
    }

    fun summarizeRequest(uri: XUri, method: String, body: Any?): String {
        val query = if (uri.query.isEmpty()) "" else " ${formatUriForLog(uri)}"
        val bodySummary = if (body == null) "" else " body=${compactForLog(body)}"
        return "$method$query$bodySummary"
    }
}
