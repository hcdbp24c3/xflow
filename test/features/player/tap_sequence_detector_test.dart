import 'dart:ui';
import 'package:flutter_test/flutter_test.dart';
import 'package:xflow/features/player/tap_sequence_detector.dart';

void main() {
  const at = Offset(100, 300);
  Duration ms(int v) => Duration(milliseconds: v);

  /// A quick tap: down at [start], up [hold] later at the same place.
  TapResult tap(TapSequenceDetector d, int start,
      {int hold = 60, Offset position = at, int pointer = 1}) {
    d.pointerDown(pointer, position, ms(start));
    return d.pointerUp(pointer, position, ms(start + hold));
  }

  group('TapSequenceDetector', () {
    test('a lone tap is reported at once as a single tap', () {
      final d = TapSequenceDetector();
      final r = tap(d, 0);
      expect(r.kind, TapKind.single);
      expect(r.count, 1);
      expect(r.position, at);
    });

    test('a second quick tap is a double tap', () {
      // Regression: the first tap used to clear the tap state on release,
      // so the second tap could never be seen as a double tap.
      final d = TapSequenceDetector();
      expect(tap(d, 0).kind, TapKind.single);
      final second = tap(d, 180);
      expect(second.kind, TapKind.multi);
      expect(second.count, 2);
      expect(second.isFirstMulti, isTrue);
    });

    test('further quick taps keep producing hearts without undoing again', () {
      final d = TapSequenceDetector();
      tap(d, 0);
      tap(d, 180);
      final third = tap(d, 360);
      expect(third.kind, TapKind.multi);
      expect(third.count, 3);
      expect(third.isFirstMulti, isFalse);
      expect(tap(d, 540).count, 4);
    });

    test('two slow taps are two single taps', () {
      final d = TapSequenceDetector();
      expect(tap(d, 0).kind, TapKind.single);
      final second = tap(d, 500);
      expect(second.kind, TapKind.single);
      expect(second.count, 1);
    });

    test('two quick taps far apart are two single taps', () {
      final d = TapSequenceDetector();
      tap(d, 0);
      final second = tap(d, 150, position: const Offset(100, 600));
      expect(second.kind, TapKind.single);
    });

    test('a swipe is not a tap', () {
      // Regression: every swipe to the next video toggled play/pause.
      final d = TapSequenceDetector();
      d.pointerDown(1, at, ms(0));
      d.pointerMove(1, at + const Offset(0, -10));
      d.pointerMove(1, at + const Offset(0, -120));
      final r = d.pointerUp(1, at + const Offset(0, -200), ms(150));
      expect(r.kind, TapKind.none);
    });

    test('a swipe that returns to its start is still not a tap', () {
      final d = TapSequenceDetector();
      d.pointerDown(1, at, ms(0));
      d.pointerMove(1, at + const Offset(0, -60));
      d.pointerMove(1, at);
      expect(d.pointerUp(1, at, ms(200)).kind, TapKind.none);
    });

    test('small finger jitter is still a tap', () {
      final d = TapSequenceDetector();
      d.pointerDown(1, at, ms(0));
      d.pointerMove(1, at + const Offset(4, -6));
      expect(d.pointerUp(1, at + const Offset(5, -8), ms(70)).kind,
          TapKind.single);
    });

    test('a long press is not a tap', () {
      final d = TapSequenceDetector();
      expect(tap(d, 0, hold: 700).kind, TapKind.none);
    });

    test('a swipe between two taps breaks the sequence', () {
      final d = TapSequenceDetector();
      tap(d, 0);
      d.pointerDown(1, at, ms(100));
      d.pointerMove(1, at + const Offset(0, 80));
      d.pointerUp(1, at + const Offset(0, 80), ms(160));
      expect(tap(d, 220).kind, TapKind.single);
    });

    test('a second finger cancels the tap', () {
      final d = TapSequenceDetector();
      d.pointerDown(1, at, ms(0));
      d.pointerDown(2, at + const Offset(80, 0), ms(20));
      expect(
          d.pointerUp(2, at + const Offset(80, 0), ms(60)).kind, TapKind.none);
      expect(d.pointerUp(1, at, ms(80)).kind, TapKind.none);
      // And the detector recovers afterwards.
      expect(tap(d, 1000).kind, TapKind.single);
    });

    test('a cancelled pointer does not count', () {
      final d = TapSequenceDetector();
      tap(d, 0);
      d.pointerDown(1, at, ms(100));
      d.pointerCancel(1);
      expect(tap(d, 200).kind, TapKind.single);
    });

    test('reset forgets the previous tap', () {
      final d = TapSequenceDetector();
      tap(d, 0);
      d.reset();
      expect(tap(d, 100).kind, TapKind.single);
    });
  });
}
