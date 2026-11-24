package com.alchemistaloha.xflow.core.settings

import com.alchemistaloha.xflow.core.model.FeedSort
import com.alchemistaloha.xflow.core.model.MediaFilter
import com.alchemistaloha.xflow.core.model.SettingsState
import com.alchemistaloha.xflow.core.model.VideoEndAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where settings are kept. A getter returns null when the key is missing or has another type. */
interface SettingsStorage {
    fun contains(key: String): Boolean
    fun getBool(key: String): Boolean?
    fun getInt(key: String): Int?
    fun getDouble(key: String): Double?
    fun getStringList(key: String): List<String>?
    fun setBool(key: String, value: Boolean)
    fun setInt(key: String, value: Int)
    fun setDouble(key: String, value: Double)
    fun setStringList(key: String, value: List<String>)
}

/** The preference keys, the same names the Flutter app used. */
object SettingsKeys {
    val STRING_LIST_KEYS: Set<String> = setOf("filters")
    val BOOL_KEYS: Set<String> = setOf(
        "autoplay", "isListView", "avoidWatchedContent", "unseenSubscriptionBoost",
        "strictSubscriptionsOnly", "includeNativeRetweets", "useChunkedSubscriptions", "showDebugInfo",
    )
    val DOUBLE_KEYS: Set<String> = setOf("freshMixRatio")
    val INT_KEYS: Set<String> = setOf(
        "mediaCacheSizeMB", "syncInterval", "syncBatchSize", "loadBatchSize", "timelineBatchSize",
        "cooldownDuration", "pruneThreshold", "saturationThreshold", "mediaSaturationThreshold",
        "fetchStrategy", "initialSyncCount", "saturationWindow", "unseenBoostLookahead", "minFavesFilter",
        "dbCandidateMultiplier", "apiRetryLimit", "chunkRotationLimit", "minNewTweetsThreshold",
        "maxQueryLength", "apiTimeoutSeconds", "maxSaturationSwaps", "maxSaturationPasses",
        "playbackRetryLimit", "autoSkipDelaySeconds", "lazyLoadThreshold", "mediaDeduplicationWindow",
        "videoEndAction",
    )
}

/**
 * Dart `SettingsNotifier`: holds the settings, loads them once and persists
 * every change under the same key names.
 */
open class SettingsController(private val storage: SettingsStorage) {
    private val _state = MutableStateFlow(SettingsState(isInitialized = false))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Dart `_init`: reads every key (defaults for missing ones) and marks the state initialized. */
    fun load() {
        val prefs = storage
        val filterStrings = prefs.getStringList("filters") ?: emptyList()

        val filters = LinkedHashSet<MediaFilter>()
        for (s in filterStrings) {
            MediaFilter.values().firstOrNull { it.name == s }?.let { filters.add(it) }
        }

        val fetchStrategyIdx = prefs.getInt("fetchStrategy") ?: 0
        val videoEndActionIdx = prefs.getInt("videoEndAction") ?: VideoEndAction.playNext.ordinal

        _state.value = SettingsState(
            isInitialized = true,
            filters = filters,
            autoplay = prefs.getBool("autoplay") ?: true,
            isListView = prefs.getBool("isListView") ?: false,
            mediaCacheSizeMB = prefs.getInt("mediaCacheSizeMB") ?: 500,
            syncInterval = prefs.getInt("syncInterval") ?: 15,
            syncBatchSize = prefs.getInt("syncBatchSize") ?: 10,
            loadBatchSize = prefs.getInt("loadBatchSize") ?: 20,
            timelineBatchSize = prefs.getInt("timelineBatchSize") ?: 20,
            cooldownDuration = prefs.getInt("cooldownDuration") ?: 15,
            pruneThreshold = prefs.getInt("pruneThreshold") ?: 50000,
            avoidWatchedContent = prefs.getBool("avoidWatchedContent") ?: true,
            unseenSubscriptionBoost = prefs.getBool("unseenSubscriptionBoost") ?: true,
            freshMixRatio = prefs.getDouble("freshMixRatio") ?: 0.3,
            saturationThreshold = prefs.getInt("saturationThreshold") ?: 2,
            mediaSaturationThreshold = prefs.getInt("mediaSaturationThreshold") ?: 1,
            // As in the Flutter app, a missing key loads as index 0 (latest).
            fetchStrategy = FeedSort.values().getOrNull(fetchStrategyIdx) ?: FeedSort.latest,
            initialSyncCount = prefs.getInt("initialSyncCount") ?: 10,
            strictSubscriptionsOnly = prefs.getBool("strictSubscriptionsOnly") ?: true,
            includeNativeRetweets = prefs.getBool("includeNativeRetweets") ?: false,
            useChunkedSubscriptions = prefs.getBool("useChunkedSubscriptions") ?: true,
            showDebugInfo = prefs.getBool("showDebugInfo") ?: false,
            saturationWindow = prefs.getInt("saturationWindow") ?: 10,
            unseenBoostLookahead = prefs.getInt("unseenBoostLookahead") ?: 6,
            minFavesFilter = prefs.getInt("minFavesFilter") ?: 50,
            dbCandidateMultiplier = prefs.getInt("dbCandidateMultiplier") ?: 5,
            apiRetryLimit = prefs.getInt("apiRetryLimit") ?: 5,
            chunkRotationLimit = prefs.getInt("chunkRotationLimit") ?: 3,
            minNewTweetsThreshold = prefs.getInt("minNewTweetsThreshold") ?: 5,
            maxQueryLength = prefs.getInt("maxQueryLength") ?: 480,
            apiTimeoutSeconds = prefs.getInt("apiTimeoutSeconds") ?: 15,
            maxSaturationSwaps = prefs.getInt("maxSaturationSwaps") ?: 1000,
            maxSaturationPasses = prefs.getInt("maxSaturationPasses") ?: 3,
            playbackRetryLimit = prefs.getInt("playbackRetryLimit") ?: 1,
            autoSkipDelaySeconds = prefs.getInt("autoSkipDelaySeconds") ?: 2,
            lazyLoadThreshold = prefs.getInt("lazyLoadThreshold") ?: 10,
            mediaDeduplicationWindow = prefs.getInt("mediaDeduplicationWindow") ?: 50,
            videoEndAction = VideoEndAction.values().getOrNull(videoEndActionIdx) ?: VideoEndAction.playNext,
        )
    }

