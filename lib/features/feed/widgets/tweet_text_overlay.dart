import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:intl/intl.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../../core/models/tweet.dart';
import '../../../core/navigation/navigation_provider.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/theme/app_widgets.dart';
import '../../../core/utils/app_logger.dart';
import '../../../core/database/entities.dart';
import '../../subscriptions/subscription_list_screen.dart';
import '../feed_provider.dart';
import '../bookmark_provider.dart';
import '../tweet_detail_screen.dart';

class TweetTextOverlay extends ConsumerStatefulWidget {
  final Tweet tweet;
  final VoidCallback? onFullscreen;
  final bool isFullscreen;

  const TweetTextOverlay({
    super.key,
    required this.tweet,
    this.onFullscreen,
    this.isFullscreen = false,
  });

  @override
  ConsumerState<TweetTextOverlay> createState() => _TweetTextOverlayState();
}

class _TweetTextOverlayState extends ConsumerState<TweetTextOverlay> {
  static const double _actionColumnWidth = 56;
  static const List<Shadow> _textShadow = [
    Shadow(offset: Offset(0, 1), blurRadius: 3, color: Colors.black54),
  ];

  // Lighter than the accent so links stay readable on top of video.
  static const Color _linkColor = Color(0xFF9CC2FF);

  bool _isExpanded = false;

