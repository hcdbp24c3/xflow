package com.alchemistaloha.xflow.data.client

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alchemistaloha.xflow.core.client.TransactionIdScript
import com.alchemistaloha.xflow.core.client.TwitterParser
import com.alchemistaloha.xflow.core.client.X_MOBILE_USER_AGENT
import com.alchemistaloha.xflow.core.util.AppLogger
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gets the `x-client-transaction-id` header from X's own generator, inside
 * a hidden WebView that has x.com loaded ([TransactionIdWebViewHost]): the
 * page's generator script is found and called
 * ([TransactionIdScript.moduleGeneratorScript]). When that gives nothing the
 * caller goes on to [LocalTransactionId].
 */
object TransactionIdService {
    /** How long one id may take before the request goes on without a local id. */
    private const val RESULT_TIMEOUT_MS = 2000L

    /** The first id of a page also has to find the generator among the page's scripts. */
    private const val FIRST_RESULT_TIMEOUT_MS = 8000L

    /** After a failure the page is left alone this long; requests use the other sources meanwhile. */
    private const val RETRY_AFTER_FAILURE_MS = 60_000L
    private const val RESULT_POLL_MS = 20L

    // Touched on the main thread only.
    private var webView: WebView? = null
    private var scriptInstalled = false
    private var requestCounter = 0L

    @Volatile
    private var isReady = false

    /** When the page started loading; 0 when no load is pending. Main thread only. */
    private var loadStartedAt = 0L
    private var localOkCount = 0
    private var localFailCount = 0
    private var pausedUntil = 0L
    private var lastProblem: String? = null

    /** A request made while the page is still loading waits this long for it, counted from the load start. */
    private const val PAGE_WAIT_MS = 8000L

    /** Call on the main thread when the hidden page starts (re)loading. */
    fun markLoading() {
        loadStartedAt = android.os.SystemClock.elapsedRealtime()
        AppLogger.log("TXID WebView loading x.com")
    }

    /** One line for the log viewer: whether ids are being made locally. */
    fun statusLine(): String =
        "TXID status: webView=${if (webView != null) "attached" else "none"} ready=$isReady moduleOk=$localOkCount moduleFail=$localFailCount" +
            (lastProblem?.let { " lastProblem=$it" } ?: "") + " | " + LocalTransactionId.statusLine()

    /** Dart callers wrote `TransactionIdService.instance`. */
    val instance: TransactionIdService get() = this

    /** Dart: `attachController`. Call on the main thread; null detaches. */
    fun attach(webView: WebView?) {
        this.webView = webView
        scriptInstalled = false
        pausedUntil = 0L
    }

    fun markReady(ready: Boolean) {
        isReady = ready
        if (ready) loadStartedAt = 0L
        // A new page: the generator has to be found again.
        scriptInstalled = false
        pausedUntil = 0L
    }

