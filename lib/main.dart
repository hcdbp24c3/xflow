import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:media_kit/media_kit.dart';
import 'features/feed/tiktok_feed_screen.dart';
import 'features/feed/hashtag_feed_screen.dart';
import 'features/subscriptions/subscription_list_screen.dart';
import 'features/profile/user_details_screen.dart';
import 'features/profile/user_media_feed_screen.dart';
import 'core/navigation/navigation_provider.dart';
import 'core/theme/app_theme.dart';
import 'core/theme/app_widgets.dart';
import 'core/client/background_sync.dart';
import 'core/client/transaction_id_service.dart';
import 'core/client/twitter_client.dart';
import 'features/settings/settings_provider.dart';
import 'core/client/twitter_account.dart';
import 'core/database/repository.dart';
import 'core/utils/lifecycle_provider.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();

  await Future.wait([
    TwitterAccount.init(),
    Repository.database,
  ]);

  runApp(const ProviderScope(child: XFlowApp()));
}

class XFlowApp extends ConsumerWidget {
  const XFlowApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Listen to lifecycle changes
    ref.listen(lifecycleProvider, (previous, next) {
      if (next == AppLifecycle.resumed) {
        debugPrint('XFLOW: App resumed. Ensuring BackgroundSync is active.');
        TwitterClient.resetQueue();
        BackgroundSync.restart(TwitterClient(), ref.read(settingsProvider));
      }
    });

    ref.listen(settingsProvider, (prev, next) {
      if (prev?.syncInterval != next.syncInterval ||
          prev?.syncBatchSize != next.syncBatchSize ||
          prev?.pruneThreshold != next.pruneThreshold) {
        BackgroundSync.restart(TwitterClient(), next);
      }
    });

    WidgetsBinding.instance.addPostFrameCallback((_) {
      BackgroundSync.start(TwitterClient(), ref.read(settingsProvider));
    });

    return MaterialApp(
      title: 'XFlow',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark(),
      home: const MainScaffold(),
    );
  }
}

class MainScaffold extends ConsumerWidget {
  const MainScaffold({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final nav = ref.watch(navigationProvider);
    final navNotifier = ref.read(navigationProvider.notifier);

    final mainScreens = IndexedStack(
      index: nav.currentTab.index,
      children: const [
        TiktokFeedScreen(),
        SubscriptionListScreen(isStandalone: false),
        HashtagListScreen(),
      ],
    );

    Widget? overlayScreen;
    String overlayKey = '';
    if (nav.selectedHashtag != null) {
      overlayKey = 'hashtag:${nav.selectedHashtag}';
      overlayScreen = HashtagMediaFeedScreen(hashtag: nav.selectedHashtag!);
    } else if (nav.selectedUser != null) {
      if (nav.userMediaInitialIndex != null) {
        overlayKey = 'media:${nav.selectedUser}';
        overlayScreen = UserMediaFeedScreen(
          screenName: nav.selectedUser!,
          initialIndex: nav.userMediaInitialIndex!,
          initialTweetId: nav.userMediaInitialTweetId,
        );
      } else {
        overlayKey = 'user:${nav.selectedUser}';
        overlayScreen = UserDetailsScreen(screenName: nav.selectedUser!);
      }
    }

    final body = Stack(
      children: [
        Visibility(
          visible: overlayScreen == null,
          maintainState: true,
          child: mainScreens,
        ),
        const Positioned(
          left: 0,
          top: 0,
          child: TransactionIdWebViewHost(),
        ),
        // Profile and hashtag screens fade in over the tabs and out again.
        Positioned.fill(
          child: AnimatedSwitcher(
            duration: AppDurations.normal,
            switchInCurve: Curves.easeOutCubic,
            switchOutCurve: Curves.easeInCubic,
            child: overlayScreen == null
                ? const SizedBox.shrink(key: ValueKey('no-overlay'))
                : ColoredBox(
                    key: ValueKey(overlayKey),
                    color: AppColors.background,
                    child: overlayScreen,
                  ),
          ),
        ),
      ],
    );

    return PopScope(
      canPop: nav.selectedUser == null && nav.selectedHashtag == null,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) {
          navNotifier.back();
        }
      },
      child: Scaffold(
        body: body,
        bottomNavigationBar: _BottomBar(
          current: nav.currentTab,
          onSelected: (tab) {
            navNotifier.setTab(tab);
            if (tab == MainTab.subscriptions) {
              ref.invalidate(subscriptionListProvider);
            }
          },
        ),
      ),
    );
  }
}

/// A slim, flat tab bar: icon over a small label, no pill indicator.
class _BottomBar extends StatelessWidget {
  final MainTab current;
  final ValueChanged<MainTab> onSelected;

  const _BottomBar({required this.current, required this.onSelected});

  static const _items = [
    (
      tab: MainTab.media,
      icon: Icons.play_circle_outline_rounded,
      selectedIcon: Icons.play_circle_rounded,
      label: 'Media',
    ),
    (
      tab: MainTab.subscriptions,
      icon: Icons.people_outline_rounded,
      selectedIcon: Icons.people_rounded,
      label: 'Subscriptions',
    ),
    (
      tab: MainTab.trending,
      icon: Icons.tag_rounded,
      selectedIcon: Icons.tag_rounded,
      label: 'Hashtags',
    ),
  ];

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: AppColors.background,
        border: Border(top: BorderSide(color: AppColors.outline, width: 0.5)),
      ),
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: 54,
          child: Row(
            children: [
              for (final item in _items)
                Expanded(
                  child: _BottomBarItem(
                    icon: item.icon,
                    selectedIcon: item.selectedIcon,
                    label: item.label,
                    selected: item.tab == current,
                    onTap: () => onSelected(item.tab),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _BottomBarItem extends StatelessWidget {
  final IconData icon;
  final IconData selectedIcon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _BottomBarItem({
    required this.icon,
    required this.selectedIcon,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.textPrimary : AppColors.textTertiary;
    return PressableScale(
      onTap: onTap,
      tooltip: label,
      pressedScale: 0.92,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          AnimatedSwitcher(
            duration: AppDurations.fast,
            child: Icon(
              selected ? selectedIcon : icon,
              key: ValueKey(selected),
              color: color,
              size: 24,
            ),
          ),
          const SizedBox(height: 3),
          AnimatedDefaultTextStyle(
            duration: AppDurations.fast,
            style: TextStyle(
              color: color,
              fontSize: 10.5,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
              letterSpacing: 0.1,
            ),
            child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
        ],
      ),
    );
  }
}
