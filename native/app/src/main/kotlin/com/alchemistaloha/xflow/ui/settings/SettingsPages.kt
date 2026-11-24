package com.alchemistaloha.xflow.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.VideoEndAction
import com.alchemistaloha.xflow.core.settings.SettingsFormat
import com.alchemistaloha.xflow.data.db.Repository
import com.alchemistaloha.xflow.data.feed.FeedStore
import com.alchemistaloha.xflow.core.translate.TranslateLogic
import com.alchemistaloha.xflow.data.settings.SettingsStore
import com.alchemistaloha.xflow.data.settings.ViewerPrefs
import com.alchemistaloha.xflow.data.subscriptions.SubscriptionListStore
import com.alchemistaloha.xflow.player.MediaCache
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.subscriptions.SubscriptionImportScreen
import com.alchemistaloha.xflow.ui.theme.AppColors
import kotlinx.coroutines.launch

// --- SUB PAGES ---

@Composable
fun PlaybackSettingsPage() {
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val notifier = SettingsStore

    val viewer by ViewerPrefs.state.collectAsStateWithLifecycle()

    SettingsPage("Playback & Feed") {
        ListRow(
            title = "Content Strategy",
            subtitle = SettingsFormat.contentStrategySubtitle(settings.fetchStrategy),
            trailing = {
                DropdownTrailing(
                    value = settings.fetchStrategy,
                    items = FeedSort.entries,
                    label = { SettingsFormat.feedSortLabel(it) },
                    onChanged = { notifier.updateDiscoveryParam(fetchStrategy = it) },
                )
            },
        )
        SwitchRow(
            title = "Autoplay",
            subtitle = "Automatically play videos when visible",
            value = settings.autoplay,
            onChanged = { notifier.toggleAutoplay(it) },
        )
        ListRow(
            title = "After Video Ends",
            subtitle = SettingsFormat.videoEndActionSubtitle(settings.videoEndAction.name),
            trailing = {
                DropdownTrailing(
                    value = settings.videoEndAction,
                    items = VideoEndAction.entries,
                    label = { SettingsFormat.enumLabel(it.name) },
                    onChanged = { notifier.updateDiscoveryParam(videoEndAction = it) },
                )
            },
        )
        SliderSetting(
            title = "Video Load Retries",
            subtitle = "Attempts to play a video if it fails to load",
            value = settings.playbackRetryLimit.toDouble(),
            min = 0.0,
            max = 5.0,
            onChanged = { notifier.updateDiscoveryParam(playbackRetryLimit = it.toInt()) },
        )
        SwitchRow(
            title = "Subtitles",
            subtitle = "Show a video's subtitles when it has them",
            value = viewer.subtitlesOn,
            onChanged = { ViewerPrefs.setSubtitlesOn(it) },
        )
        ListRow(
            title = "Translate Posts Into",
            subtitle = "Language used by Translate",
            trailing = {
                DropdownTrailing(
                    value = viewer.translateLang,
                    items = TranslateLogic.targetChoices(ViewerPrefs.deviceLang).let {
                        if (viewer.translateLang in it) it else it + viewer.translateLang
                    },
                    label = { TranslateLogic.targetChoiceLabel(it, ViewerPrefs.deviceLang) },
                    onChanged = { ViewerPrefs.setTranslateLang(it) },
                )
            },
        )
        SettingsDivider()
        Text(
            "Content Filters",
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = AppColors.textPrimary,
            ),
        )
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MediaFilter.entries.forEach { f ->
                SettingsFilterChip(
                    label = SettingsFormat.enumLabel(f.name),
                    selected = settings.filters.contains(f),
                    onSelected = { notifier.toggleFilter(f) },
                )
            }
        }
        SettingsDivider()
        SliderSetting(
            title = "Initial Feed Size",
            subtitle = "Videos to load immediately when the app starts",
            value = settings.initialSyncCount.toDouble(),
            min = 1.0,
            max = 100.0,
            onChanged = { notifier.updateDiscoveryParam(initialSyncCount = it.toInt()) },
        )
        SliderSetting(
            title = "Scroll Batch Size",
            subtitle = "New videos to load when reaching the end",
            value = settings.loadBatchSize.toDouble(),
            min = 5.0,
            max = 100.0,
            onChanged = { notifier.updateLoadBatchSize(it.toInt()) },
        )
        ListRow(
            title = "New vs. Cached Mix",
            subtitle = SettingsFormat.freshMixLabel(settings.freshMixRatio),
        )
        AppSlider(
            value = SettingsFormat.clamp(settings.freshMixRatio, 0.0, 1.0),
            min = 0.0,
            max = 1.0,
            divisions = 10,
            onChanged = { notifier.updateDiscoveryParam(freshMixRatio = it) },
        )
    }
}

