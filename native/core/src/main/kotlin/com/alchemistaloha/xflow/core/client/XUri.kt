package com.alchemistaloha.xflow.core.client

/**
 * A request address, standing in for Dart's `Uri` so that building and
 * logging requests needs no Android or OkHttp class. [queryParameters] hold
 * the decoded values, in the order they were given.
 */
data class XUri(
    val scheme: String,
    val host: String,
    val path: String,
    val queryParameters: Map<String, String> = emptyMap(),
) {
    /** The encoded query string, empty when there are no parameters. */
    val query: String
        get() = queryParameters.entries.joinToString("&") { (key, value) ->
            encodeQueryComponent(key) + "=" + encodeQueryComponent(value)
        }

    override fun toString(): String {
        val q = query
        return if (q.isEmpty()) "$scheme://$host$path" else "$scheme://$host$path?$q"
    }

    companion object {
        fun https(host: String, path: String, queryParameters: Map<String, String> = emptyMap()): XUri =
            XUri("https", host, path, LinkedHashMap(queryParameters))

        fun http(host: String, path: String, queryParameters: Map<String, String> = emptyMap()): XUri =
            XUri("http", host, path, LinkedHashMap(queryParameters))

        private const val HEX = "0123456789ABCDEF"

        /** Dart's `Uri.encodeQueryComponent`: unreserved characters stay, a space becomes `+`. */
        fun encodeQueryComponent(text: String): String {
            val out = StringBuilder(text.length + 16)
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                when {
                    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                        ch == '-' || ch == '_' || ch == '.' || ch == '~' -> out.append(ch)
                    ch == ' ' -> out.append('+')
                    else -> out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
                }
            }
            return out.toString()
        }

        /** Dart's `Uri.decodeQueryComponent`. Throws [IllegalArgumentException] on a broken escape. */
        fun decodeQueryComponent(text: String): String = java.net.URLDecoder.decode(text, "UTF-8")
    }
}
