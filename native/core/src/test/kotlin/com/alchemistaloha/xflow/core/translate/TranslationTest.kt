package com.alchemistaloha.xflow.core.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTest {
    // ---- request ----

    @Test
    fun `grok request is the body the web client sends`() {
        assertEquals(
            """{"content_type":"POST","id":"123","dst_lang":"vi"}""",
            Translation.grokBody("123", "vi"),
        )
        assertEquals(
            listOf("https://api.x.com/2/grok/translation.json", "https://x.com/i/api/2/grok/translation.json"),
            Translation.grokUris().map { it.toString() },
        )
    }

    @Test
    fun `legacy request keeps the whole query in the path`() {
        assertEquals(
            "https://x.com/i/api/1.1/strato/column/None/tweetId=9,destinationLanguage=Some(vi)," +
                "translationSource=Some(Google),feature=None,timeout=None,onlyCached=None/translation/service/translateTweet",
            Translation.legacyUri("9", "vi").toString(),
        )
    }

    // ---- grok answer ----

    @Test
    fun `text pieces of the lines are put together`() {
        val body = """
            {"result":{"content_type":"POST","text":"Xin "}}
            {"result":{"content_type":"POST","text":"chào"}}
            {"result":{"content_type":"POST","entities":{"hashtags":[{"indices":[0,3],"text":"a"}]}}}
        """.trimIndent()
        assertEquals(TranslationResult.Success("Xin chào"), Translation.parseGrok(body))
    }

    @Test
    fun `a single object without line breaks is read too`() {
        assertEquals(
            TranslationResult.Success("Hello\nworld"),
            Translation.parseGrok("""{"result":{"content_type":"POST","text":"Hello\nworld","entities":{}}}"""),
        )
    }

    @Test
    fun `poll choices and broken lines are skipped`() {
        val body = "garbage\n" +
            """{"result":{"content_type":"POLL","index":0,"text":"Có"}}""" + "\n" +
            """data: {"result":{"content_type":"POST","text":"Câu hỏi"}}""" + "\n[DONE]\n"
        assertEquals(TranslationResult.Success("Câu hỏi"), Translation.parseGrok(body))
    }

    @Test
    fun `a server error is reported with its message`() {
        assertEquals(
            TranslationResult.Failure("Rate limited"),
            Translation.parseGrok("""{"error":{"code":429,"message":"Rate limited"}}"""),
        )
        assertEquals(
            TranslationResult.Failure("Bad Authentication data"),
            Translation.parseGrok("""{"errors":[{"message":"Bad Authentication data","code":215}]}"""),
        )
    }

    @Test
    fun `text that arrived before an error is kept`() {
        val body = """{"result":{"content_type":"POST","text":"Half"}}""" + "\n" + """{"error":{"code":1,"message":"cut"}}"""
        assertEquals(TranslationResult.Success("Half"), Translation.parseGrok(body))
    }

    @Test
    fun `nothing readable is a failure`() {
        assertEquals(TranslationResult.Failure("empty answer"), Translation.parseGrok(""))
        assertEquals(TranslationResult.Failure("empty answer"), Translation.parseGrok("<html>403</html>"))
        assertEquals(
            TranslationResult.Failure("empty answer"),
            Translation.parseGrok("""{"result":{"content_type":"POST","text":""}}"""),
        )
    }

    // ---- legacy answer ----

    @Test
    fun `legacy answer gives the text and the source language`() {
        val body = """{"id_str":"9","translation":" Xin chào ","sourceLanguage":"ja","localizedSourceLanguage":"Japanese","destinationLanguage":"vi","translationSource":"Google","translationState":"Success"}"""
        assertEquals(TranslationResult.Success("Xin chào", "ja"), Translation.parseLegacy(body))
    }

    @Test
    fun `legacy answer without a translation is a failure`() {
        assertEquals(TranslationResult.Failure("NotTranslatable"), Translation.parseLegacy("""{"translationState":"NotTranslatable"}"""))
        assertEquals(TranslationResult.Failure("unreadable answer"), Translation.parseLegacy("nope"))
        assertEquals(
            TranslationResult.Failure("Sorry, that page does not exist"),
            Translation.parseLegacy("""{"errors":[{"code":34,"message":"Sorry, that page does not exist"}]}"""),
        )
    }

    // ---- when to offer it ----

    @Test
    fun `offered when the post is in another language`() {
        assertTrue(TranslateLogic.shouldOffer("こんにちは", "ja", "vi"))
        assertFalse(TranslateLogic.shouldOffer("Xin chào", "vi", "vi-VN"))
        assertFalse(TranslateLogic.shouldOffer("hello", "en", "EN"))
        assertTrue(TranslateLogic.shouldOffer("halo", "in", "en"))
        assertFalse(TranslateLogic.shouldOffer("halo", "in", "id"))
    }

    @Test
    fun `not offered when X found no language or there are no words`() {
        for (code in listOf("und", "zxx", "qme", "qht", "qam", "art")) {
            assertFalse(code, TranslateLogic.shouldOffer("word", code, "vi"))
        }
        assertFalse(TranslateLogic.shouldOffer("", "ja", "vi"))
        assertFalse(TranslateLogic.shouldOffer("https://t.co/abc #tag @user 123 🎉", "ja", "vi"))
        assertFalse(TranslateLogic.shouldOffer("   ", null, "vi"))
    }

    @Test
    fun `a post of unknown language is offered when it has words`() {
        assertTrue(TranslateLogic.shouldOffer("some words https://t.co/x", null, "vi"))
    }

    @Test
    fun `target language falls back from the setting to the device to English`() {
        assertEquals("ja", TranslateLogic.targetLanguage("ja", "vi-VN"))
        assertEquals("vi", TranslateLogic.targetLanguage("", "vi-VN"))
        assertEquals("vi", TranslateLogic.targetLanguage(null, "vi_VN"))
        assertEquals("en", TranslateLogic.targetLanguage("", ""))
        assertEquals("id", TranslateLogic.targetLanguage("", "in"))
    }

    @Test
    fun `languages are named for the label`() {
        assertEquals("Japanese", TranslateLogic.languageName("ja"))
        assertEquals("Indonesian", TranslateLogic.languageName("in"))
        assertNull(TranslateLogic.languageName("und"))
        assertNull(TranslateLogic.languageName(null))
        assertEquals("Translated from Japanese", TranslateLogic.translatedFromLabel("ja"))
        assertEquals("Translated", TranslateLogic.translatedFromLabel(null))
    }

    @Test
    fun `settings choices start with the device and do not repeat it`() {
        assertEquals("", TranslateLogic.targetChoices("vi").first())
        assertEquals(1, TranslateLogic.targetChoices("vi").count { it == "vi" })
        assertEquals("nl", TranslateLogic.targetChoices("nl-NL")[1])
        assertEquals("Device (Vietnamese)", TranslateLogic.targetChoiceLabel("", "vi-VN"))
        assertEquals("Korean", TranslateLogic.targetChoiceLabel("ko", "vi-VN"))
    }
}