@Composable
fun DiscoverySettingsPage() {
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val notifier = SettingsStore

    SettingsPage("Discovery & Diversity") {
        SwitchRow(
            title = "Avoid Seen Content",
            subtitle = "Hide videos you have already watched",
            value = settings.avoidWatchedContent,
            onChanged = { notifier.updateDiscoveryParam(avoidWatchedContent = it) },
        )
        SwitchRow(
            title = "Unseen Creator Boost",
            subtitle = "Show more content from creators you rarely see",
            value = settings.unseenSubscriptionBoost,
            onChanged = { notifier.updateDiscoveryParam(unseenSubscriptionBoost = it) },
        )
        SettingsDivider()
        SliderSetting(
            title = "Creator Diversity",
            subtitle = "Max videos from same creator in a row",
            value = settings.saturationThreshold.toDouble(),
            min = 1.0,
            max = 10.0,
            onChanged = { notifier.updateDiscoveryParam(saturationThreshold = it.toInt()) },
        )
        SliderSetting(
            title = "Video Diversity",
            subtitle = "Max copies of same video in window",
            value = settings.mediaSaturationThreshold.toDouble(),
            min = 1.0,
            max = 5.0,
            onChanged = { notifier.updateDiscoveryParam(mediaSaturationThreshold = it.toInt()) },
        )
        SliderSetting(
            title = "Diversity Window",
            subtitle = "How far back the algorithm looks to ensure variety",
            value = settings.saturationWindow.toDouble(),
            min = 5.0,
            max = 100.0,
            onChanged = { notifier.updateDiscoveryParam(saturationWindow = it.toInt()) },
        )
        SliderSetting(
            title = "Strict Deduplication",
            subtitle = "Lookback for exact media-key matches",
            value = settings.mediaDeduplicationWindow.toDouble(),
            min = 10.0,
            max = 200.0,
            onChanged = { notifier.updateDiscoveryParam(mediaDeduplicationWindow = it.toInt()) },
        )
        SliderSetting(
            title = "Discovery Range",
            subtitle = "Search depth for finding creators to boost",
            value = settings.unseenBoostLookahead.toDouble(),
            min = 2.0,
            max = 50.0,
            onChanged = { notifier.updateDiscoveryParam(unseenBoostLookahead = it.toInt()) },
        )
        SliderSetting(
            title = "Candidate Pool Size",
            subtitle = "Local search space for building a diverse feed",
            value = settings.dbCandidateMultiplier.toDouble(),
            min = 1.0,
            max = 20.0,
            onChanged = { notifier.updateDiscoveryParam(dbCandidateMultiplier = it.toInt()) },
        )
    }
}

@Composable
fun SyncSettingsPage() {
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val notifier = SettingsStore

    SettingsPage("Background Fetch") {
        SliderSetting(
            title = "Refresh Frequency (Mins)",
            subtitle = "How often the app checks for new videos",
            value = settings.syncInterval.toDouble(),
            min = 1.0,
            max = 120.0,
            onChanged = { notifier.updateSyncInterval(it.toInt()) },
        )
        SliderSetting(
            title = "Refresh Intensity",
            subtitle = "Accounts to check per refresh session",
            value = settings.syncBatchSize.toDouble(),
            min = 1.0,
            max = 50.0,
            onChanged = { notifier.updateSyncBatchSize(it.toInt()) },
        )
        SliderSetting(
            title = "Account Cool-off",
            subtitle = "Wait time before checking the same account again",
            value = settings.cooldownDuration.toDouble(),
            min = 0.0,
            max = 240.0,
            onChanged = { notifier.updateCooldownDuration(it.toInt()) },
        )
    }
}

