package com.alchemistaloha.xflow.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.PeopleOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alchemistaloha.xflow.core.settings.SettingsFormat
import com.alchemistaloha.xflow.data.client.AccountStore
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.feed.FeedStore
import com.alchemistaloha.xflow.player.MediaCache
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppRadius
import com.alchemistaloha.xflow.ui.theme.AppTopBar
import com.alchemistaloha.xflow.ui.theme.listSubtitleStyle
import com.alchemistaloha.xflow.ui.theme.listTitleStyle
import kotlinx.coroutines.launch

/** Settings home: grouped cards that open the sub-pages (Dart `SettingsScreen`). */
@Composable
fun SettingsScreen() {
    // Held as state objects so the pushed Storage page keeps reading live values.
    val metadataCount = remember { mutableIntStateOf(0) }
    val cacheSizeMB = remember { mutableDoubleStateOf(0.0) }
    val scope = rememberCoroutineScope()

    val loadStats: suspend () -> Unit = remember {
        {
            runLogged("load stats") {
                val count = Repository.getCachedMediaCount()
                val sizeBytes = MediaCache.getCacheSize()
                metadataCount.intValue = count
                cacheSizeMB.doubleValue = SettingsFormat.bytesToMB(sizeBytes)
            }
        }
    }
    LaunchedEffect(Unit) { loadStats() }

    Column(Modifier.fillMaxSize().background(AppColors.background)) {
        AppTopBar("Settings", onBack = { Navigator.pop() })
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(vertical = 16.dp),
        ) {
            SettingsGroup("Experience") {
                SettingsTile(
                    icon = Icons.Outlined.PlayCircleOutline,
                    title = "Playback & Feed",
                    subtitle = "Sorting, autoplay, and delivery mix",
                    onTap = { Navigator.push { PlaybackSettingsPage() } },
                )
                SettingsTile(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Discovery & Diversity",
                    subtitle = "Algorithm tuning and content variety",
                    onTap = { Navigator.push { DiscoverySettingsPage() } },
                )
            }
            SettingsGroup("Data & Storage") {
                SettingsTile(
                    icon = Icons.Outlined.Sync,
                    title = "Background Fetch",
                    subtitle = "Sync intervals and background crawling",
                    onTap = { Navigator.push { SyncSettingsPage() } },
                )
                SettingsTile(
                    icon = Icons.Outlined.Storage,
                    title = "Storage & Cache",
                    subtitle = SettingsFormat.storageSummary(cacheSizeMB.doubleValue, metadataCount.intValue),
                    onTap = {
                        Navigator.push {
                            StorageSettingsPage(
                                metadataCount = metadataCount.intValue,
                                cacheSizeMB = cacheSizeMB.doubleValue,
                                onRefresh = loadStats,
                            )
                        }
                    },
                )
            }
            SettingsGroup("Content Sources") {
                SettingsTile(
                    icon = Icons.Filled.PeopleOutline,
                    title = "Subscriptions",
                    subtitle = "Import, export, or clear follow list",
                    onTap = { Navigator.push { SubscriptionSettingsPage() } },
                )
                SettingsTile(
                    icon = Icons.Outlined.Search,
                    title = "Legacy Fetch",
                    subtitle = "Advanced subscription crawling parameters",
                    onTap = { Navigator.push { SearchSettingsPage() } },
                )
            }
            SettingsGroup("System") {
                SettingsTile(
                    icon = Icons.Outlined.NetworkCheck,
                    title = "Network & Performance",
                    subtitle = "Timeouts, retries, and loading thresholds",
                    onTap = { Navigator.push { NetworkSettingsPage() } },
                )
                SettingsTile(
                    icon = Icons.Outlined.BugReport,
                    title = "Diagnostics & Logs",
                    subtitle = "Debug tools and algorithm safety caps",
                    onTap = { Navigator.push { DiagnosticSettingsPage() } },
                )
                SettingsTile(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = "Logout",
                    titleColor = AppColors.danger,
                    onTap = {
                        scope.launch {
                            runLogged("logout") {
                                AccountStore.logout()
                                FeedStore.invalidate()
                                Navigator.pop()
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Section label above a rounded card holding the section's rows (Dart `_SettingsGroup`). */
@Composable
private fun SettingsGroup(title: String, children: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            modifier = Modifier.padding(start = 28.dp, top = 14.dp, end = 24.dp, bottom = 8.dp),
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = AppColors.textTertiary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            ),
        )
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.lg))
                .background(AppColors.surface),
            content = children,
        )
        Box(Modifier.size(8.dp))
    }
}

/** One row of a settings card: icon, title, optional subtitle, chevron (Dart `_SettingsTile`). */
@Composable
private fun SettingsTile(
    icon: ImageVector,
    title: String,
    onTap: () -> Unit,
    subtitle: String? = null,
    titleColor: Color? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onTap)
            .heightIn(min = if (subtitle == null) 56.dp else 72.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = titleColor ?: AppColors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
        Box(Modifier.size(width = 16.dp, height = 1.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = listTitleStyle.copy(
                    color = titleColor ?: AppColors.textPrimary,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (subtitle != null) Text(subtitle, style = listSubtitleStyle)
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = AppColors.textTertiary,
            modifier = Modifier.padding(start = 16.dp).size(20.dp),
        )
    }
}
