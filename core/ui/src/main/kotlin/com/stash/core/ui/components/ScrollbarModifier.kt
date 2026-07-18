package com.stash.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.stash.core.ui.theme.StashTheme
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Shared snapshot written by the draw layer, read by the drag-handle layer. */
private class ScrollbarTrackInfo {
    var progress by mutableStateOf(0f)
    var thumbFraction by mutableStateOf(1f)
}

/** Fader-cap palette, resolved from the active theme's own chrome tokens. */
private class FaderColors(val body: Color, val border: Color, val groove: Color)

/**
 * The cap dresses like the rest of the app's chrome — chip surface body,
 * glass hairline edge, one plum groove — rather than importing its own
 * palette. surfaceVariant/primary/glassBorderBright all resolve per theme,
 * so light, dark, and AMOLED each get their native rendering for free.
 */
@Composable
private fun faderColors(): FaderColors = FaderColors(
    body = MaterialTheme.colorScheme.surfaceVariant,
    border = StashTheme.extendedColors.glassBorderBright,
    groove = MaterialTheme.colorScheme.primary,
)

/** The thumb never shrinks below this — a scrubbable cap, not a sliver. */
private val MIN_THUMB_HEIGHT = 48.dp

/** Extra vertical slack around the cap that still counts as grabbing it. */
private val GRAB_TOLERANCE = 32.dp

/** Clamp the raw viewport fraction so the drawn cap keeps its minimum size. */
private fun DrawScope.clampedThumbFraction(rawFraction: Float): Float =
    maxOf(rawFraction, MIN_THUMB_HEIGHT.toPx() / size.height.coerceAtLeast(1f))
        .coerceAtMost(1f)

/**
 * Average row height excluding item 0 — every wired list/grid leads with one
 * oversized full-width header item, and averaging it in makes the position
 * estimate lurch whenever the header scrolls in or out of the viewport.
 */
private fun avgSizeSkippingHeader(items: List<Pair<Int, Int>>): Float {
    val body = items.filter { it.first != 0 }.ifEmpty { items }
    return body.sumOf { it.second }.toFloat() / body.size
}

/**
 * Draws the fader cap: a near-square chip-surface block with a glass
 * hairline edge and a single plum groove across the middle — the classic
 * mixer fader cap, in the app's own chrome.
 */
private fun DrawScope.drawFaderCap(
    info: ScrollbarTrackInfo,
    colors: FaderColors,
    alpha: Float,
    capWidthPx: Float,
    thumbHeightOffset: Float,
) {
    if (alpha <= 0.001f) return
    val trackHeight = size.height
    val thumbHeight = (trackHeight * info.thumbFraction) + thumbHeightOffset
    val thumbOffsetY = info.progress * (trackHeight - thumbHeight)
    val left = size.width - capWidthPx - 3.dp.toPx()
    val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())

    // Cap body — chip surface, faintly translucent so it sits IN the page.
    drawRoundRect(
        color = colors.body.copy(alpha = 0.95f * alpha),
        topLeft = Offset(left, thumbOffsetY),
        size = Size(capWidthPx, thumbHeight),
        cornerRadius = corner,
    )
    // Glass hairline edge (token carries its own low alpha).
    drawRoundRect(
        color = colors.border.copy(alpha = colors.border.alpha * alpha),
        topLeft = Offset(left, thumbOffsetY),
        size = Size(capWidthPx, thumbHeight),
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx()),
    )
    // Single center groove — the fader-cap signature.
    val grooveWidth = capWidthPx * 0.6f
    val gx = left + (capWidthPx - grooveWidth) / 2f
    val cy = thumbOffsetY + thumbHeight / 2f
    drawLine(
        color = colors.groove.copy(alpha = 0.9f * alpha),
        start = Offset(gx, cy),
        end = Offset(gx + grooveWidth, cy),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/**
 * Shared scrub gesture: claims (and scrubs) ONLY when the touch lands on the
 * VISIBLE cap. Off-thumb or faded-out touches are left unconsumed so the
 * list/grid underneath scrolls normally — otherwise this full-height strip
 * would swallow every right-edge drag and freeze scrolling along that edge.
 */
private fun Modifier.faderScrubber(
    stateKey: Any,
    info: ScrollbarTrackInfo,
    animatedAlpha: () -> Float,
    thumbHeightOffset: Float,
    onWake: () -> Unit,
    onScrub: (Float) -> Unit,
): Modifier = pointerInput(stateKey) {
    val grabTolerance = GRAB_TOLERANCE.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val trackHeight = size.height.toFloat()
        val thumbHeight = (trackHeight * info.thumbFraction) + thumbHeightOffset
        val thumbOffsetY = info.progress * (trackHeight - thumbHeight)
        val onThumb = animatedAlpha() > 0.01f && down.position.y in
            (thumbOffsetY - grabTolerance)..(thumbOffsetY + thumbHeight + grabTolerance)
        if (!onThumb) return@awaitEachGesture
        down.consume()
        onWake()
        var dragProgress = info.progress
        val maxOffset = trackHeight - thumbHeight
        verticalDrag(down.id) { change ->
            // Read the delta BEFORE consuming — a consumed change reports a
            // zero positionChange, which would freeze the scrub in place.
            val deltaY = change.positionChange().y
            change.consume()
            if (maxOffset > 0f) {
                dragProgress = (dragProgress + deltaY / maxOffset).coerceIn(0f, 1f)
                onScrub(dragProgress)
            }
        }
    }
}

