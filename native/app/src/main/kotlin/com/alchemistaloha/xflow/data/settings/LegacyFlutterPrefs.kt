package com.alchemistaloha.xflow.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.alchemistaloha.xflow.core.settings.LegacyPrefsDecoder
import com.alchemistaloha.xflow.core.settings.LegacySettingsImport
import com.alchemistaloha.xflow.core.settings.SettingsStorage
import com.alchemistaloha.xflow.core.util.AppLogger
import java.io.File

/**
 * One-time copy of the settings the Flutter app kept in its
 * `shared_preferences` file ("FlutterSharedPreferences", keys prefixed
 * "flutter."). The Flutter file is only read: nothing is written to it and it
 * is never cleared, so going back to the Flutter build still finds it.
 */
internal object LegacyFlutterPrefs {
    /** Set in "xflow_settings" once the import has been attempted. */
    const val DONE_KEY = "legacy_flutter_prefs_imported"

    fun importOnce(context: Context, nativePrefs: SharedPreferences, target: SettingsStorage) {
        if (nativePrefs.getBoolean(DONE_KEY, false)) return
        try {
            val file = File(
                File(context.applicationInfo.dataDir, "shared_prefs"),
                "${LegacyPrefsDecoder.FILE_NAME}.xml",
            )
            if (file.exists()) {
                val flutterPrefs = context.applicationContext
                    .getSharedPreferences(LegacyPrefsDecoder.FILE_NAME, Context.MODE_PRIVATE)
                val copied = LegacySettingsImport.importInto(flutterPrefs.all, target)
                AppLogger.log("XFLOW: Imported $copied settings from the Flutter app")
            }
        } catch (e: Exception) {
            AppLogger.log("XFLOW: Could not import Flutter settings, continuing with defaults: $e")
        }
        // Attempted once, whatever the outcome: a later run must not overwrite
        // what the user has changed since.
        nativePrefs.edit().putBoolean(DONE_KEY, true).apply()
    }
}
