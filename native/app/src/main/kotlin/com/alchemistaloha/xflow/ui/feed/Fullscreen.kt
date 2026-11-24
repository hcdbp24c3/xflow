package com.alchemistaloha.xflow.ui.feed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alchemistaloha.xflow.core.util.AppLogger

internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Fullscreen video: the item on screen takes the whole window, system bars hide and the
 * activity turns to the orientation that fits the video. The app shell hides its tab bar and
 * top bars while [tweetId] is set.
 */
object Fullscreen {
    /** Id of the tweet shown fullscreen, or null. */
    var tweetId: String? by mutableStateOf(null)
        private set

    val isActive: Boolean get() = tweetId != null

    fun enter(activity: Activity?, id: String, videoWidth: Int, videoHeight: Int) {
        // Default to portrait if the dimensions are missing or invalid.
        val aspectRatio = if (videoWidth > 0 && videoHeight > 0) videoWidth.toDouble() / videoHeight else 0.0
        val isLandscape = aspectRatio > 1.0
        AppLogger.log(
            "XFLOW: Fullscreen toggle. ID: $id W: $videoWidth H: $videoHeight AR: $aspectRatio Landscape: $isLandscape",
        )
        try {
            activity?.requestedOrientation =
                if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            tweetId = id
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Error toggling fullscreen: $e")
        }
    }

    fun exit(activity: Activity?) {
        if (tweetId == null) return
        tweetId = null
        try {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Error toggling fullscreen: $e")
        }
    }
}
