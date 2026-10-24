package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** The furthest a page zooms: enough to read a booklet's small print at 1200px. */
private const val MAX_ZOOM = 4f

/** Where a double-tap zooms to. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * The pages inside an album's cover (booklet, gatefold panels, inner sleeve), full screen on black:
 * swipe between them, pinch or double-tap to zoom into one (a zoomed page pans instead of turning),
 * the page count at the top. Back or the close button leaves.
 *
 * @param pages The pages' image URLs, in reading order.
 */
@Composable
internal fun AlbumInsideViewer(pages: List<String>, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pagerState = rememberPagerState { pages.size }
        // Whether each page is zoomed in: a zoomed page takes the drag to pan, so the pager holds still.
        val zoomed = remember { mutableStateMapOf<Int, Boolean>() }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = zoomed[pagerState.currentPage] != true,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                ZoomablePage(
                    url = pages[index],
                    description = "Inside page ${index + 1} of ${pages.size}",
                    onZoomedChange = { zoomed[index] = it },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
                if (pages.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${pages.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * One page, fitted to the screen. Two fingers zoom it (up to [MAX_ZOOM]) and, once zoomed, one
 * finger pans it within its edges; a double-tap zooms to [DOUBLE_TAP_ZOOM] at the tap, or back out.
 * At rest it leaves single-finger drags alone, for the pager to turn the page.
 */
@Composable
private fun ZoomablePage(url: String, description: String, onZoomedChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val zoomedIn = scale > 1f
    LaunchedEffect(zoomedIn) { onZoomedChange(zoomedIn) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Zoom about the tap: the tapped point stays under the finger.
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = DOUBLE_TAP_ZOOM
                            offset = clamp((center - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            offset = if (newScale == 1f) Offset.Zero else clamp(offset + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = remember(url) { ImageRequest.Builder(context).data(url).build() },
            contentDescription = description,
            contentScale = ContentScale.Fit,
            onSuccess = { loading = false },
            onError = {
                loading = false
                failed = true
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        when {
            loading -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            failed -> Text(
                text = "This page didn't load",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}
