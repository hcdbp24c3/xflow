package com.alchemistaloha.xflow.ui.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.alchemistaloha.xflow.core.link.LinkRouter
import com.alchemistaloha.xflow.core.link.LinkTarget
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.ui.feed.Fullscreen
import com.alchemistaloha.xflow.ui.feed.findActivity
import com.alchemistaloha.xflow.ui.nav.NavigationStore
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppIconButton

/**
 * Opens a link the user tapped. Profiles and hashtags of X open their screen in the app, web
 * pages open in the in-app browser, anything that is not a web page goes to the system.
 */
fun openLink(context: Context, url: String) {
    when (val target = LinkRouter.classify(url)) {
        is LinkTarget.Profile -> NavigationStore.selectUser(target.screenName)
        is LinkTarget.Hashtag -> NavigationStore.selectHashtag(target.hashtag)
        is LinkTarget.Browser -> {
            AppLogger.log("XFLOW: Opening link in the in-app browser: host=${LinkRouter.displayHost(target.url)}")
            // A fullscreen video keeps the window in its own orientation; a page reads upright.
            Fullscreen.exit(context.findActivity())
            Navigator.push { InAppBrowserScreen(target.url) }
        }
        is LinkTarget.System -> openWithSystem(context, target.url)
    }
}

/** Hands [url] to whatever app the system picks (the external browser for a web page). */
internal fun openWithSystem(context: Context, url: String): Boolean {
    if (url.isBlank()) return false
    return try {
        val intent = if (url.startsWith("intent:", ignoreCase = true)) {
            // An intent: link may only reach an activity that accepts links from a browser.
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        AppLogger.log("XFLOW: No app could open the link: ${e.javaClass.simpleName}")
        false
    }
}

/** The web page an intent: link names for when no app can open it, if it is http(s). */
private fun intentFallbackUrl(url: String): String? {
    if (!url.startsWith("intent:", ignoreCase = true)) return null
    return try {
        Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            .getStringExtra("browser_fallback_url")
            ?.takeIf { LinkRouter.isWebUrl(it) }
    } catch (_: Exception) {
        null
    }
}

/**
 * A web page inside the app: close button, title and host, load progress, and a menu to
 * reload, copy the link or open it in the external browser. Back goes back in the page
 * history first and closes the screen when there is none.
 *
 * It is a pushed screen, so the feed underneath stops playing while it is open and carries on
 * when it closes. The WebView shares the app's cookies (an x.com page is signed in). Nothing
 * is injected into the page, and the page gets no access to files or content providers.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowserScreen(initialUrl: String) {
    val context = LocalContext.current
    // 0..1 while a page is loading, 1 when idle.
    var progress by remember { mutableFloatStateOf(0f) }
    var title by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var menuOpen by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = true
                // The page stays on the web: no files, no content providers, no http inside https.
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress / 100f
                }

                override fun onReceivedTitle(view: WebView?, newTitle: String?) {
                    title = newTitle.orEmpty()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url?.toString() ?: return false
                    if (LinkRouter.isWebUrl(target)) return false
                    // Another app opens only for the page itself (not a frame inside it), and
                    // only after a tap or as the redirect of a link (t.co to an app link).
                    // A script on the page cannot start one by itself.
                    if (!request.isForMainFrame) return true
                    if (!request.hasGesture() && !request.isRedirect) return true
                    if (!openWithSystem(context, target)) {
                        val fallback = intentFallbackUrl(target)
                        if (fallback != null) {
                            view.loadUrl(fallback)
                        } else {
                            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (progress >= 1f) progress = 0f
                    if (url != null) currentUrl = url
                    title = ""
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    // Pages that change their address without loading (single-page apps).
                    if (url != null) currentUrl = url
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    progress = 1f
                    if (url != null) currentUrl = url
                    view.title?.let { if (it.isNotBlank()) title = it }
                }
            }
            // A download is not a page: let the system handle the file.
            setDownloadListener { url, _, _, _, _ ->
                if (url != null && LinkRouter.isWebUrl(url)) openWithSystem(context, url)
            }
            loadUrl(initialUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // onPause stops this page's media and scripts. pauseTimers is not called: it would
            // freeze every WebView of the app, including the hidden one that signs requests.
            webView.stopLoading()
            webView.onPause()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    BackHandler {
        if (menuOpen) {
            menuOpen = false
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            Navigator.pop()
        }
    }

    val host = LinkRouter.displayHost(currentUrl)
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val outline = AppColors.outline

    Column(Modifier.fillMaxSize().background(AppColors.background).navigationBarsPadding().imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(AppColors.background)
                .drawBehind {
                    drawLine(outline, Offset(0f, size.height), Offset(size.width, size.height), 0.5.dp.toPx())
                }
                .padding(top = topInset)
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconButton(icon = Icons.Rounded.Close, tooltip = "Close", onTap = { Navigator.pop() })
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    title.ifBlank { host.ifBlank { "Loading…" } },
                    color = AppColors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (host.isNotBlank()) {
                    Text(
                        host,
                        color = AppColors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                AppIconButton(icon = Icons.Rounded.MoreVert, tooltip = "More", onTap = { menuOpen = true })
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = AppColors.surfaceHigh,
                ) {
                    DropdownMenuItem(
                        text = { Text("Reload") },
                        onClick = {
                            menuOpen = false
                            webView.reload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy link") },
                        onClick = {
                            menuOpen = false
                            copyLink(context, currentUrl)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Open in browser") },
                        onClick = {
                            menuOpen = false
                            if (!openWithSystem(context, currentUrl)) {
                                Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (progress < 1f) {
                val bar = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp)
                if (progress == 0f) {
                    LinearProgressIndicator(modifier = bar, color = AppColors.accent, trackColor = Color.Transparent)
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = bar,
                        color = AppColors.accent,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
    }
}

private fun copyLink(context: Context, url: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Link", url))
        // Android 13 and later show their own "copied" notice.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        AppLogger.log("XFLOW: Could not copy the link: ${e.javaClass.simpleName}")
    }
}
