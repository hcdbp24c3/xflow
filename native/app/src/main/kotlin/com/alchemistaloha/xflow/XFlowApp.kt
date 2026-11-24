package com.alchemistaloha.xflow

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.client.TwitterAccount
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.data.settings.ViewerPrefs
import com.alchemistaloha.xflow.player.MediaCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class XFlowApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // Everything the app logs goes to Logcat under one fixed tag: `adb logcat -s XFlow`.
        AppLogger.sink = { message -> logToLogcat(message) }
        AppGraph.init(this)
        AppLogger.log(
            "XFLOW: App start. version=${installedVersionName()} " +
                "package=$packageName sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MODEL}",
        )

        // Same start-up order as the Flutter main(): settings, database, saved account.
        // Each step is on its own: a failure in one is logged and the app still opens
        // (worst case: default settings, or the login screen).
        AppGraph.scope.launch {
            step("settings") { withContext(Dispatchers.IO) { SettingsStore.init(this@XFlowApp) } }
            step("viewer settings") { withContext(Dispatchers.IO) { ViewerPrefs.init() } }
            step("database") { Repository.open() }
            step("account") { TwitterAccount.init() }
            step("summary") {
                AppLogger.log(
                    "XFLOW: Start-up done. loggedIn=${TwitterAccount.currentAccount != null} " +
                        "subscriptions=${Repository.getSubscriptions().size} cachedMedia=${Repository.getCachedMediaCount()} " +
                        "feedSource=${SettingsStore.state.value.fetchStrategy} settingsLoaded=${SettingsStore.state.value.isInitialized}",
                )
            }
            mutableReady.value = true
        }
    }

    private fun installedVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Start-up step '$name' failed: $e")
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = MediaCache.newImageLoader()

    companion object {
        /** The Logcat tag of every line this app writes. */
        const val LOG_TAG = "XFlow"
        private const val LOGCAT_CHUNK = 3500

        /** Logcat cuts a line at about 4000 bytes, so a long message is written in pieces. */
        private fun logToLogcat(message: String) {
            if (message.length <= LOGCAT_CHUNK) {
                android.util.Log.i(LOG_TAG, message)
                return
            }
            var start = 0
            var part = 1
            while (start < message.length && part <= 4) {
                val end = minOf(start + LOGCAT_CHUNK, message.length)
                android.util.Log.i(LOG_TAG, "(part $part) " + message.substring(start, end))
                start = end
                part++
            }
        }

        private val mutableReady = MutableStateFlow(false)

        /** True once storage has been opened and the UI may read it. */
        val ready: StateFlow<Boolean> = mutableReady
    }
}
