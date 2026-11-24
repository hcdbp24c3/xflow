import 'package:flutter/cupertino.dart' show CupertinoPageTransitionsBuilder;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Every colour, radius and duration the app uses lives here, so screens ask
/// the theme instead of hard-coding their own.
abstract final class AppColors {
  static const Color background = Color(0xFF000000);
  static const Color surface = Color(0xFF0F0F11);
  static const Color surfaceHigh = Color(0xFF18181B);
  static const Color surfaceHighest = Color(0xFF222226);
  static const Color outline = Color(0xFF2A2A2F);

  static const Color textPrimary = Color(0xFFF5F5F7);
  static const Color textSecondary = Color(0xFF9B9BA3);
  static const Color textTertiary = Color(0xFF6B6B73);

  /// The single accent colour.
  static const Color accent = Color(0xFF4C8DFF);

  // Meaning-carrying colours, used only for their meaning.
  static const Color like = Color(0xFFFF3B5C);
  static const Color retweet = Color(0xFF2ECC8F);
  static const Color bookmark = Color(0xFFFFC53D);
  static const Color danger = Color(0xFFFF5A5F);
  static const Color warning = Color(0xFFFFA940);
  static const Color success = Color(0xFF2ECC8F);

  /// Dark scrim behind controls that float over media.
  static const Color scrim = Color(0x66000000);
}

abstract final class AppRadius {
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 24;
}

abstract final class AppDurations {
  static const Duration fast = Duration(milliseconds: 120);
  static const Duration normal = Duration(milliseconds: 200);
  static const Duration slow = Duration(milliseconds: 320);
}

