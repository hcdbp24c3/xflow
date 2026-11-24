package com.alchemistaloha.xflow.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of test/core/client/transaction_id_service_test.dart. */
class TransactionIdServiceTest {
    @Test
    fun normalizeJavaScriptResultUnwrapsQuotedValuesAndNulls() {
        assertNull(TwitterParser.normalizeJavaScriptResult(null))
        assertNull(TwitterParser.normalizeJavaScriptResult("null"))
        assertEquals("txid-123", TwitterParser.normalizeJavaScriptResult("\"txid-123\""))
        assertEquals("txid-456", TwitterParser.normalizeJavaScriptResult("txid-456"))
    }

    @Test
    fun normalizeJavaScriptResultTreatsEmptyAsNull() {
        assertNull(TwitterParser.normalizeJavaScriptResult(""))
    }

    @Test
    fun embeddedGeneratorKeepsCriticalRemoteAdditional2JsMarkers() {
        val script = TransactionIdScript.embeddedGeneratorScript

        assertTrue(script.contains("document.querySelectorAll('[name^=tw]')[0]"))
        assertTrue(script.contains("new RTCPeerConnection()"))
        assertTrue(script.contains("r.createOffer()"))
        assertTrue(script.contains("u.currentTime = Math.round(r / 10) * 10;"))
        assertTrue(script.contains("new Uint32Array([o]).buffer"))
        assertTrue(script.contains("obfiowerehiring"))
        assertTrue(
            script.contains("new Uint8Array(await wc([t, n, o].join('!') + 'obfiowerehiring' + i))"),
        )
    }

    @Test
    fun embeddedGeneratorKeepsItsRegexEscapes() {
        val script = TransactionIdScript.embeddedGeneratorScript
        assertTrue(script.startsWith("(() => {\n  if (window.__xflowGenerateTransactionId) return;"))
        assertTrue(script.contains(".replace(/[^\\d]+/g, ' ')"))
        assertTrue(script.contains("matchAll(/([\\d.-]+)/g)"))
        assertTrue(script.trimEnd().endsWith("window.__xflowGenerateTransactionId = W();\n})();"))
    }
}
