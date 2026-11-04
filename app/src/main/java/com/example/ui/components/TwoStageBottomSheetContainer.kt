package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ui.theme.NeumorphicBg
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * High-performance two-stage bottom sheet container with zero-recomposition drag/settle:
 * - Reads [sheetOffsetPx] strictly inside layout (`Modifier.offset { ... }`) and draw
 *   (`Modifier.graphicsLayer { ... }`) lambdas so 60/120Hz drag frames never recompose
 *   the sheet content tree.
 * - Stage 1 (partially open at 38% top offset): dragging up expands to Stage 2 (0f offset)
 *   and hands residual upward delta to [contentScrollState]; dragging down or tapping the
 *   dimmed backdrop dismisses completely.
 * - Stage 2 (fully expanded at 0f offset):
 *   - Touches starting in the lower 2/3 of the screen (`downY > screenHeightPx * 0.35f`)
 *     are strictly locked to content scrolling and never move or close the sheet.
 *   - Touches starting in the upper 1/4–1/3 of the screen (`downY <= screenHeightPx * 0.35f`)
 *     reliably intercept downward swipes on [PointerEventPass.Initial] (ignoring minor
 *     horizontal finger jitter and child clickables) to drag and close the sheet completely.
 */
@Composable
internal fun AnimatedTwoStageSheetContainer(
    testTag: String,
    contentScrollState: ScrollState,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (
        requestExpand: () -> Unit
    ) -> Unit
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val isImeOpen = WindowInsets.ime.getBottom(density) > 0
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnBack by rememberUpdatedState(onBack)

    var hasInitializedSheet by remember { mutableStateOf(false) }
    var isFullyExpanded by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    var isActivelyDraggingSheet by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag(testTag)
    ) {
        val screenHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val partiallyExpandedOffsetPx = (screenHeightPx * 0.38f).coerceAtLeast(0f)
        // Upper 35% of the screen covers both the upper 1/4 and upper 1/3 zones
        val upperDismissZoneMaxYPx = screenHeightPx * 0.35f
        val dismissDistanceThresholdPx = with(density) { 44.dp.toPx() }
        val minUpperSwipeDismissPx = with(density) { 12.dp.toPx() }
        val dismissVelocityThresholdPx = with(density) { 180.dp.toPx() }
        val expandVelocityThresholdPx = with(density) { -180.dp.toPx() }

        val sheetOffsetPx = remember { Animatable(screenHeightPx) }
        val isAtTop by remember {
            derivedStateOf { sheetOffsetPx.value <= 2f }
        }

        fun animateToOffset(
            targetPx: Float,
            durationMillis: Int = 220,
            useSpring: Boolean = false,
            onFinished: (() -> Unit)? = null
        ) {
            coroutineScope.launch {
                if (useSpring) {
                    sheetOffsetPx.animateTo(
                        targetValue = targetPx,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                } else {
                    sheetOffsetPx.animateTo(
                        targetValue = targetPx,
                        animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
                    )
                }
                onFinished?.invoke()
            }
        }

        val animateDismiss: () -> Unit = {
            if (!isDismissing) {
                isDismissing = true
                isActivelyDraggingSheet = false
                animateToOffset(
                    targetPx = screenHeightPx,
                    durationMillis = 190,
                    onFinished = { currentOnDismiss() }
                )
            }
        }

        val requestExpand: () -> Unit = {
            if (!isDismissing && !isActivelyDraggingSheet) {
                isFullyExpanded = true
                if (sheetOffsetPx.value > 0.5f) {
                    animateToOffset(targetPx = 0f, durationMillis = 200)
                }
            }
        }

        fun settleFromStageOne(velocityY: Float) {
            if (isDismissing) return
            val currentOffset = sheetOffsetPx.value
            when {
                currentOffset <= partiallyExpandedOffsetPx * 0.80f || velocityY <= expandVelocityThresholdPx -> {
                    isFullyExpanded = true
                    animateToOffset(targetPx = 0f, useSpring = true)
                    if (velocityY < -250f && currentOffset <= 2f) {
                        coroutineScope.launch {
                            val flingDelta = (-velocityY * 0.18f).coerceAtMost(screenHeightPx * 0.65f)
                            contentScrollState.animateScrollBy(
                                value = flingDelta,
                                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                            )
                        }
                    }
                }
                currentOffset >= partiallyExpandedOffsetPx + dismissDistanceThresholdPx || velocityY >= dismissVelocityThresholdPx -> {
                    animateDismiss()
                }
                else -> {
                    animateToOffset(targetPx = partiallyExpandedOffsetPx, useSpring = true)
                }
            }
        }

        LaunchedEffect(screenHeightPx) {
            if (!hasInitializedSheet && screenHeightPx > 1f) {
                hasInitializedSheet = true
                val target = if (isFullyExpanded || isImeOpen) 0f else partiallyExpandedOffsetPx
                if (isImeOpen) {
                    isFullyExpanded = true
                }
                sheetOffsetPx.snapTo(screenHeightPx)
                sheetOffsetPx.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                )
            }
        }

        LaunchedEffect(isImeOpen) {
            if (isImeOpen && !isDismissing && !isActivelyDraggingSheet) {
                isFullyExpanded = true
                if (sheetOffsetPx.value > 0.5f) {
                    animateToOffset(targetPx = 0f, durationMillis = 190)
                }
            }
        }

        LaunchedEffect(contentScrollState.value) {
            if (contentScrollState.value > 0 && !isFullyExpanded && !isDismissing && !isActivelyDraggingSheet) {
                isFullyExpanded = true
                if (sheetOffsetPx.value > 0.5f) {
                    animateToOffset(targetPx = 0f, durationMillis = 180)
                }
            }
        }

        BackHandler {
            currentOnBack()
        }

        // Dimmed backdrop reads sheetOffsetPx.value inside graphicsLayer (draw phase only)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = (1f - (sheetOffsetPx.value / screenHeightPx)).coerceIn(0f, 1f)
                    alpha = 0.45f * progress
                }
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isFullyExpanded && !isAtTop
                ) {
                    animateDismiss()
                }
        )

        val currentTopCornerRadius = if (!isAtTop) 28.dp else 0.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(screenHeightPx) {
                    val effectiveSlop = (viewConfiguration.touchSlop * 0.75f).coerceAtLeast(4f)
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            pass = PointerEventPass.Initial,
                            requireUnconsumed = false
                        )
                        if (isDismissing) return@awaitEachGesture

                        val pointerId = down.id
                        val downY = down.position.y
                        val currentStartOffset = sheetOffsetPx.value
                        val startedExpanded = isFullyExpanded || currentStartOffset <= 2f
                        val startedInUpperDismissZone = downY <= upperDismissZoneMaxYPx

                        // When fully expanded, touches starting in the lower 2/3 of the screen
                        // are strictly locked to content scrolling and never move or close the sheet.
                        if (startedExpanded && !startedInUpperDismissZone) {
                            return@awaitEachGesture
                        }

                        // When half-open, tapping the dimmed backdrop above the sheet closes it.
                        if (!startedExpanded && downY < currentStartOffset) {
                            var movedPastSlop = false
                            var tapDx = 0f
                            var tapDy = 0f
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: event.changes.firstOrNull()
                                if (change == null || !change.pressed) break
                                val delta = change.positionChangeIgnoreConsumed()
                                tapDx += delta.x
                                tapDy += delta.y
                                if (hypot(tapDx, tapDy) > effectiveSlop) {
                                    movedPastSlop = true
                                    break
                                }
                            }
                            if (!movedPastSlop && !isDismissing) {
                                animateDismiss()
                            }
                            return@awaitEachGesture
                        }

                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)

                        var totalDx = 0f
                        var totalDy = 0f
                        var isSheetDragging = false
                        var dragOffsetPx = currentStartOffset

                        fun snapSheetOffset(newOffset: Float) {
                            dragOffsetPx = newOffset
                            coroutineScope.launch {
                                sheetOffsetPx.snapTo(newOffset)
                            }
                        }

                        fun applyStageOneDelta(dy: Float) {
                            if (dy < 0f) {
                                if (dragOffsetPx > 0f) {
                                    val nextOffset = (dragOffsetPx + dy).coerceAtLeast(0f)
                                    val consumedBySheet = nextOffset - dragOffsetPx
                                    snapSheetOffset(nextOffset)
                                    val leftoverUp = dy - consumedBySheet
                                    if (dragOffsetPx <= 0.5f) {
                                        snapSheetOffset(0f)
                                        isFullyExpanded = true
                                        if (leftoverUp < 0f) {
                                            contentScrollState.dispatchRawDelta(-leftoverUp)
                                        }
                                    }
                                } else {
                                    snapSheetOffset(0f)
                                    isFullyExpanded = true
                                    contentScrollState.dispatchRawDelta(-dy)
                                }
                            } else if (dy > 0f) {
                                if (contentScrollState.value > 0) {
                                    val consumedScroll = contentScrollState.dispatchRawDelta(-dy)
                                    val leftoverDown = dy + consumedScroll
                                    if (leftoverDown > 0f) {
                                        isFullyExpanded = false
                                        snapSheetOffset((dragOffsetPx + leftoverDown).coerceIn(0f, screenHeightPx))
                                    }
                                } else {
                                    isFullyExpanded = false
                                    snapSheetOffset((dragOffsetPx + dy).coerceIn(0f, screenHeightPx))
                                }
                            }
                        }

                        // Phase 1: Observe pointer movement on Initial pass until touch slop is crossed.
                        // Using hypot(totalDx, totalDy) prevents 1px horizontal finger jitter from
                        // aborting vertical swipe detection before touchSlop is reached.
                        while (!isSheetDragging) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: event.changes.firstOrNull()
                            if (change == null || !change.pressed) {
                                return@awaitEachGesture
                            }
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val delta = change.positionChangeIgnoreConsumed()
                            totalDx += delta.x
                            totalDy += delta.y

                            val absDx = abs(totalDx)
                            val absDy = abs(totalDy)
                            val distance = hypot(totalDx, totalDy)

                            if (distance > effectiveSlop) {
                                // Only abort as horizontal gesture if horizontal movement clearly dominates
                                if (absDx > absDy * 1.25f) {
                                    return@awaitEachGesture
                                }
                                if (startedExpanded) {
                                    // In Stage 2 (fully expanded) within the upper 1/3 zone:
                                    // Downward swipes (totalDy > 0) immediately capture and drag/close the sheet.
                                    // Upward swipes (totalDy <= 0) pass through to scroll Detail content down.
                                    if (totalDy <= 0f) {
                                        return@awaitEachGesture
                                    }
                                }
                                isSheetDragging = true
                                isActivelyDraggingSheet = true
                                event.changes.forEach { it.consume() }
                                dragOffsetPx = sheetOffsetPx.value
                                val postSlopDy = totalDy - (effectiveSlop * sign(totalDy))
                                if (startedExpanded) {
                                    snapSheetOffset((dragOffsetPx + postSlopDy.coerceAtLeast(1f)).coerceIn(0f, screenHeightPx))
                                } else {
                                    applyStageOneDelta(postSlopDy)
                                }
                            }
                        }

                        // Phase 2: Track active sheet drag in stationary screen coordinates
                        try {
                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: event.changes.firstOrNull()
                                if (change == null || !change.pressed) {
                                    break
                                }
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val dy = change.positionChangeIgnoreConsumed().y
                                event.changes.forEach { it.consume() }

                                if (startedExpanded) {
                                    snapSheetOffset((dragOffsetPx + dy).coerceIn(0f, screenHeightPx))
                                } else {
                                    applyStageOneDelta(dy)
                                }
                            }
                        } finally {
                            isActivelyDraggingSheet = false
                        }

                        // Phase 3: Finger lifted — close completely or settle
                        val velocityY = velocityTracker.calculateVelocity().y
                        if (startedExpanded) {
                            val finalOffset = dragOffsetPx
                            val shouldDismissCompletely =
                                finalOffset >= dismissDistanceThresholdPx ||
                                    velocityY >= dismissVelocityThresholdPx ||
                                    (finalOffset >= minUpperSwipeDismissPx && velocityY >= -80f)
                            if (shouldDismissCompletely) {
                                animateDismiss()
                            } else {
                                animateToOffset(targetPx = 0f, useSpring = true)
                            }
                        } else {
                            settleFromStageOne(velocityY)
                        }
                    }
                }
        ) {
            Surface(
                color = NeumorphicBg,
                shape = RoundedCornerShape(
                    topStart = currentTopCornerRadius,
                    topEnd = currentTopCornerRadius
                ),
                shadowElevation = if (!isAtTop) 16.dp else 0.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, sheetOffsetPx.value.roundToInt()) }
            ) {
                content(requestExpand)
            }
        }
    }
}
