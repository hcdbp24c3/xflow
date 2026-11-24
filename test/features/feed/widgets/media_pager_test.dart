import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:xflow/core/models/tweet.dart';
import 'package:xflow/features/feed/feed_provider.dart';
import 'package:xflow/features/feed/widgets/media_pager.dart';
import 'package:xflow/features/player/player_pool_provider.dart';

void main() {
  List<Tweet> makeTweets(int n) => [
        for (var i = 0; i < n; i++)
          Tweet(
            id: 't$i',
            text: 'tweet $i',
            userHandle: '@u',
            mediaUrls: ['https://test.com/$i.mp4'],
            isVideo: true,
          ),
      ];

  late _RecordingPool pool;
  late List<int> landed;
  late Map<String, bool> visible;

  Future<void> pumpPager(
    WidgetTester tester,
    List<Tweet> tweets, {
    bool isActive = true,
    PageController? controller,
  }) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [playerPoolProvider.overrideWith(() => pool)],
        child: MaterialApp(
          home: Scaffold(
            body: MediaPager(
              tweets: tweets,
              controller: controller ?? _controller,
              isActive: isActive,
              onIndexChanged: landed.add,
              itemBuilder: (context, tweet, isVisible, onError) {
                visible[tweet.id] = isVisible;
                return ColoredBox(
                  color: Colors.black,
                  child: Center(child: Text(tweet.id)),
                );
              },
            ),
          ),
        ),
      ),
    );
    await tester.pump();
  }

  setUp(() {
    pool = _RecordingPool();
    landed = [];
    visible = {};
    _controller = PageController();
  });

  tearDown(() => _controller.dispose());

  group('MediaPager', () {
    testWidgets('warms the pool around the first item', (tester) async {
      await pumpPager(tester, makeTweets(10));
      expect(pool.warmed, ['t0', 't1', 't2', 't3']);
      expect(visible['t0'], isTrue);
      expect(landed, isEmpty);
    });

    testWidgets('does nothing heavy while the page is being dragged',
        (tester) async {
      // Regression: the current item used to switch at the halfway point,
      // which created/disposed players and restarted playback mid-swipe.
      await pumpPager(tester, makeTweets(10));
      pool.reset();
      final height = tester.getSize(find.byType(MediaPager)).height;

      final gesture =
          await tester.startGesture(tester.getCenter(find.byType(MediaPager)));
      for (var i = 0; i < 8; i++) {
        await gesture.moveBy(Offset(0, -height * 0.1));
        await tester.pump(const Duration(milliseconds: 16));
      }
      // 80% of the way to the next page, finger still down.
      expect(landed, isEmpty);
      expect(pool.warmed, isEmpty);
      expect(pool.cleanups, 0);
      expect(visible['t0'], isTrue);

      await gesture.up();
      await tester.pumpAndSettle();

      expect(landed, [1]);
      expect(visible['t1'], isTrue);
      expect(visible['t0'], isFalse);
      expect(pool.warmed, ['t4']);
      expect(pool.lastActive, {'t0', 't1', 't2', 't3', 't4'});
    });

    testWidgets('a small slow drag returns to the same item', (tester) async {
      await pumpPager(tester, makeTweets(10));
      pool.reset();
      final height = tester.getSize(find.byType(MediaPager)).height;

      final gesture =
          await tester.startGesture(tester.getCenter(find.byType(MediaPager)));
      await gesture.moveBy(Offset(0, -height * 0.15));
      await tester.pump(const Duration(milliseconds: 400));
      await gesture.up();
      await tester.pumpAndSettle();

      expect(landed, isEmpty);
      expect(_controller.page, 0);
      expect(visible['t0'], isTrue);
    });

    testWidgets('a fling moves exactly one item', (tester) async {
      await pumpPager(tester, makeTweets(10));
      await tester.fling(find.byType(MediaPager), const Offset(0, -300), 4000);
      await tester.pumpAndSettle();
      expect(landed, [1]);
      expect(_controller.page, 1);

      await tester.fling(find.byType(MediaPager), const Offset(0, -300), 4000);
      await tester.pumpAndSettle();
      expect(landed, [1, 2]);
    });

    testWidgets('jumpToPage is picked up as the current item', (tester) async {
      await pumpPager(tester, makeTweets(10));
      _controller.jumpToPage(5);
      await tester.pumpAndSettle();
      expect(landed, [5]);
      expect(visible['t5'], isTrue);
      expect(pool.lastActive, {'t4', 't5', 't6', 't7', 't8'});
    });

    testWidgets(
        'an inactive pager shows nothing as visible and leaves the '
        'pool alone, then warms up when it becomes active', (tester) async {
      final tweets = makeTweets(10);
      await pumpPager(tester, tweets, isActive: false);
      expect(visible['t0'], isFalse);
      expect(pool.warmed, isEmpty);
      expect(pool.cleanups, 0);

      await pumpPager(tester, tweets, isActive: true);
      expect(visible['t0'], isTrue);
      expect(pool.warmed, ['t0', 't1', 't2', 't3']);
    });

    testWidgets('survives a feed that contains the same tweet twice',
        (tester) async {
      final tweets = makeTweets(3);
      await pumpPager(tester, [...tweets, tweets[1], tweets[2]]);
      await tester.fling(find.byType(MediaPager), const Offset(0, -300), 4000);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(landed, [1]);
    });
  });

  group('FeedNotifier.protectedHeadLength', () {
    int protect(int view, int length, {bool reset = false}) =>
        FeedNotifier.protectedHeadLength(
            viewIndex: view, currentLength: length, resetHead: reset);

    test('protects through the item after the one being watched', () {
      // Regression: only the first two items were protected, so a refresh
      // that finished while the user was on item 7 replaced item 7.
      expect(protect(7, 40), 9);
      expect(protect(0, 40), 2);
      expect(protect(1, 40), 3);
    });

    test('never exceeds the list', () {
      expect(protect(7, 5), 5);
      expect(protect(0, 0), 0);
      expect(protect(0, 1), 1);
    });

    test('an explicit refresh protects nothing', () {
      expect(protect(7, 40, reset: true), 0);
    });
  });
}

late PageController _controller;

class _RecordingPool extends PlayerPoolNotifier {
  /// Ids a player was created for, in order. Like the real pool, asking
  /// again for an id that already has a player creates nothing.
  final warmed = <String>[];
  final _existing = <String>{};
  int cleanups = 0;
  Set<String> lastActive = {};

  void reset() {
    warmed.clear();
    cleanups = 0;
  }

  @override
  Map<String, PlayerInstance> build() => {};

  @override
  void warmup(String id, String url, {bool isLandscape = false}) {
    if (_existing.add(id)) warmed.add(id);
  }

  @override
  void cleanupExcept(Set<String> activeIds) {
    cleanups++;
    lastActive = activeIds;
    _existing.retainAll(activeIds);
  }
}
