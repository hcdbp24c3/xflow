import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/client/twitter_client.dart';
import '../../core/models/tweet.dart';
import '../../core/database/repository.dart';
import '../../core/client/discovery_engine.dart';
import '../../core/utils/app_logger.dart';
import '../settings/settings_provider.dart';
import '../player/player_pool_provider.dart';

final twitterClientProvider = Provider((ref) => TwitterClient());

class FeedState {
  final List<Tweet> tweets;
  final String? cursorBottom;
  final bool isLoadingMore;
  final bool isRefreshing;

  FeedState({
    required this.tweets,
    this.cursorBottom,
    this.isLoadingMore = false,
    this.isRefreshing = false,
  });

  FeedState copyWith({
    List<Tweet>? tweets,
    String? cursorBottom,
    bool? isLoadingMore,
    bool? isRefreshing,
  }) {
    return FeedState(
      tweets: tweets ?? this.tweets,
      cursorBottom: cursorBottom ?? this.cursorBottom,
      isLoadingMore: isLoadingMore ?? this.isLoadingMore,
      isRefreshing: isRefreshing ?? this.isRefreshing,
    );
  }
}

/// Like/retweet state for tweets shown outside the main feed (hashtag and
/// profile feeds). Those tweets are not in [FeedState.tweets], so their
/// optimistic updates are kept here, keyed by tweet id.
class TweetOverridesNotifier extends Notifier<Map<String, Tweet>> {
  @override
  Map<String, Tweet> build() => {};

  void put(Tweet tweet) => state = {...state, tweet.id: tweet};
}

final tweetOverridesProvider =
    NotifierProvider<TweetOverridesNotifier, Map<String, Tweet>>(
        TweetOverridesNotifier.new);

/// Watches the freshest copy of [tweet]: the one in the main feed if it is
/// there, otherwise the locally updated copy, otherwise [tweet] itself.
Tweet watchLiveTweet(WidgetRef ref, Tweet tweet) {
  final inFeed = ref.watch(feedNotifierProvider.select((s) {
    final tweets = s.value?.tweets;
    if (tweets == null) return null;
    for (final t in tweets) {
      if (t.id == tweet.id) return t;
    }
    return null;
  }));
  if (inFeed != null) return inFeed;
  final override = ref.watch(tweetOverridesProvider.select((m) => m[tweet.id]));
  return override ?? tweet;
}

/// Same lookup as [watchLiveTweet] without subscribing, for event handlers.
Tweet readLiveTweet(WidgetRef ref, Tweet tweet) {
  final tweets = ref.read(feedNotifierProvider).value?.tweets;
  if (tweets != null) {
    for (final t in tweets) {
      if (t.id == tweet.id) return t;
    }
  }
  return ref.read(tweetOverridesProvider)[tweet.id] ?? tweet;
}

class FeedNotifier extends AsyncNotifier<FeedState> {
  List<Tweet> _runDiscoveryPipeline(
    List<Tweet> freshPool,
    List<Tweet> localPool,
    SettingsState settings,
    Map<String, int> playedByUser, {
    int protectedIndex = 0,
    List<Tweet> currentTweets = const [],
  }) {
    final head = currentTweets.take(protectedIndex).toList();
    final headIds = head.map((t) => t.id).toSet();
    final headMedia = head
        .where((t) => t.mediaUrls.isNotEmpty)
        .map((t) => t.mediaUrls.first)
        .toSet();

    // Initialize seen sets using a sliding window of already visible tweets
    final dedupeWindow = currentTweets.length >
            settings.mediaDeduplicationWindow
        ? currentTweets
            .sublist(currentTweets.length - settings.mediaDeduplicationWindow)
        : currentTweets;

    final seenIds = {...headIds, ...dedupeWindow.map((t) => t.id)};
    final seenMediaUrls = {
      ...headMedia,
      ...dedupeWindow
          .where((t) => t.mediaUrls.isNotEmpty)
          .map((t) => t.mediaUrls.first)
    };

    List<Tweet> deduplicate(List<Tweet> pool) {
      return pool.where((t) {
        if (seenIds.contains(t.id)) return false;
        if (t.mediaUrls.isNotEmpty &&
            seenMediaUrls.contains(t.mediaUrls.first)) {
          return false;
        }
        seenIds.add(t.id);
        if (t.mediaUrls.isNotEmpty) seenMediaUrls.add(t.mediaUrls.first);
        return true;
      }).toList();
    }

    final uniqueFresh = deduplicate(freshPool)..shuffle();
    final uniqueLocal = deduplicate(localPool)..shuffle();

    final interleaved = DiscoveryEngine.interleave(
        uniqueFresh, uniqueLocal, settings.freshMixRatio);

    var processed = [...head, ...interleaved];

    if (settings.unseenSubscriptionBoost) {
      processed = DiscoveryEngine.applyUnseenSubscriptionBoost(
        processed,
        playedByUser,
        lookahead: settings.unseenBoostLookahead,
        startIndex: head.length,
      );
    }

    processed = DiscoveryEngine.applySaturation(
      processed,
      threshold: settings.saturationThreshold,
      mediaThreshold: settings.mediaSaturationThreshold,
      windowSize: settings.saturationWindow,
      startIndex: head.length,
      maxSaturationSwaps: settings.maxSaturationSwaps,
      maxPasses: settings.maxSaturationPasses,
    );

    return processed;
  }

