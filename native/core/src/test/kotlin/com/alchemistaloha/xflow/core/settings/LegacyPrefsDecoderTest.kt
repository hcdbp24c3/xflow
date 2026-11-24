package com.alchemistaloha.xflow.core.settings

import com.alchemistaloha.xflow.core.settings.LegacyPrefsDecoder.BoolValue
import com.alchemistaloha.xflow.core.settings.LegacyPrefsDecoder.DoubleValue
import com.alchemistaloha.xflow.core.settings.LegacyPrefsDecoder.IntValue
import com.alchemistaloha.xflow.core.settings.LegacyPrefsDecoder.StringListValue
import com.alchemistaloha.xflow.core.settings.LegacyPrefsDecoder.StringValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyPrefsDecoderTest {
    private val dbl = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"
    private val lst = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"

    @Test
    fun `bool and int keep their value`() {
        assertEquals(BoolValue(true), LegacyPrefsDecoder.decodeValue(true))
        assertEquals(BoolValue(false), LegacyPrefsDecoder.decodeValue(false))
        assertEquals(IntValue(30), LegacyPrefsDecoder.decodeValue(30L))
        assertEquals(IntValue(7), LegacyPrefsDecoder.decodeValue(7))
    }

    @Test
    fun `double is a prefixed string`() {
        assertEquals(DoubleValue(0.45), LegacyPrefsDecoder.decodeValue(dbl + "0.45"))
        assertEquals(DoubleValue(1.0), LegacyPrefsDecoder.decodeValue(dbl + "1.0"))
        assertNull(LegacyPrefsDecoder.decodeValue(dbl + "abc"))
        assertNull(LegacyPrefsDecoder.decodeValue(dbl))
    }

    @Test
    fun `string list in the JSON form`() {
        assertEquals(
            StringListValue(listOf("video", "image")),
            LegacyPrefsDecoder.decodeValue("$lst![\"video\",\"image\"]"),
        )
        assertEquals(StringListValue(emptyList()), LegacyPrefsDecoder.decodeValue("$lst![]"))
    }

    @Test
    fun `string list in the old Base64 form or broken JSON is skipped`() {
        assertNull(LegacyPrefsDecoder.decodeValue(lst + "rO0ABXNyABNqYXZhLnV0aWwuQXJyYXlMaXN0"))
        assertNull(LegacyPrefsDecoder.decodeValue("$lst![\"video\""))
        assertNull(LegacyPrefsDecoder.decodeValue("$lst!{\"a\":1}"))
        assertNull(LegacyPrefsDecoder.decodeValue("$lst![1,2]"))
    }

    @Test
    fun `plain strings big integers and unknown types`() {
        assertEquals(StringValue("hello"), LegacyPrefsDecoder.decodeValue("hello"))
        assertNull(LegacyPrefsDecoder.decodeValue("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBCaWdJbnRlZ2Vy" + "123"))
        assertNull(LegacyPrefsDecoder.decodeValue(null))
        assertNull(LegacyPrefsDecoder.decodeValue(setOf("a")))
    }

    @Test
    fun `decodeAll strips the prefix and ignores foreign keys`() {
        val raw = mapOf<String, Any?>(
            "flutter.loadBatchSize" to 30L,
            "flutter.autoplay" to false,
            "flutter.freshMixRatio" to dbl + "0.5",
            "flutter.filters" to "$lst![\"text\"]",
            "flutter.broken" to lst + "AAAA",
            "other.key" to 1L,
            "loadBatchSize" to 99L,
        )
        assertEquals(
            mapOf(
                "loadBatchSize" to IntValue(30),
                "autoplay" to BoolValue(false),
                "freshMixRatio" to DoubleValue(0.5),
                "filters" to StringListValue(listOf("text")),
            ),
            LegacyPrefsDecoder.decodeAll(raw),
        )
    }

    @Test
    fun `import copies known keys with the right type and never overwrites`() {
        val target = MapSettingsStorage(mutableMapOf("syncInterval" to 99))
        val raw = mapOf<String, Any?>(
            "flutter.loadBatchSize" to 30L,
            "flutter.syncInterval" to 10L, // already set natively: kept
            "flutter.autoplay" to false,
            "flutter.freshMixRatio" to dbl + "0.5",
            "flutter.filters" to "$lst![\"text\",\"video\"]",
            "flutter.fetchStrategy" to 4L,
            "flutter.cooldownDuration" to "five", // wrong type: skipped
            "flutter.pruneThreshold" to 99999999999L, // out of range: skipped
            "flutter.showDebugInfo" to 1L, // wrong type: skipped
            "flutter.unknownKey" to 5L,
        )
        val copied = LegacySettingsImport.importInto(raw, target)
        assertEquals(5, copied)
        assertEquals(
            mapOf<String, Any>(
                "syncInterval" to 99,
                "loadBatchSize" to 30,
                "autoplay" to false,
                "freshMixRatio" to 0.5,
                "filters" to listOf("text", "video"),
                "fetchStrategy" to 4,
            ),
            target.values,
        )
    }

    @Test
    fun `import of an empty or foreign file copies nothing`() {
        val target = MapSettingsStorage()
        assertEquals(0, LegacySettingsImport.importInto(emptyMap<String, Any?>(), target))
        assertEquals(0, LegacySettingsImport.importInto(mapOf("x" to 1L), target))
        assertEquals(emptyMap<String, Any>(), target.values)
    }
}
