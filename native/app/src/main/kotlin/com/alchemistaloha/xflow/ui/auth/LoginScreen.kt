package com.alchemistaloha.xflow.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.alchemistaloha.xflow.core.client.TwitterFeatures
import com.alchemistaloha.xflow.core.client.XUri
import com.alchemistaloha.xflow.core.client.X_BEARER_TOKEN
import com.alchemistaloha.xflow.core.client.X_MOBILE_USER_AGENT
import com.alchemistaloha.xflow.core.model.Account
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.client.AccountStore
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.player.MediaCache
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request

private const val HOME_URL = "https://x.com/home"
private const val LOGIN_URL = "https://x.com/i/flow/login"
private const val SCREEN_NAME_JS =
    "document.documentElement.outerHTML.match(/\"screen_name\":\"([^\"]+)\"/)?.[1] ?? '';"
private val AUTH_COOKIES = setOf("guest_id", "gt", "att", "auth_token", "ct0")

/** Parses a `Cookie` header value ("a=b; c=d") in order. */
internal fun parseCookieHeader(header: String?): List<Pair<String, String>> =
    header.orEmpty().split(";").mapNotNull { part ->
        val trimmed = part.trim()
        val eq = trimmed.indexOf('=')
        if (eq <= 0) null else trimmed.substring(0, eq) to trimmed.substring(eq + 1)
    }

/**
 * Sign in to X in a WebView. When the home page loads, the session cookies are turned into
 * the request headers the client uses and saved as the account. Pops itself, then calls
 * [onResult] with true on success (false when the user backs out).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onResult: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onResultNow by rememberUpdatedState(onResult)
    // 0..1 while a page is loading, 1 when idle.
    var progress by remember { mutableFloatStateOf(0f) }
    val userFound = remember { booleanArrayOf(false) }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = X_MOBILE_USER_AGENT
            CookieManager.getInstance().setAcceptCookie(true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress / 100f
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (progress >= 1f) progress = 0f
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    progress = 1f
                    if (url != HOME_URL || userFound[0]) return
                    view.evaluateJavascript(SCREEN_NAME_JS) { raw ->
                        val screenName = raw.orEmpty().replace("\"", "")
                        if (screenName.isEmpty() || screenName == "null" || userFound[0]) return@evaluateJavascript
                        userFound[0] = true
                        scope.launch {
                            val ok = finishLogin(screenName)
                            if (ok) {
                                Navigator.pop()
                                onResultNow(true)
                            } else {
                                userFound[0] = false
                            }
                        }
                    }
                }
            }
            loadUrl(LOGIN_URL)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            Navigator.pop()
            onResultNow(false)
        }
    }

    Column(Modifier.fillMaxSize().background(AppColors.background).navigationBarsPadding().imePadding()) {
        AppTopBar(
            title = "Login to X",
            onBack = {
                Navigator.pop()
                onResultNow(false)
            },
        )
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (progress < 1f) {
                val bar = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp)
                if (progress == 0f) {
                    LinearProgressIndicator(modifier = bar, trackColor = Color.Transparent)
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = bar, trackColor = Color.Transparent)
                }
            }
        }
    }
}

/** Builds and stores the account from the WebView's cookies. False when the session is not usable. */
private suspend fun finishLogin(screenName: String): Boolean {
    return try {
        val cookies = parseCookieHeader(CookieManager.getInstance().getCookie(HOME_URL))
        val ct0 = cookies.firstOrNull { it.first == "ct0" }?.second ?: throw IllegalStateException("ct0 not found")

        val authHeader = buildJsonObject {
            put("Cookie", cookies.filter { it.first in AUTH_COOKIES }.joinToString(";") { "${it.first}=${it.second}" })
            put("authorization", X_BEARER_TOKEN)
            put("x-csrf-token", ct0)
        }

        val restId = fetchRestId(screenName, authHeader)

        val account = Account(
            id = ct0,
            screenName = screenName,
            restId = restId,
            authHeader = authHeader.toString(),
        )
        Repository.insertAccount(account)
        AccountStore.login(account)
        true
    } catch (e: Exception) {
        // Never log the exception's data: it can hold session values.
        AppLogger.log("XFLOW: Login could not be completed (${e.javaClass.simpleName}).")
        false
    }
}

/** Looks up the account's rest_id by screen name; empty when the lookup fails (as in Dart). */
private suspend fun fetchRestId(screenName: String, authHeader: JsonObject): String = withContext(Dispatchers.IO) {
    try {
        val variables = buildJsonObject {
            put("screen_name", screenName)
            put("withHighlightedLabel", true)
            put("withSafetyModeUserFields", true)
            put("withSuperFollowsUserFields", true)
        }
        val uri = XUri.https(
            "x.com",
            "/i/api" + TwitterFeatures.graphqlUserByScreenNameUriPath,
            mapOf(
                "variables" to variables.toString(),
                "features" to TwitterFeatures.defaultFeatures.toString(),
            ),
        )
        val request = Request.Builder().url(uri.toString()).apply {
            for ((name, value) in authHeader) header(name, value.jsonPrimitive.content)
            header("User-Agent", X_MOBILE_USER_AGENT)
            header("Content-Type", "application/json")
        }.build()
        MediaCache.httpClient.newCall(request).execute().use { response ->
            if (response.code != 200) return@use ""
            val body = response.body?.string().orEmpty()
            val result = Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?.get("user")?.jsonObject?.get("result")?.jsonObject
            result?.get("rest_id")?.jsonPrimitive?.content ?: ""
        }
    } catch (e: Exception) {
        ""
    }
}

