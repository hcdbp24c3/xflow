import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'bookmark_provider.dart';

class BookmarksScreen extends ConsumerWidget {
  const BookmarksScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bookmarksAsync = ref.watch(bookmarkListProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Bookmarks'),
      ),
      body: bookmarksAsync.when(
        data: (bookmarks) => bookmarks.isEmpty
            ? Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.bookmark_border,
                        size: 64, color: Colors.white24),
                    const SizedBox(height: 16),
                    const Text(
                      'Ch\u00E3a c\u00F3 bookmark n\u00E0o',
                      style: TextStyle(color: Colors.white70, fontSize: 16),
                    ),
                  ],
                ),
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
                      color: Colors.red,
                      child:
                          const Icon(Icons.delete, color: Colors.white),
                    ),
                    onDismissed: (_) {
                      ref
                          .read(bookmarkListProvider.notifier)
                          .toggleBookmark(tweet);
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(
                          content:
                              const Text('\u0110\u00E3 xo\u00E1 bookmark'),
                          action: SnackBarAction(
                            label: 'Ho\u00E0n t\u00E1c',
                            onPressed: () {
                              ref
                                  .read(bookmarkListProvider.notifier)
                                  .toggleBookmark(
                                      tweet.copyWith(isBookmarked: false));
                            },
                          ),
                        ),
                      );
                    },
                    child: ListTile(
                      leading: CircleAvatar(
                        radius: 20,
                        backgroundColor: Colors.white24,
                        backgroundImage:
                            tweet.userAvatarUrlHighRes != null
                                ? CachedNetworkImageProvider(
                                    tweet.userAvatarUrlHighRes!)
                                : null,
                        child: tweet.userAvatarUrlHighRes == null
                            ? const Icon(Icons.person, size: 20)
                            : null,
                      ),
                      title: Text(
                        tweet.userHandle,
                        style: const TextStyle(
                            fontWeight: FontWeight.bold, fontSize: 14),
                      ),
                      subtitle: Text(
                        tweet.text,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 14),
                      ),
                      trailing: const Icon(Icons.bookmark,
                          color: Colors.amber),
                    ),
                  );
                },
              ),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, st) => Center(
          child: Text('L\u1ED7i: $e', style: const TextStyle(color: Colors.white70)),
        ),
      ),
    );
  }
}