abstract final class AppTheme {
  /// Light status-bar icons over the always-dark UI.
  static const SystemUiOverlayStyle overlayStyle = SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    statusBarBrightness: Brightness.dark,
    systemNavigationBarColor: AppColors.background,
    systemNavigationBarIconBrightness: Brightness.light,
  );

  static ThemeData dark() {
    const scheme = ColorScheme(
      brightness: Brightness.dark,
      primary: AppColors.accent,
      onPrimary: Colors.white,
      primaryContainer: Color(0xFF16284A),
      onPrimaryContainer: Color(0xFFD6E4FF),
      secondary: AppColors.textPrimary,
      onSecondary: AppColors.background,
      secondaryContainer: AppColors.surfaceHighest,
      onSecondaryContainer: AppColors.textPrimary,
      tertiary: AppColors.accent,
      onTertiary: Colors.white,
      error: AppColors.danger,
      onError: Colors.white,
      surface: AppColors.background,
      onSurface: AppColors.textPrimary,
      onSurfaceVariant: AppColors.textSecondary,
      surfaceContainerLowest: AppColors.background,
      surfaceContainerLow: AppColors.surface,
      surfaceContainer: AppColors.surface,
      surfaceContainerHigh: AppColors.surfaceHigh,
      surfaceContainerHighest: AppColors.surfaceHighest,
      outline: AppColors.outline,
      outlineVariant: AppColors.outline,
      inverseSurface: AppColors.textPrimary,
      onInverseSurface: AppColors.background,
      scrim: Colors.black,
      shadow: Colors.black,
    );

    final base = ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: scheme,
      scaffoldBackgroundColor: AppColors.background,
      canvasColor: AppColors.background,
      splashFactory: InkRipple.splashFactory,
      highlightColor: Colors.white.withValues(alpha: 0.04),
      splashColor: Colors.white.withValues(alpha: 0.06),
      visualDensity: VisualDensity.standard,
    );

    final text = base.textTheme
        .apply(
          bodyColor: AppColors.textPrimary,
          displayColor: AppColors.textPrimary,
        )
        .copyWith(
          headlineSmall: base.textTheme.headlineSmall?.copyWith(
            fontSize: 22,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.3,
            color: AppColors.textPrimary,
          ),
          titleLarge: base.textTheme.titleLarge?.copyWith(
            fontSize: 20,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.2,
            color: AppColors.textPrimary,
          ),
          titleMedium: base.textTheme.titleMedium?.copyWith(
            fontSize: 16,
            fontWeight: FontWeight.w600,
            letterSpacing: -0.1,
            color: AppColors.textPrimary,
          ),
          titleSmall: base.textTheme.titleSmall?.copyWith(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            color: AppColors.textPrimary,
          ),
          bodyLarge: base.textTheme.bodyLarge?.copyWith(
            fontSize: 16,
            height: 1.4,
            letterSpacing: 0,
            color: AppColors.textPrimary,
          ),
          bodyMedium: base.textTheme.bodyMedium?.copyWith(
            fontSize: 14,
            height: 1.4,
            letterSpacing: 0,
            color: AppColors.textPrimary,
          ),
          bodySmall: base.textTheme.bodySmall?.copyWith(
            fontSize: 12.5,
            height: 1.35,
            letterSpacing: 0,
            color: AppColors.textSecondary,
          ),
          labelLarge: base.textTheme.labelLarge?.copyWith(
            fontSize: 14,
            fontWeight: FontWeight.w600,
            letterSpacing: 0,
          ),
          labelSmall: base.textTheme.labelSmall?.copyWith(
            fontSize: 11,
            fontWeight: FontWeight.w500,
            letterSpacing: 0.1,
            color: AppColors.textSecondary,
          ),
        );

    final buttonShape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(AppRadius.md),
    );
    const buttonPadding = EdgeInsets.symmetric(horizontal: 20, vertical: 12);

    return base.copyWith(
      textTheme: text,
      appBarTheme: AppBarTheme(
        backgroundColor: AppColors.background,
        surfaceTintColor: Colors.transparent,
        foregroundColor: AppColors.textPrimary,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: true,
        systemOverlayStyle: overlayStyle,
        titleTextStyle: text.titleMedium?.copyWith(fontSize: 17),
        iconTheme: const IconThemeData(color: AppColors.textPrimary, size: 22),
      ),
      cardTheme: CardThemeData(
        color: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        margin: const EdgeInsets.symmetric(vertical: 4),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.lg),
        ),
        clipBehavior: Clip.antiAlias,
      ),
      listTileTheme: ListTileThemeData(
        iconColor: AppColors.textSecondary,
        // No textColor here: it would override the subtitle colour below.
        titleTextStyle: text.bodyLarge?.copyWith(
          fontSize: 15.5,
          color: AppColors.textPrimary,
        ),
        subtitleTextStyle: text.bodySmall?.copyWith(
          fontSize: 12.5,
          color: AppColors.textSecondary,
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16),
      ),
      dividerTheme: const DividerThemeData(
        color: AppColors.outline,
        thickness: 0.5,
        space: 1,
      ),
      iconTheme: const IconThemeData(color: AppColors.textPrimary, size: 22),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(foregroundColor: AppColors.textPrimary),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          shape: buttonShape,
          padding: buttonPadding,
          textStyle: text.labelLarge,
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: AppColors.surfaceHighest,
          foregroundColor: AppColors.textPrimary,
          surfaceTintColor: Colors.transparent,
          elevation: 0,
          shape: buttonShape,
          padding: buttonPadding,
          textStyle: text.labelLarge,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: AppColors.textPrimary,
          side: const BorderSide(color: AppColors.outline),
          shape: buttonShape,
          padding: buttonPadding,
          textStyle: text.labelLarge,
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: AppColors.accent,
          shape: buttonShape,
          textStyle: text.labelLarge,
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: AppColors.surfaceHigh,
        hintStyle: text.bodyMedium?.copyWith(color: AppColors.textTertiary),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
          borderSide: const BorderSide(color: AppColors.accent, width: 1),
        ),
      ),
      searchBarTheme: SearchBarThemeData(
        elevation: const WidgetStatePropertyAll(0),
        backgroundColor: const WidgetStatePropertyAll(AppColors.surfaceHigh),
        surfaceTintColor: const WidgetStatePropertyAll(Colors.transparent),
        overlayColor: const WidgetStatePropertyAll(Colors.transparent),
        constraints: const BoxConstraints(minHeight: 44, maxHeight: 44),
        shape: WidgetStatePropertyAll(RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
        )),
        hintStyle: WidgetStatePropertyAll(
          text.bodyMedium?.copyWith(color: AppColors.textTertiary),
        ),
        textStyle: WidgetStatePropertyAll(text.bodyMedium),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: AppColors.surfaceHigh,
        selectedColor: AppColors.textPrimary,
        side: BorderSide.none,
        showCheckmark: false,
        labelStyle: text.labelLarge?.copyWith(fontSize: 13),
        secondaryLabelStyle:
            text.labelLarge?.copyWith(fontSize: 13, color: Colors.black),
        shape: const StadiumBorder(),
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: AppColors.surfaceHigh,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.xl),
        ),
        titleTextStyle: text.titleLarge?.copyWith(fontSize: 18),
        contentTextStyle:
            text.bodyMedium?.copyWith(color: AppColors.textSecondary),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius:
              BorderRadius.vertical(top: Radius.circular(AppRadius.xl)),
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: AppColors.surfaceHighest,
        contentTextStyle: text.bodyMedium,
        actionTextColor: AppColors.accent,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
        ),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: AppColors.surfaceHigh,
        surfaceTintColor: Colors.transparent,
        elevation: 4,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
        ),
        textStyle: text.bodyMedium,
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: AppColors.accent,
        linearTrackColor: Colors.transparent,
        circularTrackColor: Colors.transparent,
        linearMinHeight: 2,
      ),
      sliderTheme: SliderThemeData(
        trackHeight: 3,
        activeTrackColor: AppColors.accent,
        inactiveTrackColor: AppColors.surfaceHighest,
        thumbColor: Colors.white,
        overlayColor: AppColors.accent.withValues(alpha: 0.12),
        thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 8),
        overlayShape: const RoundSliderOverlayShape(overlayRadius: 18),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) =>
            states.contains(WidgetState.selected)
                ? Colors.white
                : AppColors.textSecondary),
        trackColor: WidgetStateProperty.resolveWith((states) =>
            states.contains(WidgetState.selected)
                ? AppColors.accent
                : AppColors.surfaceHighest),
        trackOutlineColor: const WidgetStatePropertyAll(Colors.transparent),
      ),
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: _FadeSlidePageTransitionsBuilder(),
          TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
          TargetPlatform.linux: _FadeSlidePageTransitionsBuilder(),
          TargetPlatform.macOS: _FadeSlidePageTransitionsBuilder(),
          TargetPlatform.windows: _FadeSlidePageTransitionsBuilder(),
          TargetPlatform.fuchsia: _FadeSlidePageTransitionsBuilder(),
        },
      ),
    );
  }
}

/// A short fade with a slight horizontal slide, the same for every pushed
/// screen. No scaling or shadows, so it stays cheap on mid-range phones.
class _FadeSlidePageTransitionsBuilder extends PageTransitionsBuilder {
  const _FadeSlidePageTransitionsBuilder();

  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    final curved = CurvedAnimation(
      parent: animation,
      curve: Curves.easeOutCubic,
      reverseCurve: Curves.easeInCubic,
    );
    return FadeTransition(
      opacity: curved,
      child: SlideTransition(
        position: Tween<Offset>(
          begin: const Offset(0.06, 0),
          end: Offset.zero,
        ).animate(curved),
        child: child,
      ),
    );
  }
}
