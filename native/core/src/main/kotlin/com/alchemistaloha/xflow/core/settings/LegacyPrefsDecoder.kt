package com.alchemistaloha.xflow.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes values written by Flutter's `shared_preferences` plugin on Android
 * (file "FlutterSharedPreferences", keys prefixed "flutter."). Pure: it only
 * looks at the map it is given and never writes anywhere.
 */
object LegacyPrefsDecoder {
    const val FILE_NAME = "FlutterSharedPreferences"
    const val KEY_PREFIX = "flutter."
    const val DOUBLE_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"
    const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
    const val JSON_LIST_PREFIX = "$LIST_PREFIX!"
    const val BIG_INTEGER_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBCaWdJbnRlZ2Vy"

    sealed interface Value
    data class BoolValue(val value: Boolean) : Value
    data class IntValue(val value: Long) : Value
    data class DoubleValue(val value: Double) : Value
    data class StringValue(val value: String) : Value
    data class StringListValue(val value: List<String>) : Value

    /**
     * One stored value, or null when it cannot be read: an unknown type, a
     * malformed number, a BigInteger, or a list in the older Base64
     * Java-serialized form (which is skipped on purpose).
     */
    fun decodeValue(raw: Any?): Value? = when (raw) {
        is Boolean -> BoolValue(raw)
        is Long -> IntValue(raw)
        is Int -> IntValue(raw.toLong())
        is Float -> DoubleValue(raw.toDouble())
        is Double -> DoubleValue(raw)
        is String -> decodeString(raw)
        else -> null
    }

    private fun decodeString(raw: String): Value? {
        if (raw.startsWith(JSON_LIST_PREFIX)) {
            return decodeJsonList(raw.substring(JSON_LIST_PREFIX.length))
        }
        if (raw.startsWith(LIST_PREFIX)) return null // older Base64 form: skipped
        if (raw.startsWith(BIG_INTEGER_PREFIX)) return null
        if (raw.startsWith(DOUBLE_PREFIX)) {
            val number = raw.substring(DOUBLE_PREFIX.length).toDoubleOrNull() ?: return null
            return DoubleValue(number)
        }
        return StringValue(raw)
    }

    private fun decodeJsonList(json: String): Value? = try {
        val element = Json.parseToJsonElement(json)
        if (element !is JsonArray) {
            null
        } else {
            val items = ArrayList<String>()
            var ok = true
            for (item in element) {
                if (item is JsonPrimitive && item !is JsonNull && item.isString) {
                    items.add(item.content)
                } else {
                    ok = false
                }
            }
            if (ok) StringListValue(items) else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Every readable "flutter."-prefixed entry of [raw], keyed without the
     * prefix. Entries that cannot be decoded are left out.
     */
    fun decodeAll(raw: Map<String, *>): Map<String, Value> {
        val out = LinkedHashMap<String, Value>()
        for ((key, value) in raw) {
            if (!key.startsWith(KEY_PREFIX)) continue
            val decoded = decodeValue(value) ?: continue
            out[key.substring(KEY_PREFIX.length)] = decoded
        }
        return out
    }
}

/** Copies the known setting keys from a decoded Flutter prefs map into the native storage. */
object LegacySettingsImport {
    /**
     * Writes every known key found in [flutterPrefs] (the raw contents of the
     * Flutter file) into [target], with the right type, unless [target]
     * already has that key. Returns the number of keys copied. Values of the
     * wrong type or out of range are skipped.
     */
    fun importInto(flutterPrefs: Map<String, *>, target: SettingsStorage): Int {
        val decoded = LegacyPrefsDecoder.decodeAll(flutterPrefs)
        var copied = 0
        for ((key, value) in decoded) {
            if (target.contains(key)) continue
            when {
                key in SettingsKeys.INT_KEYS && value is LegacyPrefsDecoder.IntValue -> {
                    if (value.value < Int.MIN_VALUE || value.value > Int.MAX_VALUE) continue
                    target.setInt(key, value.value.toInt())
                }
                key in SettingsKeys.BOOL_KEYS && value is LegacyPrefsDecoder.BoolValue ->
                    target.setBool(key, value.value)
                key in SettingsKeys.DOUBLE_KEYS && value is LegacyPrefsDecoder.DoubleValue ->
                    target.setDouble(key, value.value)
                // Dart's getDouble also accepted a whole number stored as an int.
                key in SettingsKeys.DOUBLE_KEYS && value is LegacyPrefsDecoder.IntValue ->
                    target.setDouble(key, value.value.toDouble())
                key in SettingsKeys.STRING_LIST_KEYS && value is LegacyPrefsDecoder.StringListValue ->
                    target.setStringList(key, value.value)
                else -> continue
            }
            copied++
        }
        return copied
    }
}
