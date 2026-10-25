package com.stash.feature.nowplaying.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.stash.core.ui.ambient.rememberReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Released under this much zoom, a page pinched off the art drops back onto it. */
private const val LIFT_MIN_SCALE = 1.1f

/** Released under this share of the screen's fit, an open page goes back onto the art. */
private const val CLOSE_BELOW_FIT = 0.85f

/** Pinched in, a page shrinks to this much of its size on the art at the least. */
private const val MIN_SCALE = 0.5f

/** The furthest in a page zooms, in fills of the screen: enough for a booklet's small print. */
private const val MAX_FITS = 5f

/** The scrim behind a page that fills the screen. */
private const val SCRIM_ALPHA = 0.94f

/**
 * A page of the album that can be pinched off the art: its image, what it is, and its Discogs page
 * when the image comes from Discogs (for the credit its terms ask for).
 */
internal class LiftedPage(
    val key: String,
    val request: ImageRequest,
    val description: String,
    val discogsPage: String? = null,
)

/**
 * A page pinched off the art and held up to the screen. Two fingers on a page lift it out of the
 * art's box ([LiftablePage]) into [PageZoomLayer], over all of Now Playing, where it follows the
 * fingers. Let go barely zoomed, it drops back onto the art; further, it stays up, filling the
 * screen at the least, to pinch and pan around; a tap (or back) puts it back.
 *
 * Positions are in window px, so the page lands back where it was lifted from.
 */
@Stable
internal class PageZoom(private val scope: CoroutineScope, private val reducedMotion: () -> Boolean) {
    /** The page held up, null while every page is on the art. */
    var page by mutableStateOf<LiftedPage?>(null)
        private set

    /** Where the page sits on the art: where it lifts from and lands back. */
    var origin by mutableStateOf(Rect.Zero)
        private set

    /** The screen the layer covers. */
    internal var viewport = Rect.Zero

    /** The page's size on screen over its size on the art. */
    var scale by mutableFloatStateOf(1f)
        private set

    /** The page's top-left corner on screen. */
    var topLeft by mutableStateOf(Offset.Zero)
        private set

    private var settling: Job? = null

    /** The scale at which the page fills the screen (one side edge to edge). */
    private val fit: Float
        get() {
            if (origin.width <= 0f || origin.height <= 0f || viewport.isEmpty) return 1f
            return minOf(viewport.width / origin.width, viewport.height / origin.height).coerceAtLeast(1f)
        }

    /** 0 on the art, 1 once the page fills the screen: the scrim and the corners follow it. */
    val progress: Float
        get() = ((scale - 1f) / (fit - 1f).coerceAtLeast(0.15f)).coerceIn(0f, 1f)

    /** Lifts [page] off the art from [from]. */
    fun lift(page: LiftedPage, from: Rect) {
        settling?.cancel()
        this.page = page
        origin = from
        scale = 1f
        topLeft = from.topLeft
    }

    /** A finger on the open page: it holds still under it. */
    fun grab() {
        settling?.cancel()
    }

    /** The fingers moved: zoomed by [zoom] about [centroid] and moved by [pan]. */
    fun transform(centroid: Offset, zoom: Float, pan: Offset) {
        val s = (scale * zoom).coerceIn(MIN_SCALE, fit * MAX_FITS)
        topLeft = centroid + (topLeft - centroid) * (s / scale) + pan
        scale = s
    }

    /** The fingers let go: the page drops back, or settles over the screen. */
    fun release(fromArt: Boolean) {
        val fit = fit
        if (scale < if (fromArt) minOf(LIFT_MIN_SCALE, fit) else fit * CLOSE_BELOW_FIT) {
            close()
            return
        }
        val s = maxOf(scale, fit)
        // Where the page's middle is now, at the settled scale: then kept within the screen.
        val middle = topLeft + Offset(origin.width, origin.height) * (scale / 2f)
        animateTo(s, resting(s, middle - Offset(origin.width, origin.height) * (s / 2f)))
    }

    /** Puts the page back onto the art. */
    fun close() {
        if (page == null) return
        animateTo(1f, origin.topLeft) { page = null }
    }

    /** [topLeft] for a page at [s]: centred on a side that fits the screen, else edge to edge. */
    private fun resting(s: Float, topLeft: Offset): Offset {
        fun axis(pos: Float, size: Float, lo: Float, hi: Float) =
            if (size <= hi - lo) lo + (hi - lo - size) / 2f else pos.coerceIn(hi - size, lo)
        return Offset(
            axis(topLeft.x, origin.width * s, viewport.left, viewport.right),
            axis(topLeft.y, origin.height * s, viewport.top, viewport.bottom),
        )
    }

