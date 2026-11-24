package com.alchemistaloha.xflow.core.client

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The vectors in fixtures/txid/vectors.json are made-up pages (random key,
 * random frames in the layout of the x.com document) with the ids a
 * reference implementation gives for them. The first case was also run
 * through X's own generator script of 2026-11-24, with the CSS animation
 * replaced by the same interpolation, and gave the same id.
 */
class TransactionIdGeneratorTest {
    private val vectors: JsonObject by lazy {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/txid/vectors.json"))
        Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
    }
    private val pages: JsonArray get() = vectors["pages"]!!.jsonArray
    private val indices: TransactionIdIndices
        get() = vectors["indices"]!!.jsonArray.map { it.jsonPrimitive.int }.let { TransactionIdIndices(it[0], it.drop(1)) }

    @Test
    fun theVectorsUseTheIndicesCompiledIntoTheApp() {
        assertEquals(TransactionIdIndices.DEFAULT, indices)
    }

    @Test
    fun readsTheKeyAndTheFourFramesFromThePage() {
        val page = assertNotNull(TransactionIdGenerator.parsePage(pages[0].jsonPrimitive.content))
        assertEquals(48, page.key.size)
        assertEquals(4, page.frames.size)
        for (frame in page.frames) {
            assertEquals(16, frame.size)
            for (row in frame) assertEquals(11, row.size)
        }
        assertNull(TransactionIdGenerator.problem(page, indices))
    }

    @Test
    fun givesTheReferenceAnimationKeyAndId() {
        val cases = vectors["cases"]!!.jsonArray
        assertTrue(cases.size >= 12)
        for (case in cases) {
            val c = case.jsonObject
            val page = assertNotNull(TransactionIdGenerator.parsePage(pages[c["page"]!!.jsonPrimitive.int].jsonPrimitive.content))
            val animationKey = TransactionIdGenerator.animationKey(page, indices)
            assertEquals(c["animationKey"]!!.jsonPrimitive.content, animationKey)
            val id = TransactionIdGenerator.generate(
                method = c["method"]!!.jsonPrimitive.content,
                path = c["path"]!!.jsonPrimitive.content,
                key = page.key,
                animationKey = animationKey,
                nowSeconds = c["now"]!!.jsonPrimitive.long,
                randomByte = c["random"]!!.jsonPrimitive.int,
            )
            assertEquals(c["id"]!!.jsonPrimitive.content, id)
        }
    }

    @Test
    fun theIdHasTheLayoutXReads() {
        val page = assertNotNull(TransactionIdGenerator.parsePage(pages[1].jsonPrimitive.content))
        val id = TransactionIdGenerator.generate("GET", "/i/api/graphql/a/B", page.key, "abc", nowSeconds = 1795500000, randomByte = 0x5a)
        assertEquals(94, id.length)
        assertTrue(Regex("^[A-Za-z0-9+/]+$").matches(id))
        val bytes = Base64.getDecoder().decode(id + "==")
        assertEquals(1 + 48 + 4 + 16 + 1, bytes.size)
        val random = bytes[0].toInt() and 0xff
        assertEquals(0x5a, random)
        // The key follows the random byte, XOR-ed with it; the last byte is 3.
        for (i in 0 until 48) assertEquals(page.key[i].toInt() and 0xff, (bytes[1 + i].toInt() and 0xff) xor random)
        assertEquals(3, (bytes.last().toInt() and 0xff) xor random)
        // The time is seconds since 2023-05-01T07:00:00Z, little endian.
        var time = 0L
        for (i in 0 until 4) time = time or (((bytes[49 + i].toInt() and 0xff) xor random).toLong() shl (8 * i))
        assertEquals(1795500000L - 1682924400L, time)
    }

    @Test
    fun aDifferentPathOrMethodGivesADifferentHash() {
        val page = assertNotNull(TransactionIdGenerator.parsePage(pages[2].jsonPrimitive.content))
        fun id(method: String, path: String) =
            TransactionIdGenerator.generate(method, path, page.key, "k", nowSeconds = 1795500000, randomByte = 1)
        assertTrue(id("GET", "/a") != id("GET", "/b"))
        assertTrue(id("GET", "/a") != id("POST", "/a"))
    }

    @Test
    fun aPageWithoutTheKeyOrTheFramesIsRejected() {
        val html = pages[0].jsonPrimitive.content
        assertNull(TransactionIdGenerator.parsePage("<html><body></body></html>"))
        assertNull(TransactionIdGenerator.parsePage(html.replace("twitter-site-verification", "something-else")))
        assertNull(TransactionIdGenerator.parsePage(html.replace("loading-x-anim-3", "other-3")))
    }

    @Test
    fun aKeyTooShortForTheIndicesIsReportedNotThrown() {
        val page = assertNotNull(TransactionIdGenerator.parsePage(pages[0].jsonPrimitive.content))
        val short = TransactionIdPage(page.key.copyOf(20), page.frames)
        assertNotNull(TransactionIdGenerator.problem(short, indices))
    }

    @Test
    fun hexOfFractionMatchesJavaScriptToString16() {
        // Number(x.toFixed(2)).toString(16) in a browser.
        assertEquals("0", TransactionIdGenerator.hexOfFraction(0.0))
        assertEquals("1", TransactionIdGenerator.hexOfFraction(1.0))
        assertEquals("0.8", TransactionIdGenerator.hexOfFraction(0.5))
        assertEquals("0.4", TransactionIdGenerator.hexOfFraction(0.25))
        assertEquals("0.deb851eb851eb8", TransactionIdGenerator.hexOfFraction(0.87))
        assertEquals("0.028f5c28f5c28f6", TransactionIdGenerator.hexOfFraction(0.01))
        assertEquals("0.fd70a3d70a3d7", TransactionIdGenerator.hexOfFraction(0.99))
    }

    @Test
    fun cubicBezierIsLinearForALinearCurveAndClampedOutside() {
        val linear = doubleArrayOf(0.25, 0.25, 0.75, 0.75)
        assertEquals(0.5, TransactionIdGenerator.cubicBezier(linear, 0.5), 1e-4)
        assertEquals(0.0, TransactionIdGenerator.cubicBezier(linear, 0.0), 1e-9)
        assertEquals(1.0, TransactionIdGenerator.cubicBezier(linear, 1.0), 1e-9)
    }

    @Test
    fun thePageScriptLooksForTheImportOfTheGenerator() {
        val script = TransactionIdScript.moduleGeneratorScript
        assertTrue(script.contains(TransactionIdScript.MODULE_IMPORT_JS_REGEX))
        assertTrue(script.contains("x-client-transaction-id"))
        assertTrue(script.contains("window.__xflowTxidModule"))
        // The same expression, as Kotlin sees it, finds the import in a line shaped like X's script.
        val body = TransactionIdScript.MODULE_IMPORT_JS_REGEX.removePrefix("/").removeSuffix("/g")
        val line = "function Fs(){return Bs??=w(()=>import(`./AbC_12-x.js`).then(e=>e.default()),__vite__mapDeps([0])),Bs}"
        assertEquals("AbC_12-x.js", Regex(body).find(line)?.groupValues?.get(1))
        assertNull(Regex(body).find("import(`./other.js`).then(e=>({default:e.Thing}))"))
    }
}