  @override
  Widget build(BuildContext context) {
    final bottomPadding = widget.isFullscreen ? 48.0 : 36.0;
    final rightPadding = widget.isFullscreen ? 24.0 : 6.0;

    return Stack(
      children: [
        // Scrim behind the caption. It ignores touches, so tapping and
        // double-tapping the media still work down here.
        Positioned(
          bottom: 0,
          left: 0,
          right: 0,
          height: 260,
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.transparent,
                    Colors.black.withValues(alpha: 0.28),
                    Colors.black.withValues(alpha: 0.62),
                  ],
                  stops: const [0.0, 0.5, 1.0],
                ),
              ),
            ),
          ),
        ),
        // Caption (bottom left). The right inset keeps it clear of the
        // action column.
        Positioned(
          bottom: 0,
          left: 0,
          right: 0,
          child: Padding(
            padding: EdgeInsets.fromLTRB(
                16, 0, _actionColumnWidth + rightPadding + 8, bottomPadding),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                _buildUserHeader(context),
                // A text-only tweet already shows its text as the page.
                if (widget.tweet.mediaUrls.isNotEmpty &&
                    widget.tweet.text.trim().isNotEmpty) ...[
                  const SizedBox(height: 10),
                  _buildTweetText(),
                ],
              ],
            ),
          ),
        ),
        // Action Buttons (Right Side)
        Positioned(
          right: rightPadding,
          bottom: bottomPadding - 6,
          child: _buildActionButtons(),
        ),
      ],
    );
  }

  Widget _buildActionButtons() {
    // Watch live tweet state so buttons update immediately after toggle
    final tweet = watchLiveTweet(ref, widget.tweet);
    // The saved state comes from the bookmark list: a tweet in a feed never
    // has isBookmarked set, so the icon used to stay off after saving.
    final isBookmarked = ref.watch(bookmarkListProvider.select((bookmarks) =>
        bookmarks.value?.any((t) => t.id == tweet.id) ?? tweet.isBookmarked));
    const gap = SizedBox(height: 14);

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        _ActionButton(
          icon: tweet.isLiked
              ? Icons.favorite_rounded
              : Icons.favorite_border_rounded,
          color: tweet.isLiked ? AppColors.like : Colors.white,
          active: tweet.isLiked,
          label: _formatCount(tweet.favoriteCount),
          onTap: () {
            ref
                .read(feedNotifierProvider.notifier)
                .toggleLike(tweet.id, tweet: widget.tweet);
          },
        ),
        gap,
        _ActionButton(
          icon: Icons.mode_comment_outlined,
          label: _formatCount(tweet.replyCount),
          onTap: () {
            TweetRepliesSheet.show(context, tweet);
          },
        ),
        gap,
        _ActionButton(
          icon: Icons.repeat_rounded,
          color: tweet.isRetweeted ? AppColors.retweet : Colors.white,
          active: tweet.isRetweeted,
          label: _formatCount(tweet.retweetCount),
          onTap: () {
            ref
                .read(feedNotifierProvider.notifier)
                .toggleRetweet(tweet.id, tweet: widget.tweet);
          },
        ),
        gap,
        _ActionButton(
          icon: isBookmarked
              ? Icons.bookmark_rounded
              : Icons.bookmark_border_rounded,
          color: isBookmarked ? AppColors.bookmark : Colors.white,
          active: isBookmarked,
          label: "Lưu",
          onTap: () {
            ref
                .read(bookmarkListProvider.notifier)
                .toggleBookmark(tweet.copyWith(isBookmarked: isBookmarked));
          },
        ),
        gap,
        _ActionButton(
          icon: Icons.ios_share_rounded,
          label: "Chia sẻ",
          onTap: () async {
            final handle = tweet.userHandle.replaceFirst('@', '');
            final url = 'https://x.com/$handle/status/${tweet.id}';
            await Share.share(url, subject: 'Xem tweet này');
          },
        ),
        if (widget.onFullscreen != null && tweet.isVideo) ...[
          gap,
          _ActionButton(
            icon: widget.isFullscreen
                ? Icons.fullscreen_exit_rounded
                : Icons.fullscreen_rounded,
            label: widget.isFullscreen ? "Exit" : "Full",
            onTap: widget.onFullscreen!,
          ),
        ],
      ],
    );
  }

  String _formatCount(int count) {
    if (count >= 1000000) {
      return '${(count / 1000000).toStringAsFixed(1)}M';
    } else if (count >= 1000) {
      return '${(count / 1000).toStringAsFixed(1)}K';
    }
    return count.toString();
  }

  Widget _buildUserHeader(BuildContext context) {
    final subscriptionState = ref.watch(subscriptionListProvider);
    final isSubscribed =
        subscriptionState.isSubscribed(widget.tweet.userHandle);

    String dateStr = "";
    if (widget.tweet.createdAt != null) {
      try {
        final date = widget.tweet.createdAt!.toLocal();
        dateStr = DateFormat.yMMMd().add_Hm().format(date);
      } catch (e) {
        AppLogger.log('XFLOW: Error formatting date: $e');
        dateStr = "ERR";
      }
    }

    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        GestureDetector(
          onTap: () {
            final handle = widget.tweet.userHandle.replaceFirst('@', '');
            ref.read(navigationProvider.notifier).selectUser(handle);
          },
          child: CircleAvatar(
            radius: 18,
            backgroundColor: AppColors.surfaceHighest,
            backgroundImage: widget.tweet.userAvatarUrlHighRes != null
                ? CachedNetworkImageProvider(widget.tweet.userAvatarUrlHighRes!)
                : null,
            child: widget.tweet.userAvatarUrlHighRes == null
                ? const Icon(Icons.person_rounded,
                    color: AppColors.textSecondary, size: 20)
                : null,
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: GestureDetector(
            onTap: () {
              final handle = widget.tweet.userHandle.replaceFirst('@', '');
              ref.read(navigationProvider.notifier).selectUser(handle);
            },
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Flexible(
                      child: Text(
                        widget.tweet.userHandle,
                        style: const TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.w700,
                          fontSize: 15,
                          letterSpacing: -0.1,
                          shadows: _textShadow,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (isSubscribed) ...[
                      const SizedBox(width: 4),
                      const Icon(Icons.check_circle_rounded,
                          color: AppColors.accent, size: 15),
                    ],
                    if (!isSubscribed) ...[
                      const SizedBox(width: 8),
                      PressableScale(
                        onTap: () {
                          ref
                              .read(subscriptionListProvider.notifier)
                              .toggleSubscription(
                                Subscription(
                                  id: widget.tweet.userHandle,
                                  screenName: widget.tweet.userHandle
                                      .replaceFirst('@', ''),
                                  name: widget.tweet.userHandle,
                                  profileImageUrl: widget.tweet.userAvatarUrl,
                                ),
                              );
                        },
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 12, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: 0.2),
                            border: Border.all(
                                color: Colors.white.withValues(alpha: 0.85)),
                            borderRadius: BorderRadius.circular(AppRadius.xl),
                          ),
                          child: const Text(
                            'Follow',
                            style: TextStyle(
                              color: Colors.white,
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
                if (dateStr.isNotEmpty)
                  Text(
                    dateStr,
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.7),
                      fontSize: 11.5,
                      shadows: _textShadow,
                    ),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildTweetText() {
    final text = widget.tweet.text;
    final List<InlineSpan> spans = [];

    // Parse text for hashtags, mentions, and URLs
    final List<String> words = [];
    final matches = RegExp(r'(\s+)').allMatches(text);
    int lastEnd = 0;
    for (final match in matches) {
      if (match.start > lastEnd) {
        words.add(text.substring(lastEnd, match.start));
      }
      words.add(text.substring(match.start, match.end));
      lastEnd = match.end;
    }
    if (lastEnd < text.length) {
      words.add(text.substring(lastEnd));
    }

    for (final word in words) {
      if (word.startsWith('#') && word.length > 1) {
        spans.add(
          TextSpan(
            text: word,
            style: const TextStyle(
              color: _linkColor,
              fontWeight: FontWeight.w600,
            ),
            recognizer: TapGestureRecognizer()
              ..onTap = () {
                ref.read(navigationProvider.notifier).selectHashtag(word);
              },
          ),
        );
      } else if (word.startsWith('@') && word.length > 1) {
        spans.add(
          TextSpan(
            text: word,
            style: const TextStyle(
              color: _linkColor,
              fontWeight: FontWeight.w500,
            ),
            recognizer: TapGestureRecognizer()
              ..onTap = () {
                final handle = word.replaceFirst('@', '');
                ref.read(navigationProvider.notifier).selectUser(handle);
              },
          ),
        );
      } else if (word.startsWith('http://') || word.startsWith('https://')) {
        // Clean URL (remove trailing punctuation)
        final cleanUrl = word.replaceAll(RegExp(r'[.,!?;:)\]]+$'), '');
        spans.add(
          TextSpan(
            text: cleanUrl,
            style: const TextStyle(
              color: _linkColor,
              fontWeight: FontWeight.w500,
              decoration: TextDecoration.underline,
            ),
            recognizer: TapGestureRecognizer()
              ..onTap = () async {
                final uri = Uri.parse(cleanUrl);
                await launchUrl(uri, mode: LaunchMode.platformDefault);
              },
          ),
        );
      } else {
        spans.add(TextSpan(text: word));
      }
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text.rich(
          TextSpan(
            children: spans,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 14,
              height: 1.35,
              shadows: _textShadow,
            ),
          ),
          maxLines: _isExpanded ? null : 3,
          overflow: _isExpanded ? TextOverflow.visible : TextOverflow.ellipsis,
        ),
        if (!_isExpanded && widget.tweet.text.length > 100)
          GestureDetector(
            onTap: () {
              setState(() {
                _isExpanded = true;
              });
            },
            child: const Padding(
              padding: EdgeInsets.only(top: 4.0),
              child: Text(
                "Xem thêm",
                style: TextStyle(
                  color: Colors.white70,
                  fontWeight: FontWeight.w600,
                  fontSize: 12.5,
                  shadows: _textShadow,
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _ActionButton extends StatefulWidget {
  final IconData icon;
  final Color color;
  final String label;
  final VoidCallback? onTap;

  /// Whether the action is on (liked, retweeted, saved). The icon pops when
  /// this turns on, wherever the change came from, so a double-tap like
  /// also plays here.
  final bool active;

  const _ActionButton({
    required this.icon,
    this.color = Colors.white,
    required this.label,
    this.onTap,
    this.active = false,
  });

  @override
  State<_ActionButton> createState() => _ActionButtonState();
}

class _ActionButtonState extends State<_ActionButton>
    with SingleTickerProviderStateMixin {
  late final AnimationController _pop = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 280),
  );
  late final Animation<double> _popScale = TweenSequence<double>([
    TweenSequenceItem(
      tween:
          Tween(begin: 1.0, end: 1.3).chain(CurveTween(curve: Curves.easeOut)),
      weight: 40,
    ),
    TweenSequenceItem(
      tween: Tween(begin: 1.3, end: 1.0)
          .chain(CurveTween(curve: Curves.easeOutBack)),
      weight: 60,
    ),
  ]).animate(_pop);

  @override
  void didUpdateWidget(_ActionButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active && !oldWidget.active) _pop.forward(from: 0);
  }

  @override
  void dispose() {
    _pop.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return PressableScale(
      onTap: widget.onTap,
      tooltip: widget.label,
      child: SizedBox(
        width: 56,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ScaleTransition(
              scale: _popScale,
              child: Icon(
                widget.icon,
                color: widget.color,
                size: 29,
                shadows: const [
                  Shadow(
                      offset: Offset(0, 1),
                      blurRadius: 6,
                      color: Colors.black54),
                ],
              ),
            ),
            const SizedBox(height: 3),
            Text(
              widget.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 11,
                fontWeight: FontWeight.w600,
                shadows: [
                  Shadow(
                      offset: Offset(0, 1),
                      blurRadius: 3,
                      color: Colors.black54),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
