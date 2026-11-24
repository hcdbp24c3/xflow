package com.alchemistaloha.xflow.player

import androidx.compose.runtime.mutableStateMapOf
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.subtitles.HlsSubtitles
import com.alchemistaloha.xflow.core.subtitles.SubtitleCue
import com.alchemistaloha.xflow.core.subtitles.SubtitleTrack
import com.alchemistaloha.xflow.core.subtitles.WebVtt
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.client.TwitterAccount
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.feed.twitterClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What is known about the subtitles of one video. */
sealed interface SubtitleInfo {
    data object Loading : SubtitleInfo

    /** The video has none (or they could not be read; the log says which). */
    data object None : SubtitleInfo

    data class Available(val tracks: List<SubtitleTrack>) : SubtitleInfo
}

/**
 * Subtitle tracks and cues of the videos seen in this run of the app.
 *
 * The tracks are listed in the video's HLS master playlist. A post that came
 * from X in this run carries the playlist address; one read back from the
 * local cache gets it from the extras table, and when it is not there either
 * the post is looked up on X once (at most [LOOKUP_LIMIT] such lookups per
 * quarter of an hour, so a feed of old cached posts cannot use up the rate
 * limit). Main thread only; the maps are Compose state.
 */
object SubtitleStore {
    private const val LOOKUP_LIMIT = 20
    private const val LOOKUP_WINDOW_MS = 15 * 60_000L
    private const val MAX_SEGMENTS = 60
    private const val KEPT_VIDEOS = 80

    private val infos = mutableStateMapOf<String, SubtitleInfo>()
    private val cues = mutableStateMapOf<String, List<SubtitleCue>>()
    private val loadingCues = HashSet<String>()
    private val lookups = ArrayDeque<Long>()

    fun info(tweetId: String): SubtitleInfo? = infos[tweetId]

    /** Cues of a track, null until [loadCues] has brought them. */
    fun cues(track: SubtitleTrack): List<SubtitleCue>? = cues[track.url]

    /** Finds out which subtitle tracks [tweet]'s video has. Does nothing when already known or under way. */
    fun load(tweet: Tweet) {
        if (!tweet.isVideo || infos.containsKey(tweet.id)) return
        if (infos.size > KEPT_VIDEOS) {
            // Old entries only: the ones on screen are asked for again at once.
            infos.keys.take(infos.size - KEPT_VIDEOS / 2).forEach { infos.remove(it) }
        }
        infos[tweet.id] = SubtitleInfo.Loading
        AppGraph.scope.launch {
            val result = try {
                findTracks(tweet)
            } catch (e: CancellationException) {
                infos.remove(tweet.id)
                throw e
            } catch (e: Exception) {
                AppLogger.log("Subtitles: ${tweet.id} failed: $e")
                emptyList()
            }
            infos[tweet.id] = if (result.isEmpty()) SubtitleInfo.None else SubtitleInfo.Available(result)
        }
    }

    private suspend fun findTracks(tweet: Tweet): List<SubtitleTrack> {
        val playlistUrl = playlistOf(tweet)
        if (playlistUrl == null) {
            AppLogger.log("Subtitles: ${tweet.id} has no playlist address")
            return emptyList()
        }
        val master = TwitterAccount.fetchPublicText(playlistUrl)
        if (master == null) {
            AppLogger.log("Subtitles: ${tweet.id} playlist could not be read")
            return emptyList()
        }
        val tracks = HlsSubtitles.parseMaster(master, playlistUrl)
        AppLogger.log("Subtitles: ${tweet.id} tracks=${tracks.map { it.language.ifEmpty { "?" } }}")
        return tracks
    }

    private suspend fun playlistOf(tweet: Tweet): String? {
        tweet.hlsUrl?.let { return it }
        // A post parsed in this run names its language; without a playlist it is a GIF or an old upload.
        if (tweet.lang != null) return null
        Repository.getTweetExtras(tweet.id)?.let { (lang, hlsUrl) ->
            if (hlsUrl != null || lang != null) return hlsUrl
        }
        val now = System.currentTimeMillis()
        while (lookups.isNotEmpty() && now - lookups.first() > LOOKUP_WINDOW_MS) lookups.removeFirst()
        if (lookups.size >= LOOKUP_LIMIT) {
            AppLogger.log("Subtitles: ${tweet.id} not looked up, $LOOKUP_LIMIT lookups already made in the last 15 min")
            return null
        }
        lookups.addLast(now)
        val found = twitterClient.fetchTweetDetail(tweet.id).tweets.firstOrNull { it.id == tweet.id }
        if (found == null) {
            AppLogger.log("Subtitles: ${tweet.id} lookup did not return the post")
            return null
        }
        Repository.saveTweetExtras(if (found.lang == null) found.copy(lang = "und") else found)
        return found.hlsUrl
    }

    /** Brings the cues of [track]. Does nothing when they are here or on their way. */
    fun loadCues(track: SubtitleTrack) {
        if (cues.containsKey(track.url) || !loadingCues.add(track.url)) return
        AppGraph.scope.launch {
            val loaded = try {
                readCues(track)
            } catch (e: CancellationException) {
                loadingCues.remove(track.url)
                throw e
            } catch (e: Exception) {
                AppLogger.log("Subtitles: cues failed: $e")
                emptyList()
            }
            loadingCues.remove(track.url)
            if (cues.size > KEPT_VIDEOS) cues.keys.take(cues.size - KEPT_VIDEOS / 2).forEach { cues.remove(it) }
            cues[track.url] = loaded
        }
    }

    private suspend fun readCues(track: SubtitleTrack): List<SubtitleCue> {
        val text = TwitterAccount.fetchPublicText(track.url) ?: run {
            AppLogger.log("Subtitles: track playlist could not be read")
            return emptyList()
        }
        // A track address that is already a WebVTT file.
        if (text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("WEBVTT")) return WebVtt.parse(text)
        val segments = HlsSubtitles.parseSegments(text, track.url).take(MAX_SEGMENTS)
        val all = ArrayList<SubtitleCue>()
        for (segment in segments) {
            val vtt = TwitterAccount.fetchPublicText(segment) ?: continue
            all += WebVtt.parse(vtt)
        }
        AppLogger.log("Subtitles: ${track.language.ifEmpty { "?" }} files=${segments.size} cues=${all.size}")
        return all.distinct().sortedBy { it.startMs }
    }
}
