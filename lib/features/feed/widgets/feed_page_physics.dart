import 'package:flutter/widgets.dart';

/// Page snapping for the vertical media feed.
///
/// Behaves like [PageScrollPhysics] (one page per swipe) with two changes:
/// the snap spring is tighter and critically damped, so a page lands quickly
/// without overshoot, and a release slower than [minFlingVelocity] snaps to
/// the nearest page instead of always advancing in the direction of the last
/// few pixels of movement.
///
/// Use it with `PageView(pageSnapping: false)`; with snapping on, `PageView`
/// wraps its physics in [PageScrollPhysics] and this class would be bypassed.
class FeedPageScrollPhysics extends ScrollPhysics {
  const FeedPageScrollPhysics({super.parent});

  /// Logical pixels per second a release needs to turn the page on its own.
  static const double flingVelocity = 250.0;

  static final SpringDescription _spring = SpringDescription.withDampingRatio(
    mass: 0.4,
    stiffness: 220.0,
    ratio: 1.0,
  );

  @override
  FeedPageScrollPhysics applyTo(ScrollPhysics? ancestor) {
    return FeedPageScrollPhysics(parent: buildParent(ancestor));
  }

  @override
  SpringDescription get spring => _spring;

  @override
  bool get allowImplicitScrolling => false;

  /// The pixel offset a release at [pixels] with [velocity] settles on.
  static double targetPixels({
    required double pixels,
    required double viewportDimension,
    required double minScrollExtent,
    required double maxScrollExtent,
    required double velocity,
  }) {
    if (viewportDimension <= 0) return pixels;
    double page = pixels / viewportDimension;
    if (velocity < -flingVelocity) {
      page -= 0.5;
    } else if (velocity > flingVelocity) {
      page += 0.5;
    }
    final target = page.roundToDouble() * viewportDimension;
    return target.clamp(minScrollExtent, maxScrollExtent);
  }

  @override
  Simulation? createBallisticSimulation(
      ScrollMetrics position, double velocity) {
    // Past the ends: let the parent physics bring the list back in range.
    if ((velocity <= 0.0 && position.pixels <= position.minScrollExtent) ||
        (velocity >= 0.0 && position.pixels >= position.maxScrollExtent)) {
      return super.createBallisticSimulation(position, velocity);
    }
    final tolerance = toleranceFor(position);
    final target = targetPixels(
      pixels: position.pixels,
      viewportDimension: position.viewportDimension,
      minScrollExtent: position.minScrollExtent,
      maxScrollExtent: position.maxScrollExtent,
      velocity: velocity,
    );
    if ((target - position.pixels).abs() > tolerance.distance) {
      return ScrollSpringSimulation(
        spring,
        position.pixels,
        target,
        velocity,
        tolerance: tolerance,
      );
    }
    return null;
  }
}