    fun updateMediaCacheSize(megabytes: Int) {
        _state.value = _state.value.copy(mediaCacheSizeMB = megabytes)
        storage.setInt("mediaCacheSizeMB", megabytes)
    }

    fun updateSyncInterval(minutes: Int) {
        _state.value = _state.value.copy(syncInterval = minutes)
        storage.setInt("syncInterval", minutes)
    }

    fun updateSyncBatchSize(size: Int) {
        _state.value = _state.value.copy(syncBatchSize = size)
        storage.setInt("syncBatchSize", size)
    }

    fun updateLoadBatchSize(size: Int) {
        _state.value = _state.value.copy(loadBatchSize = size)
        storage.setInt("loadBatchSize", size)
    }

    fun updateTimelineBatchSize(size: Int) {
        _state.value = _state.value.copy(timelineBatchSize = size)
        storage.setInt("timelineBatchSize", size)
    }

    fun updateCooldownDuration(minutes: Int) {
        _state.value = _state.value.copy(cooldownDuration = minutes)
        storage.setInt("cooldownDuration", minutes)
    }

    fun updatePruneThreshold(count: Int) {
        _state.value = _state.value.copy(pruneThreshold = count)
        storage.setInt("pruneThreshold", count)
    }

    fun updateDiscoveryParam(
        avoidWatchedContent: Boolean? = null,
        unseenSubscriptionBoost: Boolean? = null,
        freshMixRatio: Double? = null,
        saturationThreshold: Int? = null,
        mediaSaturationThreshold: Int? = null,
        fetchStrategy: FeedSort? = null,
        initialSyncCount: Int? = null,
        strictSubscriptionsOnly: Boolean? = null,
        includeNativeRetweets: Boolean? = null,
        useChunkedSubscriptions: Boolean? = null,
        saturationWindow: Int? = null,
        unseenBoostLookahead: Int? = null,
        minFavesFilter: Int? = null,
        dbCandidateMultiplier: Int? = null,
        apiRetryLimit: Int? = null,
        chunkRotationLimit: Int? = null,
        minNewTweetsThreshold: Int? = null,
        maxQueryLength: Int? = null,
        apiTimeoutSeconds: Int? = null,
        maxSaturationSwaps: Int? = null,
        maxSaturationPasses: Int? = null,
        playbackRetryLimit: Int? = null,
        autoSkipDelaySeconds: Int? = null,
        lazyLoadThreshold: Int? = null,
        mediaDeduplicationWindow: Int? = null,
        videoEndAction: VideoEndAction? = null,
    ) {
        val s = _state.value
        _state.value = s.copy(
            avoidWatchedContent = avoidWatchedContent ?: s.avoidWatchedContent,
            unseenSubscriptionBoost = unseenSubscriptionBoost ?: s.unseenSubscriptionBoost,
            freshMixRatio = freshMixRatio ?: s.freshMixRatio,
            saturationThreshold = saturationThreshold ?: s.saturationThreshold,
            mediaSaturationThreshold = mediaSaturationThreshold ?: s.mediaSaturationThreshold,
            fetchStrategy = fetchStrategy ?: s.fetchStrategy,
            initialSyncCount = initialSyncCount ?: s.initialSyncCount,
            strictSubscriptionsOnly = strictSubscriptionsOnly ?: s.strictSubscriptionsOnly,
            includeNativeRetweets = includeNativeRetweets ?: s.includeNativeRetweets,
            useChunkedSubscriptions = useChunkedSubscriptions ?: s.useChunkedSubscriptions,
            saturationWindow = saturationWindow ?: s.saturationWindow,
            unseenBoostLookahead = unseenBoostLookahead ?: s.unseenBoostLookahead,
            minFavesFilter = minFavesFilter ?: s.minFavesFilter,
            dbCandidateMultiplier = dbCandidateMultiplier ?: s.dbCandidateMultiplier,
            apiRetryLimit = apiRetryLimit ?: s.apiRetryLimit,
            chunkRotationLimit = chunkRotationLimit ?: s.chunkRotationLimit,
            minNewTweetsThreshold = minNewTweetsThreshold ?: s.minNewTweetsThreshold,
            maxQueryLength = maxQueryLength ?: s.maxQueryLength,
            apiTimeoutSeconds = apiTimeoutSeconds ?: s.apiTimeoutSeconds,
            maxSaturationSwaps = maxSaturationSwaps ?: s.maxSaturationSwaps,
            maxSaturationPasses = maxSaturationPasses ?: s.maxSaturationPasses,
            playbackRetryLimit = playbackRetryLimit ?: s.playbackRetryLimit,
            autoSkipDelaySeconds = autoSkipDelaySeconds ?: s.autoSkipDelaySeconds,
            lazyLoadThreshold = lazyLoadThreshold ?: s.lazyLoadThreshold,
            mediaDeduplicationWindow = mediaDeduplicationWindow ?: s.mediaDeduplicationWindow,
            videoEndAction = videoEndAction ?: s.videoEndAction,
        )
        if (avoidWatchedContent != null) storage.setBool("avoidWatchedContent", avoidWatchedContent)
        if (unseenSubscriptionBoost != null) storage.setBool("unseenSubscriptionBoost", unseenSubscriptionBoost)
        if (freshMixRatio != null) storage.setDouble("freshMixRatio", freshMixRatio)
        if (saturationThreshold != null) storage.setInt("saturationThreshold", saturationThreshold)
        if (mediaSaturationThreshold != null) storage.setInt("mediaSaturationThreshold", mediaSaturationThreshold)
        if (fetchStrategy != null) storage.setInt("fetchStrategy", fetchStrategy.ordinal)
        if (initialSyncCount != null) storage.setInt("initialSyncCount", initialSyncCount)
        if (strictSubscriptionsOnly != null) storage.setBool("strictSubscriptionsOnly", strictSubscriptionsOnly)
        if (includeNativeRetweets != null) storage.setBool("includeNativeRetweets", includeNativeRetweets)
        if (useChunkedSubscriptions != null) storage.setBool("useChunkedSubscriptions", useChunkedSubscriptions)
        if (saturationWindow != null) storage.setInt("saturationWindow", saturationWindow)
        if (unseenBoostLookahead != null) storage.setInt("unseenBoostLookahead", unseenBoostLookahead)
        if (minFavesFilter != null) storage.setInt("minFavesFilter", minFavesFilter)

        if (dbCandidateMultiplier != null) storage.setInt("dbCandidateMultiplier", dbCandidateMultiplier)
        if (apiRetryLimit != null) storage.setInt("apiRetryLimit", apiRetryLimit)
        if (chunkRotationLimit != null) storage.setInt("chunkRotationLimit", chunkRotationLimit)
        if (minNewTweetsThreshold != null) storage.setInt("minNewTweetsThreshold", minNewTweetsThreshold)
        if (maxQueryLength != null) storage.setInt("maxQueryLength", maxQueryLength)
        if (apiTimeoutSeconds != null) storage.setInt("apiTimeoutSeconds", apiTimeoutSeconds)
        if (maxSaturationSwaps != null) storage.setInt("maxSaturationSwaps", maxSaturationSwaps)
        if (maxSaturationPasses != null) storage.setInt("maxSaturationPasses", maxSaturationPasses)
        if (playbackRetryLimit != null) storage.setInt("playbackRetryLimit", playbackRetryLimit)
        if (autoSkipDelaySeconds != null) storage.setInt("autoSkipDelaySeconds", autoSkipDelaySeconds)
        if (lazyLoadThreshold != null) storage.setInt("lazyLoadThreshold", lazyLoadThreshold)
        if (mediaDeduplicationWindow != null) storage.setInt("mediaDeduplicationWindow", mediaDeduplicationWindow)
        if (videoEndAction != null) storage.setInt("videoEndAction", videoEndAction.ordinal)
    }

    fun toggleFilter(filter: MediaFilter) {
        val nextFilters = LinkedHashSet(_state.value.filters)
        if (nextFilters.contains(filter)) {
            nextFilters.remove(filter)
        } else {
            nextFilters.add(filter)
        }
        _state.value = _state.value.copy(filters = nextFilters)
        storage.setStringList("filters", nextFilters.map { it.name })
    }

    fun toggleAutoplay(value: Boolean) {
        _state.value = _state.value.copy(autoplay = value)
        storage.setBool("autoplay", value)
    }

    fun toggleListView(value: Boolean) {
        _state.value = _state.value.copy(isListView = value)
        storage.setBool("isListView", value)
    }

    fun toggleDebugInfo(value: Boolean) {
        _state.value = _state.value.copy(showDebugInfo = value)
        storage.setBool("showDebugInfo", value)
    }
}
