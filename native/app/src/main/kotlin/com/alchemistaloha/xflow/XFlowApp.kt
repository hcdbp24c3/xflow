package com.alchemistaloha.xflow

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.client.TwitterAccount
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.settings.SettingsStore
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
        AppGraph.init(this)

        // Same start-up order as the Flutter main(): settings, database, saved account.
        // Each step is on its own: a failure in one is logged and the app still opens
        // (worst case: default settings, or the login screen).
        AppGraph.scope.launch {
            step("settings") { withContext(Dispatchers.IO) { SettingsStore.init(this@XFlowApp) } }
            step("database") { Repository.open() }
            step("account") { TwitterAccount.init() }
            mutableReady.value = true
        }
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
        private val mutableReady = MutableStateFlow(false)

        /** True once storage has been opened and the UI may read it. */
        val ready: StateFlow<Boolean> = mutableReady
    }
}
