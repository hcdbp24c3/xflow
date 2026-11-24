import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/navigation/navigation_provider.dart';
import 'profile_provider.dart';
import '../player/widgets/media_container.dart';
import '../../core/models/tweet.dart';
import '../feed/widgets/media_pager.dart';
import '../feed/widgets/tweet_text_overlay.dart';
import '../settings/settings_provider.dart';
import '../settings/settings_screen.dart';

class UserMediaFeedScreen extends ConsumerStatefulWidget {
  final String screenName;
  final int initialIndex;
  final String? initialTweetId;

  const UserMediaFeedScreen({
    super.key,
    required this.screenName,
    required this.initialIndex,
    this.initialTweetId,
  });

  @override
  ConsumerState<UserMediaFeedScreen> createState() =>
      _UserMediaFeedScreenState();
}

class _UserMediaFeedScreenState extends ConsumerState<UserMediaFeedScreen> {
  late PageController _pageController;
  bool _initialized = false;

  @override
  void initState() {
    super.initState();
    _pageController = PageController(initialPage: widget.initialIndex);
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  void _onIndexChanged(int index) {
    final feedAsync = ref.read(userMediaNotifierProvider(widget.screenName));
    if (!feedAsync.hasValue) return;
    final tweets = feedAsync.value!.tweets;
    final settings = ref.read(settingsProvider);
    if (index >= tweets.length - settings.lazyLoadThreshold &&
        !feedAsync.value!.isLoadingMore) {
      ref
          .read(userMediaNotifierProvider(widget.screenName).notifier)
          .fetchMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    final feedAsync = ref.watch(userMediaNotifierProvider(widget.screenName));

    // Listen for data arrival to handle initial index adjustment if list shifted
    ref.listen(userMediaNotifierProvider(widget.screenName), (prev, next) {
      if (next.hasValue && !_initialized) {
        final tweets = next.value!.tweets;
        int targetIndex = widget.initialIndex;

        if (widget.initialTweetId != null) {
          final foundIndex =
              tweets.indexWhere((t) => t.id == widget.initialTweetId);
          if (foundIndex != -1) {
            targetIndex = foundIndex;
          }
        }

        // The pager picks the new page up from the jump itself.
        if (targetIndex < tweets.length && _pageController.hasClients) {
          _pageController.jumpToPage(targetIndex);
        }
        _initialized = true;
      }
    });
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: Colors.white),
          onPressed: () => ref.read(navigationProvider.notifier).back(),
        ),
        title: Text('@${widget.screenName}',
            style: const TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
                color: Colors.white)),
        centerTitle: true,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh, color: Colors.white),
            onPressed: () => ref
                .read(userMediaNotifierProvider(widget.screenName).notifier)
                .refresh(),
          ),
          IconButton(
            icon: const Icon(Icons.settings_outlined, color: Colors.white),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (c) => const SettingsScreen()),
            ),
          ),
        ],
      ),
      body: feedAsync.when(
        data: (state) {
          final tweets = state.tweets;
          if (tweets.isEmpty) {
            if (state.isRefreshing) {
              return const Center(child: CircularProgressIndicator());
            }
            return Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('No media found.',
                      style: TextStyle(color: Colors.white70)),
                  const SizedBox(height: 16),
                  ElevatedButton(
                    onPressed: () => ref
                        .read(userMediaNotifierProvider(widget.screenName)
                            .notifier)
                        .refresh(),
                    child: const Text('Refresh'),
                  ),
                ],
              ),
            );
          }

          return Stack(
            children: [
              MediaPager(
                tweets: tweets,
                controller: _pageController,
                initialIndex: widget.initialIndex,
                onIndexChanged: _onIndexChanged,
                itemBuilder: (context, tweet, isVisible, onPlaybackError) {
                  return UserMediaFeedItem(
                    tweet: tweet,
                    isVisible: isVisible,
                    onPlaybackError: onPlaybackError,
                  );
                },
              ),
              if (state.isRefreshing)
                const Positioned(
                  top: 0,
                  left: 0,
                  right: 0,
                  child: LinearProgressIndicator(
                    minHeight: 2,
                    backgroundColor: Colors.transparent,
                    valueColor: AlwaysStoppedAnimation<Color>(Colors.blue),
                  ),
                ),
            ],
          );
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, st) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Error: $e', style: const TextStyle(color: Colors.white70)),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: () => ref
                    .read(userMediaNotifierProvider(widget.screenName).notifier)
                    .refresh(),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
class UserMediaFeedItem extends StatelessWidget {
  final Tweet tweet;
  final bool isVisible;
  final bool autoFullscreen;
  final VoidCallback? onPlaybackError;

  const UserMediaFeedItem({
    super.key,
    required this.tweet,
    required this.isVisible,
    this.autoFullscreen = false,
    this.onPlaybackError,
  });

  @override
  Widget build(BuildContext context) {
    return TiktokMediaContainer(
      tweet: tweet,
      isVisible: isVisible,
      autoFullscreen: autoFullscreen,
      overlayBuilder: (context, onFullscreen, isFullscreen) => TweetTextOverlay(
        tweet: tweet,
        onFullscreen: onFullscreen,
        isFullscreen: isFullscreen,
      ),
      onPlaybackError: onPlaybackError,
    );
  }
}
