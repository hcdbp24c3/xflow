import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'bookmark_provider.dart';
import '../../core/theme/app_theme.dart';
import '../../core/theme/app_widgets.dart';

class BookmarksScreen extends ConsumerWidget {
  const BookmarksScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bookmarksAsync = ref.watch(bookmarkListProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Đã lưu'),
      ),
      body: bookmarksAsync.when(
        data: (bookmarks) => bookmarks.isEmpty
            ? const EmptyState(
                icon: Icons.bookmark_border_rounded,
                message: 'Ch\u01B0a c\u00F3 bookmark n\u00E0o',
              )
            : ListView.builder(
                itemCount: bookmarks.length,
                itemBuilder: (context, index) {
                  final tweet = bookmarks[index];
                  return Dismissible(
                    key: ValueKey(tweet.id),
                    direction: DismissDirection.endToStart,
                    background: Container(
                      alignment: Alignment.centerRight,
                      padding: const EdgeInsets.only(right: 24),
                      color: AppColors.danger,
                      child: const Icon(Icons.delete_outline_rounded,
                          color: Colors.white),
                    ),
                    onDismissed: (_) {
                      ref
                          .read(bookmarkListProvider.notifier)
                          .toggleBookmark(tweet.copyWith(isBookmarked: true));
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(
                          content: const Text('\u0110\u00E3 xo\u00E1 bookmark'),
                          action: SnackBarAction(
                            label: 'Ho\u00E0n t\u00E1c',
                            onPressed: () {
                              ref
                                  .read(bookmarkListProvider.notifier)
                                  .addBookmark(tweet);
                            },
                          ),
                        ),
                      );
                    },
                    child: ListTile(
                      leading: CircleAvatar(
                        radius: 20,
                        backgroundColor: AppColors.surfaceHighest,
                        backgroundImage: tweet.userAvatarUrlHighRes != null
                            ? CachedNetworkImageProvider(
                                tweet.userAvatarUrlHighRes!)
                            : null,
                        child: tweet.userAvatarUrlHighRes == null
                            ? const Icon(Icons.person_rounded,
                                size: 20, color: AppColors.textSecondary)
                            : null,
                      ),
                      title: Text(
                        tweet.userHandle,
                        style: const TextStyle(
                            fontWeight: FontWeight.w600, fontSize: 14),
                      ),
                      subtitle: Text(
                        tweet.text,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 13, color: AppColors.textSecondary),
                      ),
                      trailing: const Icon(Icons.bookmark_rounded,
                          size: 20, color: AppColors.bookmark),
                    ),
                  );
                },
              ),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, st) => EmptyState(
          icon: Icons.error_outline_rounded,
          message: 'L\u1ED7i: $e',
        ),
      ),
    );
  }
}
