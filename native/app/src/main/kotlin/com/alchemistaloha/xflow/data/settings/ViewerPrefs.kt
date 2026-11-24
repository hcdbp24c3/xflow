package com.alchemistaloha.xflow.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.translate.TranslateLogic
import com.alchemistaloha.xflow.core.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Translation and subtitle choices. */
data class ViewerPrefsState(
    /** Language posts are translated into; empty means the device language. */
    val translateLang: String = "",
    val subtitlesOn: Boolean = false,
    /** Language of the subtitle track last picked by hand; empty means the device language. */
    val subtitleLang: String = "",
) {
    val targetLang: String get() = TranslateLogic.targetLanguage(translateLang, ViewerPrefs.deviceLang)
}

/**
 * Settings the Flutter app never had, kept in a file of their own so the
 * settings file that mirrors Flutter's stays as it was.
 */
object ViewerPrefs {
    private const val FILE_NAME = "xflow_viewer"
    private val _state = MutableStateFlow(ViewerPrefsState())
    val state: StateFlow<ViewerPrefsState> = _state

    @Volatile private var prefs: SharedPreferences? = null

    val deviceLang: String get() = Locale.getDefault().language

    private fun file(): SharedPreferences? {
        prefs?.let { return it }
        return try {
            AppGraph.appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).also { prefs = it }
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Viewer settings could not be opened: $e")
            null
        }
    }

    fun init() {
        val p = file() ?: return
        _state.value = ViewerPrefsState(
            translateLang = p.getString("translateLang", "") ?: "",
            subtitlesOn = p.getBoolean("subtitlesOn", false),
            subtitleLang = p.getString("subtitleLang", "") ?: "",
        )
    }

    fun setTranslateLang(lang: String) {
        _state.value = _state.value.copy(translateLang = lang)
        file()?.edit()?.putString("translateLang", lang)?.apply()
    }

    fun setSubtitlesOn(on: Boolean) {
        _state.value = _state.value.copy(subtitlesOn = on)
        file()?.edit()?.putBoolean("subtitlesOn", on)?.apply()
    }

    fun setSubtitleLang(lang: String) {
        _state.value = _state.value.copy(subtitleLang = lang)
        file()?.edit()?.putString("subtitleLang", lang)?.apply()
    }
}