/**
 * Excludes ONLY the thumb's current rect (plus grab slack) from the system
 * back-gesture band. Android silently caps exclusion at 200dp per edge, so
 * excluding the whole strip is quietly ignored and the system keeps stealing
 * edge touches — a thumb-sized rect always fits the budget and follows the
 * cap wherever it sits.
 */
private fun Modifier.thumbGestureExclusion(
    info: ScrollbarTrackInfo,
    thumbHeightOffset: Float,
    grabTolerancePx: Float,
): Modifier = systemGestureExclusion { coords ->
    val trackHeight = coords.size.height.toFloat()
    val thumbHeight = (trackHeight * info.thumbFraction) + thumbHeightOffset
    val thumbOffsetY = info.progress * (trackHeight - thumbHeight)
    Rect(
        left = 0f,
        top = (thumbOffsetY - grabTolerancePx).coerceAtLeast(0f),
        right = coords.size.width.toFloat(),
        bottom = (thumbOffsetY + thumbHeight + grabTolerancePx).coerceAtMost(trackHeight),
    )
}

@Composable
fun BoxScope.VerticalScrollbar(
    state: LazyListState,
    width: Dp = 12.dp,
    idleDelayMs: Long = 1500L,
    thumbHeightOffset: Float = 0f,
    hitZoneWidth: Dp = 44.dp,
) {
    val info = remember { ScrollbarTrackInfo() }
    val colors = faderColors()
    val grabTolerancePx = with(LocalDensity.current) { GRAB_TOLERANCE.toPx() }
    var alpha by remember { mutableStateOf(0f) }
    val animatedAlpha by animateFloatAsState(alpha, tween(200), label = "scrollbarAlpha")
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) alpha = 1f else { delay(idleDelayMs); alpha = 0f }
    }

    // Draw-only layer — fillMaxSize but NO pointerInput, so it never blocks touches.
    Box(
        modifier = Modifier.fillMaxSize().drawWithContent {
            drawContent()
            val layout = state.layoutInfo
            val totalItems = layout.totalItemsCount
            if (totalItems == 0 || layout.visibleItemsInfo.isEmpty()) return@drawWithContent
            val firstVisible = layout.visibleItemsInfo.first()
            val avgItemSize = avgSizeSkippingHeader(layout.visibleItemsInfo.map { it.index to it.size })
            val estimatedTotalHeight = avgItemSize * totalItems
            if (estimatedTotalHeight <= layout.viewportEndOffset) return@drawWithContent

            val scrolledPx = firstVisible.index * avgItemSize - firstVisible.offset
            val maxScrollPx = estimatedTotalHeight - layout.viewportEndOffset
            info.thumbFraction = clampedThumbFraction(layout.viewportEndOffset / estimatedTotalHeight)
            info.progress = (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
            drawFaderCap(info, colors, animatedAlpha, width.toPx(), thumbHeightOffset)
        },
    )

    // Narrow grab strip — ONLY this slice is touchable, everything left of it
    // passes straight through to the list underneath untouched.
    // systemGestureExclusion: the strip lives on the screen's right edge,
    // which Android's back-gesture nav owns — without the exclusion the
    // system intercepts edge drags and the cap is randomly ungrabbable.
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(hitZoneWidth)
            .thumbGestureExclusion(info, thumbHeightOffset, grabTolerancePx)
            .faderScrubber(
                stateKey = state,
                info = info,
                animatedAlpha = { animatedAlpha },
                thumbHeightOffset = thumbHeightOffset,
                onWake = { alpha = 1f },
                onScrub = { progress ->
                    // Pixel-granular target: index + intra-item offset. Snapping
                    // to whole items reads as comically jumpy on tall rows.
                    val layout = state.layoutInfo
                    val total = layout.totalItemsCount
                    if (total > 0 && layout.visibleItemsInfo.isNotEmpty()) {
                        val avgItemSize =
                            avgSizeSkippingHeader(layout.visibleItemsInfo.map { it.index to it.size })
                        val maxScrollPx =
                            (avgItemSize * total - layout.viewportEndOffset).coerceAtLeast(0f)
                        val targetPx = progress * maxScrollPx
                        val index = (targetPx / avgItemSize).toInt().coerceIn(0, total - 1)
                        val offsetPx = (targetPx - index * avgItemSize).roundToInt()
                        scope.launch { state.scrollToItem(index, offsetPx) }
                    }
                },
            ),
    )
}