@Composable
fun StorageSettingsPage(
    metadataCount: Int,
    cacheSizeMB: Double,
    onRefresh: suspend () -> Unit,
) {
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val notifier = SettingsStore
    val scope = rememberCoroutineScope()

    SettingsPage("Storage & Cache") {
        ListRow(
            title = "Local Media Cache",
            subtitle = SettingsFormat.cacheUsage(cacheSizeMB, settings.mediaCacheSizeMB),
        )
        AppSlider(
            value = SettingsFormat.clamp(settings.mediaCacheSizeMB.toDouble(), 100.0, 2000.0),
            min = 100.0,
            max = 2000.0,
            divisions = 19,
            label = SettingsFormat.cacheLimitLabel(settings.mediaCacheSizeMB),
            onChanged = {
                val megabytes = Math.round(it).toInt()
                notifier.updateMediaCacheSize(megabytes)
                MediaCache.applyMaxSize(megabytes)
            },
        )
        ListRow(
            leading = Icons.Filled.CleaningServices,
            tint = AppColors.warning,
            title = "Clear Media Cache",
            onTap = {
                scope.launch {
                    runLogged("clear media cache") {
                        MediaCache.clearCache()
                        onRefresh()
                    }
                }
            },
        )
        SettingsDivider()
        SliderSetting(
            title = "Database Record Limit",
            subtitle = "Max video metadata entries to keep in storage",
            value = settings.pruneThreshold.toDouble(),
            min = 1000.0,
            max = 100000.0,
            divisions = 99,
            onChanged = { notifier.updatePruneThreshold(it.toInt()) },
        )
        ListRow(
            leading = Icons.Filled.DeleteOutline,
            tint = AppColors.danger,
            title = "Purge Seen Metadata",
            subtitle = "Delete database records for watched videos",
            onTap = {
                scope.launch {
                    runLogged("purge seen metadata") {
                        Repository.purgeSeenMetadata()
                        onRefresh()
                    }
                }
            },
        )
    }
}

@Composable
fun SubscriptionSettingsPage() {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    SettingsPage("Subscriptions", snackbarHostState = snackbar) {
        ListRow(
            leading = Icons.Filled.ImportExport,
            title = "Import Subscriptions",
            subtitle = "Sync follows from an existing X account",
            onTap = { Navigator.push { SubscriptionImportScreen() } },
        )
        ListRow(
            leading = Icons.Filled.DeleteSweep,
            tint = AppColors.warning,
            title = "Clear All Subscriptions",
            onTap = {
                scope.launch {
                    runLogged("clear subscriptions") {
                        Repository.clearSubscriptions()
                        FeedStore.invalidate()
                        // Not in the Dart screen: also drop the cached follow list so the
                        // Subscriptions tab does not keep showing the cleared accounts.
                        SubscriptionListStore.invalidate()
                        snackbar.showSnackbar("Cleared")
                    }
                }
            },
        )
    }
}

