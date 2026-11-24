package com.alchemistaloha.xflow.core.client

import java.security.MessageDigest
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * What the x.com document gives to the `x-client-transaction-id` algorithm:
 * the site verification key and the number rows of the four loading frames.
 */
class TransactionIdPage(
    /** The decoded `twitter-site-verification` key. */
    val key: ByteArray,
    /** Per loading frame, the rows of numbers of its second path. */
    val frames: List<List<List<Int>>>,
)

/** Which bytes of the key choose the frame row and the animation time. */
data class TransactionIdIndices(val row: Int, val time: List<Int>) {
    override fun toString(): String = "row=$row time=${time.joinToString(",")}"

    companion object {
        /**
         * Read from X's own generator module on 2026-11-24 (by running it and
         * recording which key bytes it reads). X changes these with a new
         * build of the module; the ids made here then stop being accepted and
         * the request falls back to the other sources.
         */
        val DEFAULT = TransactionIdIndices(row = 11, time = listOf(25, 3, 45))
    }
}

/**
 * The `x-client-transaction-id` header computed without a browser.
 *
 * Written from the published descriptions of the algorithm (the header is
 * the key, a timestamp and a SHA-256 over method, path, time and an
 * "animation key", XOR-ed with a random byte). X's page gets the animation
 * key from a CSS animation; here the same cubic-bezier interpolation is
 * done with arithmetic, so the browser is not needed.
 */
object TransactionIdGenerator {
    private const val KEYWORD = "obfiowerehiring"
    private const val EPOCH_SECONDS = 1682924400L
    private const val TOTAL_TIME = 4096.0
    private const val TRAILER = 3

    private val keyRegex = Regex("""<meta[^>]*name=["']twitter-site-verification["'][^>]*>""")
    private val contentRegex = Regex("""content=["']([^"']+)["']""")
    private val frameRegex = Regex("""<svg[^>]*id=["']loading-x-anim-(\d)["'][\s\S]*?</svg>""")
    private val pathRegex = Regex("""<path[^>]*\sd=["']([^"']+)["']""")
    private val notDigits = Regex("""[^\d]+""")

