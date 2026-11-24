package com.alchemistaloha.xflow.core.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Page snapping rule for the vertical media feed (Dart FeedPageScrollPhysics.targetPixels):
 * one page per swipe; a release slower than [FLING_VELOCITY] snaps to the nearest page instead
 * of always advancing in the direction of the last few pixels of movement.
 */
object FeedPagePhysics {
    /** Logical pixels (dp) per second a release needs to turn the page on its own. */
    const val FLING_VELOCITY = 250.0

    /** Snap spring: mass 0.4, stiffness 220, critically damped (lands fast, no overshoot). */
    const val SPRING_MASS = 0.4
    const val SPRING_STIFFNESS = 220.0
    const val SPRING_DAMPING_RATIO = 1.0

    /** Compose springs have unit mass: the equivalent stiffness is k/m. */
    const val COMPOSE_SPRING_STIFFNESS = (SPRING_STIFFNESS / SPRING_MASS).toFloat()

    fun targetPixels(
        pixels: Double,
        viewportDimension: Double,
        minScrollExtent: Double,
        maxScrollExtent: Double,
        velocity: Double,
    ): Double {
        if (viewportDimension <= 0) return pixels
        var page = pixels / viewportDimension
        if (velocity < -FLING_VELOCITY) page -= 0.5 else if (velocity > FLING_VELOCITY) page += 0.5
        val target = Math.rint(page).let { r ->
            // Dart roundToDouble rounds half away from zero; rint rounds half to even.
            if (abs(page - Math.floor(page) - 0.5) < 1e-12) (if (page >= 0) Math.floor(page) + 1 else Math.floor(page)) else r
        } * viewportDimension
        return target.coerceIn(minScrollExtent, maxScrollExtent)
    }

    /** The page a release at fractional [page] with [velocity] (dp/s) settles on. */
    fun targetPage(page: Double, velocity: Double, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        return (targetPixels(page, 1.0, 0.0, (pageCount - 1).toDouble(), velocity)).roundToInt()
    }
}

/**
 * The decisions of the Dart MediaPager, without any UI: which item is "current", when the
 * current item is silenced by a drag, and which items the player pool keeps warm.
 *
 * Both the current item and the pool are updated when a swipe has landed, not while the page
 * is still moving: creating and disposing native players mid-animation makes swiping stutter,
 * and switching the current item at the halfway point makes the next video start (and the
 * previous one stop) under the user's finger.
 */
class MediaPagerLogic(initialIndex: Int = 0) {
    var currentIndex: Int = initialIndex
        private set
    var poolCenter: Int = initialIndex
        private set
    var pausedByScroll: Boolean = false
        private set

    sealed interface ScrollAction {
        /** The current item is more than half off screen: pause it now. */
        data object PauseCurrent : ScrollAction

        /** Swiped several pages without landing: re-centre the warm window on [center]. */
        data class CatchUpPool(val center: Int) : ScrollAction
    }

    sealed interface CommitResult {
        data object Nothing : CommitResult

        /** Dragged away and came back to the same item: resume it. */
        data object ResumeCurrent : CommitResult

        /** A new item landed. Pool work follows after [POOL_DELAY_MS]. */
        data class Changed(val index: Int) : CommitResult
    }

    /**
     * Runs on every scroll tick. [isCurrentPlaying] is asked only when a pause is being
     * considered. Returns the cheap things that cannot wait for landing.
     */
    fun onScroll(page: Double, isCurrentPlaying: () -> Boolean): List<ScrollAction> {
        val actions = ArrayList<ScrollAction>(2)
        if (!pausedByScroll && abs(page - currentIndex) >= 0.5) {
            if (isCurrentPlaying()) {
                pausedByScroll = true
                actions.add(ScrollAction.PauseCurrent)
            }
        }
        if (abs(page - poolCenter) >= 2) {
            actions.add(ScrollAction.CatchUpPool(page.roundToInt()))
        }
        return actions
    }

    /** True when [page] is close enough to a whole page to count as landed. */
    fun isLanded(page: Double): Boolean = abs(page - page.roundToInt()) <= LANDED_EPSILON

    fun commit(index: Int, itemCount: Int): CommitResult {
        if (itemCount <= 0) return CommitResult.Nothing
        val clamped = index.coerceIn(0, itemCount - 1)
        if (clamped == currentIndex) {
            if (pausedByScroll) {
                pausedByScroll = false
                return CommitResult.ResumeCurrent
            }
            return CommitResult.Nothing
        }
        pausedByScroll = false
        currentIndex = clamped
        return CommitResult.Changed(clamped)
    }

    /** Indices to keep warm around [center] (default: the current item), current always included. */
    fun warmWindow(itemCount: Int, center: Int? = null): List<Int> {
        val around = center ?: currentIndex
        val out = LinkedHashSet<Int>()
        if (currentIndex in 0 until itemCount) out.add(currentIndex)
        for (i in around - 1..around + 3) if (i in 0 until itemCount) out.add(i)
        poolCenter = around
        return out.toList()
    }

    companion object {
        const val LANDED_EPSILON = 0.02
        const val POOL_DELAY_MS = 140L

        /** Stable, unique keys for a feed that can (rarely) contain the same tweet twice. */
        fun uniqueKeys(ids: List<String>): List<String> {
            val seen = HashSet<String>()
            return ids.mapIndexed { i, id -> if (seen.add(id)) id else "$id#$i" }
        }
    }
}

/** Dart _formatCount in tweet_text_overlay.dart. */
fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> oneDecimal(count / 1_000_000.0) + "M"
    count >= 1_000 -> oneDecimal(count / 1_000.0) + "K"
    else -> count.toString()
}

private fun oneDecimal(v: Double): String {
    val tenths = Math.round(v * 10.0)
    return "${tenths / 10}.${tenths % 10}"
}

/** mm:ss as the seek bar shows it (minutes wrap at 60 like the Dart helper). */
fun formatSeekTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    return minutes.toString().padStart(2, '0') + ":" + seconds.toString().padStart(2, '0')
}

/** One piece of caption text (Dart _buildTweetText): plain text, #hashtag, @mention or URL. */
data class CaptionSpan(val text: String, val kind: Kind, val target: String = text) {
    enum class Kind { TEXT, HASHTAG, MENTION, URL }
}

private val whitespace = Regex("(\\s+)")
private val trailingPunctuation = Regex("[.,!?;:)\\]]+$")

fun parseCaption(text: String): List<CaptionSpan> {
    val words = ArrayList<String>()
    var lastEnd = 0
    for (m in whitespace.findAll(text)) {
        if (m.range.first > lastEnd) words.add(text.substring(lastEnd, m.range.first))
        words.add(m.value)
        lastEnd = m.range.last + 1
    }
    if (lastEnd < text.length) words.add(text.substring(lastEnd))
    return words.map { word ->
        when {
            word.startsWith("#") && word.length > 1 -> CaptionSpan(word, CaptionSpan.Kind.HASHTAG)
            word.startsWith("@") && word.length > 1 ->
                CaptionSpan(word, CaptionSpan.Kind.MENTION, word.replaceFirst("@", ""))
            word.startsWith("http://") || word.startsWith("https://") -> {
                val clean = word.replace(trailingPunctuation, "")
                CaptionSpan(clean, CaptionSpan.Kind.URL, clean)
            }
            else -> CaptionSpan(word, CaptionSpan.Kind.TEXT)
        }
    }
}
