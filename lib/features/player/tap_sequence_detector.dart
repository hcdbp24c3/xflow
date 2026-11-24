import 'dart:ui' show Offset;

/// What a completed pointer sequence means for the media surface.
enum TapKind {
  /// Not a tap: the finger moved (a swipe), was held too long, or another
  /// finger was down at the same time.
  none,

  /// First tap of a sequence. The player toggles play/pause right away.
  single,

  /// Second or later tap of a quick sequence. Shows a heart and likes.
  multi,
}

class TapResult {
  final TapKind kind;

  /// Where the finger was lifted, in the listener's local coordinates.
  final Offset position;

  /// 1 for a single tap, 2 for a double tap, 3+ for further quick taps.
  final int count;

  const TapResult(this.kind, this.position, this.count);

  static const none = TapResult(TapKind.none, Offset.zero, 0);

  /// True only for the second tap of a sequence. The first tap already
  /// toggled play/pause, so this is the moment to undo that toggle.
  bool get isFirstMulti => kind == TapKind.multi && count == 2;
}

/// Turns raw pointer events into single taps and quick multi-tap sequences.
///
/// It is fed from a `Listener` rather than a `GestureDetector`, so it never
/// competes with the page scroll or the overlay buttons. Because raw events
/// also arrive for swipes, a pointer only counts as a tap when it stays within
/// [touchSlop] and is lifted within [maxTapDuration].
///
/// The first tap is reported immediately (no waiting to see whether a second
/// one follows), which keeps play/pause instant. A second tap that lands
/// within [multiTapWindow] and [multiTapSlop] of the previous one is reported
/// as [TapKind.multi]; the caller undoes the play/pause toggle on
/// [TapResult.isFirstMulti].
class TapSequenceDetector {
  TapSequenceDetector({
    this.touchSlop = 18.0,
    this.maxTapDuration = const Duration(milliseconds: 500),
    this.multiTapWindow = const Duration(milliseconds: 300),
    this.multiTapSlop = 100.0,
  });

  final double touchSlop;
  final Duration maxTapDuration;
  final Duration multiTapWindow;
  final double multiTapSlop;

  int? _pointer;
  Offset _downPosition = Offset.zero;
  Duration _downTime = Duration.zero;
  bool _rejected = false;
  int _activePointers = 0;

  Offset? _lastTapPosition;
  Duration? _lastTapTime;
  int _count = 0;

  /// [time] is the event time stamp (`PointerEvent.timeStamp`).
  void pointerDown(int pointer, Offset position, Duration time) {
    _activePointers++;
    if (_activePointers > 1) {
      // Second finger: this is a pinch or a stray touch, not a tap.
      _rejected = true;
      return;
    }
    _pointer = pointer;
    _downPosition = position;
    _downTime = time;
    _rejected = false;
  }

  void pointerMove(int pointer, Offset position) {
    if (pointer != _pointer || _rejected) return;
    if ((position - _downPosition).distance > touchSlop) {
      _rejected = true;
    }
  }

  TapResult pointerUp(int pointer, Offset position, Duration time) {
    if (_activePointers > 0) _activePointers--;
    if (pointer != _pointer) return TapResult.none;
    _pointer = null;

    final moved = (position - _downPosition).distance > touchSlop;
    final tooLong = time - _downTime > maxTapDuration;
    if (_rejected || moved || tooLong) {
      _rejected = false;
      _breakSequence();
      return TapResult.none;
    }

    final lastTime = _lastTapTime;
    final lastPosition = _lastTapPosition;
    final continues = lastTime != null &&
        lastPosition != null &&
        _downTime - lastTime <= multiTapWindow &&
        (position - lastPosition).distance <= multiTapSlop;

    _count = continues ? _count + 1 : 1;
    _lastTapTime = time;
    _lastTapPosition = position;

    return TapResult(
      _count == 1 ? TapKind.single : TapKind.multi,
      position,
      _count,
    );
  }

  void pointerCancel(int pointer) {
    if (_activePointers > 0) _activePointers--;
    if (pointer != _pointer) return;
    _pointer = null;
    _rejected = false;
    _breakSequence();
  }

  /// Forgets everything, e.g. when the item scrolls off screen.
  void reset() {
    _pointer = null;
    _rejected = false;
    _activePointers = 0;
    _breakSequence();
  }

  void _breakSequence() {
    _lastTapTime = null;
    _lastTapPosition = null;
    _count = 0;
  }
}