  @override
  FutureOr<FeedState> build() async {
    final settings = ref.watch(settingsProvider);

    debugPrint(
        'XFLOW: Building FeedNotifier. fetchStrategy: ${settings.fetchStrategy}');

    try {
      // Stage 1: Immediate local candidate retrieval
      final localPool = await Repository.getCachedMediaCandidates(
        settings.loadBatchSize * settings.dbCandidateMultiplier,
        avoidWatchedContent: settings.avoidWatchedContent,
        filters: settings.filters,
      );
      final localTagged =
          localPool.map((t) => t.copyWith(source: 'Cache')).toList();
      AppLogger.log(
          'XFLOW: Cold start: Retrieved ${localPool.length} local candidates');

      // EARLY MEDIA WARMUP
      if (localTagged.isNotEmpty) {
        final pool = ref.read(playerPoolProvider.notifier);
        for (int i = 0; i < localTagged.length && i < 3; i++) {
          final tweet = localTagged[i];
          if (tweet.isVideo && tweet.mediaUrls.isNotEmpty) {
            pool.warmup(tweet.id, tweet.mediaUrls.first);
          }
        }
      }

      // TRIGGER BACKGROUND SYNC
      if (settings.isInitialized) {
        Future.delayed(Duration.zero, () => _refreshInBackground());
      }

      return FeedState(
        tweets: localTagged,
        cursorBottom: null,
        isRefreshing: localTagged.isEmpty,
      );
    } catch (e, st) {
      debugPrint('XFLOW: Error in build(): $e\n$st');
      return FeedState(tweets: [], isRefreshing: false);
    }
  }

  Future<void> refresh() async {
    final currentState = state.value;
    if (currentState == null || currentState.isRefreshing) return;

    state = AsyncData(currentState.copyWith(isRefreshing: true));
    await _refreshInBackground(resetHead: true);
  }

