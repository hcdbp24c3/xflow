package com.alchemistaloha.xflow.data.feed

import androidx.compose.runtime.mutableStateMapOf
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.model.Tweet
import com.alchemistaloha.xflow.core.translate.TranslationResult
import com.alchemistaloha.xflow.data.settings.ViewerPrefs
import kotlinx.coroutines.launch

/** Where the translation of one post stands. */
sealed interface TranslationState {
    data object Loading : TranslationState

    /** [showing] is false while the reader looks at the original again. */
    data class Ready(val text: String, val sourceLang: String?, val showing: Boolean) : TranslationState

    data class Failed(val reason: String) : TranslationState
}

/**
 * Translations asked for in this run of the app, by post id. Kept in memory
 * only: a post translated once is not asked for again until the app restarts
 * or the target language changes. Main thread only; the map is Compose state.
 */
object TranslationStore {
    private val states = mutableStateMapOf<String, TranslationState>()
    private var language = ""

    operator fun get(tweetId: String): TranslationState? = states[tweetId]

    /** Translate, or flip between the translation and the original when it is already here. */
    fun toggle(tweet: Tweet) {
        val target = ViewerPrefs.state.value.targetLang
        if (target != language) {
            states.clear()
            language = target
        }
        when (val current = states[tweet.id]) {
            is TranslationState.Loading -> return
            is TranslationState.Ready -> {
                states[tweet.id] = current.copy(showing = !current.showing)
                return
            }
            else -> Unit
        }
        states[tweet.id] = TranslationState.Loading
        AppGraph.scope.launch {
            val result = twitterClient.translateTweet(tweet.id, target)
            // The language changed while this was on its way: the answer is for the old one.
            if (language != target) return@launch
            states[tweet.id] = when (result) {
                is TranslationResult.Success ->
                    TranslationState.Ready(result.text, result.sourceLang ?: tweet.lang, showing = true)
                is TranslationResult.Failure -> TranslationState.Failed(result.reason)
            }
        }
    }
}