    /** Reads the key and the frames out of the x.com document, or null when one is missing. */
    fun parsePage(html: String): TransactionIdPage? {
        val meta = keyRegex.find(html)?.value ?: return null
        val content = contentRegex.find(meta)?.groupValues?.get(1) ?: return null
        val key = try {
            Base64.getDecoder().decode(content.trim().let { it + "=".repeat((4 - it.length % 4) % 4) })
        } catch (e: IllegalArgumentException) {
            return null
        }
        val frames = ArrayList<List<List<Int>>>()
        for (frame in frameRegex.findAll(html).sortedBy { it.groupValues[1] }) {
            val paths = pathRegex.findAll(frame.value).map { it.groupValues[1] }.toList()
            if (paths.size < 2) return null
            val d = paths[1]
            if (d.length <= 9) return null
            frames.add(
                d.substring(9).split("C").map { part ->
                    notDigits.replace(part, " ").trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt() }
                },
            )
        }
        if (frames.size < 4) return null
        return TransactionIdPage(key, frames)
    }

    /** Why [page] cannot be used with [indices], or null when it can. */
    fun problem(page: TransactionIdPage, indices: TransactionIdIndices): String? {
        val needed = (indices.time + indices.row + 5).max()
        if (page.key.size <= needed) return "key has ${page.key.size} bytes, index $needed needed"
        val rows = page.frames[(page.key[5].toInt() and 0xff) % 4]
        val row = rows.getOrNull((page.key[indices.row].toInt() and 0xff) % 16)
            ?: return "frame has ${rows.size} rows"
        if (row.size < 11) return "row has ${row.size} numbers"
        return null
    }

    /** The text X's page reads back from the animated element. */
    fun animationKey(page: TransactionIdPage, indices: TransactionIdIndices = TransactionIdIndices.DEFAULT): String {
        problem(page, indices)?.let { throw IllegalArgumentException(it) }
        fun keyAt(i: Int) = page.key[i].toInt() and 0xff
        val row = page.frames[keyAt(5) % 4][keyAt(indices.row) % 16]

        var frameTime = 1
        for (i in indices.time) frameTime *= keyAt(i) % 16
        val time = jsRound(frameTime / 10.0) * 10 / TOTAL_TIME

        val fromColor = doubleArrayOf(row[0].toDouble(), row[1].toDouble(), row[2].toDouble())
        val toColor = doubleArrayOf(row[3].toDouble(), row[4].toDouble(), row[5].toDouble())
        val toRotation = floor(scale(row[6], 60.0, 360.0))
        val curve = DoubleArray(4) { i -> round2(scale(row[7 + i], if (i % 2 == 1) -1.0 else 0.0, 1.0)) }

        val progress = cubicBezier(curve, time)
        val out = StringBuilder()
        for (i in 0 until 3) {
            val value = (fromColor[i] * (1 - progress) + toColor[i] * progress).coerceIn(0.0, 255.0)
            out.append(jsRound(value).toString(16))
        }
        val radians = toRotation * progress * PI / 180
        for (value in doubleArrayOf(cos(radians), -sin(radians), sin(radians), cos(radians))) {
            out.append(hexOfFraction(abs(round2(value))))
        }
        out.append("00")
        return out.toString().replace(".", "").replace("-", "")
    }

    /**
     * The header value for [method] and [path] (the path only, no query).
     * [nowSeconds] and [randomByte] are parameters so a test can fix them.
     */
    fun generate(
        method: String,
        path: String,
        key: ByteArray,
        animationKey: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        randomByte: Int = (Math.random() * 256).toInt(),
    ): String {
        val time = nowSeconds - EPOCH_SECONDS
        val timeBytes = ByteArray(4) { i -> (time shr (8 * i)).toByte() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$method!$path!$time$KEYWORD$animationKey".toByteArray(Charsets.UTF_8))

        val random = randomByte and 0xff
        val out = ByteArray(1 + key.size + 4 + 16 + 1)
        var at = 0
        out[at++] = random.toByte()
        for (b in key) out[at++] = ((b.toInt() and 0xff) xor random).toByte()
        for (b in timeBytes) out[at++] = ((b.toInt() and 0xff) xor random).toByte()
        for (i in 0 until 16) out[at++] = ((hash[i].toInt() and 0xff) xor random).toByte()
        out[at] = (TRAILER xor random).toByte()
        return Base64.getEncoder().withoutPadding().encodeToString(out)
    }

    // ---- arithmetic ------------------------------------------------------

    private fun scale(value: Int, min: Double, max: Double): Double = value * (max - min) / 255 + min

    /** JavaScript `Math.round`: halves go up. */
    private fun jsRound(value: Double): Int = floor(value + 0.5).toInt()

    /** JavaScript `Number(value.toFixed(2))`: the exact value rounded to two decimals, ties away from zero. */
    private fun round2(value: Double): Double =
        java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP).toDouble()

    private fun bezier(a: Double, b: Double, m: Double): Double =
        3.0 * a * (1 - m) * (1 - m) * m + 3.0 * b * (1 - m) * m * m + m * m * m

    /** The y of the CSS `cubic-bezier(x1, y1, x2, y2)` curve at [time] (0..1). */
    fun cubicBezier(c: DoubleArray, time: Double): Double {
        if (time <= 0.0) {
            val gradient = when {
                c[0] > 0.0 -> c[1] / c[0]
                c[1] == 0.0 && c[2] > 0.0 -> c[3] / c[2]
                else -> 0.0
            }
            return gradient * time
        }
        if (time >= 1.0) {
            val gradient = when {
                c[2] < 1.0 -> (c[3] - 1.0) / (c[2] - 1.0)
                c[2] == 1.0 && c[0] < 1.0 -> (c[1] - 1.0) / (c[0] - 1.0)
                else -> 0.0
            }
            return 1.0 + gradient * (time - 1.0)
        }
        var start = 0.0
        var end = 1.0
        var mid = 0.0
        while (start < end) {
            mid = (start + end) / 2
            val x = bezier(c[0], c[2], mid)
            if (abs(time - x) < 0.00001) return bezier(c[1], c[3], mid)
            if (x < time) start = mid else end = mid
            if (mid == start && mid == end) break
        }
        return bezier(c[1], c[3], mid)
    }

    /** JavaScript `Number.prototype.toString(16)` for a value in 0..1 with two decimals. */
    fun hexOfFraction(value: Double): String {
        val whole = floor(value).toLong()
        var fraction = value - whole
        val out = StringBuilder(whole.toString(16))
        if (fraction == 0.0) return out.toString()
        out.append('.')
        var digits = 0
        while (fraction > 0 && digits < 60) {
            fraction *= 16
            val digit = floor(fraction).toInt()
            fraction -= digit
            out.append(digit.toString(16))
            digits++
        }
        return out.toString()
    }
}
