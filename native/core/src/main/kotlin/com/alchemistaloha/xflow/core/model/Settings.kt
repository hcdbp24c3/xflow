package com.alchemistaloha.xflow.core.model

// Order matters: settings are stored by ordinal, as the Flutter app did.
enum class FeedSort { latest, popular, trending, algorithmic, chronological, videomixer }

enum class MediaFilter { video, image, text }

enum class VideoEndAction { pause, replay, playNext }

/** Every user setting, with the same names and defaults as the Flutter app. */
data class SettingsState(
    val isInitialized: Boolean = false,
    val filters: Set<MediaFilter> = emptySet(),
    val autoplay: Boolean = true,
    val isListView: Boolean = false,
    val mediaCacheSizeMB: Int = 500,

    val syncInterval: Int = 15,
    val syncBatchSize: Int = 10,
    val loadBatchSize: Int = 20,
    val timelineBatchSize: Int = 20,
    val cooldownDuration: Int = 15,
    val pruneThreshold: Int = 50000,

    val avoidWatchedContent: Boolean = true,
    val unseenSubscriptionBoost: Boolean = true,
    val freshMixRatio: Double = 0.3,
    val saturationThreshold: Int = 2,
    val mediaSaturationThreshold: Int = 1,
    val fetchStrategy: FeedSort = FeedSort.chronological,
    val initialSyncCount: Int = 10,
    val strictSubscriptionsOnly: Boolean = true,
    val includeNativeRetweets: Boolean = false,
    val useChunkedSubscriptions: Boolean = true,
    val showDebugInfo: Boolean = false,

    val saturationWindow: Int = 10,
    val unseenBoostLookahead: Int = 6,
    val minFavesFilter: Int = 50,

    val dbCandidateMultiplier: Int = 5,
    val apiRetryLimit: Int = 5,
    val chunkRotationLimit: Int = 3,
    val minNewTweetsThreshold: Int = 5,
    val maxQueryLength: Int = 480,
    val apiTimeoutSeconds: Int = 15,
    val maxSaturationSwaps: Int = 1000,
    val maxSaturationPasses: Int = 3,

    val playbackRetryLimit: Int = 1,
    val autoSkipDelaySeconds: Int = 2,
    val lazyLoadThreshold: Int = 10,
    val mediaDeduplicationWindow: Int = 50,
    // A finished video loops, like TikTok. "Auto next" (playNext) is opt-in.
    val videoEndAction: VideoEndAction = VideoEndAction.replay,
)
