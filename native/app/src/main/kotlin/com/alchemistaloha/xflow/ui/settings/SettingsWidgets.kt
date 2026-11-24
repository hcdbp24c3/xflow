package com.alchemistaloha.xflow.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alchemistaloha.xflow.core.settings.SettingsFormat
import com.alchemistaloha.xflow.core.util.AppLogger
import com.alchemistaloha.xflow.ui.nav.Navigator
import com.alchemistaloha.xflow.ui.theme.AppColors
import com.alchemistaloha.xflow.ui.theme.AppRadius
import com.alchemistaloha.xflow.ui.theme.AppTopBar
import com.alchemistaloha.xflow.ui.theme.listSubtitleStyle
import com.alchemistaloha.xflow.ui.theme.listTitleStyle
import kotlin.coroutines.cancellation.CancellationException

/**
 * A pushed settings page (Dart `Scaffold(appBar: AppBar(title), body: ListView)`):
 * black background, top bar with back, scrolling body, optional snackbar host.
 */
@Composable
internal fun SettingsPage(
    title: String,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(title, onBack = { Navigator.pop() })
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
                content = content,
            )
        }
        if (snackbarHostState != null) {
            AppSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Floating snackbar in the app's look (Dart snackBarTheme). */
@Composable
internal fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier.navigationBarsPadding()) { data ->
        Snackbar(
            snackbarData = data,
            shape = RoundedCornerShape(AppRadius.md),
            containerColor = AppColors.surfaceHighest,
            contentColor = AppColors.textPrimary,
            actionColor = AppColors.accent,
        )
    }
}

/** Flutter `ListTile`: optional leading icon, title, subtitle, trailing. */
@Composable
internal fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: ImageVector? = null,
    tint: Color? = null,
    onTap: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable(role = Role.Button, onClick = onTap) else Modifier)
            .heightIn(min = if (subtitle == null) 56.dp else 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = tint ?: AppColors.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Box(Modifier.size(width = 16.dp, height = 1.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (tint != null) listTitleStyle.copy(color = tint) else listTitleStyle)
            if (subtitle != null) Text(subtitle, style = listSubtitleStyle)
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 16.dp)) { trailing() }
        }
    }
}

/** Flutter `SwitchListTile`: the whole row toggles. */
@Composable
internal fun SwitchRow(
    title: String,
    value: Boolean,
    onChanged: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.toggleable(value = value, role = Role.Switch, onValueChange = onChanged),
        trailing = {
            Switch(
                checked = value,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AppColors.accent,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = AppColors.textSecondary,
                    uncheckedTrackColor = AppColors.surfaceHighest,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        },
    )
}

/** Flutter `Divider()` with the app's dividerTheme (0.5 thick, outline colour). */
@Composable
internal fun SettingsDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = AppColors.outline)
}

/** Flutter `DropdownButton` used as a ListTile trailing: current value, arrow, menu. */
@Composable
internal fun <T> DropdownTrailing(
    value: T,
    items: List<T>,
    label: (T) -> String,
    onChanged: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(AppRadius.sm))
                .clickable(role = Role.DropdownList) { expanded = true }
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(value), style = MaterialTheme.typography.bodyLarge.copy(color = AppColors.textPrimary))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = AppColors.textSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label(item),
                            style = MaterialTheme.typography.bodyLarge.copy(color = AppColors.textPrimary),
                        )
                    },
                    onClick = {
                        expanded = false
                        onChanged(item)
                    },
                )
            }
        }
    }
}

/** Flutter `FilterChip` with the app's chipTheme: pill, no checkmark, inverted when selected. */
@Composable
internal fun SettingsFilterChip(label: String, selected: Boolean, onSelected: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) AppColors.textPrimary else AppColors.surfaceHigh)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onSelected() })
            .heightIn(min = 32.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 13.sp,
                color = if (selected) Color.Black else AppColors.textPrimary,
            ),
        )
    }
}

/**
 * Dart `_SliderSetting`: title, optional subtitle, the whole-number value at
 * the right, and a slider underneath. [divisions] defaults to one stop per
 * whole number, as in Dart.
 */
@Composable
internal fun SliderSetting(
    title: String,
    value: Double,
    min: Double,
    max: Double,
    onChanged: (Double) -> Unit,
    subtitle: String? = null,
    divisions: Int? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        ListRow(
            title = title,
            subtitle = subtitle,
            trailing = {
                Text(
                    SettingsFormat.sliderValueLabel(value),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = AppColors.textPrimary,
                    ),
                )
            },
        )
        AppSlider(
            value = SettingsFormat.clamp(value, min, max),
            min = min,
            max = max,
            divisions = SettingsFormat.sliderDivisions(min, max, divisions),
            onChanged = onChanged,
        )
    }
}

/**
 * Slider drawn like the Flutter sliderTheme: 3dp track (accent / surfaceHighest),
 * white 8dp-radius thumb, faint accent halo while dragging. It reports only
 * the evenly spaced stops, and only when the stop changes, like a Flutter
 * `Slider` with `divisions`.
 */
@Composable
internal fun AppSlider(
    value: Double,
    min: Double,
    max: Double,
    divisions: Int,
    onChanged: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChanged by rememberUpdatedState(onChanged)
    var dragging by remember { mutableStateOf(false) }
    val inset = 24.dp

    fun report(raw: Double) {
        val snapped = SettingsFormat.snapToDivision(raw, min, max, divisions)
        if (snapped != currentValue) currentOnChanged(snapped)
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = min.toFloat()..max.toFloat(),
                    steps = (divisions - 1).coerceAtLeast(0),
                )
                setProgress { target ->
                    report(target.toDouble())
                    true
                }
            }
            .pointerInput(min, max, divisions) {
                val insetPx = inset.toPx()
                detectTapGestures { offset ->
                    val width = (size.width - 2 * insetPx).coerceAtLeast(1f)
                    val fraction = ((offset.x - insetPx) / width).coerceIn(0f, 1f)
                    report(min + fraction * (max - min))
                }
            }
            .pointerInput(min, max, divisions) {
                val insetPx = inset.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    val width = (size.width - 2 * insetPx).coerceAtLeast(1f)
                    val fraction = ((change.position.x - insetPx) / width).coerceIn(0f, 1f)
                    report(min + fraction * (max - min))
                }
            },
    ) {
        val insetPx = inset.toPx()
        val trackWidth = (size.width - 2 * insetPx).coerceAtLeast(1f)
        val trackHeight = 3.dp.toPx()
        val centerY = size.height / 2f
        val fraction = SettingsFormat.sliderFraction(value, min, max).toFloat()
        val thumbX = insetPx + trackWidth * fraction
        val radius = CornerRadius(trackHeight / 2f)
        drawRoundRect(
            color = AppColors.surfaceHighest,
            topLeft = Offset(insetPx, centerY - trackHeight / 2f),
            size = Size(trackWidth, trackHeight),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = AppColors.accent,
            topLeft = Offset(insetPx, centerY - trackHeight / 2f),
            size = Size(trackWidth * fraction, trackHeight),
            cornerRadius = radius,
        )
        if (dragging) {
            drawCircle(AppColors.accent.copy(alpha = 0.12f), radius = 18.dp.toPx(), center = Offset(thumbX, centerY))
        }
        drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(thumbX, centerY))
    }
}

/** Runs [block]; a failure is logged instead of crashing the screen. */
internal suspend fun runLogged(what: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.log("Settings: $what failed: $e")
    }
}
