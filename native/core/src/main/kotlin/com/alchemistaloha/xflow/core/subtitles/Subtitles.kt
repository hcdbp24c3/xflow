package com.alchemistaloha.xflow.core.subtitles

import java.util.Locale

/** One subtitle track of a video: [url] is its playlist. */
data class SubtitleTrack(val name: String, val language: String, val url: String)

/** One line of subtitles, shown from [startMs] until [endMs]. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Subtitles of an X video. X does not list them next to the mp4 files: they
 * are in the HLS master playlist of the video (the `application/x-mpegURL`
 * variant), as `#EXT-X-MEDIA:TYPE=SUBTITLES,…,URI="…"` lines. Each points to
 * a playlist of WebVTT files, usually just one.
 */
object HlsSubtitles {
    private val attribute = Regex("""([A-Z0-9-]+)=("([^"]*)"|[^,]*)""")

    /** The subtitle tracks named in a master playlist, in its order. */
    fun parseMaster(playlist: String, playlistUrl: String): List<SubtitleTrack> {
        val tracks = mutableListOf<SubtitleTrack>()
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:")) continue
            val attributes = attributes(line.substringAfter(':'))
            if (attributes["TYPE"] != "SUBTITLES") continue
            val uri = attributes["URI"]?.takeIf { it.isNotBlank() } ?: continue
            val url = resolve(playlistUrl, uri) ?: continue
            tracks += SubtitleTrack(
                name = attributes["NAME"].orEmpty(),
                language = attributes["LANGUAGE"].orEmpty().lowercase(Locale.ROOT),
                url = url,
            )
        }
        return tracks
    }

    /** The files of a subtitle playlist, in order. */
    fun parseSegments(playlist: String, playlistUrl: String): List<String> = playlist.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { resolve(playlistUrl, it) }
        .toList()

    private fun attributes(text: String): Map<String, String> =
        attribute.findAll(text).associate { m -> m.groupValues[1] to (m.groups[3]?.value ?: m.groupValues[2]) }

    /** [reference] as an address of its own: absolute, from the host's root, or next to [base]. */
    fun resolve(base: String, reference: String): String? {
        if (reference.startsWith("https://") || reference.startsWith("http://")) return reference
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return null
        val pathStart = base.indexOf('/', schemeEnd + 3).let { if (it < 0) base.length else it }
        val origin = base.substring(0, pathStart)
        if (reference.startsWith("//")) return base.substring(0, schemeEnd + 1) + reference
        if (reference.startsWith("/")) return origin + reference
        val path = base.substring(pathStart).substringBefore('?').substringBefore('#')
        val directory = path.substringBeforeLast('/', "")
        return "$origin$directory/$reference"
    }

    /**
     * The track to show: the one last chosen, else the device language, else
     * English, else the first.
     */
    fun pickTrack(tracks: List<SubtitleTrack>, preferredLang: String?, deviceLang: String?): SubtitleTrack? {
        if (tracks.isEmpty()) return null
        for (wanted in listOf(preferredLang, deviceLang, "en")) {
            val code = base(wanted)
            if (code.isEmpty()) continue
            tracks.firstOrNull { base(it.language) == code }?.let { return it }
        }
        return tracks.first()
    }

    /** What a track is called in the menu: its language when it names one, else the uploader's name for it. */
    fun label(track: SubtitleTrack, inLocale: Locale = Locale.ENGLISH): String {
        val code = base(track.language)
        if (code.isNotEmpty() && code != "und") {
            val name = Locale.forLanguageTag(code).getDisplayLanguage(inLocale)
            if (name.isNotEmpty() && !name.equals(code, ignoreCase = true)) {
                return name.replaceFirstChar { it.titlecase(inLocale) }
            }
            return code.uppercase(Locale.ROOT)
        }
        return track.name.substringBeforeLast('.').ifBlank { "Subtitles" }
    }

    private fun base(lang: String?): String =
        lang.orEmpty().trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
}

/** A reader for WebVTT that keeps the timing and the words and drops the styling. */
object WebVtt {
    private val timing = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val tag = Regex("""<[^>]*>""")

    /**
     * The cues of one file, sorted by start. [offsetMs] is added to every
     * time. X serves one file per track with times counted from the start of
     * the video, so `X-TIMESTAMP-MAP` headers are not interpreted.
     */
    fun parse(text: String, offsetMs: Long = 0): List<SubtitleCue> {
        val lines = text.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = mutableListOf<SubtitleCue>()
        val shift = offsetMs
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val match = timing.find(line)
            if (match == null) {
                i++
                continue
            }
            val start = parseTime(match.groupValues[1])
            val end = parseTime(match.groupValues[2])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                // A cue with no blank line before the next one.
                if (timing.containsMatchIn(lines[i])) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            val words = clean(body.toString())
            if (start != null && end != null && end > start && words.isNotEmpty()) {
                cues += SubtitleCue(start + shift, end + shift, words)
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /** `00:01:02.345`, `01:02.345` or `1:02,3` in milliseconds. */
    fun parseTime(text: String): Long? {
        val parts = text.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return hours * 3_600_000 + minutes * 60_000 + Math.round(seconds * 1000)
    }

    private fun clean(text: String): String = text
        .replace(tag, "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&lrm;", "").replace("&rlm;", "")
        .lines().joinToString("\n") { it.trim() }
        .trim()

    /**
     * What is on screen at [positionMs]. Cues that overlap are stacked in
     * their order. [cues] must be sorted by start, as [parse] returns them.
     */
    fun textAt(cues: List<SubtitleCue>, positionMs: Long): String {
        if (cues.isEmpty()) return ""
        // First cue that starts after the position; everything showing now is before it.
        var low = 0
        var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) low = mid + 1 else high = mid
        }
        val showing = ArrayList<String>(2)
        var index = low - 1
        // Overlaps are short: looking a few cues back is enough.
        var looked = 0
        while (index >= 0 && looked < 6) {
            val cue = cues[index]
            if (positionMs < cue.endMs && showing.none { it == cue.text }) showing.add(0, cue.text)
            index--
            looked++
        }
        return showing.joinToString("\n")
    }
}
