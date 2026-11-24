package com.alchemistaloha.xflow.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Lines shaped like X's scripts of 2026-11-24; the names are made up. */
class TransactionIdAssetsTest {
    private val base = "https://abs.twimg.com/x-web/x-web"

    @Test
    fun findsTheEntryScriptInTheDocument() {
        val html = """<link rel="modulepreload" href="$base/entry-client-AbCd.js"><script type="module" src="$base/entry-client-AbCd.js"></script>"""
        assertEquals(listOf("$base/entry-client-AbCd.js"), TransactionIdAssets.documentScripts(html))
    }

    @Test
    fun followsRelativeImports() {
        val entry = """import{a as b}from"./assets/Main0001.js";import"./assets/Other002.js";x(()=>import(`./assets/Main0001.js`))"""
        assertEquals(
            listOf("$base/assets/Main0001.js", "$base/assets/Other002.js"),
            TransactionIdAssets.referencedScripts(entry, "$base/entry-client-AbCd.js"),
        )
        assertEquals(
            listOf("$base/Up.js"),
            TransactionIdAssets.referencedScripts("""import"../Up.js"""", "$base/assets/Main0001.js"),
        )
    }

    @Test
    fun findsTheGeneratorNextToTheHeaderName() {
        val main = """x(()=>import(`./Far00000.js`).then(e=>e.default()));""" + " ".repeat(500) +
            """function Fs(){return Bs??=w(()=>import(`./Gen12345.js`).then(e=>e.default()),m([0])),Bs}""" +
            """async function f(e){e.headers.set(`x-client-transaction-id`,await (await Fs())(p,m))}"""
        assertEquals("$base/assets/Gen12345.js", TransactionIdAssets.generatorUrl(main, "$base/assets/Main0001.js"))
        assertNull(TransactionIdAssets.generatorUrl("import(`./Gen12345.js`).then(e=>e.default())", "$base/assets/a.js"))
        assertNull(TransactionIdAssets.generatorUrl("`x-client-transaction-id`", "$base/assets/a.js"))
    }

    @Test
    fun readsTheIndicesOutOfTheGenerator() {
        val generator = """k=M[w(s.a)](e[11],16),t=M[w(s.d)](M[C(s.k)](e[25],16),M[w(s.i)](e[3],16)),M[q](e[45],16))],z=a[5]%2"""
        assertEquals(TransactionIdIndices(11, listOf(25, 3, 45)), TransactionIdAssets.indices(generator))
        assertNull(TransactionIdAssets.indices("nothing here"))
        assertNull(TransactionIdAssets.indices("a[1],16"))
    }
}
