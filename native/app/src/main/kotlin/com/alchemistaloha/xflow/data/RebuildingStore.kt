package com.alchemistaloha.xflow.data

import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.model.Async
import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * The Kotlin shape of a Riverpod `AsyncNotifier` whose `build()` watches the
 * settings: the value is built lazily on first use, rebuilt when the settings
 * change (if `watchesSettings`) and rebuilt on [invalidate].
 *
 * All state changes happen on the main thread (`AppGraph.scope`). Work that
 * belongs to one build runs in [buildScope], which is cancelled when the store
 * is rebuilt, so a stale request can never overwrite newer state.
 */
abstract class RebuildingStore<T : Any>(private val watchesSettings: Boolean) {
    protected val mutableState = MutableStateFlow<Async<T>>(Async.Loading)

    private var started = false
    private var buildJob: Job? = null
    private var builtFor: SettingsState? = null

    /**
     * Counts rebuilds. A long-running method notes it at the start and uses
     * [isCurrent] before writing, so work begun for an earlier build is dropped.
     */
    protected var generation = 0
        private set

    protected fun isCurrent(startedAt: Int): Boolean = startedAt == generation

    /** Scope of the current build; replaced on every rebuild. */
    protected var buildScope: CoroutineScope = AppGraph.scope + SupervisorJob()
        private set

    /** Loading until the first build finishes. Reading it starts the store. */
    val state: StateFlow<Async<T>>
        get() {
            ensureStarted()
            return mutableState
        }

    /** Current value, or null while loading or after an error. */
    protected val value: T? get() = mutableState.value.valueOrNull

    /** Builds the value: the body of the Dart `build()`. */
    protected abstract suspend fun build(settings: SettingsState): T

    /** Called on the main thread right after a build has published its value. */
    protected open fun onBuilt(settings: SettingsState) {}

    /** Called when the store is about to be rebuilt (the old notifier is gone). */
    protected open fun onReset() {}

    /** Starts the first build if it has not started yet. Safe from any thread. */
    fun ensureStarted() {
        AppGraph.scope.launch {
            if (started) {
                // A settings change that arrived while nobody was looking.
                if (watchesSettings && builtFor != null && builtFor != SettingsStore.state.value &&
                    mutableState.subscriptionCount.value == 0
                ) {
                    rebuild()
                }
                return@launch
            }
            started = true
            rebuild()
            if (watchesSettings) {
                AppGraph.scope.launch {
                    SettingsStore.state.drop(1).collect { settings ->
                        // Like Riverpod, an unobserved store is rebuilt lazily on its next use.
                        if (settings != builtFor && (alwaysRebuild || mutableState.subscriptionCount.value > 0)) {
                            rebuild()
                        }
                    }
                }
            }
        }
    }

    /** True for stores that must follow the settings even when no screen observes them. */
    protected open val alwaysRebuild: Boolean get() = false

    /** Riverpod `ref.invalidate` / `invalidateSelf`: drop the state and build again. */
    fun invalidate() {
        AppGraph.scope.launch {
            if (!started) {
                ensureStarted()
            } else {
                rebuild()
            }
        }
    }

    private fun rebuild() {
        buildJob?.cancel()
        buildScope.cancel()
        buildScope = AppGraph.scope + SupervisorJob()
        generation++
        onReset()
        mutableState.value = Async.Loading
        val settings = SettingsStore.state.value
        builtFor = settings
        val scope = buildScope
        buildJob = scope.launch {
            try {
                val built = build(settings)
                mutableState.value = Async.Data(built)
                onBuilt(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("XFLOW: ${this@RebuildingStore.javaClass.simpleName} build failed: $e")
                mutableState.value = Async.Error(e)
            }
        }
    }

    /**
     * Runs [block] on the main thread as part of the current build and waits
     * for it. Like a Dart future, the work carries on if the caller goes away
     * (a screen closing must not leave a "loading" flag stuck); it stops only
     * when the store is rebuilt.
     */
    protected suspend fun inBuild(block: suspend () -> Unit) {
        buildScope.launch { block() }.join()
    }

    /** Same, for work that must finish even across a rebuild (a like, a bookmark). */
    protected suspend fun inApp(block: suspend () -> Unit) {
        AppGraph.scope.launch { block() }.join()
    }
}
