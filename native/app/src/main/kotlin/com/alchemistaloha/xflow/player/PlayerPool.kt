package com.alchemistaloha.xflow.player

import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.util.AppLogger

/**
 * A small pool of prepared players, one per tweet id, kept warm around the item on screen.
 * Port of the Flutter playerPoolProvider. Main thread only.
 *
 * The map is Compose snapshot state: a composable that reads `PlayerPool[id]` recomposes only
 * when that id's player appears or goes away.
 */
@OptIn(UnstableApi::class)
object PlayerPool {
    private val players = mutableStateMapOf<String, ExoPlayer>()

    operator fun get(id: String): ExoPlayer? = players[id]

    val size: Int get() = players.size

    /** Creates and prepares a player for [url] without starting it. No-op when [id] is warm. */
    fun warmup(id: String, url: String) {
        if (players.containsKey(id)) return
        try {
            val player = newPlayer()
            player.setMediaItem(MediaItem.fromUri(url))
            player.playWhenReady = false
            player.prepare()
            players[id] = player
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Error warming up player: $e")
        }
    }

    /** Re-opens the media after a playback error (Dart `player.open(Media(url), play: ...)`). */
    fun reopen(id: String, url: String, play: Boolean) {
        val player = players[id] ?: return
        player.stop()
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = play
        player.prepare()
    }

    fun cleanupExcept(activeIds: Set<String>) {
        // Nothing to drop: leave the map alone so no reader is notified.
        if (players.keys.all { it in activeIds }) return
        val drop = players.keys.filter { it !in activeIds }
        for (id in drop) players.remove(id)?.release()
    }

    fun pauseAll() {
        for (p in players.values) p.pause()
    }

    fun releaseAll() {
        val all = players.values.toList()
        players.clear()
        for (p in all) p.release()
    }

    private fun newPlayer(): ExoPlayer {
        val ctx = AppGraph.appContext
        // Short buffers: up to five players are warm at once, and feed clips are short.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_500, 20_000, 500, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(MediaCache.dataSourceFactory))
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NONE)
            .build()
            .apply { repeatMode = ExoPlayer.REPEAT_MODE_OFF }
    }
}
