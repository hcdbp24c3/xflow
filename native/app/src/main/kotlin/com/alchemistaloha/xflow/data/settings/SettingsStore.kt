package com.alchemistaloha.xflow.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.alchemistaloha.xflow.core.settings.SettingsController
import com.alchemistaloha.xflow.core.settings.SettingsStorage
import com.alchemistaloha.xflow.core.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * `SharedPreferences` file "xflow_settings" behind [SettingsStorage]. Key
 * names are the Flutter ones. Ints and booleans are stored natively; a double
 * is stored as its text and a string list as a JSON array, since
 * SharedPreferences has neither a double nor an ordered list.
 */
internal object SettingsPrefs : SettingsStorage {
    const val FILE_NAME = "xflow_settings"

    @Volatile
    private var prefs: SharedPreferences? = null

    val isAttached: Boolean get() = prefs != null

    fun attach(context: Context): SharedPreferences {
        prefs?.let { return it }
        val opened = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs = opened
        return opened
    }

    private fun require(): SharedPreferences =
        prefs ?: throw IllegalStateException("SettingsStore.init(context) has not been called")

    override fun contains(key: String): Boolean = require().contains(key)

    private inline fun <T> read(key: String, get: SharedPreferences.() -> T): T? {
        val p = require()
        if (!p.contains(key)) return null
        return try {
            p.get()
        } catch (e: ClassCastException) {
            null
        }
    }

    override fun getBool(key: String): Boolean? = read(key) { getBoolean(key, false) }

    override fun getInt(key: String): Int? = read(key) { getInt(key, 0) }

    override fun getDouble(key: String): Double? = read(key) { getString(key, null) }?.toDoubleOrNull()

    override fun getStringList(key: String): List<String>? {
        val text = read(key) { getString(key, null) } ?: return null
        return try {
            val element = Json.parseToJsonElement(text)
            if (element is JsonArray) element.mapNotNull { (it as? JsonPrimitive)?.content } else null
        } catch (e: Exception) {
            null
        }
    }

    override fun setBool(key: String, value: Boolean) = require().edit().putBoolean(key, value).apply()

    override fun setInt(key: String, value: Int) = require().edit().putInt(key, value).apply()

    override fun setDouble(key: String, value: Double) = require().edit().putString(key, value.toString()).apply()

    override fun setStringList(key: String, value: List<String>) = require().edit()
        .putString(key, buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }.toString())
        .apply()
}

/**
 * The user's settings (Dart `settingsProvider` / `SettingsNotifier`).
 *
 * `state` and every update method (`updateMediaCacheSize`, `updateSyncInterval`,
 * `updateSyncBatchSize`, `updateLoadBatchSize`, `updateTimelineBatchSize`,
 * `updateCooldownDuration`, `updatePruneThreshold`, `updateDiscoveryParam`,
 * `toggleFilter`, `toggleAutoplay`, `toggleListView`, `toggleDebugInfo`) come
 * from [SettingsController]. Call [init] once at startup before any update.
 */
object SettingsStore : SettingsController(SettingsPrefs) {
    /**
     * Opens the settings file, brings over the Flutter app's settings the
     * first time, then loads everything and sets `isInitialized`. Safe to call
     * more than once; later calls do nothing.
     */
    @Synchronized
    fun init(context: Context) {
        if (state.value.isInitialized) return
        try {
            val prefs = SettingsPrefs.attach(context)
            LegacyFlutterPrefs.importOnce(context, prefs, SettingsPrefs)
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Settings storage could not be prepared: $e")
        }
        try {
            load()
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Settings could not be loaded, using defaults: $e")
        }
    }
}
