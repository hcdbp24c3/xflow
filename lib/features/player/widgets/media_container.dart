import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../../../core/models/tweet.dart';
import '../../../core/utils/media_cache_manager.dart';
import '../../../core/utils/app_logger.dart';
import '../../feed/widgets/text_tweet_card.dart';
import '../player_pool_provider.dart';
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
  StreamSubscription? _errorSubscription;
  StreamSubscription? _completedSubscription;

  // Double-tap like state
  Offset? _likePosition;
  bool _showLikeHeart = false;
  AnimationController? _likeAnimController;
  Animation<double>? _likeScaleAnim;
  Animation<double>? _likeOpacityAnim;

  // Seek bar state
  bool _isSeeking = false;
  double _seekPosition = 0.0;

  // Single-tap delay to avoid conflict with double-tap
  Timer? _tapTimer;

  @override
  void didUpdateWidget(TiktokMediaContainer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.isVisible && widget.isVisible) {
      _isAutoFullscreenDone = false;
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

  @override
  void dispose() {
    _errorSubscription?.cancel();
    _completedSubscription?.cancel();
    _likeAnimController?.dispose();
    _tapTimer?.cancel();
    super.dispose();
  }

  void _handleDoubleTap(TapDownDetails details) {
    final tweetId = widget.tweet.id;
    final tweet = ref.read(feedNotifierProvider).value?.tweets.firstWhere(
          (t) => t.id == tweetId,
          orElse: () => widget.tweet,
        );
    // Only like, don't toggle (skip if already liked)
    if (tweet != null && tweet.isLiked) return;

    ref.read(feedNotifierProvider.notifier).toggleLike(tweetId);

    setState(() {
      _likePosition = details.localPosition;
      _showLikeHeart = true;
    });

    _likeAnimController?.dispose();
    _likeAnimController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    );

    _likeScaleAnim = TweenSequence<double>([
      TweenSequenceItem(tween: Tween(begin: 0.0, end: 1.2), weight: 30),
      TweenSequenceItem(tween: Tween(begin: 1.2, end: 1.0), weight: 20),
      TweenSequenceItem(tween: Tween(begin: 1.0, end: 1.0), weight: 30),
      TweenSequenceItem(tween: Tween(begin: 1.0, end: 0.0), weight: 20),
    ]).animate(CurvedAnimation(
      parent: _likeAnimController!,
      curve: Curves.easeOut,
    ));

    _likeOpacityAnim = Tween<double>(begin: 1.0, end: 0.0).animate(
      CurvedAnimation(
        parent: _likeAnimController!,
        curve: const Interval(0.6, 1.0),
      ),
    );

    _likeAnimController!.addStatusListener((status) {
      if (status == AnimationStatus.completed && mounted) {
        setState(() => _showLikeHeart = false);
      }
    });

    _likeAnimController!.forward();
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

  @override
  Widget build(BuildContext context) {
    if (widget.tweet.mediaUrls.isEmpty) {
      return Stack(
        children: [
          TextTweetCard(text: widget.tweet.text),
          if (widget.overlayBuilder != null)
            Positioned.fill(child: widget.overlayBuilder!(context, null, false)),
        ],
      );
    }

    if (!widget.tweet.isVideo) {
      return Stack(
        children: [
          GestureDetector(
            onDoubleTapDown: (details) {
              _handleDoubleTap(details);
            },
            behavior: HitTestBehavior.opaque,
            child: _buildImageGallery(),
          ),
          if (_showLikeHeart && _likePosition != null)
            Positioned(
              left: _likePosition!.dx - 40,
              top: _likePosition!.dy - 40,
              child: AnimatedBuilder(
                animation: _likeAnimController!,
                builder: (context, child) {
                  return Opacity(
                    opacity: _likeOpacityAnim?.value ?? 0,
                    child: Transform.scale(
                      scale: _likeScaleAnim?.value ?? 0,
                      child: child,
                    ),
                  );
                },
                child: const Icon(
                  Icons.favorite,
                  color: Colors.red,
                  size: 80,
                ),
              ),
            ),
          if (widget.tweet.mediaUrls.length > 1)
            Positioned(
              bottom: 120, // Above the text overlay
              left: 0,
              right: 0,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: List.generate(widget.tweet.mediaUrls.length, (index) {
                  return Container(
                    width: 6,
                    height: 6,
                    margin: const EdgeInsets.symmetric(horizontal: 3),
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _imageIndex == index
                          ? Colors.white
                          : Colors.white.withValues(alpha: 0.4),
                    ),
                  );
                }),
              ),
            ),
          if (widget.overlayBuilder != null)
            Positioned.fill(child: widget.overlayBuilder!(context, null, false)),
        ],
      );
    }

    final pool = ref.watch(playerPoolProvider);
    final instance = pool[widget.tweet.id];

    if (instance == null) {
      return const Center(child: CircularProgressIndicator());
    }

    // Subscribe to events if not already doing so
    _errorSubscription ??= instance.player.stream.error.listen(_handleError);
    _completedSubscription ??=
        instance.player.stream.completed.listen((completed) {
      if (completed) _handleCompleted();
    });

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
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.error_outline,
                      color: Colors.white70, size: 48),
                  const SizedBox(height: 16),
                  const Text('Playback failed. Moving to next...',
                      style: TextStyle(color: Colors.white70)),
                  const SizedBox(height: 8),
                  Text('Error: ${snapshot.data}',
                      style:
                          const TextStyle(color: Colors.white38, fontSize: 12)),
                ],
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
                  final double aspectRatio = (width != null && height != null && height != 0) 
                      ? width / height 
                      : 0.0;
                  final isLandscape = aspectRatio > 1.0;

                  AppLogger.log('XFLOW: Fullscreen toggle. ID: ${widget.tweet.id} W: $width H: $height AR: $aspectRatio Landscape: $isLandscape');

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
          if (widget.isVisible && widget.autoFullscreen && !_isAutoFullscreenDone) {
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
                                              .toggleLike(widget.tweet.id);
                                        },
                                        icon: Consumer(
                                          builder: (context, ref, child) {
                                            final isLiked = ref.watch(
                                                feedNotifierProvider.select(
                                                    (s) => s.value?.tweets
                                                        .firstWhere((t) =>
                                                            t.id ==
                                                            widget.tweet.id)
                                                        .isLiked ??
                                                    false));
                                            return Icon(
                                              isLiked
                                                  ? Icons.favorite
                                                  : Icons.favorite_border,
                                              color: isLiked
                                                  ? Colors.red
                                                  : Colors.white,
                                            );
                                          },
                                        ),
                                      ),
                                      // Exit Fullscreen Button
                                      MaterialCustomButton(
                                        onPressed: onFullscreen,
                                        icon: const Icon(Icons.fullscreen_exit),
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
                          return Stack(
                            children: [
                              GestureDetector(
                                onTap: () {
                                  // Delay single-tap to check if double-tap follows
                                  _tapTimer?.cancel();
                                  _tapTimer = Timer(
                                      const Duration(milliseconds: 300), () {
                                    if (instance.player.state.playing) {
                                      instance.player.pause();
                                    } else {
                                      instance.player.play();
                                    }
                                  });
                                },
                                onDoubleTapDown: (details) {
                                  _tapTimer?.cancel();
                                  _handleDoubleTap(details);
                                },
                                behavior: HitTestBehavior.deferToChild,
                              ),
                              // Double-tap heart overlay
                              if (_showLikeHeart && _likePosition != null)
                                Positioned(
                                  left: _likePosition!.dx - 40,
                                  top: _likePosition!.dy - 40,
                                  child: AnimatedBuilder(
                                    animation: _likeAnimController!,
                                    builder: (context, child) {
                                      return Opacity(
                                        opacity: _likeOpacityAnim?.value ?? 0,
                                        child: Transform.scale(
                                          scale: _likeScaleAnim?.value ?? 0,
                                          child: child,
                                        ),
                                      );
                                    },
                                    child: const Icon(
                                      Icons.favorite,
                                      color: Colors.red,
                                      size: 80,
                                    ),
                                  ),
                                ),
                              if (widget.overlayBuilder != null)
                                Positioned.fill(
                                  child: widget.overlayBuilder!(
                                    context,
                                    onFullscreen,
                                    false,
                                  ),
                                ),
                            ],
                          );
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
              // Seek Bar at the very bottom
              Positioned(
                bottom: 16,
                left: 0,
                right: 0,
                child: _buildSeekBar(instance),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _buildSeekBar(PlayerInstance instance) {
    return StreamBuilder<Duration>(
      stream: instance.player.stream.position,
      builder: (context, snapshot) {
        final position = snapshot.data ?? Duration.zero;
        final duration = instance.player.state.duration;

        if (duration == Duration.zero) return const SizedBox.shrink();

        final progress = _isSeeking
            ? _seekPosition
            : position.inMilliseconds / duration.inMilliseconds;

        return LayoutBuilder(
          builder: (context, constraints) {
            final totalWidth = constraints.maxWidth;
            const touchAreaHeight = 56.0;
            const barHeight = 4.0;
            const thumbSize = 18.0;

            return GestureDetector(
              onHorizontalDragStart: (details) {
                _isSeeking = true;
                _seekPosition =
                    (details.localPosition.dx / totalWidth).clamp(0.0, 1.0);
                setState(() {});
              },
              onHorizontalDragUpdate: (details) {
                _seekPosition =
                    (details.localPosition.dx / totalWidth).clamp(0.0, 1.0);
                setState(() {});
              },
              onHorizontalDragEnd: (details) {
                final seekTo = Duration(
                  milliseconds:
                      (_seekPosition * duration.inMilliseconds).round(),
                );
                instance.player.seek(seekTo);
                _isSeeking = false;
                setState(() {});
              },
              onTapDown: (details) {
                _isSeeking = true;
                _seekPosition =
                    (details.localPosition.dx / totalWidth).clamp(0.0, 1.0);
                setState(() {});
              },
              onTapUp: (details) {
                final seekTo = Duration(
                  milliseconds:
                      (_seekPosition * duration.inMilliseconds).round(),
                );
                instance.player.seek(seekTo);
                _isSeeking = false;
                setState(() {});
              },
              child: SizedBox(
                height: touchAreaHeight,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    // Background bar
                    Positioned(
                      left: 0,
                      right: 0,
                      top: (touchAreaHeight - barHeight) / 2,
                      child: Container(
                        height: barHeight,
                        color: Colors.white24,
                      ),
                    ),
                    // Progress bar
                    Positioned(
                      left: 0,
                      top: (touchAreaHeight - barHeight) / 2,
                      child: Container(
                        height: barHeight,
                        width: totalWidth * progress.clamp(0.0, 1.0),
                        color: Colors.white,
                      ),
                    ),
                    // Thumb
                    Positioned(
                      left: totalWidth * progress.clamp(0.0, 1.0) - thumbSize / 2,
                      top: (touchAreaHeight - thumbSize) / 2,
                      child: Container(
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
                        top: 0,
                        left: (totalWidth * _seekPosition).clamp(
                            thumbSize / 2, totalWidth - thumbSize / 2),
                        child: Transform.translate(
                          offset: const Offset(0, -14),
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: Colors.black87,
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Text(
                              _formatDuration(Duration(
                                milliseconds:
                                    (_seekPosition * duration.inMilliseconds)
                                        .round(),
                              )),
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 12,
                              ),
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

  String _formatDuration(Duration d) {
    final minutes = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  Widget _buildProgressBar(PlayerInstance instance) {
    return StreamBuilder<Duration>(
      stream: instance.player.stream.position,
      builder: (context, snapshot) {
        final position = snapshot.data ?? Duration.zero;
        final duration = instance.player.state.duration;

        if (duration == Duration.zero) return const SizedBox.shrink();

        final progress = position.inMilliseconds / duration.inMilliseconds;

        return Container(
          height: 2,
          width: double.infinity,
          color: Colors.white12,
          child: FractionallySizedBox(
            alignment: Alignment.centerLeft,
            widthFactor: progress.clamp(0.0, 1.0),
            child: Container(color: Colors.white),
          ),
        );
      },
    );
  }

  Widget _buildImageGallery() {
    if (widget.tweet.mediaUrls.length == 1) {
      return SizedBox.expand(
        child: Center(
          child: CachedNetworkImage(
            cacheManager: CustomMediaCacheManager.getInstance(),
            imageUrl: widget.tweet.mediaUrls.first,
            fit: BoxFit.contain,
            placeholder: (context, url) =>
                const Center(child: CircularProgressIndicator()),
            errorWidget: (context, url, error) => const Icon(Icons.error),
          ),
        ),
      );
    }

    return PageView.builder(
      scrollDirection: Axis.horizontal,
      itemCount: widget.tweet.mediaUrls.length,
      onPageChanged: (index) {
        setState(() {
          _imageIndex = index;
        });
      },
      itemBuilder: (context, index) {
        return SizedBox.expand(
          child: Center(
            child: CachedNetworkImage(
              cacheManager: CustomMediaCacheManager.getInstance(),
              imageUrl: widget.tweet.mediaUrls[index],
              fit: BoxFit.contain,
              placeholder: (context, url) =>
                  const Center(child: CircularProgressIndicator()),
              errorWidget: (context, url, error) => const Icon(Icons.error),
            ),
          ),
        );
      },
    );
  }
}
