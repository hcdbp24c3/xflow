package com.alchemistaloha.xflow.core.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Parses the `created_at` of an X post ("Wed Oct 10 20:19:24 +0000 2018") and
 * returns epoch milliseconds. Falls back to an ISO-8601 parse, as the Flutter
 * app fell back to `DateTime.tryParse`. Returns null when neither matches.
 */
fun parseTwitterDateTime(dateStr: String?): Long? {
    if (dateStr.isNullOrEmpty()) return null

    try {
        // SimpleDateFormat is not thread-safe, so one per call.
        val format = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)
        val position = ParsePosition(0)
        val date = format.parse(dateStr, position)
        if (date != null && position.index == dateStr.length) return date.time
    } catch (_: Exception) {
        // fall through to the ISO parse
    }
    return parseIsoDateTime(dateStr)
}

private val isoPattern = Regex(
    "^([+-]?\\d{4,6})-?(\\d\\d)-?(\\d\\d)" +
        "(?:[ T](\\d\\d)(?::?(\\d\\d)(?::?(\\d\\d)(?:[.,](\\d+))?)?)?" +
        "( ?[zZ]| ?([-+])(\\d\\d)(?::?(\\d\\d))?)?)?$",
)

/** The subset of ISO-8601 that Dart's `DateTime.tryParse` accepts. No offset means local time. */
private fun parseIsoDateTime(text: String): Long? {
    val match = isoPattern.matchEntire(text) ?: return null
    val groups = match.groupValues
    fun int(index: Int): Int = groups[index].ifEmpty { "0" }.toInt()

    val year = groups[1].toInt()
    val month = int(2)
    val day = int(3)
    val hour = int(4)
    val minute = int(5)
    val second = int(6)
    val millis = if (groups[7].isEmpty()) 0 else (("0." + groups[7]).toDouble() * 1000).toInt()
    if (month !in 1..12 || day !in 1..31 || hour > 24 || minute > 59 || second > 59) return null

    val hasZone = groups[8].isNotEmpty()
    val calendar = GregorianCalendar(if (hasZone) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
    calendar.clear()
    calendar.set(year, month - 1, day, hour, minute, second)
    calendar.set(Calendar.MILLISECOND, millis)
    var result = calendar.timeInMillis
    if (hasZone && groups[9].isNotEmpty()) {
        val offsetMinutes = int(10) * 60 + int(11)
        val sign = if (groups[9] == "-") -1 else 1
        result -= sign * offsetMinutes * 60_000L
    }
    return result
}
