import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/models/tweet.dart';
import '../../player/player_pool_provider.dart';
import '../../settings/settings_provider.dart';
import 'feed_page_physics.dart';

typedef MediaPagerItemBuilder = Widget Function(
  BuildContext context,
  Tweet tweet,
  bool isVisible,
  VoidCallback onPlaybackError,
);

/// The vertical, one-item-per-page media list shared by the main feed, the
/// hashtag feed and the profile feed.
///
/// It decides which item is "current" and keeps the player pool warm around
/// it. Both are done when a swipe has landed, not while the page is still
/// moving: creating and disposing native players mid-animation is what made
/// swiping stutter, and switching the current item at the halfway point made
/// the next video start (and the previous one stop) under the user's finger.
class MediaPager extends ConsumerStatefulWidget {
  final List<Tweet> tweets;
  final PageController controller;

  /// False while another screen covers this feed. An inactive pager plays
  /// nothing and leaves the shared player pool alone.
  final bool isActive;
  final int initialIndex;

  /// Called once per landed page change.
  final ValueChanged<int>? onIndexChanged;
  final MediaPagerItemBuilder itemBuilder;
  final bool precacheImages;

  const MediaPager({
    super.key,
    required this.tweets,
    required this.controller,
    required this.itemBuilder,
    this.isActive = true,
    this.initialIndex = 0,
    this.onIndexChanged,
    this.precacheImages = true,
  });

  @override
  ConsumerState<MediaPager> createState() => MediaPagerState();
}

class MediaPagerState extends ConsumerState<MediaPager> {
  /// How close to a whole page the scroll must be to count as landed.
  static const double _landedEpsilon = 0.02;

  /// Pool work waits this long after landing so it never shares a frame
  /// with the end of the snap animation.
  static const Duration _poolDelay = Duration(milliseconds: 140);

  late int _currentIndex;
  int _poolCenter = 0;
  bool _pausedByScroll = false;
  Timer? _poolTimer;

  List<Key> _keys = const [];
  Map<Key, int> _indexByKey = const {};

  int get currentIndex => _currentIndex;

  @override
  void initState() {
    super.initState();
    _currentIndex = widget.initialIndex;
    _poolCenter = _currentIndex;
    _rebuildKeys();
    widget.controller.addListener(_onScroll);
    WidgetsBinding.instance.addPostFrameCallback((_) => _managePool());
  }

