package com.alchemistaloha.xflow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Every colour, radius and duration the app uses lives here, so screens ask
 * the theme instead of hard-coding their own.
 */
object AppColors {
    val background = Color(0xFF000000)
    val surface = Color(0xFF0F0F11)
    val surfaceHigh = Color(0xFF18181B)
    val surfaceHighest = Color(0xFF222226)
    val outline = Color(0xFF2A2A2F)

    val textPrimary = Color(0xFFF5F5F7)
    val textSecondary = Color(0xFF9B9BA3)
    val textTertiary = Color(0xFF6B6B73)

    /** The single accent colour. */
    val accent = Color(0xFF4C8DFF)

    // Meaning-carrying colours, used only for their meaning.
    val like = Color(0xFFFF3B5C)
    val retweet = Color(0xFF2ECC8F)
    val bookmark = Color(0xFFFFC53D)
    val danger = Color(0xFFFF5A5F)
    val warning = Color(0xFFFFA940)
    val success = Color(0xFF2ECC8F)

    /** Dark scrim behind controls that float over media. */
    val scrim = Color(0x66000000)
}

object AppRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** Animation lengths in milliseconds. */
object AppDurations {
    const val fast = 120
    const val normal = 200
    const val slow = 320
}

private val scheme = darkColorScheme(
    primary = AppColors.accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF16284A),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = AppColors.textPrimary,
    onSecondary = AppColors.background,
    secondaryContainer = AppColors.surfaceHighest,
    onSecondaryContainer = AppColors.textPrimary,
    tertiary = AppColors.accent,
    onTertiary = Color.White,
    error = AppColors.danger,
    onError = Color.White,
    background = AppColors.background,
    onBackground = AppColors.textPrimary,
    surface = AppColors.background,
    onSurface = AppColors.textPrimary,
    surfaceVariant = AppColors.surfaceHigh,
    onSurfaceVariant = AppColors.textSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = AppColors.background,
    surfaceContainerLow = AppColors.surface,
    surfaceContainer = AppColors.surface,
    surfaceContainerHigh = AppColors.surfaceHigh,
    surfaceContainerHighest = AppColors.surfaceHighest,
    outline = AppColors.outline,
    outlineVariant = AppColors.outline,
    inverseSurface = AppColors.textPrimary,
    inverseOnSurface = AppColors.background,
    scrim = Color.Black,
)

private val base = Typography()

private val typography = Typography(
    headlineSmall = base.headlineSmall.copy(
        fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
        color = AppColors.textPrimary,
    ),
    titleLarge = base.titleLarge.copy(
        fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp,
        color = AppColors.textPrimary,
    ),
    titleMedium = base.titleMedium.copy(
        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp,
        color = AppColors.textPrimary,
    ),
    titleSmall = base.titleSmall.copy(
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary,
    ),
    bodyLarge = base.bodyLarge.copy(
        fontSize = 16.sp, lineHeight = 1.4.em, letterSpacing = 0.sp, color = AppColors.textPrimary,
    ),
    bodyMedium = base.bodyMedium.copy(
        fontSize = 14.sp, lineHeight = 1.4.em, letterSpacing = 0.sp, color = AppColors.textPrimary,
    ),
    bodySmall = base.bodySmall.copy(
        fontSize = 12.5.sp, lineHeight = 1.35.em, letterSpacing = 0.sp,
        color = AppColors.textSecondary,
    ),
    labelLarge = base.labelLarge.copy(
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    ),
    labelSmall = base.labelSmall.copy(
        fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp,
        color = AppColors.textSecondary,
    ),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(AppRadius.sm),
    small = RoundedCornerShape(AppRadius.sm),
    medium = RoundedCornerShape(AppRadius.md),
    large = RoundedCornerShape(AppRadius.lg),
    extraLarge = RoundedCornerShape(AppRadius.xl),
)

/** The one dark theme: true black, one accent, compact type. */
@Composable
fun XFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}

/** Text style for list-row titles (Flutter ListTile title). */
val listTitleStyle: TextStyle
    get() = typography.bodyLarge.copy(fontSize = 15.5.sp, color = AppColors.textPrimary)

/** Text style for list-row subtitles (Flutter ListTile subtitle). */
val listSubtitleStyle: TextStyle
    get() = typography.bodySmall.copy(fontSize = 12.5.sp, color = AppColors.textSecondary)
