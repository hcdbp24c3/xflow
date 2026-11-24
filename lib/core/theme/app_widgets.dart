import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'app_theme.dart';

/// Shrinks its child slightly while pressed. Used for every tappable control
/// that sits on top of media, so they all answer a touch the same way.
class PressableScale extends StatefulWidget {
  final Widget child;
  final VoidCallback? onTap;
  final double pressedScale;
  final String? tooltip;

  const PressableScale({
    super.key,
    required this.child,
    this.onTap,
    this.pressedScale = 0.88,
    this.tooltip,
  });

  @override
  State<PressableScale> createState() => _PressableScaleState();
}

class _PressableScaleState extends State<PressableScale> {
  bool _pressed = false;

  void _set(bool value) {
    if (_pressed != value && mounted) setState(() => _pressed = value);
  }

  @override
  Widget build(BuildContext context) {
    final enabled = widget.onTap != null;
    Widget result = GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: enabled ? (_) => _set(true) : null,
      onTapUp: enabled ? (_) => _set(false) : null,
      onTapCancel: enabled ? () => _set(false) : null,
      onTap: widget.onTap,
      child: AnimatedScale(
        scale: _pressed ? widget.pressedScale : 1.0,
        duration: AppDurations.fast,
        curve: Curves.easeOut,
        child: widget.child,
      ),
    );
    if (widget.tooltip != null) {
      result = Semantics(
        button: true,
        label: widget.tooltip,
        child: result,
      );
    }
    return result;
  }
}

/// A round icon button on a translucent dark disc, for controls that float
/// over video. A plain fill, not a blur: blurring video every frame is
/// expensive.
class FloatingIconButton extends StatelessWidget {
  final IconData icon;
  final VoidCallback? onTap;
  final String? tooltip;
  final double size;

  const FloatingIconButton({
    super.key,
    required this.icon,
    this.onTap,
    this.tooltip,
    this.size = 38,
  });

  @override
  Widget build(BuildContext context) {
    return PressableScale(
      onTap: onTap,
      tooltip: tooltip,
      child: Padding(
        // Keeps the touch target at 46px while the disc stays small.
        padding: const EdgeInsets.all(4),
        child: Container(
          width: size,
          height: size,
          decoration: const BoxDecoration(
            color: AppColors.scrim,
            shape: BoxShape.circle,
          ),
          child: Icon(icon, color: Colors.white, size: size * 0.53),
        ),
      ),
    );
  }
}

/// The row of floating controls at the top of a full-bleed feed. It replaces
/// the solid app bar so media can run edge to edge.
class FeedTopBar extends StatelessWidget {
  final Widget? leading;
  final Widget? title;
  final List<Widget> actions;

  const FeedTopBar({
    super.key,
    this.leading,
    this.title,
    this.actions = const [],
  });

  @override
  Widget build(BuildContext context) {
    final topInset = MediaQuery.paddingOf(context).top;
    return Stack(
      children: [
        // Fades the top of the media so white controls stay readable.
        IgnorePointer(
          child: Container(
            height: topInset + 96,
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [Color(0x8C000000), Color(0x00000000)],
              ),
            ),
          ),
        ),
        Padding(
          padding: EdgeInsets.fromLTRB(8, topInset + 4, 8, 0),
          child: SizedBox(
            height: 46,
            child: Row(
              children: [
                if (leading != null) leading!,
                if (title != null)
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                      child: DefaultTextStyle.merge(
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 16,
                          fontWeight: FontWeight.w700,
                          letterSpacing: -0.2,
                          shadows: [
                            Shadow(blurRadius: 6, color: Colors.black54),
                          ],
                        ),
                        child: title!,
                      ),
                    ),
                  )
                else
                  const Spacer(),
                ...actions,
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// Wraps a full-bleed media screen: black background and light status-bar
/// icons.
class MediaScaffoldBody extends StatelessWidget {
  final Widget child;
  const MediaScaffoldBody({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: AppTheme.overlayStyle,
      child: ColoredBox(color: AppColors.background, child: child),
    );
  }
}

/// The one look for "nothing here", loading and error states.
class EmptyState extends StatelessWidget {
  final IconData? icon;
  final String message;
  final String? detail;
  final List<Widget> actions;
  final bool loading;

  const EmptyState({
    super.key,
    this.icon,
    required this.message,
    this.detail,
    this.actions = const [],
    this.loading = false,
  });

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (loading)
              const SizedBox(
                width: 26,
                height: 26,
                child: CircularProgressIndicator(strokeWidth: 2.5),
              )
            else if (icon != null)
              Icon(icon, size: 44, color: AppColors.textTertiary),
            if (loading || icon != null) const SizedBox(height: 16),
            Text(
              message,
              textAlign: TextAlign.center,
              style: text.bodyMedium?.copyWith(color: AppColors.textSecondary),
            ),
            if (detail != null) ...[
              const SizedBox(height: 6),
              Text(
                detail!,
                textAlign: TextAlign.center,
                maxLines: 4,
                overflow: TextOverflow.ellipsis,
                style: text.bodySmall?.copyWith(color: AppColors.textTertiary),
              ),
            ],
            for (final action in actions) ...[
              const SizedBox(height: 12),
              action,
            ],
          ],
        ),
      ),
    );
  }
}

/// Thin progress line pinned to the top edge of a feed.
class TopProgressLine extends StatelessWidget {
  const TopProgressLine({super.key});

  @override
  Widget build(BuildContext context) {
    return const Positioned(
      top: 0,
      left: 0,
      right: 0,
      child: LinearProgressIndicator(
        minHeight: 2,
        backgroundColor: Colors.transparent,
      ),
    );
  }
}
