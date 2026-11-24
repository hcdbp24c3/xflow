import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'feed_provider.dart';
import '../player/widgets/media_container.dart';
import '../../core/models/tweet.dart';
import '../../core/database/repository.dart';
import '../settings/settings_screen.dart';
import '../settings/settings_provider.dart';
import '../auth/login_screen.dart';
import 'bookmarks_screen.dart';
import '../../core/client/account_provider.dart';
import '../../core/navigation/navigation_provider.dart';
import '../../core/theme/app_theme.dart';
import '../../core/theme/app_widgets.dart';
import 'widgets/media_pager.dart';
import 'widgets/tweet_text_overlay.dart';

class TiktokFeedScreen extends ConsumerStatefulWidget {
  const TiktokFeedScreen({super.key});

  @override
  ConsumerState<TiktokFeedScreen> createState() => _TiktokFeedScreenState();
}

class _TiktokFeedScreenState extends ConsumerState<TiktokFeedScreen> {
  final PageController _pageController = PageController();

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  /// Runs once per landed page, not on every scroll tick.
  void _onIndexChanged(int index) {
    final notifier = ref.read(feedNotifierProvider.notifier);
    notifier.reportViewIndex(index);

    final feedAsync = ref.read(feedNotifierProvider);
    if (!feedAsync.hasValue) return;
    final tweets = feedAsync.value!.tweets;

    if (index < tweets.length) {
      Repository.markMediaAsPlayed(tweets[index].id);
    }

    final settings = ref.read(settingsProvider);
    if (index >= tweets.length - settings.lazyLoadThreshold &&
        !feedAsync.value!.isRefreshing) {
      notifier.fetchMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    final account = ref.watch(accountProvider);

    return Scaffold(
      backgroundColor: AppColors.background,
      body: MediaScaffoldBody(
        child: Stack(
          children: [
            Positioned.fill(child: _buildMediaFeed()),
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: FeedTopBar(
                leading: Padding(
                  padding: const EdgeInsets.only(left: 8),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      SvgPicture.asset(
                        'assets/app_icon.svg',
                        height: 22,
                        width: 22,
                      ),
                      const SizedBox(width: 8),
                      const Text(
                        "XFlow",
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 18,
                          fontWeight: FontWeight.w800,
                          letterSpacing: -0.4,
                          shadows: [
                            Shadow(blurRadius: 6, color: Colors.black54),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                actions: [
                  if (account == null)
                    Padding(
                      padding: const EdgeInsets.only(right: 4),
                      child: PressableScale(
                        onTap: () => _goToLogin(),
                        child: Container(
                          height: 34,
                          padding: const EdgeInsets.symmetric(horizontal: 14),
                          alignment: Alignment.center,
                          decoration: BoxDecoration(
                            color: Colors.white,
                            borderRadius: BorderRadius.circular(AppRadius.xl),
                          ),
                          child: const Text(
                            'Login',
                            style: TextStyle(
                              color: Colors.black,
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                      ),
                    ),
                  FloatingIconButton(
                    icon: Icons.refresh_rounded,
                    tooltip: 'Refresh',
                    onTap: () {
                      if (_pageController.hasClients) {
                        _pageController.jumpToPage(0);
                      }
                      ref
                          .read(feedNotifierProvider.notifier)
                          .reportViewIndex(0);
                      ref.read(feedNotifierProvider.notifier).refresh();
                    },
                  ),
                  FloatingIconButton(
                    icon: Icons.bookmark_border_rounded,
                    tooltip: 'Bookmarks',
                    onTap: () => Navigator.push(
                      context,
                      MaterialPageRoute(
                          builder: (c) => const BookmarksScreen()),
                    ),
                  ),
                  FloatingIconButton(
                    icon: Icons.settings_outlined,
                    tooltip: 'Settings',
                    onTap: () => Navigator.push(
                      context,
                      MaterialPageRoute(builder: (c) => const SettingsScreen()),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMediaFeed() {
    final feedAsync = ref.watch(feedNotifierProvider);
    final nav = ref.watch(navigationProvider);
    final isScreenActive = nav.selectedUser == null &&
        nav.selectedHashtag == null &&
        nav.currentTab == MainTab.media;

    return feedAsync.when(
      data: (state) {
        final tweets = state.tweets;
        if (tweets.isEmpty) {
          if (state.isRefreshing) {
            return const EmptyState(
              loading: true,
              message: 'Fetching latest media...',
            );
          }
          return _buildNoItemsState();
        }
        return Stack(
          children: [
            MediaPager(
              tweets: tweets,
              controller: _pageController,
              isActive: isScreenActive,
              onIndexChanged: _onIndexChanged,
              itemBuilder: (context, tweet, isVisible, onPlaybackError) {
                return TiktokFeedItem(
                  tweet: tweet,
                  isVisible: isVisible,
                  onPlaybackError: onPlaybackError,
                );
              },
            ),
            if (state.isRefreshing) const TopProgressLine(),
          ],
        );
      },
      loading: () => const Center(
        child: SizedBox(
          width: 26,
          height: 26,
          child: CircularProgressIndicator(strokeWidth: 2.5),
        ),
      ),
      error: (e, st) => _buildErrorState(e),
    );
  }

  Widget _buildNoItemsState() {
    return EmptyState(
      icon: Icons.play_circle_outline_rounded,
      message: 'No media found.',
      actions: [
        FilledButton.tonal(
          onPressed: () => _goToLogin(),
          child: const Text('Login to X'),
        ),
      ],
    );
  }

  Widget _buildErrorState(Object e) {
    return EmptyState(
      icon: Icons.cloud_off_rounded,
      message: 'Error: $e',
      actions: [
        FilledButton.tonal(
          onPressed: () => _goToLogin(),
          child: const Text('Login to X'),
        ),
        TextButton(
          onPressed: () => ref.invalidate(feedNotifierProvider),
          child: const Text('Retry'),
        ),
      ],
    );
  }

  Future<void> _goToLogin() async {
    final success = await Navigator.push(
      context,
      MaterialPageRoute(builder: (c) => const LoginScreen()),
    );
    if (success == true) {
      ref.invalidate(feedNotifierProvider);
    }
  }
}

class TiktokFeedItem extends ConsumerWidget {
  final Tweet tweet;
  final bool isVisible;
  final bool autoFullscreen;
  final VoidCallback? onPlaybackError;

  const TiktokFeedItem({
    super.key,
    required this.tweet,
    required this.isVisible,
    this.autoFullscreen = false,
    this.onPlaybackError,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(settingsProvider);

    return RepaintBoundary(
      child: Stack(
        children: [
          TiktokMediaContainer(
            tweet: tweet,
            isVisible: isVisible,
            autoFullscreen: autoFullscreen,
            overlayBuilder: (context, onFullscreen, isFullscreen) =>
                TweetTextOverlay(
              tweet: tweet,
              onFullscreen: onFullscreen,
              isFullscreen: isFullscreen,
            ),
            onPlaybackError: onPlaybackError,
          ),
          if (settings.showDebugInfo) DiscoveryDebugOverlay(tweet: tweet),
        ],
      ),
    );
  }
}

class DiscoveryDebugOverlay extends StatelessWidget {
  final Tweet tweet;
  const DiscoveryDebugOverlay({super.key, required this.tweet});

  Future<(int, int)> _fetchDebugStats() async {
    final mediaCount = await Repository.getMediaPlayedCount(tweet.id);
    final userCount = await Repository.getUserPlayedCount(tweet.userHandle);
    return (mediaCount, userCount);
  }

  @override
  Widget build(BuildContext context) {
    return Positioned(
      top: MediaQuery.paddingOf(context).top + 60,
      left: 10,
      child: FutureBuilder<(int, int)>(
        future: _fetchDebugStats(),
        builder: (context, snapshot) {
          final stats = snapshot.data ?? (0, 0);
          return Card(
            color: Colors.black.withValues(alpha: 0.6),
            child: Padding(
              padding: const EdgeInsets.all(8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  _debugLine('TYPE', tweet.isVideo ? 'VIDEO' : 'IMAGE'),
                  _debugLine('SOURCE', tweet.source ?? 'UNKNOWN'),
                  _debugLine('ID', tweet.id.substring(tweet.id.length - 8)),
                  _debugLine('MEDIA', '${tweet.mediaUrls.length} urls'),
                  _debugLine('SEEN', '${stats.$1} times'),
                  _debugLine('ACCT_SEEN', '${stats.$2} times'),
                  if (tweet.createdAt != null)
                    _debugLine(
                        'TS',
                        tweet.createdAt!
                            .toLocal()
                            .toString()
                            .split(' ')
                            .last
                            .split('.')
                            .first),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _debugLine(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 1),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            '$label: ',
            style: const TextStyle(
              color: AppColors.success,
              fontSize: 10,
              fontWeight: FontWeight.bold,
              fontFamily: 'monospace',
            ),
          ),
          Text(
            value,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 10,
              fontFamily: 'monospace',
            ),
          ),
        ],
      ),
    );
  }
}
