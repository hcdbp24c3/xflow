import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:xflow/core/client/twitter_client.dart';
import 'package:xflow/core/models/tweet.dart';
import 'package:xflow/features/feed/feed_provider.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Tweet tweet(String id, {bool liked = false, int likes = 5}) => Tweet(
        id: id,
        text: 'tweet $id',
        userHandle: '@someone',
        mediaUrls: ['https://test.com/$id.mp4'],
        isVideo: true,
        isLiked: liked,
        favoriteCount: likes,
      );

  ProviderContainer makeContainer(
      _FakeTwitterClient client, List<Tweet> feedTweets) {
    final container = ProviderContainer(
      overrides: [
        twitterClientProvider.overrideWithValue(client),
        feedNotifierProvider.overrideWith(
            () => _StaticFeedNotifier(FeedState(tweets: feedTweets))),
      ],
    );
    addTearDown(container.dispose);
    // Keep the auto-dispose feed alive for the duration of the test.
    container.listen(feedNotifierProvider, (_, __) {});
    return container;
  }

  group('toggleLike', () {
    test('likes a tweet that is in the main feed', () async {
      final client = _FakeTwitterClient();
      final container = makeContainer(client, [tweet('a'), tweet('b')]);
      await container.read(feedNotifierProvider.future);

      await container.read(feedNotifierProvider.notifier).toggleLike('b');

      final b = container.read(feedNotifierProvider).value!.tweets[1];
      expect(b.isLiked, isTrue);
      expect(b.favoriteCount, 6);
      expect(client.favorited, ['b']);
      expect(container.read(tweetOverridesProvider), isEmpty);
    });

    test('likes a tweet that is NOT in the main feed (hashtag/profile feed)',
        () async {
      // Regression: toggleLike returned silently when the tweet was not in
      // the main feed, so likes did nothing on hashtag and profile feeds.
      final client = _FakeTwitterClient();
      final container = makeContainer(client, [tweet('a')]);
      await container.read(feedNotifierProvider.future);
      final outside = tweet('outside');

      await container
          .read(feedNotifierProvider.notifier)
          .toggleLike('outside', tweet: outside);

      expect(client.favorited, ['outside']);
      final stored = container.read(tweetOverridesProvider)['outside'];
      expect(stored, isNotNull);
      expect(stored!.isLiked, isTrue);
      expect(stored.favoriteCount, 6);

      // A second toggle starts from the stored state and unlikes.
      await container
          .read(feedNotifierProvider.notifier)
          .toggleLike('outside', tweet: outside);
      expect(client.unfavorited, ['outside']);
      final after = container.read(tweetOverridesProvider)['outside']!;
      expect(after.isLiked, isFalse);
      expect(after.favoriteCount, 5);
    });

    test('reverts when the request fails', () async {
      final client = _FakeTwitterClient()..succeed = false;
      final container = makeContainer(client, [tweet('a')]);
      await container.read(feedNotifierProvider.future);
      final outside = tweet('outside');

      await container.read(feedNotifierProvider.notifier).toggleLike('a');
      await container
          .read(feedNotifierProvider.notifier)
          .toggleLike('outside', tweet: outside);

      expect(container.read(feedNotifierProvider).value!.tweets[0].isLiked,
          isFalse);
      expect(
          container.read(tweetOverridesProvider)['outside']!.isLiked, isFalse);
    });

    test('an unknown tweet without a fallback is ignored', () async {
      final client = _FakeTwitterClient();
      final container = makeContainer(client, [tweet('a')]);
      await container.read(feedNotifierProvider.future);

      await container.read(feedNotifierProvider.notifier).toggleLike('nope');

      expect(client.favorited, isEmpty);
      expect(container.read(tweetOverridesProvider), isEmpty);
    });

    test('retweet works for a tweet outside the main feed', () async {
      final client = _FakeTwitterClient();
      final container = makeContainer(client, [tweet('a')]);
      await container.read(feedNotifierProvider.future);

      await container
          .read(feedNotifierProvider.notifier)
          .toggleRetweet('outside', tweet: tweet('outside'));

      expect(client.retweeted, ['outside']);
      expect(container.read(tweetOverridesProvider)['outside']!.isRetweeted,
          isTrue);
    });
  });
}

class _StaticFeedNotifier extends FeedNotifier {
  final FeedState initial;
  _StaticFeedNotifier(this.initial);

  @override
  FutureOr<FeedState> build() => initial;
}

class _FakeTwitterClient extends TwitterClient {
  bool succeed = true;
  final favorited = <String>[];
  final unfavorited = <String>[];
  final retweeted = <String>[];

  @override
  Future<bool> favoriteTweet(String tweetId) async {
    favorited.add(tweetId);
    return succeed;
  }

  @override
  Future<bool> unfavoriteTweet(String tweetId) async {
    unfavorited.add(tweetId);
    return succeed;
  }

  @override
  Future<bool> retweetTweet(String tweetId) async {
    retweeted.add(tweetId);
    return succeed;
  }

  @override
  Future<bool> unretweetTweet(String tweetId) async => succeed;
}