    /** The id for a request to [path], or null when the page is not ready or the script fails. */
    suspend fun generateForRequest(path: String, method: String = "GET"): String? =
        withContext(Dispatchers.Main.immediate) {
            // The first requests of a session start before x.com has loaded in the
            // hidden page. Give the page a moment instead of sending them without an id.
            if (webView != null && !isReady && loadStartedAt != 0L) {
                val startedAt = loadStartedAt
                var waited = 0L
                while (!isReady && loadStartedAt == startedAt &&
                    android.os.SystemClock.elapsedRealtime() - startedAt < PAGE_WAIT_MS
                ) {
                    delay(100)
                    waited += 100
                }
                AppLogger.log("TXID waited ${waited}ms for the x.com page: ready=$isReady")
            }

            val view = webView
            if (view == null || !isReady) {
                AppLogger.log(
                    "TXID local unavailable: " + if (view == null) "no WebView attached" else "x.com page not ready",
                )
                return@withContext null
            }

            if (android.os.SystemClock.elapsedRealtime() < pausedUntil) return@withContext null

            try {
                ensureScriptInstalled(view)
                val id = normalizeJavaScriptResult(runGenerator(view, path, method))
                if (id == null || !ID_SHAPE.matches(id)) {
                    failed(view, if (id == null) "the generator returned nothing" else "unexpected shape (length=${id.length})")
                    null
                } else {
                    localOkCount++
                    if (localOkCount == 1) AppLogger.log("TXID webview-module works (length=${id.length})")
                    id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(view, "${e::class.simpleName}: ${e.message}")
                null
            }
        }

    private val ID_SHAPE = Regex("^[A-Za-z0-9+/=_-]{40,200}$")

    /** Notes the failure and leaves the page alone for a while; the search for the generator starts over after that. */
    private fun failed(view: WebView, problem: String) {
        localFailCount++
        pausedUntil = android.os.SystemClock.elapsedRealtime() + RETRY_AFTER_FAILURE_MS
        scriptInstalled = false
        if (problem != lastProblem || localFailCount == 1) {
            AppLogger.log("TXID webview-module failed: $problem; using the other sources for ${RETRY_AFTER_FAILURE_MS / 1000}s")
        }
        lastProblem = problem
        view.evaluateJavascript("window.__xflowTxidModule = null;", null)
    }

    private suspend fun ensureScriptInstalled(view: WebView) {
        if (scriptInstalled) return
        evaluate(view, TransactionIdScript.moduleGeneratorScript)
        scriptInstalled = true
    }

    /**
     * Calls the generator and waits for its promise. `evaluateJavascript`
     * returns as soon as the script has run and gives `{}` for a promise, so
     * the script parks the settled value on the page and it is read back.
     */
    private suspend fun runGenerator(view: WebView, path: String, method: String): String? {
        val token = JsonPrimitive("r${requestCounter++}").toString()
        evaluate(
            view,
            """
            (function() {
              var store = window.__xflowTxidResults = window.__xflowTxidResults || {};
              (async function() {
                var found = await window.__xflowTxidModule;
                return await found.fn(${JsonPrimitive(path)}, ${JsonPrimitive(method)});
              })().then(
                function(v) { store[$token] = { value: v == null ? null : String(v) }; },
                function(e) { store[$token] = { error: String(e) }; }
              );
              return true;
            })();
            """.trimIndent(),
        )

        val readScript = """
            (function() {
              var store = window.__xflowTxidResults;
              if (!store || !($token in store)) return null;
              var result = store[$token];
              delete store[$token];
              return JSON.stringify(result);
            })();
        """.trimIndent()

        val timeout = if (localOkCount == 0) FIRST_RESULT_TIMEOUT_MS else RESULT_TIMEOUT_MS
        var waited = 0L
        while (true) {
            val raw = evaluate(view, readScript)
            if (raw != null && raw != "null") {
                // evaluateJavascript gives the JSON form of the returned string.
                val text = (Json.parseToJsonElement(raw) as JsonPrimitive).content
                val result = Json.parseToJsonElement(text) as JsonObject
                val error = result["error"]
                if (error != null && error !is JsonNull) {
                    throw IllegalStateException((error as JsonPrimitive).content)
                }
                val value = result["value"]
                return if (value == null || value is JsonNull) null else (value as JsonPrimitive).content
            }
            if (waited >= timeout) {
                throw IllegalStateException("no result after ${timeout}ms")
            }
            delay(RESULT_POLL_MS)
            waited += RESULT_POLL_MS
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }

    fun normalizeJavaScriptResult(value: Any?): String? = TwitterParser.normalizeJavaScriptResult(value)

    val embeddedGeneratorScript: String
        get() = TransactionIdScript.embeddedGeneratorScript
}

/** Flutter: `LoginScreen.homeUrl`. */
private const val TRANSACTION_ID_HOME_URL = "https://x.com/home"

@SuppressLint("SetJavaScriptEnabled")
private fun createTransactionIdWebView(context: Context): WebView? =
    try {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            // webview_flutter turns DOM storage on for every Android WebView; x.com needs it.
            settings.domStorageEnabled = true
            settings.userAgentString = X_MOBILE_USER_AGENT
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val ready = url != null && url.startsWith("https://x.com/")
                    TransactionIdService.markReady(ready)
                    if (ready) {
                        AppLogger.log("TXID WebView ready at ${url?.substringBefore('?')}")
                    } else {
                        AppLogger.log("TXID WebView finished on an unexpected page: ${url?.substringBefore('?')}")
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        AppLogger.log("TXID WebView page error: ${error?.errorCode} ${error?.description}")
                    }
                }
            }
        }
    } catch (e: Exception) {
        // No WebView on the device (or it is being updated): requests go on without a local id.
        AppLogger.log("TXID WebView unavailable: ${e::class.simpleName}: $e")
        null
    }

/**
 * A hidden 1x1 WebView that keeps x.com open while an account is logged in,
 * so [TransactionIdService] has a page to run in. Put it once at the root of
 * the app. It loads x.com/home when an account appears or changes, and shows
 * nothing while logged out.
 */
@Composable
fun TransactionIdWebViewHost() {
    val context = LocalContext.current
    val webView = remember { createTransactionIdWebView(context) }
    val account by AccountStore.state.collectAsState()

    DisposableEffect(webView) {
        TransactionIdService.attach(webView)
        onDispose {
            TransactionIdService.markReady(false)
            TransactionIdService.attach(null)
            webView?.destroy()
        }
    }

    val current = account
    if (current == null || webView == null) {
        SideEffect { TransactionIdService.markReady(false) }
        return
    }

    // Runs after the frame, once per account id, and again after a logout and login.
    LaunchedEffect(current.id) {
        TransactionIdService.markLoading()
        webView.loadUrl(TRANSACTION_ID_HOME_URL)
    }

    AndroidView(
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0.01f),
    )
}
