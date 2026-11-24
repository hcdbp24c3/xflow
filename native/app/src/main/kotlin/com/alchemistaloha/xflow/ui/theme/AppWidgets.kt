package com.alchemistaloha.xflow.ui.theme

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shrinks its content slightly while pressed. Used for every tappable control
 * that sits on top of media, so they all answer a touch the same way.
 */
@Composable
fun PressableScale(
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    pressedScale: Float = 0.88f,
    tooltip: String? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onTap != null) pressedScale else 1f,
        animationSpec = tween(AppDurations.fast, easing = EaseOut),
        label = "pressScale",
    )
    Box(
        modifier = modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = onTap != null,
                role = Role.Button,
                onClick = { onTap?.invoke() },
            )
            .then(
                if (tooltip != null) Modifier.semantics { contentDescription = tooltip } else Modifier,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * A round icon button on a translucent dark disc, for controls that float
 * over video. A plain fill, not a blur: blurring video every frame is
 * expensive.
 */
@Composable
fun FloatingIconButton(
    icon: ImageVector,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tooltip: String? = null,
    size: Dp = 38.dp,
) {
    PressableScale(onTap = onTap, tooltip = tooltip, modifier = modifier) {
        // Keeps the touch target at 46dp while the disc stays small.
        Box(Modifier.padding(4.dp)) {
            Box(
                Modifier.size(size).background(AppColors.scrim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.53f))
            }
        }
    }
}

/**
 * The row of floating controls at the top of a full-bleed feed. It replaces
 * the solid app bar so media can run edge to edge.
 */
@Composable
fun FeedTopBar(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxWidth()) {
        // Fades the top of the media so white controls stay readable.
        Box(
            Modifier
                .fillMaxWidth()
                .height(topInset + 96.dp)
                .background(Brush.verticalGradient(listOf(Color(0x8C000000), Color(0x00000000)))),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = topInset + 4.dp, end = 8.dp)
                .height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            if (title != null) {
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    ProvideTextStyle(
                        LocalTextStyle.current.merge(
                            TextStyle(
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp,
                                shadow = Shadow(color = Color(0x8A000000), blurRadius = 6f),
                            ),
                        ),
                    ) { title() }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            actions()
        }
    }
}

/**
 * Solid top bar for ordinary (non-media) screens: back button, centred title,
 * optional actions. Sits on the black background, no elevation.
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier
            .fillMaxWidth()
            .background(AppColors.background)
            .padding(top = topInset)
            .height(56.dp),
    ) {
        Text(
            title,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onBack != null) {
            PressableScale(
                onTap = onBack,
                tooltip = "Back",
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = AppColors.textPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** A plain icon button for [AppTopBar] actions and list rows. */
@Composable
fun AppIconButton(
    icon: ImageVector,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tooltip: String? = null,
    tint: Color = AppColors.textPrimary,
) {
    PressableScale(onTap = onTap, tooltip = tooltip, modifier = modifier.size(44.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** The one look for "nothing here", loading and error states. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    detail: String? = null,
    loading: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.height(4.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = AppColors.textTertiary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(4.dp))
            }
            Text(
                message,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium.copy(color = AppColors.textSecondary),
            )
            if (detail != null) {
                Text(
                    detail,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(color = AppColors.textTertiary),
                )
            }
            actions()
        }
    }
}

/** Thin progress line; place it at the top edge of a feed. */
@Composable
fun TopProgressLine(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier = modifier.fillMaxWidth().height(2.dp),
        color = AppColors.accent,
        trackColor = Color.Transparent,
    )
}

/** Small spacer helpers to keep layouts readable. */
@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))

/**
 * The app icon (assets/app_icon.svg) drawn as vectors: a black rounded square
 * with five blue tiles on its diagonals. Colours are the icon's own, so it is
 * not tinted. Flutter shows it 22x22 in the feed's top bar.
 */
@Composable
fun AppLogoMark(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        // The SVG is drawn on a 512 x 512 board.
        val unit = this.size.minDimension / 512f
        fun tile(x: Float, y: Float, side: Float, radius: Float, color: Color, alpha: Float = 1f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(x * unit, y * unit),
                size = Size(side * unit, side * unit),
                cornerRadius = CornerRadius(radius * unit),
                alpha = alpha,
            )
        }
        tile(0f, 0f, 512f, 128f, Color(0xFF000000))
        tile(140f, 140f, 60f, 10f, Color(0xFF2196F3))
        tile(210f, 210f, 92f, 15f, Color(0xFF64B5F6))
        tile(312f, 312f, 60f, 10f, Color(0xFF2196F3))
        tile(312f, 140f, 60f, 10f, Color(0xFFBBDEFB), alpha = 0.6f)
        tile(140f, 312f, 60f, 10f, Color(0xFFBBDEFB), alpha = 0.6f)
    }
}

/**
 * Icon + "XFlow", as the Flutter feed shows it at the left of its top bar:
 * 22dp icon, 8dp gap, 18sp extra-bold white text with a soft shadow.
 */
@Composable
fun AppLogoWordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AppLogoMark(size = 22.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            "XFlow",
            style = TextStyle(
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                shadow = Shadow(color = Color(0x8A000000), blurRadius = 6f),
            ),
        )
    }
}
