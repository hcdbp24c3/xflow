package com.alchemistaloha.xflow.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {
    private fun resource(name: String): String =
        javaClass.getResource("/subtitles/$name")!!.readText()

    private val masterUrl = "https://video.twimg.com/amplify_video/2124940778208215040/pl/grgsQjvH1y0gJXu3.m3u8?tag=16&v=554"

    // ---- master playlist (a real one from video.twimg.com) ----

    @Test
    fun `master playlist of an X video yields its subtitle track`() {
        val tracks = HlsSubtitles.parseMaster(resource("master.m3u8"), masterUrl)
        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals("en", track.language)
        assertEquals("https://video.twimg.com/amplify_video/2124940778208215040/pl/s0/gipYa7evSWj7vfSD.m3u8", track.url)
        assertTrue(track.name.endsWith(".srt"))
        assertEquals("English", HlsSubtitles.label(track))
    }

    @Test
    fun `audio and video entries are not subtitle tracks`() {
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:NAME="Audio",TYPE=AUDIO,GROUP-ID="audio-32000",AUTOSELECT=YES,URI="/a.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=276801,RESOLUTION=480x270
            /v.m3u8
        """.trimIndent()
        assertTrue(HlsSubtitles.parseMaster(playlist, masterUrl).isEmpty())
    }

    @Test
    fun `several tracks keep their order and a comma inside a name does not split it`() {
        val playlist = """
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Tiếng Việt, bản 2",URI="vi/sub.m3u8",LANGUAGE="vi-VN"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="EN",URI="https://other.example/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="no uri"
        """.trimIndent()
        val tracks = HlsSubtitles.parseMaster(playlist, "https://video.twimg.com/x/pl/master.m3u8?tag=1")
        assertEquals(listOf("vi-vn", "en"), tracks.map { it.language })
        assertEquals("Tiếng Việt, bản 2", tracks[0].name)
        assertEquals("https://video.twimg.com/x/pl/vi/sub.m3u8", tracks[0].url)
        assertEquals("https://other.example/en.m3u8", tracks[1].url)
    }

    @Test
    fun `track playlist lists its vtt files`() {
        val segments = HlsSubtitles.parseSegments(
            resource("track.m3u8"),
            "https://video.twimg.com/amplify_video/2124940778208215040/pl/s0/gipYa7evSWj7vfSD.m3u8",
        )
        assertEquals(
            listOf("https://video.twimg.com/subtitles/amplify_video/2124940778208215040/0/iixzT4b5HNoiiY3-.vtt"),
            segments,
        )
    }

    @Test
    fun `resolve handles absolute, root and relative references`() {
        val base = "https://video.twimg.com/a/b/c.m3u8?tag=1"
        assertEquals("https://x.example/y", HlsSubtitles.resolve(base, "https://x.example/y"))
        assertEquals("https://video.twimg.com/r/s.vtt", HlsSubtitles.resolve(base, "/r/s.vtt"))
        assertEquals("https://video.twimg.com/a/b/s.vtt", HlsSubtitles.resolve(base, "s.vtt"))
        assertEquals("https://cdn.example/s.vtt", HlsSubtitles.resolve(base, "//cdn.example/s.vtt"))
        assertNull(HlsSubtitles.resolve("not a url", "s.vtt"))
    }

    // ---- choosing a track ----

    private val tracks = listOf(
        SubtitleTrack("Japanese", "ja", "j"),
        SubtitleTrack("English", "en", "e"),
        SubtitleTrack("Vietnamese", "vi", "v"),
    )

    @Test
    fun `the remembered language wins, then the device, then English, then the first`() {
        assertEquals("v", HlsSubtitles.pickTrack(tracks, "vi", "ja")?.url)
        assertEquals("j", HlsSubtitles.pickTrack(tracks, null, "ja-JP")?.url)
        assertEquals("e", HlsSubtitles.pickTrack(tracks, "fr", "de")?.url)
        assertEquals("j", HlsSubtitles.pickTrack(tracks.filter { it.language != "en" }, "fr", "de")?.url)
        assertNull(HlsSubtitles.pickTrack(emptyList(), "en", "en"))
    }

    @Test
    fun `a track without a language is called by its file name`() {
        assertEquals("talk", HlsSubtitles.label(SubtitleTrack("talk.srt", "", "u")))
        assertEquals("Subtitles", HlsSubtitles.label(SubtitleTrack("", "", "u")))
        assertEquals("Vietnamese", HlsSubtitles.label(SubtitleTrack("x", "vi", "u")))
    }

    // ---- WebVTT ----

    @Test
    fun `a real X subtitle file parses into timed cues`() {
        val cues = WebVtt.parse(resource("sample.vtt"))
        assertTrue(cues.size >= 4)
        assertEquals(5638L, cues[0].startMs)
        assertEquals(9442L, cues[0].endMs)
        assertEquals("In America\nand at NASA, we never cease to explore.", cues[0].text)
        assertEquals("", WebVtt.textAt(cues, 0))
        assertEquals(cues[0].text, WebVtt.textAt(cues, 6000))
        // The gap between the first and the second cue.
        assertEquals("", WebVtt.textAt(cues, 9500))
        assertEquals(cues[1].text, WebVtt.textAt(cues, 9642))
    }

    @Test
    fun `identifiers, settings, tags, notes and styles are dropped`() {
        val vtt = "\uFEFFWEBVTT - title\r\n\r\nSTYLE\r\n::cue { color: red }\r\n\r\nNOTE a comment\r\n\r\n" +
            "intro\r\n00:01.000 --> 00:02.500 line:90% align:center\r\n<v Ann><b>Hi</b> &amp; bye</v>\r\n\r\n" +
            "1:00:00,000 --> 1:00:01,000\r\nlate\r\n"
        val cues = WebVtt.parse(vtt)
        assertEquals(
            listOf(SubtitleCue(1000, 2500, "Hi & bye"), SubtitleCue(3_600_000, 3_601_000, "late")),
            cues,
        )
    }

    @Test
    fun `empty and backwards cues are skipped and the offset is added`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n\n00:00:05.000 --> 00:00:04.000\nback\n\n00:00:03.000 --> 00:00:04.000\nok"
        assertEquals(listOf(SubtitleCue(13_000, 14_000, "ok")), WebVtt.parse(vtt, offsetMs = 10_000))
    }

    @Test
    fun `cues are sorted and overlapping ones are stacked`() {
        val vtt = "WEBVTT\n\n00:05.000 --> 00:08.000\nsecond\n\n00:01.000 --> 00:06.000\nfirst\n00:10.000 --> 00:11.000\nthird"
        val cues = WebVtt.parse(vtt)
        assertEquals(listOf("first", "second", "third"), cues.map { it.text })
        assertEquals("first\nsecond", WebVtt.textAt(cues, 5500))
        assertEquals("second", WebVtt.textAt(cues, 7000))
        assertEquals("", WebVtt.textAt(cues, 8000))
        assertEquals("third", WebVtt.textAt(cues, 10_000))
        assertEquals("", WebVtt.textAt(emptyList(), 10))
    }

    @Test
    fun `times are read with or without hours`() {
        assertEquals(62_345L, WebVtt.parseTime("01:02.345"))
        assertEquals(3_723_004L, WebVtt.parseTime("01:02:03.004"))
        assertEquals(62_300L, WebVtt.parseTime("1:02,3"))
        assertNull(WebVtt.parseTime("12"))
        assertNull(WebVtt.parseTime("a:b"))
    }
}