  Future<void> _refreshInBackground({bool resetHead = false}) async {
    if (!ref.exists(feedNotifierProvider)) return;

    final client = ref.read(twitterClientProvider);
    final settings = ref.read(settingsProvider);

    debugPrint('XFLOW: Background refresh started');

    try {
      // 1. Fetch from API
      final TweetResponse freshResponse;
      if (settings.fetchStrategy == FeedSort.videomixer) {
        freshResponse = await client.fetchVideoMixer(
          count: settings.timelineBatchSize,
          filters: settings.filters,
        );
      } else if (settings.fetchStrategy == FeedSort.algorithmic) {
        freshResponse = await client.fetchAlgorithmicTimeline(
          count: settings.timelineBatchSize,
          filters: settings.filters,
        );
      } else if (settings.fetchStrategy == FeedSort.chronological) {
        freshResponse = await client.fetchChronologicalTimeline(
          count: settings.timelineBatchSize,
          filters: settings.filters,
        );
      } else {
        freshResponse = await client.fetchSubscribedMedia(
          sort: settings.fetchStrategy,
          filters: settings.filters,
          subBatchSize: settings.syncBatchSize,
          loadBatchSize: settings.initialSyncCount,
          cooldownMinutes: settings.cooldownDuration,
          strictSubscriptionsOnly: settings.strictSubscriptionsOnly,
          includeNativeRetweets: settings.includeNativeRetweets,
          useChunkedSubscriptions: settings.useChunkedSubscriptions,
          minFaves: settings.minFavesFilter,
        );
      }

      final freshPool = freshResponse.tweets;
      final freshTagged =
          freshPool.map((t) => t.copyWith(source: 'API')).toList();

      debugPrint(
          'XFLOW: Background refresh returned ${freshPool.length} fresh tweets');

      if (freshPool.isNotEmpty) {
        await Repository.insertCachedMedia(freshPool);
      }

      // 2. Fetch local pool (now including fresh items)
      final localPool = await Repository.getCachedMediaCandidates(
        settings.loadBatchSize * settings.dbCandidateMultiplier,
        avoidWatchedContent: settings.avoidWatchedContent,
        filters: settings.filters,
      );
      final localTagged =
          localPool.map((t) => t.copyWith(source: 'Cache')).toList();

      final currentAsync = ref.read(feedNotifierProvider);
      if (currentAsync.hasValue) {
        final current = currentAsync.value!;

        final playedByUser = settings.unseenSubscriptionBoost
            ? await Repository.getPlayedCountsByUser()
            : const <String, int>{};

        // PROTECT THE ACTIVE VIDEO: If user is at index 0, protect index 0 from being swapped.
        // We protect up to 2 items to ensure the "next" item also doesn't jump unexpectedly.
        final processed = _runDiscoveryPipeline(
          freshTagged,
          localTagged,
          settings,
          playedByUser,
          protectedIndex: resetHead ? 0 : 2,
          currentTweets: resetHead ? [] : current.tweets,
        );

        state = AsyncData(current.copyWith(
          tweets: processed,
          cursorBottom: freshResponse.cursorBottom,
          isRefreshing: false,
        ));
        debugPrint(
            'XFLOW: Feed state updated from background refresh. Total: ${processed.length}');
      }
    } catch (e) {
      debugPrint('XFLOW: Background refresh error: $e');
      final currentAsync = ref.read(feedNotifierProvider);
      if (currentAsync.hasValue) {
        state = AsyncData(currentAsync.value!.copyWith(isRefreshing: false));
      }
    }
  }