@Composable
fun NetworkSettingsPage() {
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val notifier = SettingsStore

    SettingsPage("Network & Performance") {
        SliderSetting(
            title = "Network Timeout (Secs)",
            value = settings.apiTimeoutSeconds.toDouble(),
            min = 5.0,
            max = 60.0,
            onChanged = { notifier.updateDiscoveryParam(apiTimeoutSeconds = it.toInt()) },
        )
        SliderSetting(
            title = "API Request Size",
            subtitle = "Target items to request from X per call",
            value = settings.timelineBatchSize.toDouble(),
            min = 5.0,
            max = 200.0,
            onChanged = { notifier.updateTimelineBatchSize(it.toInt()) },
        )
        SliderSetting(
            title = "Network Retry Limit",
            subtitle = "Attempts per page if a request fails",
            value = settings.apiRetryLimit.toDouble(),
            min = 1.0,
            max = 10.0,
            onChanged = { notifier.updateDiscoveryParam(apiRetryLimit = it.toInt()) },
        )
        SliderSetting(
            title = "Pre-load Threshold",
            subtitle = "Fetch next batch when this many remain in feed",
            value = settings.lazyLoadThreshold.toDouble(),
            min = 1.0,
            max = 50.0,
            onChanged = { notifier.updateDiscoveryParam(lazyLoadThreshold = it.toInt()) },
        )
        SliderSetting(
            title = "Failure Skip Delay",
            subtitle = "Seconds to wait before skipping broken media",
            value = settings.autoSkipDelaySeconds.toDouble(),
            min = 1.0,
            max = 10.0,
            onChanged = { notifier.updateDiscoveryParam(autoSkipDelaySeconds = it.toInt()) },
        )
    }
}

@Composable
fun SearchSettingsPage() {
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val notifier = SettingsStore

    SettingsPage("Legacy Fetch") {
        SwitchRow(
            title = "Strict Subscriptions",
            subtitle = "Disable trending fallback for empty queries",
            value = settings.strictSubscriptionsOnly,
            onChanged = { notifier.updateDiscoveryParam(strictSubscriptionsOnly = it) },
        )
        SwitchRow(
            title = "Include Retweets",
            value = settings.includeNativeRetweets,
            onChanged = { notifier.updateDiscoveryParam(includeNativeRetweets = it) },
        )
        SwitchRow(
            title = "Chunked Crawling",
            subtitle = "Iterate through follow list in blocks",
            value = settings.useChunkedSubscriptions,
            onChanged = { notifier.updateDiscoveryParam(useChunkedSubscriptions = it) },
        )
        SliderSetting(
            title = "Minimum Favorites Filter",
            value = settings.minFavesFilter.toDouble(),
            min = 0.0,
            max = 1000.0,
            divisions = 20,
            onChanged = { notifier.updateDiscoveryParam(minFavesFilter = it.toInt()) },
        )
        SliderSetting(
            title = "Max Search Query Length",
            value = settings.maxQueryLength.toDouble(),
            min = 100.0,
            max = 1000.0,
            divisions = 18,
            onChanged = { notifier.updateDiscoveryParam(maxQueryLength = it.toInt()) },
        )
        SliderSetting(
            title = "Rotation Cycle",
            subtitle = "Blocks to skip before repeating creators",
            value = settings.chunkRotationLimit.toDouble(),
            min = 1.0,
            max = 10.0,
            onChanged = { notifier.updateDiscoveryParam(chunkRotationLimit = it.toInt()) },
        )
        SliderSetting(
            title = "Minimum Yield",
            subtitle = "New items required before finishing loop",
            value = settings.minNewTweetsThreshold.toDouble(),
            min = 1.0,
            max = 20.0,
            onChanged = { notifier.updateDiscoveryParam(minNewTweetsThreshold = it.toInt()) },
        )
    }
}

@Composable
fun DiagnosticSettingsPage() {
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val notifier = SettingsStore

    SettingsPage("Diagnostics") {
        SwitchRow(
            title = "Overlay Source Info",
            subtitle = "Display discovery metadata on top of videos",
            value = settings.showDebugInfo,
            onChanged = { notifier.toggleDebugInfo(it) },
        )
        SliderSetting(
            title = "Algorithm Safety Cap",
            subtitle = "Max calculations for diversity logic",
            value = settings.maxSaturationSwaps.toDouble(),
            min = 100.0,
            max = 5000.0,
            divisions = 49,
            onChanged = { notifier.updateDiscoveryParam(maxSaturationSwaps = it.toInt()) },
        )
        SettingsDivider()
        ListRow(
            leading = Icons.Filled.Terminal,
            title = "View App Logs",
            onTap = { Navigator.push { LogViewerScreen() } },
        )
    }
}
