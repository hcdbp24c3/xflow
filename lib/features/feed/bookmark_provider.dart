import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/tweet.dart';
import '../../core/database/repository.dart';
import '../../core/utils/app_logger.dart';

class BookmarkListNotifier extends AsyncNotifier<List<Tweet>> {
  @override
  FutureOr<List<Tweet>> build() async {
    return _loadBookmarks();
  }

  Future<List<Tweet>> _loadBookmarks() async {
    try {
      final bookmarks = await Repository.getBookmarks();
      AppLogger.log('XFLOW: Loaded ${bookmarks.length} bookmarks');
      return bookmarks;
    } catch (e, st) {
      AppLogger.log('XFLOW: Error loading bookmarks: $e\n$st');
      return [];
    }
  }

  Future<void> loadBookmarks() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(() => _loadBookmarks());
  }

  Future<void> toggleBookmark(Tweet tweet) async {
    final currentState = state.value;
    if (currentState == null) return;

    final newIsBookmarked = !tweet.isBookmarked;

    if (newIsBookmarked) {
      // Add bookmark: optimistic insert at the top
      final updatedTweet = tweet.copyWith(isBookmarked: true);
      state = AsyncData([updatedTweet, ...currentState]);

      try {
        await Repository.insertBookmark(tweet);
        AppLogger.log('XFLOW: Bookmarked tweet ${tweet.id}');
      } catch (e) {
        AppLogger.log('XFLOW: Failed to bookmark tweet ${tweet.id}: $e');
        state = await AsyncValue.guard(() => _loadBookmarks());
      }
    } else {
      // Remove bookmark: optimistic removal
      final updated =
          currentState.where((t) => t.id != tweet.id).toList();
      state = AsyncData(updated);

      try {
        await Repository.removeBookmark(tweet.id);
        AppLogger.log('XFLOW: Removed bookmark for tweet ${tweet.id}');
      } catch (e) {
        AppLogger.log('XFLOW: Failed to remove bookmark ${tweet.id}: $e');
        state = await AsyncValue.guard(() => _loadBookmarks());
      }
    }
  }

  Future<void> addBookmark(Tweet tweet) async {
    final currentState = state.value;
    if (currentState == null) return;

    final updatedTweet = tweet.copyWith(isBookmarked: true);
    state = AsyncData([updatedTweet, ...currentState]);

    try {
      await Repository.insertBookmark(tweet);
      AppLogger.log('XFLOW: Bookmarked tweet ${tweet.id}');
    } catch (e) {
      AppLogger.log('XFLOW: Failed to bookmark tweet ${tweet.id}: $e');
      state = await AsyncValue.guard(() => _loadBookmarks());
    }
  }
}

final bookmarkListProvider =
    AsyncNotifierProvider.autoDispose<BookmarkListNotifier, List<Tweet>>(
        BookmarkListNotifier.new);
