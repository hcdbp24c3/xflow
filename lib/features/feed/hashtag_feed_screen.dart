import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/navigation/navigation_provider.dart';
import '../player/widgets/media_container.dart';
import '../../core/models/tweet.dart';
import '../settings/settings_provider.dart';
import '../settings/settings_screen.dart';
import 'hashtag_provider.dart';
import 'widgets/media_pager.dart';
import 'widgets/tweet_text_overlay.dart';
import '../../core/theme/app_theme.dart';
import '../../core/theme/app_widgets.dart';

class HashtagListScreen extends ConsumerWidget {
  const HashtagListScreen({super.key});

  void _showAddHashtagDialog(BuildContext context, WidgetRef ref) {
    final controller = TextEditingController();
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Add Hashtag'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(
            hintText: 'e.g. #nature or nature',
            prefixText: '#',
          ),
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              final tag = controller.text.trim();
              if (tag.isNotEmpty) {
                ref.read(hashtagListProvider.notifier).addHashtag(tag);
              }
              Navigator.pop(context);
            },
            child: const Text('Add'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hashtagsAsync = ref.watch(hashtagListProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Hashtags'),
        actions: [
          IconButton(
            icon: const Icon(Icons.add),
            onPressed: () => _showAddHashtagDialog(context, ref),
          ),
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (c) => const SettingsScreen()),
            ),
          ),
        ],
      ),
      body: hashtagsAsync.when(
        data: (tags) => tags.isEmpty
            ? EmptyState(
                icon: Icons.tag_rounded,
                message: 'No hashtags added yet',
                actions: [
                  FilledButton.tonal(
                    onPressed: () => _showAddHashtagDialog(context, ref),
                    child: const Text('Add your first hashtag'),
                  ),
                ],
              )
            : ListView.builder(
                itemCount: tags.length,
                itemBuilder: (context, index) {
                  final tag = tags[index];
                  return ListTile(
                    leading: Container(
                      width: 36,
                      height: 36,
                      decoration: BoxDecoration(
                        color: AppColors.surfaceHigh,
                        borderRadius: BorderRadius.circular(AppRadius.md),
                      ),
                      child: const Icon(Icons.tag_rounded,
                          size: 18, color: AppColors.accent),
                    ),
                    title: Text(tag,
                        style: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w500)),
                    trailing: IconButton(
                      icon: const Icon(Icons.delete_outline_rounded,
                          size: 20, color: AppColors.textTertiary),
                      onPressed: () => _confirmDelete(context, ref, tag),
                    ),
                    onTap: () {
                      ref.read(navigationProvider.notifier).selectHashtag(tag);
                    },
                  );
                },
              ),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, st) => EmptyState(
          icon: Icons.error_outline_rounded,
          message: 'Error: $e',
        ),
      ),
    );
  }

  void _confirmDelete(BuildContext context, WidgetRef ref, String tag) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Remove Hashtag'),
        content: Text('Are you sure you want to remove $tag?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              ref.read(hashtagListProvider.notifier).removeHashtag(tag);
              Navigator.pop(context);
            },
            child:
                const Text('Remove', style: TextStyle(color: AppColors.danger)),
          ),
        ],
      ),
    );
  }
}

class HashtagMediaFeedScreen extends ConsumerStatefulWidget {
  final String hashtag;

  const HashtagMediaFeedScreen({super.key, required this.hashtag});

  @override
  ConsumerState<HashtagMediaFeedScreen> createState() =>
      _HashtagMediaFeedScreenState();
}

class _HashtagMediaFeedScreenState
    extends ConsumerState<HashtagMediaFeedScreen> {
  final PageController _pageController = PageController();

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  void _onIndexChanged(int index) {
    final feedAsync = ref.read(hashtagMediaProvider(widget.hashtag));
    if (!feedAsync.hasValue) return;
    final tweets = feedAsync.value!.tweets;
    final settings = ref.read(settingsProvider);
    if (index >= tweets.length - settings.lazyLoadThreshold &&
        !feedAsync.value!.isLoadingMore) {
      ref.read(hashtagMediaProvider(widget.hashtag).notifier).fetchMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    final feedAsync = ref.watch(hashtagMediaProvider(widget.hashtag));

    return Scaffold(
      backgroundColor: AppColors.background,
      body: MediaScaffoldBody(
        child: Stack(
          children: [
            Positioned.fill(
              child: feedAsync.when(
                data: (state) {
                  final tweets = state.tweets;
                  if (tweets.isEmpty) {
                    return const EmptyState(
                      icon: Icons.tag_rounded,
                      message: 'No media found',
                    );
                  }

                  return Stack(
                    children: [
                      MediaPager(
                        tweets: tweets,
                        controller: _pageController,
                        // The hashtag feed never precached images.
                        precacheImages: false,
                        onIndexChanged: _onIndexChanged,
                        itemBuilder:
                            (context, tweet, isVisible, onPlaybackError) {
                          return HashtagFeedItem(
                            tweet: tweet,
                            isVisible: isVisible,
                            onPlaybackError: onPlaybackError,
                          );
                        },
                      ),
                      if (state.isLoadingMore || state.isRefreshing)
                        const TopProgressLine(),
                    ],
                  );
                },
                loading: () => const EmptyState(loading: true, message: ''),
                error: (e, st) => EmptyState(
                  icon: Icons.cloud_off_rounded,
                  message: 'Error: $e',
                  actions: [
                    FilledButton.tonal(
                      onPressed: () => ref
                          .read(hashtagMediaProvider(widget.hashtag).notifier)
                          .refresh(),
                      child: const Text('Retry'),
                    ),
                  ],
                ),
              ),
            ),
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: FeedTopBar(
                leading: FloatingIconButton(
                  icon: Icons.arrow_back_rounded,
                  tooltip: 'Back',
                  onTap: () => ref.read(navigationProvider.notifier).back(),
                ),
                title: Text(widget.hashtag),
                actions: [
                  FloatingIconButton(
                    icon: Icons.refresh_rounded,
                    tooltip: 'Refresh',
                    onTap: () {
                      if (_pageController.hasClients) {
                        _pageController.jumpToPage(0);
                      }
                      ref
                          .read(hashtagMediaProvider(widget.hashtag).notifier)
                          .refresh();
                    },
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
}

class HashtagFeedItem extends StatelessWidget {
  final Tweet tweet;
  final bool isVisible;
  final bool autoFullscreen;
  final VoidCallback? onPlaybackError;

  const HashtagFeedItem({
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
