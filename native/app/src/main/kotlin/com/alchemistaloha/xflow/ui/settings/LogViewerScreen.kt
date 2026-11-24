package com.alchemistaloha.xflow.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppIconButton
import com.alchemistaloha.xflow.ui.theme.AppTopBar
import kotlinx.coroutines.launch

/** The in-memory app log, newest line first, with copy and clear (Dart `LogViewerScreen`). */
@Composable
fun LogViewerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Like the Dart screen, the list is a snapshot taken when the screen is
    // built; clearing takes a new one.
    var generation by remember { mutableIntStateOf(0) }
    val logs = remember(generation) { AppLogger.logs }

    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                "App Logs",
                onBack = { Navigator.pop() },
                actions = {
                    AppIconButton(
                        icon = Icons.Filled.ContentCopy,
                        onTap = {
                            try {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("App Logs", logs.joinToString("\n")))
                            } catch (e: Exception) {
                                AppLogger.log("LogViewer: copy failed: $e")
                            }
                            scope.launch { snackbar.showSnackbar("Logs copied to clipboard") }
                        },
                    )
                    AppIconButton(
                        icon = Icons.Filled.DeleteOutline,
                        onTap = {
                            AppLogger.clear()
                            generation++
                        },
                    )
                },
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
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
                            color = AppColors.textPrimary,
                        ),
                    )
                }
            }
        }
        AppSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
