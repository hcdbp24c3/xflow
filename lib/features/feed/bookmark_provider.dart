import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/models/tweet.dart';
import '../../core/database/repository.dart';

class BookmarkListNotifier extends AsyncNotifier<List<Tweet>> {
  @override
  FutureOr<List<Tweet>> build() async {
    return _loadBookmarks();
  }

  Future<List<Tweet>> _loadBookmarks() async {
    try {
      final bookmarks = await Repository.getBookmarks();
      debugPrint('XFLOW: Loaded ${bookmarks.length} bookmarks');
      return bookmarks;
    } catch (e, st) {
      debugPrint('XFLOW: Error loading bookmarks: $e\n$st');
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
        debugPrint('XFLOW: Bookmarked tweet ${tweet.id}');
      } catch (e) {
        debugPrint('XFLOW: Failed to bookmark tweet ${tweet.id}: $e');
        state = await AsyncValue.guard(() => _loadBookmarks());
      }
    } else {
      // Remove bookmark: optimistic removal
      final updated =
          currentState.where((t) => t.id != tweet.id).toList();
      state = AsyncData(updated);

      try {
        await Repository.removeBookmark(tweet.id);
        debugPrint('XFLOW: Removed bookmark for tweet ${tweet.id}');
      } catch (e) {
        debugPrint('XFLOW: Failed to remove bookmark ${tweet.id}: $e');
        state = await AsyncValue.guard(() => _loadBookmarks());
      }
    }
  }
}

final bookmarkListProvider =
    AsyncNotifierProvider.autoDispose<BookmarkListNotifier, List<Tweet>>(
        BookmarkListNotifier.new);