  Future<void> fetchMore() async {
    final currentState = state.value;
    if (currentState == null || currentState.isLoadingMore) return;

    final settings = ref.read(settingsProvider);

    state = AsyncData(currentState.copyWith(isLoadingMore: true));

    try {
      final seenIds = state.value!.tweets.map((t) => t.id).toSet();

      // Use dynamic deduplication window from settings
      final dedupeWindow = state.value!.tweets.length >
              settings.mediaDeduplicationWindow
          ? state.value!.tweets.sublist(
              state.value!.tweets.length - settings.mediaDeduplicationWindow)
          : state.value!.tweets;

      final seenMedia = dedupeWindow
          .where((t) => t.mediaUrls.isNotEmpty)
          .map((t) => t.mediaUrls.first)
          .toSet();

      List<Tweet> allNewTweets = [];
      String? currentCursor = state.value!.cursorBottom;
      final seenCursors = <String>{};
      int apiRetries = 0;
      int chunkRotations = 0;
      final maxRetries =
          settings.apiRetryLimit * 2; // Increase limit for robustness

      while (allNewTweets.length < settings.minNewTweetsThreshold &&
          apiRetries < maxRetries &&
          chunkRotations < settings.chunkRotationLimit) {
        // 1. Try to fetch from DB first (refresh pool)
        final dbCandidates = await Repository.getCachedMediaCandidates(
          settings.loadBatchSize * settings.dbCandidateMultiplier,
          avoidWatchedContent: settings.avoidWatchedContent,
          filters: settings.filters,
        );

        var localNew = dbCandidates.where((t) {
          return !seenIds.contains(t.id) &&
              (t.mediaUrls.isEmpty || !seenMedia.contains(t.mediaUrls.first));
        }).toList();

        if (localNew.isNotEmpty) {
          allNewTweets.addAll(localNew);
          if (allNewTweets.length >= settings.minNewTweetsThreshold) break;
        }

        // 2. Hit the API
        final client = ref.read(twitterClientProvider);
        if (currentCursor != null) seenCursors.add(currentCursor);

        try {
          final TweetResponse response;
          if (settings.fetchStrategy == FeedSort.videomixer) {
            response = await client.fetchVideoMixer(
              cursor: currentCursor,
              count: settings.timelineBatchSize,
              filters: settings.filters,
            );
          } else if (settings.fetchStrategy == FeedSort.algorithmic) {
            response = await client.fetchAlgorithmicTimeline(
              cursor: currentCursor,
              count: settings.timelineBatchSize,
              filters: settings.filters,
            );
          } else if (settings.fetchStrategy == FeedSort.chronological) {
            response = await client.fetchChronologicalTimeline(
              cursor: currentCursor,
              count: settings.timelineBatchSize,
              filters: settings.filters,
            );
          } else {
            response = await client.fetchSubscribedMedia(
              cursor: currentCursor,
              sort: settings.fetchStrategy,
              filters: settings.filters,
              subBatchSize: settings.syncBatchSize,
              loadBatchSize: settings.loadBatchSize,
              cooldownMinutes: settings.cooldownDuration,
              strictSubscriptionsOnly: settings.strictSubscriptionsOnly,
              includeNativeRetweets: settings.includeNativeRetweets,
              useChunkedSubscriptions: settings.useChunkedSubscriptions,
              minFaves: settings.minFavesFilter,
              maxQueryLength: settings.maxQueryLength,
              timeoutSeconds: settings.apiTimeoutSeconds,
            );
          }

          final freshUnique = response.tweets.where((t) {
            return !seenIds.contains(t.id) &&
                (t.mediaUrls.isEmpty || !seenMedia.contains(t.mediaUrls.first));
          }).toList();

          if (response.tweets.isNotEmpty) {
            await Repository.insertCachedMedia(response.tweets);
          }

          allNewTweets.addAll(freshUnique);
          apiRetries++;

          // Handle Pagination vs Rotation
          if (response.cursorBottom != null &&
              response.cursorBottom != currentCursor &&
              !seenCursors.contains(response.cursorBottom!)) {
            currentCursor = response.cursorBottom;
          } else {
            // Chunk exhausted or stuck cursor
            AppLogger.log(
                'XFLOW: Chunk exhausted or stuck cursor. Rotating to next subscription chunk.');
            currentCursor = null;
            chunkRotations++;
            await Future.delayed(const Duration(milliseconds: 300));
          }
        } catch (e) {
          AppLogger.log('XFLOW: fetchMore API error: $e');
          apiRetries++;
          await Future.delayed(const Duration(seconds: 1));
        }

        if (allNewTweets.length < settings.minNewTweetsThreshold) {
          await Future.delayed(const Duration(milliseconds: 500));
        }
      }

      if (allNewTweets.isEmpty) {
        state = AsyncData(state.value!.copyWith(isLoadingMore: false));
        return;
      }

      // Shuffle candidates BEFORE taking the batch to improve diversity
      allNewTweets.shuffle();

      final finalNewTweets = allNewTweets
          .take(settings.loadBatchSize)
          .map((t) => t.copyWith(source: t.source ?? 'Mixed'))
          .toList();

      var combined = [...state.value!.tweets, ...finalNewTweets];

      // Apply diversity enforcement to the new tail
      combined = DiscoveryEngine.applySaturation(
        combined,
        threshold: settings.saturationThreshold,
        mediaThreshold: settings.mediaSaturationThreshold,
        windowSize: settings.saturationWindow,
        startIndex: state.value!.tweets.length,
        maxSaturationSwaps: settings.maxSaturationSwaps,
        maxPasses: settings.maxSaturationPasses,
      );

      state = AsyncData(state.value!.copyWith(
        tweets: combined,
        cursorBottom: currentCursor,
        isLoadingMore: false,
      ));
      debugPrint(
          'XFLOW: fetchMore complete. Added ${finalNewTweets.length} tweets. Total: ${combined.length}');
    } catch (e, st) {
      debugPrint('Error fetching more: $e\n$st');
      state = AsyncData(state.value!.copyWith(isLoadingMore: false));
    }
  }