@Composable
fun BoxScope.VerticalScrollbar(
    state: LazyGridState,
    width: Dp = 12.dp,
    idleDelayMs: Long = 1500L,
    thumbHeightOffset: Float = 0f,
    hitZoneWidth: Dp = 44.dp,
) {
    val info = remember { ScrollbarTrackInfo() }
    val colors = faderColors()
    val grabTolerancePx = with(LocalDensity.current) { GRAB_TOLERANCE.toPx() }
    var alpha by remember { mutableStateOf(0f) }
    val animatedAlpha by animateFloatAsState(alpha, tween(200), label = "scrollbarAlpha")
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) alpha = 1f else { delay(idleDelayMs); alpha = 0f }
    }

    Box(
        modifier = Modifier.fillMaxSize().drawWithContent {
            drawContent()
            val layout = state.layoutInfo
            val totalItems = layout.totalItemsCount
            if (totalItems == 0 || layout.visibleItemsInfo.isEmpty()) return@drawWithContent
            val firstVisible = layout.visibleItemsInfo.first()
            val avgItemHeight =
                avgSizeSkippingHeader(layout.visibleItemsInfo.map { it.index to it.size.height })
            val columns = layout.visibleItemsInfo.map { it.column }.distinct().size.coerceAtLeast(1)
            val totalRows = (totalItems + columns - 1) / columns
            val estimatedTotalHeight = avgItemHeight * totalRows
            if (estimatedTotalHeight <= layout.viewportEndOffset) return@drawWithContent

            val scrolledPx = firstVisible.row * avgItemHeight - firstVisible.offset.y
            val maxScrollPx = estimatedTotalHeight - layout.viewportEndOffset
            info.thumbFraction = clampedThumbFraction(layout.viewportEndOffset / estimatedTotalHeight)
            info.progress = (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
            drawFaderCap(info, colors, animatedAlpha, width.toPx(), thumbHeightOffset)
        },
    )

    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(hitZoneWidth)
            .thumbGestureExclusion(info, thumbHeightOffset, grabTolerancePx)
            .faderScrubber(
                stateKey = state,
                info = info,
                animatedAlpha = { animatedAlpha },
                thumbHeightOffset = thumbHeightOffset,
                onWake = { alpha = 1f },
                onScrub = { progress ->
                    // Pixel-granular target: row + intra-row offset (see list overload).
                    val layout = state.layoutInfo
                    val totalItems = layout.totalItemsCount
                    if (totalItems > 0 && layout.visibleItemsInfo.isNotEmpty()) {
                        val columns = layout.visibleItemsInfo
                            .map { it.column }.distinct().size.coerceAtLeast(1)
                        val totalRows = (totalItems + columns - 1) / columns
                        val avgRowHeight = avgSizeSkippingHeader(
                            layout.visibleItemsInfo.map { it.index to it.size.height },
                        )
                        val maxScrollPx =
                            (avgRowHeight * totalRows - layout.viewportEndOffset).coerceAtLeast(0f)
                        val targetPx = progress * maxScrollPx
                        val targetRow = (targetPx / avgRowHeight).toInt().coerceAtLeast(0)
                        val index = (targetRow * columns).coerceIn(0, totalItems - 1)
                        val offsetPx = (targetPx - targetRow * avgRowHeight).roundToInt()
                        scope.launch { state.scrollToItem(index, offsetPx) }
                    }
                },
            ),
    )
}