  @override
  void didUpdateWidget(MediaPager oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onScroll);
      widget.controller.addListener(_onScroll);
    }
    final tweetsChanged = !identical(oldWidget.tweets, widget.tweets);
    if (tweetsChanged) _rebuildKeys();
    if (tweetsChanged || (!oldWidget.isActive && widget.isActive)) {
      // Not during build: the pool is a provider.
      WidgetsBinding.instance.addPostFrameCallback((_) => _managePool());
    }
  }

  @override
  void dispose() {
    _poolTimer?.cancel();
    widget.controller.removeListener(_onScroll);
    super.dispose();
  }

  void _rebuildKeys() {
    final seen = <String>{};
    final keys = <Key>[];
    final indexByKey = <Key, int>{};
    for (var i = 0; i < widget.tweets.length; i++) {
      final id = widget.tweets[i].id;
      // A feed can, rarely, contain the same tweet twice; keys must not.
      final key = ValueKey<String>(seen.add(id) ? id : '$id#$i');
      keys.add(key);
      indexByKey[key] = i;
    }
    _keys = keys;
    _indexByKey = indexByKey;
  }

  double? get _page {
    final controller = widget.controller;
    if (!controller.hasClients) return null;
    final position = controller.position;
    if (!position.hasPixels || !position.hasViewportDimension) return null;
    return controller.page;
  }

  /// Runs on every scroll tick, so it only does the two cheap things that
  /// cannot wait for landing.
  void _onScroll() {
    final page = _page;
    if (page == null) return;

    // The current item is more than half off screen: silence it now rather
    // than letting it play until the next page lands.
    if (!_pausedByScroll && (page - _currentIndex).abs() >= 0.5) {
      final player = _playerAt(_currentIndex);
      if (player != null && player.player.state.playing) {
        player.player.pause();
        _pausedByScroll = true;
      }
    }

    // Swiped several pages without ever landing: the warm window is behind,
    // catch up now so the user does not arrive at an unprepared page.
    if ((page - _poolCenter).abs() >= 2) {
      _managePool(center: page.round());
    }
  }

  bool _onScrollNotification(ScrollNotification notification) {
    // Depth 0 is this pager; deeper ones are image galleries inside an item.
    if (notification.depth != 0) return false;
    if (notification is ScrollEndNotification ||
        (notification is ScrollUpdateNotification &&
            notification.dragDetails == null)) {
      _commitIfLanded();
    }
    return false;
  }

  void _commitIfLanded() {
    final page = _page;
    if (page == null) return;
    final nearest = page.round();
    if ((page - nearest).abs() > _landedEpsilon) return;
    _commit(nearest);
  }

  void _commit(int index) {
    if (widget.tweets.isEmpty) return;
    index = index.clamp(0, widget.tweets.length - 1);

    if (index == _currentIndex) {
      // Dragged away and came back to the same item.
      if (_pausedByScroll) {
        _pausedByScroll = false;
        if (widget.isActive) _playerAt(index)?.player.play();
      }
      return;
    }

    _pausedByScroll = false;
    setState(() => _currentIndex = index);
    widget.onIndexChanged?.call(index);

    _poolTimer?.cancel();
    _poolTimer = Timer(_poolDelay, _managePool);
  }

  PlayerInstance? _playerAt(int index) {
    if (index < 0 || index >= widget.tweets.length) return null;
    return ref.read(playerPoolProvider)[widget.tweets[index].id];
  }

  void _managePool({int? center}) {
    if (!mounted || !widget.isActive) return;
    try {
      final tweets = widget.tweets;
      final around = center ?? _currentIndex;
      final pool = ref.read(playerPoolProvider.notifier);
      final activeIds = <String>{};

      // Always keep the current item, even during a catch-up further away.
      if (_currentIndex >= 0 && _currentIndex < tweets.length) {
        activeIds.add(tweets[_currentIndex].id);
      }

      for (int i = around - 1; i <= around + 3; i++) {
        if (i < 0 || i >= tweets.length) continue;
        final tweet = tweets[i];
        activeIds.add(tweet.id);

        if (tweet.isVideo && tweet.mediaUrls.isNotEmpty) {
          pool.warmup(tweet.id, tweet.mediaUrls.first);
        } else if (widget.precacheImages && tweet.mediaUrls.isNotEmpty) {
          for (final url in tweet.mediaUrls) {
            precacheImage(NetworkImage(url), context);
          }
        }
      }
      pool.cleanupExcept(activeIds);
      _poolCenter = around;
    } catch (e) {
      debugPrint('XFLOW: Error managing player pool: $e');
    }
  }

  void _handlePlaybackError(int index) {
    if (index != _currentIndex || !mounted) return;
    final settings = ref.read(settingsProvider);
    Future.delayed(Duration(seconds: settings.autoSkipDelaySeconds), () {
      if (mounted && _currentIndex == index && widget.controller.hasClients) {
        widget.controller.nextPage(
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOutCubic,
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final tweets = widget.tweets;
    return NotificationListener<ScrollNotification>(
      onNotification: _onScrollNotification,
      child: PageView.builder(
        controller: widget.controller,
        scrollDirection: Axis.vertical,
        // Snapping is done by FeedPageScrollPhysics itself.
        pageSnapping: false,
        physics: const FeedPageScrollPhysics(),
        itemCount: tweets.length,
        findChildIndexCallback: (key) => _indexByKey[key],
        itemBuilder: (context, index) {
          return KeyedSubtree(
            key: _keys[index],
            child: widget.itemBuilder(
              context,
              tweets[index],
              index == _currentIndex && widget.isActive,
              () => _handlePlaybackError(index),
            ),
          );
        },
      ),
    );
  }
}
