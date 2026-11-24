package com.alchemistaloha.xflow.ui.subscriptions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alchemistaloha.xflow.AppGraph
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.feed.twitterClient
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppRadius
import com.alchemistaloha.xflow.ui.theme.AppTopBar
import com.alchemistaloha.xflow.ui.theme.HSpace
import com.alchemistaloha.xflow.ui.theme.VSpace
import com.alchemistaloha.xflow.data.subscriptions.SubscriptionListStore
import com.alchemistaloha.xflow.data.feed.FeedStore
import com.alchemistaloha.xflow.data.sync.BackgroundSync
import com.alchemistaloha.xflow.data.settings.SettingsStore
import kotlinx.coroutines.launch

/** Where an import stands; mirrors the Dart stream of "users found so far". */
private sealed interface ImportStatus {
    data object Idle : ImportStatus
    data class Active(val count: Int) : ImportStatus
    data class Done(val count: Int) : ImportStatus
    data class Failed(val error: Throwable) : ImportStatus
}

/** Prints like a Dart `Exception('...')`, so the error line reads the same. */
private class ImportException(message: String) : Exception(message) {
    override fun toString(): String = "Exception: $message"
}

private val usernameChar = Regex("[a-zA-Z0-9_]")

/** Copies another account's following list into the local subscriptions. */
@Composable
fun SubscriptionImportScreen() {
    var fromScreenName by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<ImportStatus>(ImportStatus.Idle) }
    var isImporting by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun importSubscriptions() {
        val name = fromScreenName
        if (name == null || name.trim().isEmpty()) {
            scope.launch { snackbar.showSnackbar("Please enter a username") }
            return
        }

        isImporting = true
        status = ImportStatus.Active(0)

        // On the app scope: like the Dart future, an import that has started
        // finishes even if the screen is closed meanwhile.
        AppGraph.scope.launch {
            try {
                val client = twitterClient

                val user = client.fetchProfile(name) ?: throw ImportException("User not found")

                val following = client.fetchFollowing(user.id)
                val count = if (following.isNotEmpty()) {
                    Repository.insertSubscriptions(following)
                    AppLogger.log("IMPORT done: saved ${following.size} subscriptions")
                    // The Flutter screen left the list and the feed as they were, so an
                    // empty feed stayed empty until the app was restarted.
                    SubscriptionListStore.invalidate()
                    FeedStore.onSubscriptionsChanged()
                    BackgroundSync.syncNow(client, SettingsStore.state.value)
                    following.size
                } else {
                    AppLogger.log("IMPORT done: X returned no accounts for this user")
                    0
                }
                status = ImportStatus.Done(count)
            } catch (e: Throwable) {
                AppLogger.log("Import error: $e\n${e.stackTraceToString()}")
                status = ImportStatus.Failed(e)
            } finally {
                isImporting = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(AppColors.background).navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title = "Import Subscriptions", onBack = { Navigator.pop() })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.lg))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.ImportExport,
                        contentDescription = null,
                        tint = AppColors.accent,
                        modifier = Modifier.size(40.dp),
                    )
                    VSpace(16.dp)
                    Text(
                        "Enter a username to sync their following list into your XFlow subscriptions.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    VSpace(24.dp)
                    val text = fromScreenName.orEmpty()
                    TextField(
                        value = text,
                        onValueChange = { value ->
                            // Keeps the leading run of allowed characters, at most 15.
                            fromScreenName = value
                                .takeWhile { usernameChar.matches(it.toString()) }
                                .take(15)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Username") },
                        placeholder = { Text("Enter X username") },
                        leadingIcon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
                        supportingText = {
                            Text(
                                "${text.length}/15",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                    VSpace(16.dp)
                    Button(
                        onClick = { importSubscriptions() },
                        enabled = !isImporting,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Icon(
                                Icons.Filled.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        HSpace(8.dp)
                        Text(if (isImporting) "Importing..." else "Start Import")
                    }
                }
                VSpace(32.dp)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (val s = status) {
                        is ImportStatus.Failed -> StatusCard(
                            icon = Icons.Filled.ErrorOutline,
                            color = AppColors.danger,
                            message = "Error: ${s.error}",
                        )
                        is ImportStatus.Active -> StatusCard(
                            icon = Icons.Filled.Sync,
                            color = AppColors.accent,
                            message = "Found ${s.count} users so far...",
                            isSpinning = true,
                        )
                        is ImportStatus.Done -> StatusCard(
                            icon = Icons.Filled.CheckCircleOutline,
                            color = AppColors.success,
                            message = "Success! Imported ${s.count} users.",
                        )
                        ImportStatus.Idle -> Unit
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun StatusCard(icon: ImageVector, color: Color, message: String, isSpinning: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.1f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.2f)), shape)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSpinning) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        }
        HSpace(16.dp)
        Text(
            message,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium.copy(color = color, fontWeight = FontWeight.Medium),
        )
    }
}
