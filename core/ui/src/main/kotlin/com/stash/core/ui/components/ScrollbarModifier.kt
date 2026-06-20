package com.stash.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Shared snapshot written by the draw layer, read by the drag-handle layer. */
private class ScrollbarTrackInfo {
    var progress by mutableStateOf(0f)
    var thumbFraction by mutableStateOf(1f)
}

@Composable
fun BoxScope.VerticalScrollbar(
    state: LazyListState,
    color: Color = Color.White.copy(alpha = 0.5f),
    width: Dp = 6.dp,
    idleDelayMs: Long = 800L,
    thumbHeightOffset: Float = 0f,
    hitZoneWidth: Dp = 36.dp,
) {
    val info = remember { ScrollbarTrackInfo() }
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }
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
            val avgItemSize = layout.visibleItemsInfo.sumOf { it.size }.toFloat() / layout.visibleItemsInfo.size
            val estimatedTotalHeight = avgItemSize * totalItems
            if (estimatedTotalHeight <= layout.viewportEndOffset) return@drawWithContent

            val scrolledPx = firstVisible.index * avgItemSize - firstVisible.offset
            val maxScrollPx = estimatedTotalHeight - layout.viewportEndOffset
            info.thumbFraction = (layout.viewportEndOffset / estimatedTotalHeight).coerceIn(0.05f, 1f)
            info.progress = (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
            if (animatedAlpha <= 0.001f) return@drawWithContent

            val trackHeight = size.height
            val thumbHeight = (trackHeight * info.thumbFraction) + thumbHeightOffset
            val thumbOffsetY = info.progress * (trackHeight - thumbHeight)
            drawRoundRect(
                color = color.copy(alpha = color.alpha * animatedAlpha),
                topLeft = Offset(size.width - widthPx - 2f, thumbOffsetY),
                size = Size(widthPx, thumbHeight),
                cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
            )
        },
    )

    // Narrow grab strip — ONLY this slice is touchable, everything left of it
    // passes straight through to the list/grid underneath untouched.
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(hitZoneWidth)
            .pointerInput(state) {
                var isDraggingThumb = false
                val grabTolerance = 24.dp.toPx()
                var dragProgress = info.progress

                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val trackHeight = size.height.toFloat()
                        val thumbHeight = (trackHeight * info.thumbFraction) + thumbHeightOffset
                        val thumbOffsetY = info.progress * (trackHeight - thumbHeight)
                        isDraggingThumb = offset.y in (thumbOffsetY - grabTolerance)..(thumbOffsetY + thumbHeight + grabTolerance)
                        if (isDraggingThumb) {
                            dragProgress = info.progress  // snapshot current position on drag start
                            alpha = 1f
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (!isDraggingThumb) return@detectVerticalDragGestures
                        change.consume()
                        val trackHeight = size.height.toFloat()
                        val thumbHeight = (trackHeight * info.thumbFraction) + thumbHeightOffset
                        val maxOffset = trackHeight - thumbHeight
                        if (maxOffset <= 0f) return@detectVerticalDragGestures
                        dragProgress = (dragProgress + dragAmount / maxOffset).coerceIn(0f, 1f)
                        val targetIndex = (dragProgress * (state.layoutInfo.totalItemsCount - 1)).roundToInt()
                        scope.launch { state.scrollToItem(targetIndex) }
                    },
                )
            },
    )
}

@Composable
fun BoxScope.VerticalScrollbar(
    state: LazyGridState,
    color: Color = Color.White.copy(alpha = 0.5f),
    width: Dp = 6.dp,
    idleDelayMs: Long = 800L,
    thumbHeightOffset: Float = 0f,
    hitZoneWidth: Dp = 36.dp,
) {
    val info = remember { ScrollbarTrackInfo() }
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }
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
            val avgItemHeight = layout.visibleItemsInfo.map { it.size.height }.average().toFloat()
            val columns = layout.visibleItemsInfo.map { it.column }.distinct().size.coerceAtLeast(1)
            val totalRows = (totalItems + columns - 1) / columns
            val estimatedTotalHeight = avgItemHeight * totalRows
            if (estimatedTotalHeight <= layout.viewportEndOffset) return@drawWithContent

            val scrolledPx = firstVisible.row * avgItemHeight - firstVisible.offset.y
            val maxScrollPx = estimatedTotalHeight - layout.viewportEndOffset
            info.thumbFraction = (layout.viewportEndOffset / estimatedTotalHeight).coerceIn(0.05f, 1f)
            info.progress = (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
            if (animatedAlpha <= 0.001f) return@drawWithContent

            val trackHeight = size.height
            val thumbHeight = (trackHeight * info.thumbFraction) + thumbHeightOffset
            val thumbOffsetY = info.progress * (trackHeight - thumbHeight)
            drawRoundRect(
                color = color.copy(alpha = color.alpha * animatedAlpha),
                topLeft = Offset(size.width - widthPx - 2f, thumbOffsetY),
                size = Size(widthPx, thumbHeight),
                cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
            )
        },
    )

    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(hitZoneWidth)
            .pointerInput(state) {
                var isDraggingThumb = false
                val grabTolerance = 24.dp.toPx()
                var dragProgress = info.progress

                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val trackHeight = size.height.toFloat()
                        val thumbHeight = (trackHeight * info.thumbFraction) + thumbHeightOffset
                        val thumbOffsetY = info.progress * (trackHeight - thumbHeight)
                        isDraggingThumb = offset.y in (thumbOffsetY - grabTolerance)..(thumbOffsetY + thumbHeight + grabTolerance)
                        if (isDraggingThumb) {
                            dragProgress = info.progress  // snapshot current position on drag start
                            alpha = 1f
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (!isDraggingThumb) return@detectVerticalDragGestures
                        change.consume()
                        val trackHeight = size.height.toFloat()
                        val thumbHeight = (trackHeight * info.thumbFraction) + thumbHeightOffset
                        val maxOffset = trackHeight - thumbHeight
                        if (maxOffset <= 0f) return@detectVerticalDragGestures
                        dragProgress = (dragProgress + dragAmount / maxOffset).coerceIn(0f, 1f)
                        val columns = state.layoutInfo.visibleItemsInfo.map { it.column }.distinct().size.coerceAtLeast(1)
                        val totalItems = state.layoutInfo.totalItemsCount
                        val totalRows = (totalItems + columns - 1) / columns
                        val targetRow = (dragProgress * (totalRows - 1)).roundToInt()
                        val targetIndex = (targetRow * columns).coerceIn(0, totalItems - 1)
                        scope.launch { state.scrollToItem(targetIndex) }
                    },
                )
            },
    )
}