  /// Toggles the like on [tweetId]. Pass [tweet] when the tweet may live
  /// outside the main feed (hashtag or profile feed); without it such a
  /// tweet cannot be found and nothing happens.
  Future<void> toggleLike(String tweetId, {Tweet? tweet}) {
    return _toggleInteraction(
      tweetId,
      fallback: tweet,
      label: 'like',
      apply: (t) {
        final liked = !t.isLiked;
        final count = t.favoriteCount + (liked ? 1 : -1);
        return t.copyWith(
            isLiked: liked, favoriteCount: count >= 0 ? count : 0);
      },
      send: (client, updated) => updated.isLiked
          ? client.favoriteTweet(tweetId)
          : client.unfavoriteTweet(tweetId),
    );
  }

  Future<void> toggleRetweet(String tweetId, {Tweet? tweet}) {
    return _toggleInteraction(
      tweetId,
      fallback: tweet,
      label: 'retweet',
      apply: (t) {
        final retweeted = !t.isRetweeted;
        final count = t.retweetCount + (retweeted ? 1 : -1);
        return t.copyWith(
            isRetweeted: retweeted, retweetCount: count >= 0 ? count : 0);
      },
      send: (client, updated) => updated.isRetweeted
          ? client.retweetTweet(tweetId)
          : client.unretweetTweet(tweetId),
    );
  }

  Future<void> _toggleInteraction(
    String tweetId, {
    required Tweet? fallback,
    required String label,
    required Tweet Function(Tweet) apply,
    required Future<bool> Function(TwitterClient, Tweet) send,
  }) async {
    final currentState = state.value;
    final tweetIndex =
        currentState?.tweets.indexWhere((t) => t.id == tweetId) ?? -1;
    final inFeed = currentState != null && tweetIndex != -1;

    final Tweet original;
    if (inFeed) {
      original = currentState.tweets[tweetIndex];
    } else {
      final known = ref.read(tweetOverridesProvider)[tweetId] ?? fallback;
      if (known == null) return;
      original = known;
    }
    final updated = apply(original);

    // Optimistic UI update
    void write(Tweet value) {
      final latest = state.value;
      final idx = latest?.tweets.indexWhere((t) => t.id == tweetId) ?? -1;
      if (latest != null && idx != -1) {
        final tweets = List<Tweet>.from(latest.tweets);
        tweets[idx] = value;
        state = AsyncData(latest.copyWith(tweets: tweets));
      } else if (!inFeed) {
        ref.read(tweetOverridesProvider.notifier).put(value);
      }
    }

    write(updated);

    final client = ref.read(twitterClientProvider);
    bool success;
    try {
      success = await send(client, updated);
    } catch (e) {
      AppLogger.log('XFLOW: toggle $label for $tweetId threw: $e');
      success = false;
    }

    if (!success) {
      write(original); // Revert on failure
      AppLogger.log('XFLOW: Failed to toggle $label for $tweetId, reverted.');
    } else {
      AppLogger.log('XFLOW: Successfully toggled $label for $tweetId');
    }
  }

  Future<bool> postReply(String tweetId, String text) async {
    final client = ref.read(twitterClientProvider);
    final success = await client.postTweet(text, inReplyToTweetId: tweetId);

    if (success) {
      AppLogger.log('XFLOW: Successfully posted reply to $tweetId');
      // Refresh the tweet detail to show new reply
      // The caller should handle UI refresh
    } else {
      AppLogger.log('XFLOW: Failed to post reply to $tweetId');
    }
    return success;
  }
}

final feedNotifierProvider =
    AsyncNotifierProvider.autoDispose<FeedNotifier, FeedState>(
        FeedNotifier.new);
