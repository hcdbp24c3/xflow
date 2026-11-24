package com.alchemistaloha.xflow.core.translate

import com.alchemistaloha.xflow.core.client.XUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** What a translation request came back with. */
sealed interface TranslationResult {
    /** [sourceLang] is a language code when the answer names one. */
    data class Success(val text: String, val sourceLang: String? = null) : TranslationResult

    /** [reason] is short enough to show under the post. */
    data class Failure(val reason: String) : TranslationResult
}

/**
 * X's "Translate post". The x.com web client (November 2026) sends
 * `POST /2/grok/translation.json` with `{content_type:"POST", id, dst_lang}`
 * and reads the answer as JSON lines: each line is `{result:{content_type,
 * text, entities}}` or `{error:{code, message}}`, and the `text` pieces of
 * the `POST` lines put together are the translation.
 *
 * The endpoint it used before, a Strato column that answered one JSON
 * object, is kept as a second way in.
 */
object Translation {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The hosts the web client can reach the endpoint on, in the order they are tried. */
    fun grokUris(): List<XUri> = listOf(
        XUri.https("api.x.com", "/2/grok/translation.json"),
        XUri.https("x.com", "/i/api/2/grok/translation.json"),
    )

    fun grokBody(tweetId: String, targetLang: String): String = buildJsonObject {
        put("content_type", "POST")
        put("id", tweetId)
        put("dst_lang", targetLang)
    }.toString()

    /** The older endpoint. The whole request sits in the path, commas and brackets included. */
    fun legacyUri(tweetId: String, targetLang: String): XUri = XUri.https(
        "x.com",
        "/i/api/1.1/strato/column/None/tweetId=$tweetId,destinationLanguage=Some($targetLang)," +
            "translationSource=Some(Google),feature=None,timeout=None,onlyCached=None" +
            "/translation/service/translateTweet",
    )

    /**
     * Reads a Grok answer. Lines that are not JSON, or are of a kind this
     * app does not show (poll choices, entity lists), are skipped; an answer
     * with no text at all is a failure, so the caller can try the other way.
     */
    fun parseGrok(body: String): TranslationResult {
        val text = StringBuilder()
        var error: String? = null
        for (raw in body.lineSequence()) {
            var line = raw.trim()
            if (line.startsWith("data:")) line = line.removePrefix("data:").trim()
            if (line.isEmpty() || line == "[DONE]") continue
            val element = try {
                json.parseToJsonElement(line) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: continue
            val failure = element["error"] as? JsonObject ?: (element["errors"].firstObject())
            if (failure != null) {
                error = failure["message"].text() ?: "error ${failure["code"].text() ?: "?"}"
                continue
            }
            val result = element["result"] as? JsonObject ?: continue
            val kind = result["content_type"].text()
            if (kind != null && kind != "POST") continue
            result["text"].text()?.let { text.append(it) }
        }
        val translated = text.toString().trim()
        return when {
            translated.isNotEmpty() -> TranslationResult.Success(translated)
            error != null -> TranslationResult.Failure(error)
            else -> TranslationResult.Failure("empty answer")
        }
    }

    /** Reads the older endpoint's answer: `{translation, sourceLanguage, translationState, …}`. */
    fun parseLegacy(body: String): TranslationResult {
        val element = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return TranslationResult.Failure("unreadable answer")
        element["errors"].firstObject()?.let {
            return TranslationResult.Failure(it["message"].text() ?: "error ${it["code"].text() ?: "?"}")
        }
        val translated = element["translation"].text()?.trim().orEmpty()
        if (translated.isEmpty()) {
            return TranslationResult.Failure(element["translationState"].text() ?: "empty answer")
        }
        return TranslationResult.Success(translated, element["sourceLanguage"].text()?.takeIf { it.isNotBlank() })
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonElement?.firstObject(): JsonObject? =
        (this as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
}

/** When "Translate" is offered and how languages are named. */
object TranslateLogic {
    /** Codes X uses for "no language": undetermined, no text, only links, hashtags, mentions, emoji or media. */
    private val noLanguage = setOf("und", "zxx", "qme", "qht", "qam", "qct", "qst", "art", "")

    /** Languages offered in Settings, most likely first. Any other device language is added in front. */
    val commonTargets: List<String> = listOf(
        "vi", "en", "ja", "ko", "zh", "es", "fr", "de", "pt", "ru", "th", "id", "it", "tr", "ar", "hi",
    )

    /** `vi-VN`, `vi_VN`, `VI` -> `vi`. X writes Indonesian as `in` and Hebrew as `iw`. */
    fun normalize(lang: String?): String {
        val base = lang.orEmpty().trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        return when (base) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> base
        }
    }

    /** The language to translate into: the one chosen in Settings, else the device's, else English. */
    fun targetLanguage(chosen: String?, deviceLang: String?): String =
        normalize(chosen).ifEmpty { normalize(deviceLang) }.ifEmpty { "en" }

    /**
     * Whether a post gets a "Translate" button. A post whose language is not
     * known (one read back from the local cache) gets it whenever it has
     * words in it, since nothing says it is already in the reader's language.
     */
    fun shouldOffer(text: String, lang: String?, targetLang: String): Boolean {
        if (!hasWords(text)) return false
        if (lang == null) return true
        val source = normalize(lang)
        if (source in noLanguage) return false
        return source != normalize(targetLang)
    }

    /** True when something is left after links, mentions and hashtags are taken out. */
    fun hasWords(text: String): Boolean = text
        .replace(Regex("""https?://\S+"""), " ")
        .replace(Regex("""[@#]\w+"""), " ")
        .any { it.isLetter() }

    /** `ja` -> `Japanese`; an unknown code is shown as it is, an empty one as null. */
    fun languageName(lang: String?, inLocale: Locale = Locale.ENGLISH): String? {
        val code = normalize(lang)
        if (code.isEmpty() || code in noLanguage) return null
        val name = Locale.forLanguageTag(code).getDisplayLanguage(inLocale)
        return if (name.isEmpty() || name.equals(code, ignoreCase = true)) code else name.replaceFirstChar { it.titlecase(inLocale) }
    }

    /** The line under a translated post. */
    fun translatedFromLabel(sourceLang: String?): String =
        languageName(sourceLang)?.let { "Translated from $it" } ?: "Translated"

    /** The choices of the Settings row: "" stands for the device language. */
    fun targetChoices(deviceLang: String?): List<String> {
        val device = normalize(deviceLang)
        val out = mutableListOf("")
        if (device.isNotEmpty() && device !in commonTargets) out += device
        out += commonTargets
        return out
    }

    fun targetChoiceLabel(choice: String, deviceLang: String?): String =
        if (choice.isEmpty()) "Device (${languageName(targetLanguage("", deviceLang)) ?: "English"})"
        else languageName(choice) ?: choice
}
