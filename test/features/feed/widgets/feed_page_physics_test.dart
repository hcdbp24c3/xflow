import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xflow/features/feed/widgets/feed_page_physics.dart';

void main() {
  const page = 800.0;
  double target(double pixels, double velocity) =>
      FeedPageScrollPhysics.targetPixels(
        pixels: pixels,
        viewportDimension: page,
        minScrollExtent: 0,
        maxScrollExtent: page * 9,
        velocity: velocity,
      );

  group('FeedPageScrollPhysics.targetPixels', () {
    test('a short, slow drag snaps back to the same page', () {
      expect(target(page * 2 + 120, 80), page * 2);
      expect(target(page * 2 - 120, -80), page * 2);
    });

    test('a slow drag past halfway goes to the next page', () {
      expect(target(page * 2 + 450, 0), page * 3);
      expect(target(page * 2 - 450, 0), page * 1);
    });

    test('a fling turns exactly one page, however fast', () {
      expect(target(page * 2 + 40, 900), page * 3);
      expect(target(page * 2 + 40, 9000), page * 3);
      expect(target(page * 2 - 40, -900), page * 1);
      expect(target(page * 2 - 40, -9000), page * 1);
    });

    test('a fling back towards the current page returns to it', () {
      // Dragged 30% towards the next page, then flicked back.
      expect(target(page * 2 + 240, -900), page * 2);
    });

    test('never targets beyond the ends', () {
      expect(target(10, -3000), 0);
      expect(target(page * 9 - 10, 3000), page * 9);
    });
  });

  group('FeedPageScrollPhysics', () {
    FixedScrollMetrics metrics(double pixels) => FixedScrollMetrics(
          minScrollExtent: 0,
          maxScrollExtent: page * 9,
          pixels: pixels,
          viewportDimension: page,
          axisDirection: AxisDirection.down,
          devicePixelRatio: 1,
        );

    test('settles on the target page and stops there', () {
      const physics = FeedPageScrollPhysics();
      final sim =
          physics.createBallisticSimulation(metrics(page * 2 + 40), 900);
      expect(sim, isNotNull);
      expect(sim!.x(5), closeTo(page * 3, 0.5));
      expect(sim.isDone(5), isTrue);
      // Critically damped: it must not run past the page and come back.
      for (var t = 0.0; t < 2; t += 0.01) {
        expect(sim.x(t), lessThanOrEqualTo(page * 3 + 0.5));
      }
    });

    test('lands within a third of a second', () {
      const physics = FeedPageScrollPhysics();
      final sim =
          physics.createBallisticSimulation(metrics(page * 2 + 40), 900);
      expect((sim!.x(0.33) - page * 3).abs(), lessThan(8));
    });

    test('no simulation when already resting on a page', () {
      const physics = FeedPageScrollPhysics();
      expect(physics.createBallisticSimulation(metrics(page * 4), 0), isNull);
    });

    test('applyTo keeps the type', () {
      const physics = FeedPageScrollPhysics();
      expect(physics.applyTo(const ClampingScrollPhysics()),
          isA<FeedPageScrollPhysics>());
    });
  });
}