    private fun animateTo(s: Float, to: Offset, then: () -> Unit = {}) {
        settling?.cancel()
        val s0 = scale
        val from = topLeft
        settling = scope.launch {
            if (!reducedMotion()) {
                animate(0f, 1f, animationSpec = spring(dampingRatio = 1f, stiffness = 500f)) { f, _ ->
                    scale = lerp(s0, s, f)
                    topLeft = lerp(from, to, f)
                }
            }
            scale = s
            topLeft = to
            then()
        }
    }
}

/** A [PageZoom] for the album [key]: a new album puts any page back. */
@Composable
internal fun rememberPageZoom(key: Any?): PageZoom {
    val scope = rememberCoroutineScope()
    val reduced by rememberUpdatedState(rememberReducedMotion())
    return remember(key) { PageZoom(scope) { reduced } }
}

/**
 * A page on the art (or the front itself) that two fingers lift off it into [zoom]'s layer. While
 * it is held up, its place on the art is left empty. One finger is left alone, for the pages to
 * turn, the songs to skip and the art to flip. A null [page] (no image) stays put.
 */
@Composable
internal fun LiftablePage(
    zoom: PageZoom,
    page: LiftedPage?,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val bounds = remember { arrayOf(Rect.Zero) }
    Box(
        modifier = modifier
            .onGloballyPositioned { bounds[0] = it.boundsInWindow() }
            .graphicsLayer { alpha = if (page != null && zoom.page?.key == page.key) 0f else 1f }
            .pointerInput(zoom, page?.key) {
                if (page == null) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var lifted = false
                    do {
                        val event = awaitPointerEvent()
                        if (!lifted && event.changes.count { it.pressed } > 1) {
                            zoom.lift(page, bounds[0])
                            lifted = true
                        }
                        if (lifted) {
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (centroid.isSpecified) {
                                zoom.transform(bounds[0].topLeft + centroid, event.calculateZoom(), event.calculatePan())
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (lifted) zoom.release(fromArt = true)
                }
            },
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * The page held up, over all of Now Playing (drawn last in its box, the whole screen): on a scrim
 * that darkens as it grows, its corners squaring off as it fills the screen. Pinch to zoom, drag to
 * pan; a tap or back puts it back on the art. Takes no touches while no page is up.
 */
@Composable
internal fun PageZoomLayer(zoom: PageZoom, modifier: Modifier = Modifier) {
    val page = zoom.page
    val density = LocalDensity.current
    val corner = with(density) { 20.dp.toPx() }
    val layerOrigin = remember { arrayOf(Offset.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                zoom.viewport = it.boundsInWindow()
                layerOrigin[0] = it.positionInWindow()
            }
            .then(if (page == null) Modifier else Modifier.zoomedPageGestures(zoom) { layerOrigin[0] }),
    ) {
        if (page == null) return@Box
        BackHandler { zoom.close() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black, alpha = SCRIM_ALPHA * zoom.progress) },
        )
        val size = with(density) { DpSize(zoom.origin.width.toDp(), zoom.origin.height.toDp()) }
        AsyncImage(
            model = page.request,
            contentDescription = page.description,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = zoom.topLeft.x - layerOrigin[0].x
                    translationY = zoom.topLeft.y - layerOrigin[0].y
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    shape = RoundedCornerShape(corner * (1f - zoom.progress) / zoom.scale)
                    clip = true
                },
        )
        page.discogsPage?.let {
            DiscogsCredit(
                page = it,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .graphicsLayer { alpha = zoom.progress },
            )
        }
    }
}

/** The open page's touches: pinch and drag move it, a tap puts it back. All are kept from below. */
private fun Modifier.zoomedPageGestures(zoom: PageZoom, layerOrigin: () -> Offset): Modifier =
    semantics { onClick(label = "Put the page back") { zoom.close(); true } }
        .pointerInput(zoom) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                zoom.grab()
                var moved = false
                var travel = Offset.Zero
                // The credit's tap opens Discogs: it doesn't also put the page back.
                var taken = false
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) taken = true
                    if (!moved) {
                        travel += event.calculatePan()
                        moved = event.changes.count { it.pressed } > 1 || travel.getDistance() > viewConfiguration.touchSlop
                    }
                    if (moved) {
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (centroid.isSpecified) {
                            zoom.transform(layerOrigin() + centroid, event.calculateZoom(), event.calculatePan())
                        }
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                if (moved) zoom.release(fromArt = false) else if (!taken) zoom.close()
            }
        }
