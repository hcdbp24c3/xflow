package com.alchemistaloha.xflow.ui.theme

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * State of a [DraggableBottomSheet]. [fraction] is the sheet's height as a
 * share of the window height; below [min] the sheet is on its way out.
 */
@Stable
class DraggableSheetState internal constructor(
    internal val initial: Float,
    internal val min: Float,
    internal val max: Float,
) {
    var fraction by mutableFloatStateOf(initial)
        private set

    /** 1 = slid fully out of view, 0 = in place. Drives the open and close slide. */
    internal val slide = Animatable(1f)
    internal var windowHeightPx = 0f
    internal var scope: CoroutineScope? = null
    internal var onDismiss: (() -> Unit)? = null
    private var settleJob: Job? = null
    private var closing = false

    /** Slides the sheet away, then reports the dismissal. */
    fun close() {
        if (closing) return
        closing = true
        settleJob?.cancel()
        val scope = scope
        if (scope == null) {
            onDismiss?.invoke()
            return
        }
        scope.launch {
            try {
                slide.animateTo(1f, tween(AppDurations.normal))
            } finally {
                onDismiss?.invoke()
            }
        }
    }

    internal fun stopSettling() {
        settleJob?.cancel()
        settleJob = null
    }

    /** Moves the sheet edge by [deltaPx] (negative = up). Returns the pixels used. */
    internal fun dragBy(deltaPx: Float): Float {
        val full = windowHeightPx
        if (full <= 0f || closing) return 0f
        stopSettling()
        val before = fraction
        val after = (before - deltaPx / full).coerceIn(0f, max)
        fraction = after
        return (before - after) * full
    }

    /** The finger left with [velocityPx] (positive = downwards): rest, or dismiss. */
    internal fun settle(velocityPx: Float) {
        val full = windowHeightPx
        val scope = scope ?: return
        if (full <= 0f || closing) return
        val projected = fraction - velocityPx * 0.2f / full
        val dismiss = fraction < min - 0.06f || projected < min - 0.05f
        val target = if (dismiss) 0f else projected.coerceIn(min, max)
        if (dismiss) closing = true
        settleJob?.cancel()
        settleJob = scope.launch {
            try {
                animate(
                    initialValue = fraction,
                    targetValue = target,
                    initialVelocity = -velocityPx / full,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = 0.001f,
                    ),
                ) { value, _ -> fraction = value.coerceIn(0f, max) }
            } finally {
                if (dismiss) onDismiss?.invoke()
            }
        }
    }
}

@Composable
fun rememberDraggableSheetState(
    initialFraction: Float = 0.7f,
    minFraction: Float = 0.4f,
    maxFraction: Float = 0.95f,
): DraggableSheetState = remember { DraggableSheetState(initialFraction, minFraction, maxFraction) }

/** Makes a non-scrolling part of the sheet (handle, header) drag the sheet. */
fun Modifier.sheetDragArea(state: DraggableSheetState): Modifier = composed {
    draggable(
        state = rememberDraggableState { delta -> state.dragBy(delta) },
        orientation = Orientation.Vertical,
        onDragStarted = { state.stopSettling() },
        onDragStopped = { velocity -> state.settle(velocity) },
    )
}

/**
 * A modal bottom sheet whose height follows the finger, the counterpart of
 * Flutter's `showModalBottomSheet` + `DraggableScrollableSheet`.
 *
 * It opens at `initialFraction` of the window height and can be dragged
 * between `minFraction` and `maxFraction`. A scrolling child takes part:
 * dragging up first grows the sheet and then scrolls the list; dragging
 * down first scrolls the list back to its top and then shrinks the sheet.
 * Dragged (or flung) below the minimum, the sheet closes. Tapping outside
 * it and the system back button close it too. The content is padded above
 * the keyboard and the navigation bar.
 *
 * Content that does not scroll should either be wrapped in a
 * `verticalScroll` or carry [sheetDragArea], so that a drag on it still
 * moves the sheet.
 */
@Composable
fun DraggableBottomSheet(
    onDismiss: () -> Unit,
    state: DraggableSheetState = rememberDraggableSheetState(),
    shape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val onDismissNow by rememberUpdatedState(onDismiss)

    DisposableEffect(state, scope) {
        state.scope = scope
        state.onDismiss = { onDismissNow() }
        onDispose {
            state.scope = null
            state.onDismiss = null
        }
    }
    LaunchedEffect(state) { state.slide.animateTo(0f, tween(AppDurations.slow, easing = EaseOutCubicSheet)) }

    val connection = remember(state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val dy = available.y
                // Upwards: the sheet grows before the list scrolls. Downwards: the list
                // goes first (see onPostScroll), unless the sheet is already on its way out.
                val sheetFirst = (dy < 0f && state.fraction < state.max) ||
                    (dy > 0f && state.fraction < state.min)
                return if (sheetFirst) Offset(0f, state.dragBy(dy)) else Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                // The list is at its top and the finger keeps going down.
                return Offset(0f, state.dragBy(available.y))
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // While the sheet is between its stops the fling belongs to the sheet.
                if (state.fraction >= state.max) return Velocity.Zero
                state.settle(available.y)
                return available
            }
        }
    }

    Dialog(
        onDismissRequest = { state.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let {
                // The sheet brings its own slide; the keyboard must resize, not pan.
                it.setWindowAnimations(0)
                it.setDimAmount(0.54f)
                @Suppress("DEPRECATION")
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            onDispose { }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fullPx = constraints.maxHeight.toFloat()
            state.windowHeightPx = fullPx
            val shown = state.fraction.coerceIn(state.min, state.max)

            // Outside the sheet: a tap closes it.
            Box(Modifier.fillMaxSize().pointerInput(state) { detectTapGestures { state.close() } })

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(maxHeight * shown)
                    .graphicsLayer {
                        // Below the minimum the sheet keeps its size and slides down instead.
                        val below = (state.min - state.fraction).coerceAtLeast(0f) * fullPx
                        translationY = below + state.slide.value * size.height
                    }
                    .clip(shape)
                    .background(containerColor)
                    // Taps on the sheet itself must not reach the "outside" layer.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .nestedScroll(connection)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ) {
                // Handle bar
                Box(
                    Modifier.fillMaxWidth().sheetDragArea(state),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .padding(top = 10.dp, bottom = 6.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                    )
                }
                content()
            }
        }
    }
}

private val EaseOutCubicSheet = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
