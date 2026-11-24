package com.alchemistaloha.xflow.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.XFlowApp
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.client.AccountStore
import com.alchemistaloha.xflow.data.client.TransactionIdService
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.feed.FeedStore
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppIconButton
import com.alchemistaloha.xflow.ui.theme.AppTopBar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The clipboard travels through a binder call, which cannot carry much more than this. */
private const val MAX_CLIPBOARD_CHARS = 200_000

/**
 * The log as one text, oldest line first, under a header that says what the
 * app is running on and what state it is in. Holds no cookies or tokens: the
 * log never gets them, and the header has only counts and names.
 */
private suspend fun buildLogReport(context: Context): String {
    val settings = SettingsStore.state.value
    val (subscriptions, cached) = try {
        Repository.getSubscriptions().size.toString() to Repository.getCachedMediaCount().toString()
    } catch (e: Exception) {
        "?" to "?"
    }
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
    val header = buildString {
        appendLine("XFlow (Kotlin) $version  package=${context.packageName}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Logged in: ${AccountStore.state.value != null}  subscriptions=$subscriptions  cachedMedia=$cached")
        appendLine(
            "Feed source: ${settings.fetchStrategy.name}  filters=${settings.filters.joinToString(",") { it.name }}  " +
                "minFaves=${settings.minFavesFilter}  strictSubs=${settings.strictSubscriptionsOnly}  avoidWatched=${settings.avoidWatchedContent}",
        )
        appendLine("Last feed request: ${FeedStore.lastOutcome.value?.toLogLine() ?: "none yet"}")
        appendLine(TransactionIdService.statusLine())
        appendLine("Logcat: adb logcat -s ${XFlowApp.LOG_TAG}")
        appendLine("----")
    }
    return header + AppLogger.logs.joinToString("\n")
}

/**
 * The in-memory app log, newest line first, with copy, share and clear
 * (Dart `LogViewerScreen`). The same lines go to Logcat under the tag "XFlow".
 * Unlike the Dart screen the list follows the log while it is open.
 */
@Composable
fun LogViewerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val revision by AppLogger.revision.collectAsState()
    val logs = remember(revision) { AppLogger.logs }

    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                "App Logs (${logs.size})",
                onBack = { Navigator.pop() },
                actions = {
                    AppIconButton(
                        icon = Icons.Filled.Share,
                        onTap = {
                            AppGraph.scope.launch {
                                try {
                                    val report = buildLogReport(context)
                                    val file = withContext(Dispatchers.IO) {
                                        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
                                        File(dir, "xflow-log.txt").apply { writeText(report) }
                                    }
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "XFlow log")
                                        clipData = ClipData.newRawUri("XFlow log", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, "Share logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                } catch (e: Exception) {
                                    AppLogger.log("LogViewer: share failed: ${e::class.simpleName}: ${e.message}")
                                    snackbar.showSnackbar("Could not share the logs")
                                }
                            }
                        },
                    )
                    AppIconButton(
                        icon = Icons.Filled.ContentCopy,
                        onTap = {
                            scope.launch {
                                val message = try {
                                    val report = buildLogReport(context)
                                    // Keep the header and the newest lines when the log is too long to copy.
                                    val text = if (report.length <= MAX_CLIPBOARD_CHARS) {
                                        report
                                    } else {
                                        val headerEnd = report.indexOf("----\n") + 5
                                        report.substring(0, headerEnd) + "(older lines left out)\n" +
                                            report.substring(report.length - MAX_CLIPBOARD_CHARS + headerEnd + 30)
                                    }
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("App Logs", text))
                                    if (text.length < report.length) {
                                        "Newest logs copied (too long for the clipboard: use Share for all)"
                                    } else {
                                        "Logs copied to clipboard"
                                    }
                                } catch (e: Exception) {
                                    AppLogger.log("LogViewer: copy failed: ${e::class.simpleName}: ${e.message}")
                                    "Could not copy the logs: use Share"
                                }
                                snackbar.showSnackbar(message)
                            }
                        },
                    )
                    AppIconButton(
                        icon = Icons.Filled.DeleteOutline,
                        onTap = { AppLogger.clear() },
                    )
                },
            )
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().fillMaxWidth().navigationBarsPadding(),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(logs.size) { index ->
                        val log = logs[logs.size - 1 - index] // Show latest logs first
                        Text(
                            log,
                            modifier = Modifier.padding(vertical = 2.dp),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = when {
                                    log.contains("issue=ok") -> AppColors.textPrimary
                                    log.contains("FETCH ") && !log.contains("issue=empty") -> AppColors.danger
                                    log.contains("rror") || log.contains("failed") -> AppColors.danger
                                    else -> AppColors.textPrimary
                                },
                            ),
                        )
                    }
                }
            }
        }
        AppSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
