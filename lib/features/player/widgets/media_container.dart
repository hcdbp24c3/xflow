import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/models/tweet.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/utils/media_cache_manager.dart';
import '../../../core/utils/app_logger.dart';
import '../../feed/widgets/text_tweet_card.dart';
import '../player_pool_provider.dart';
import '../tap_sequence_detector.dart';
import '../../settings/settings_provider.dart';
import '../../feed/feed_provider.dart';

class TiktokMediaContainer extends ConsumerStatefulWidget {
  final Tweet tweet;
  final bool isVisible;
  final bool autoFullscreen;
  final Widget Function(
          BuildContext context, VoidCallback? onFullscreen, bool isFullscreen)?
      overlayBuilder;
  final VoidCallback? onPlaybackError;

  const TiktokMediaContainer({
    super.key,
    required this.tweet,
    required this.isVisible,
    this.autoFullscreen = false,
    this.overlayBuilder,
    this.onPlaybackError,
  });

  @override
  ConsumerState<TiktokMediaContainer> createState() =>
      _TiktokMediaContainerState();
}

class _TiktokMediaContainerState extends ConsumerState<TiktokMediaContainer>
    with SingleTickerProviderStateMixin {
  final GlobalKey<VideoState> _videoKey = GlobalKey<VideoState>();
  int _imageIndex = 0;
  int _retryCount = 0;
  bool _isAutoFullscreenDone = false;
  PlayerInstance? _boundInstance;
  StreamSubscription? _errorSubscription;
  StreamSubscription? _completedSubscription;

  // Double-tap like state
  static const double _heartSize = 96;
  Offset _likePosition = Offset.zero;
  double _likeAngle = 0;
  late final AnimationController _likeAnimController;
  late final Animation<double> _likeScaleAnim;
  late final Animation<double> _likeOpacityAnim;
  late final Animation<double> _likeRiseAnim;

  // Raw tap detection (no GestureDetector — avoids gesture arena conflict)
  final TapSequenceDetector _taps = TapSequenceDetector();
  // Play state before the first tap of a sequence, restored on double tap.
  bool? _playingBeforeTap;

  // Play/pause state
  bool _isPlaying = false;
  StreamSubscription<bool>? _playingSubscription;
  // True only when the user paused by tapping; drives the centre play icon,
  // so it does not flash while loading or while a swipe pauses the video.
  bool _userPaused = false;
  bool _hasAutoPlayed = false;

  @override
  void initState() {
    super.initState();
    _likeAnimController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 700),
    );
    _likeScaleAnim = TweenSequence<double>([
      TweenSequenceItem(
        tween: Tween(begin: 0.3, end: 1.15)
            .chain(CurveTween(curve: Curves.easeOutBack)),
        weight: 28,
      ),
      TweenSequenceItem(
        tween: Tween(begin: 1.15, end: 1.0)
            .chain(CurveTween(curve: Curves.easeOut)),
        weight: 14,
      ),
      TweenSequenceItem(tween: ConstantTween(1.0), weight: 28),
      TweenSequenceItem(
        tween: Tween(begin: 1.0, end: 1.25)
            .chain(CurveTween(curve: Curves.easeIn)),
        weight: 30,
      ),
    ]).animate(_likeAnimController);
    _likeOpacityAnim = Tween<double>(begin: 1.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _likeAnimController,
        curve: const Interval(0.62, 1.0, curve: Curves.easeIn),
      ),
    );
    _likeRiseAnim = Tween<double>(begin: 0.0, end: -36.0).animate(
      CurvedAnimation(
        parent: _likeAnimController,
        curve: const Interval(0.45, 1.0, curve: Curves.easeIn),
      ),
    );
  }

  @override
  void didUpdateWidget(TiktokMediaContainer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.isVisible && widget.isVisible) {
      _isAutoFullscreenDone = false;
    }
    if (oldWidget.isVisible && !widget.isVisible) {
      _taps.reset();
      _playingBeforeTap = null;
      _userPaused = false;
    }
    // Reset auto-play flag when tweet changes
    if (oldWidget.tweet.id != widget.tweet.id) {
      _hasAutoPlayed = false;
    }
    // Move play/pause out of build() to avoid side effects during rebuild
    if (oldWidget.isVisible != widget.isVisible) {
      final pool = ref.read(playerPoolProvider);
      final instance = pool[widget.tweet.id];
      if (instance != null) {
        if (widget.isVisible) {
          instance.player.play();
        } else {
          instance.player.pause();
        }
      }
    }
  }

  /// Listens to [instance]'s events. If the pool hands this tweet a new
  /// player (the old one was disposed and warmed up again), the listeners
  /// move over to it instead of staying on the dead one.
  void _bindPlayer(PlayerInstance instance) {
    if (identical(_boundInstance, instance)) return;
    _unbindPlayer();
    _boundInstance = instance;
    _isPlaying = instance.player.state.playing;
    _errorSubscription = instance.player.stream.error.listen(_handleError);
    _completedSubscription =
        instance.player.stream.completed.listen((completed) {
      if (completed) _handleCompleted();
    });
    _playingSubscription = instance.player.stream.playing.listen((playing) {
      if (!mounted || playing == _isPlaying) return;
      setState(() {
        _isPlaying = playing;
        if (playing) _userPaused = false;
      });
    });
  }

  void _unbindPlayer() {
    _errorSubscription?.cancel();
    _completedSubscription?.cancel();
    _playingSubscription?.cancel();
    _errorSubscription = null;
    _completedSubscription = null;
    _playingSubscription = null;
    _boundInstance = null;
    _hasAutoPlayed = false;
  }

  @override
  void dispose() {
    _errorSubscription?.cancel();
    _completedSubscription?.cancel();
    _playingSubscription?.cancel();
    _likeAnimController.dispose();
    super.dispose();
  }

  // --- Raw tap detection (no GestureDetector) ---
  // Uses Listener to avoid gesture arena competition with the page scroll.
  // Overlay buttons sit above the Listener and absorb their own touches, so
  // a touch that reaches here is on the media itself. Swipes also reach
  // here; TapSequenceDetector rejects them by distance and duration.

  void _handlePointerDown(PointerDownEvent event) {
    _taps.pointerDown(event.pointer, event.localPosition, event.timeStamp);
  }

  void _handlePointerMove(PointerMoveEvent event) {
    _taps.pointerMove(event.pointer, event.localPosition);
  }

  void _handlePointerCancel(PointerCancelEvent event) {
    _taps.pointerCancel(event.pointer);
  }

  void _handlePointerUp(PointerUpEvent event) {
    final tap =
        _taps.pointerUp(event.pointer, event.localPosition, event.timeStamp);
    // A touch on a page that is still sliding in is not for this item.
    if (!widget.isVisible) return;

    switch (tap.kind) {
      case TapKind.none:
        return;
      case TapKind.single:
        _togglePlayPause();
      case TapKind.multi:
        if (tap.isFirstMulti) _undoPlayPauseToggle();
        _handleDoubleTapAt(tap.position);
    }
  }

  /// Instant play/pause on the first tap — no waiting for a second tap.
  void _togglePlayPause() {
    if (!widget.tweet.isVideo) return;
    final instance = ref.read(playerPoolProvider)[widget.tweet.id];
    if (instance == null) return;
    final wasPlaying = instance.player.state.playing;
    _playingBeforeTap = wasPlaying;
    if (wasPlaying) {
      instance.player.pause();
    } else {
      instance.player.play();
    }
    if (mounted) setState(() => _userPaused = wasPlaying);
  }

  /// The first tap of a double tap already toggled playback; put it back so
  /// liking a video never pauses it.
  void _undoPlayPauseToggle() {
    final before = _playingBeforeTap;
    _playingBeforeTap = null;
    if (before == null || !widget.tweet.isVideo) return;
    final instance = ref.read(playerPoolProvider)[widget.tweet.id];
    if (instance == null) return;
    if (before) {
      instance.player.play();
    } else {
      instance.player.pause();
    }
    if (mounted) setState(() => _userPaused = !before);
  }

  void _handleDoubleTapAt(Offset localPosition) {
    // Only like if NOT already liked (but always show hearts — TikTok style).
    // The tweet is passed along because hashtag and profile feeds show
    // tweets that are not in the main feed's list.
    final live = readLiveTweet(ref, widget.tweet);
    if (!live.isLiked) {
      ref
          .read(feedNotifierProvider.notifier)
          .toggleLike(widget.tweet.id, tweet: widget.tweet);
    }
    HapticFeedback.lightImpact();

    setState(() {
      _likePosition = localPosition;
      _likeAngle = (math.Random().nextDouble() - 0.5) * 0.5;
    });
    _likeAnimController.forward(from: 0);
  }

  void _handleCompleted() async {
    if (!mounted || !widget.isVisible) return;

    // Exit fullscreen if active when video completes
    final state = _videoKey.currentState;
    if (state != null && state.isFullscreen()) {
      await state.exitFullscreen();
    }

    final settings = ref.read(settingsProvider);
    switch (settings.videoEndAction) {
      case VideoEndAction.pause:
        // Already stopped at the end
        break;
      case VideoEndAction.replay:
        final pool = ref.read(playerPoolProvider);
        final instance = pool[widget.tweet.id];
        instance?.player.seek(Duration.zero);
        instance?.player.play();
        break;
      case VideoEndAction.playNext:
        widget.onPlaybackError
            ?.call(); // Re-use the same callback for auto-advance
        break;
    }
  }

  void _handleError(dynamic error) async {
    final settings = ref.read(settingsProvider);

    // Exit fullscreen on error
    final state = _videoKey.currentState;
    if (state != null && state.isFullscreen()) {
      await state.exitFullscreen();
    }

    if (_retryCount < settings.playbackRetryLimit) {
      _retryCount++;
      AppLogger.log(
          'XFLOW: Video playback error. Retrying ($_retryCount/${settings.playbackRetryLimit})... Error: $error');

      final pool = ref.read(playerPoolProvider);
      final instance = pool[widget.tweet.id];
      if (instance != null) {
        // Re-open media to retry
        instance.player
            .open(Media(widget.tweet.mediaUrls.first), play: widget.isVisible);
      }
    } else {
      AppLogger.log('XFLOW: Video playback failed after retry. Skipping item.');
      widget.onPlaybackError?.call();
    }
  }

  /// The heart that pops where the user double-tapped.
  Widget _buildLikeHeart() {
    return AnimatedBuilder(
      animation: _likeAnimController,
      builder: (context, child) {
        if (!_likeAnimController.isAnimating) return const SizedBox.shrink();
        return Positioned(
          left: _likePosition.dx - _heartSize / 2,
          top: _likePosition.dy - _heartSize / 2 + _likeRiseAnim.value,
          child: IgnorePointer(
            child: Opacity(
              opacity: _likeOpacityAnim.value,
              child: Transform.rotate(
                angle: _likeAngle,
                child: Transform.scale(
                  scale: _likeScaleAnim.value,
                  child: child,
                ),
              ),
            ),
          ),
        );
      },
      child: const Icon(
        Icons.favorite_rounded,
        color: AppColors.like,
        size: _heartSize,
        shadows: [Shadow(blurRadius: 18, color: Colors.black38)],
      ),
    );
  }

  Widget _buildThumbnail() {
    final url = widget.tweet.thumbnailUrl;
    if (url == null) return const SizedBox.shrink();
    return Positioned.fill(
      child: CachedNetworkImage(
        imageUrl: url,
        fit: BoxFit.contain,
        fadeInDuration: Duration.zero,
        placeholder: (context, url) => const SizedBox.shrink(),
        errorWidget: (context, url, error) => const SizedBox.shrink(),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (widget.tweet.mediaUrls.isEmpty) {
      return Stack(
        children: [
          TextTweetCard(text: widget.tweet.text),
          if (widget.overlayBuilder != null)
            Positioned.fill(
                child: widget.overlayBuilder!(context, null, false)),
        ],
      );
    }

    if (!widget.tweet.isVideo) {
      return Stack(
        children: [
          Listener(
            behavior: HitTestBehavior.opaque,
            onPointerDown: _handlePointerDown,
            onPointerMove: _handlePointerMove,
            onPointerUp: _handlePointerUp,
            onPointerCancel: _handlePointerCancel,
            child: _buildImageGallery(),
          ),
          _buildLikeHeart(),
          if (widget.tweet.mediaUrls.length > 1)
            Positioned(
              bottom: 132, // Above the text overlay
              left: 0,
              right: 0,
              child: IgnorePointer(
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children:
                      List.generate(widget.tweet.mediaUrls.length, (index) {
                    final active = _imageIndex == index;
                    return AnimatedContainer(
                      duration: AppDurations.normal,
                      curve: Curves.easeOut,
                      width: active ? 14 : 5,
                      height: 5,
                      margin: const EdgeInsets.symmetric(horizontal: 2.5),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(3),
                        color: active
                            ? Colors.white
                            : Colors.white.withValues(alpha: 0.4),
                      ),
                    );
                  }),
                ),
              ),
            ),
          if (widget.overlayBuilder != null)
            Positioned.fill(
                child: widget.overlayBuilder!(context, null, false)),
        ],
      );
    }

    // Watch only this tweet's player. Watching the whole pool rebuilt every
    // item on screen each time any player was created or disposed.
    final instance =
        ref.watch(playerPoolProvider.select((pool) => pool[widget.tweet.id]));

    if (instance == null) {
      _unbindPlayer();
      // No player yet: show the poster and the overlay rather than a bare
      // spinner, so the page already looks like itself while swiping in.
      return Stack(
        children: [
          _buildThumbnail(),
          const Center(child: _ThinSpinner()),
          if (widget.overlayBuilder != null)
            Positioned.fill(
                child: widget.overlayBuilder!(context, null, false)),
        ],
      );
    }

    _bindPlayer(instance);

    // Auto-play on first build when visible (warmup opens with play:false)
    if (widget.isVisible && !instance.player.state.playing && !_hasAutoPlayed) {
      _hasAutoPlayed = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) instance.player.play();
      });
    }

    final settings = ref.watch(settingsProvider);
    return PopScope(
      canPop: true,
      onPopInvokedWithResult: (didPop, result) async {
        final state = _videoKey.currentState;
        if (state != null && state.isFullscreen()) {
          await state.exitFullscreen();
        }
      },
      child: StreamBuilder(
        stream: instance.player.stream.error,
        builder: (context, snapshot) {
          if (snapshot.hasData && _retryCount >= settings.playbackRetryLimit) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 32),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.error_outline_rounded,
                        color: AppColors.textTertiary, size: 44),
                    const SizedBox(height: 16),
                    const Text('Playback failed. Moving to next...',
                        style: TextStyle(color: AppColors.textSecondary)),
                    const SizedBox(height: 8),
                    Text('Error: ${snapshot.data}',
                        textAlign: TextAlign.center,
                        maxLines: 4,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            color: AppColors.textTertiary, fontSize: 12)),
                  ],
                ),
              ),
            );
          }

          // Determine orientation based on current player state
          Future<void> onFullscreen() async {
            final state = _videoKey.currentState;
            if (state != null) {
              try {
                if (state.isFullscreen()) {
                  await state.exitFullscreen();
                } else {
                  // 1. Get latest dimensions
                  final width = instance.player.state.width;
                  final height = instance.player.state.height;

                  // Use aspect ratio for better detection.
                  // Default to portrait (isLandscape = false) if dimensions are missing or invalid.
                  final double aspectRatio =
                      (width != null && height != null && height != 0)
                          ? width / height
                          : 0.0;
                  final isLandscape = aspectRatio > 1.0;

                  AppLogger.log(
                      'XFLOW: Fullscreen toggle. ID: ${widget.tweet.id} W: $width H: $height AR: $aspectRatio Landscape: $isLandscape');

                  // 2. Start orientation change immediately
                  if (isLandscape) {
                    await SystemChrome.setPreferredOrientations([
                      DeviceOrientation.landscapeLeft,
                      DeviceOrientation.landscapeRight,
                    ]);
                  } else {
                    await SystemChrome.setPreferredOrientations([
                      DeviceOrientation.portraitUp,
                    ]);
                  }

                  // 3. Enter fullscreen
                  await state.enterFullscreen();
                }
              } catch (e) {
                AppLogger.log('XFLOW: Error toggling fullscreen: $e');
              }
            }
          }

          // Handle auto-fullscreen if requested
          if (widget.isVisible &&
              widget.autoFullscreen &&
              !_isAutoFullscreenDone) {
            _isAutoFullscreenDone = true;
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted) {
                final state = _videoKey.currentState;
                if (state != null && !state.isFullscreen()) {
                  onFullscreen();
                }
              }
            });
          }

          return Stack(
            children: [
              // Thumbnail placeholder — shows while video is loading
              _buildThumbnail(),
              Positioned.fill(
                child: Center(
                  child: RepaintBoundary(
                    child: MaterialVideoControlsTheme(
                      normal: const MaterialVideoControlsThemeData(
                        displaySeekBar: false,
                        automaticallyImplySkipNextButton: false,
                        automaticallyImplySkipPreviousButton: false,
                      ),
                      fullscreen: MaterialVideoControlsThemeData(
                        displaySeekBar: false, // Custom layout below
                        automaticallyImplySkipNextButton: false,
                        automaticallyImplySkipPreviousButton: false,
                        buttonBarHeight: 100.0,
                        bottomButtonBarMargin: EdgeInsets.zero,
                        seekBarPositionColor: Colors.white,
                        seekBarThumbColor: Colors.white,
                        primaryButtonBar: [
                          const Spacer(),
                          const MaterialPlayOrPauseButton(iconSize: 64),
                          const Spacer(),
                        ],
                        bottomButtonBar: [
                          Expanded(
                            child: Container(
                              color: Colors.black.withValues(alpha: 0.5),
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 16.0, vertical: 8.0),
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                mainAxisAlignment: MainAxisAlignment.end,
                                children: [
                                  const MaterialSeekBar(),
                                  Row(
                                    children: [
                                      const MaterialPositionIndicator(),
                                      const Spacer(),
                                      // Like Button
                                      MaterialCustomButton(
                                        onPressed: () {
                                          ref
                                              .read(
                                                  feedNotifierProvider.notifier)
                                              .toggleLike(widget.tweet.id,
                                                  tweet: widget.tweet);
                                        },
                                        icon: Consumer(
                                          builder: (context, ref, child) {
                                            final isLiked = watchLiveTweet(
                                                    ref, widget.tweet)
                                                .isLiked;
                                            return Icon(
                                              isLiked
                                                  ? Icons.favorite_rounded
                                                  : Icons
                                                      .favorite_border_rounded,
                                              color: isLiked
                                                  ? AppColors.like
                                                  : Colors.white,
                                            );
                                          },
                                        ),
                                      ),
                                      // Exit Fullscreen Button
                                      MaterialCustomButton(
                                        onPressed: onFullscreen,
                                        icon: const Icon(
                                            Icons.fullscreen_exit_rounded),
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                      child: Video(
                        key: _videoKey,
                        controller: instance.controller,
                        fit: BoxFit.contain,
                        controls: (state) {
                          if (state.isFullscreen()) {
                            return MaterialVideoControls(state);
                          }
                          return const SizedBox.shrink();
                        },
                        onExitFullscreen: () async {
                          await SystemChrome.setPreferredOrientations([
                            DeviceOrientation.portraitUp,
                          ]);
                        },
                      ),
                    ),
                  ),
                ),
              ),
              // Play/pause + double-tap via Listener (raw pointer events)
              // No gesture arena competition — overlay buttons work independently.
              Positioned(
                top: 0,
                left: 0,
                right: 0,
                bottom: 100,
                child: Listener(
                  behavior: HitTestBehavior.translucent,
                  onPointerDown: _handlePointerDown,
                  onPointerMove: _handlePointerMove,
                  onPointerUp: _handlePointerUp,
                  onPointerCancel: _handlePointerCancel,
                  child: const SizedBox.expand(),
                ),
              ),
              // Buffering: a thin spinner, only for the item on screen.
              if (widget.isVisible)
                Positioned.fill(
                  child: IgnorePointer(
                    child: StreamBuilder<bool>(
                      stream: instance.player.stream.buffering,
                      initialData: instance.player.state.buffering,
                      builder: (context, snapshot) {
                        final buffering = snapshot.data ?? false;
                        return AnimatedOpacity(
                          opacity: buffering && !_userPaused ? 1.0 : 0.0,
                          duration: AppDurations.normal,
                          child: const Center(child: _ThinSpinner()),
                        );
                      },
                    ),
                  ),
                ),
              // Paused: a single translucent play glyph, no disc behind it.
              Positioned.fill(
                child: IgnorePointer(
                  child: Center(
                    child: AnimatedOpacity(
                      opacity: _userPaused && !_isPlaying ? 1.0 : 0.0,
                      duration: AppDurations.fast,
                      child: AnimatedScale(
                        scale: _userPaused && !_isPlaying ? 1.0 : 1.35,
                        duration: AppDurations.normal,
                        curve: Curves.easeOutCubic,
                        child: Icon(
                          Icons.play_arrow_rounded,
                          color: Colors.white.withValues(alpha: 0.72),
                          size: 84,
                          shadows: const [
                            Shadow(blurRadius: 24, color: Colors.black45),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              // Double-tap heart
              _buildLikeHeart(),
              // Text overlay — OUTSIDE Video widget so taps are not intercepted
              if (widget.overlayBuilder != null)
                Positioned.fill(
                  child: widget.overlayBuilder!(
                    context,
                    onFullscreen,
                    false,
                  ),
                ),
              // Seek Bar at very bottom (TikTok-style thin line)
              Positioned(
                bottom: 0,
                left: 0,
                right: 0,
                child: _TiktokSeekBar(player: instance.player),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _buildImage(String url) {
    return SizedBox.expand(
      child: Center(
        child: CachedNetworkImage(
          cacheManager: CustomMediaCacheManager.getInstance(),
          imageUrl: url,
          fit: BoxFit.contain,
          fadeInDuration: AppDurations.normal,
          placeholder: (context, url) => const Center(child: _ThinSpinner()),
          errorWidget: (context, url, error) => const Icon(
              Icons.broken_image_outlined,
              color: AppColors.textTertiary,
              size: 40),
        ),
      ),
    );
  }

  Widget _buildImageGallery() {
    if (widget.tweet.mediaUrls.length == 1) {
      return _buildImage(widget.tweet.mediaUrls.first);
    }

    return PageView.builder(
      scrollDirection: Axis.horizontal,
      itemCount: widget.tweet.mediaUrls.length,
      onPageChanged: (index) {
        setState(() {
          _imageIndex = index;
        });
      },
      itemBuilder: (context, index) =>
          _buildImage(widget.tweet.mediaUrls[index]),
    );
  }
}

class _ThinSpinner extends StatelessWidget {
  const _ThinSpinner();

  @override
  Widget build(BuildContext context) {
    return const SizedBox(
      width: 28,
      height: 28,
      child: CircularProgressIndicator(
        strokeWidth: 2.2,
        color: Colors.white70,
      ),
    );
  }
}

/// Standalone seek bar widget — rebuilds only itself, not the parent Stack.
///
/// At rest it is a hairline along the bottom edge. While the finger is on
/// it, the line thickens, a thumb appears and a time label follows the
/// finger.
class _TiktokSeekBar extends StatefulWidget {
  final Player player;
  const _TiktokSeekBar({required this.player});

  @override
  State<_TiktokSeekBar> createState() => _TiktokSeekBarState();
}

class _TiktokSeekBarState extends State<_TiktokSeekBar> {
  bool _isSeeking = false;
  double _seekPosition = 0.0;

  String _formatDuration(Duration d) {
    final minutes = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<Duration>(
      stream: widget.player.stream.position,
      builder: (context, snapshot) {
        final position = snapshot.data ?? Duration.zero;
        final duration = widget.player.state.duration;

        if (duration == Duration.zero) return const SizedBox.shrink();

        final progress = (_isSeeking
                ? _seekPosition
                : position.inMilliseconds / duration.inMilliseconds)
            .clamp(0.0, 1.0);

        return LayoutBuilder(
          builder: (context, constraints) {
            final totalWidth = constraints.maxWidth;
            const touchAreaHeight = 24.0;
            const labelWidth = 104.0;
            final barHeight = _isSeeking ? 4.0 : 2.0;
            final thumbSize = _isSeeking ? 12.0 : 0.0;

            return Listener(
              behavior: HitTestBehavior.opaque,
              onPointerDown: (event) {
                setState(() {
                  _isSeeking = true;
                  _seekPosition =
                      (event.localPosition.dx / totalWidth).clamp(0.0, 1.0);
                });
              },
              onPointerMove: (event) {
                setState(() {
                  _seekPosition =
                      (event.localPosition.dx / totalWidth).clamp(0.0, 1.0);
                });
              },
              onPointerUp: (event) {
                final seekTo = Duration(
                  milliseconds:
                      (_seekPosition * duration.inMilliseconds).round(),
                );
                widget.player.seek(seekTo);
                setState(() => _isSeeking = false);
              },
              onPointerCancel: (event) {
                setState(() => _isSeeking = false);
              },
              child: SizedBox(
                height: touchAreaHeight,
                child: Stack(
                  clipBehavior: Clip.none,
                  children: [
                    // Background bar
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 0,
                      child: AnimatedContainer(
                        duration: AppDurations.fast,
                        height: barHeight,
                        color: Colors.white.withValues(alpha: 0.22),
                      ),
                    ),
                    // Progress bar
                    Positioned(
                      left: 0,
                      bottom: 0,
                      child: AnimatedContainer(
                        duration: AppDurations.fast,
                        height: barHeight,
                        width: totalWidth * progress,
                        color: Colors.white,
                      ),
                    ),
                    // Thumb, only while seeking
                    Positioned(
                      left: totalWidth * progress - thumbSize / 2,
                      bottom: barHeight / 2 - thumbSize / 2,
                      child: AnimatedContainer(
                        duration: AppDurations.fast,
                        width: thumbSize,
                        height: thumbSize,
                        decoration: const BoxDecoration(
                          color: Colors.white,
                          shape: BoxShape.circle,
                        ),
                      ),
                    ),
                    // Time label when seeking
                    if (_isSeeking)
                      Positioned(
                        bottom: 30,
                        left: (totalWidth * progress - labelWidth / 2)
                            .clamp(8.0, totalWidth - labelWidth - 8.0),
                        width: labelWidth,
                        child: Container(
                          alignment: Alignment.center,
                          padding: const EdgeInsets.symmetric(vertical: 5),
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: 0.7),
                            borderRadius: BorderRadius.circular(AppRadius.sm),
                          ),
                          child: Text(
                            '${_formatDuration(Duration(
                              milliseconds:
                                  (progress * duration.inMilliseconds).round(),
                            ))} / ${_formatDuration(duration)}',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                              fontFeatures: [FontFeature.tabularFigures()],
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }
}
