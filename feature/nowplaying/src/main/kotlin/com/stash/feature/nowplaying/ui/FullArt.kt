package com.stash.feature.nowplaying.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.theme.LocalStashColors
import com.stash.core.ui.theme.StashDarkColorScheme
import com.stash.core.ui.theme.StashExtendedColorsDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** The crossfade from one page to the next. */
private const val PAGE_FADE_MS = 2_200L

/** The art growing from its square to the full width, and back. */
private const val GROW_MS = 650

/**
 * The art fading out as lyrics open (quickly, under the cover flying to the header) and back in as
 * they close (a beat slower, as the cover lands).
 */
private const val ART_OUT_MS = 180
private const val ART_IN_MS = 320

/** Where the art starts melting into the blend under it, of its height. */
private const val ART_FADE_FROM = 0.55f

/** The blend: the page drawn this small and stretched, softened further where Android can blur. */
private const val BLEND_DECODE_PX = 48
private val BLEND_BLUR = 28.dp

/** The solid blend: the page's bottom edge color, this much of its brightness (as on the mockup). */
private const val SOLID_BLEND_DARKEN = 0.62f

/** A gap this wide between the drawn clock and the player's own is a seek, followed at once. */
private const val RESYNC_MS = 700L

/** The most pages drawn at once: the one showing and the ones still fading out under it. */
private const val MAX_SHOWN = 3

/**
 * Just after a song changes the player can still report the last one's position for a tick: for
 * this long, only a position near the start is believed.
 */
private const val NEW_SONG_SETTLE_MS = 1_000L
private const val NEW_SONG_START_MS = 3_000L

/** One page full art can show: the front first, then the back and the pages inside. */
@Immutable
internal class FullArtPage(
    val key: String,
    val request: ImageRequest,
    /** What TalkBack reads for it. */
    val description: String,
    /** Its Discogs page when its image comes from Discogs (the credit links there), else null. */
    val discogsPage: String? = null,
)

/**
 * A page's image in hand, a tiny copy of it stretched into the blurred blend under the controls,
 * and the color of its bottom edge, darkened, for the solid one.
 */
private class LoadedPage(val image: ImageBitmap, val small: ImageBitmap, val solid: Color)

/** A page on screen: fading in from [bornMs] (frame time), its pan timed from [atPlayMs] (playing time). */
internal class Shown(val page: FullArtPage, val bornMs: Long, val atPlayMs: Long, val direction: Int)

/**
 * Full art's state, hoisted so Now Playing can hide its square art while the full art is out:
 * [grow] (0 the square, 1 the full width), the page on screen and the ones fading out under it,
 * and the song's clock, smoothed between the player's 250ms ticks so the pan never steps. Changes
 * only on the frame loop and gestures, both on the main thread.
 */
@Stable
internal class FullArtState {
    val grow = Animatable(0f)

    internal val shown = mutableStateListOf<Shown>()

    /** The frame clock: the art pans on it. */
    internal var frameMs by mutableLongStateOf(0L)

    /** Moves only while a page fades in: the blend redraws then, and is still otherwise. */
    internal var fadeMs by mutableLongStateOf(0L)

    /** The first page is in hand: the art grows out only then, so the square never leaves a gap. */
    var ready by mutableStateOf(false)
        private set

    /** Time spent playing (it stands still while paused): the pan's clock. */
    internal var playMs = 0L
    private var songMs = 0L
    private var lastFrameMs = 0L
    private var settleUntilMs = 0L
    private var segment = -1
    private var offset = 0
    private var turns = 0
    private var pending: FullArtPage? = null
    private var reported = -1

    /** Set from the layer on each composition: what a page turned by hand turns through. */
    internal var pages: List<FullArtPage> = emptyList()
    internal var schedule: FullArtSchedule = fullArtSchedule(0L, 1)
    internal var loaded: Map<String, *> = emptyMap<String, Any>()

    /** The page on screen's index, told to [onPage] when it changes. */
    internal var onPage: (Int) -> Unit = {}

    private fun pageFor(segment: Int): FullArtPage = pages[(schedule.pageAt(segment) + offset).mod(pages.size)]

    /** Opened on [start] (the page the square art was showing); the pages go on from there. */
    fun open(now: Long, position: Long, start: Int) {
        songMs = position
        lastFrameMs = now
        segment = schedule.segmentAt(position)
        val first = start.coerceIn(0, pages.size - 1)
        offset = (first - schedule.pageAt(segment)).mod(pages.size)
        shown.clear()
        pending = null
        ready = false
        shown.add(Shown(pages[first], now - PAGE_FADE_MS, playMs, direction()))
    }

    /** A new song: its front, faded to over the page the last one ended on. */
    fun newSong(now: Long) {
        songMs = 0L
        settleUntilMs = now + NEW_SONG_SETTLE_MS
        segment = 0
        offset = 0
        pending = pageFor(0)
    }

    /** A page turned by hand ([step] 1 the next, -1 the one before): the pages go on from it. */
    fun turn(step: Int) {
        if (pages.size < 2) return
        val at = pages.indexOfFirst { it.key == shown.lastOrNull()?.page?.key }.coerceAtLeast(0)
        val next = (at + step).mod(pages.size)
        offset = (next - schedule.pageAt(segment.coerceAtLeast(0))).mod(pages.size)
        pending = pages[next]
    }

    /** One frame: the clocks move, and the page the song has reached comes in once it's loaded. */
    fun tick(now: Long, position: Long, playing: Boolean, failed: Set<String>, still: Boolean) {
        val dt = if (lastFrameMs == 0L) 0L else (now - lastFrameMs).coerceIn(0L, 100L)
        lastFrameMs = now
        frameMs = now
        if (playing) playMs += dt
        // The last song's position, still reported a moment after the change, is passed over.
        val believed = now >= settleUntilMs || position < NEW_SONG_START_MS
        songMs = when {
            !believed -> songMs + if (playing) dt else 0L
            playing -> (songMs + dt).let { if (abs(it - position) > RESYNC_MS) position else it }
            else -> position
        }
        if (pages.isEmpty()) return

        val at = schedule.segmentAt(songMs)
        if (at != segment) {
            segment = at
            pending = pageFor(at)
        }
        // A page that can't load is passed over for the one after it.
        var skips = 0
        while (pending?.key in failed && skips < pages.size) {
            offset++
            skips++
            pending = pageFor(segment)
        }
        val next = pending
        if (next != null && next.key in loaded) {
            pending = null
            val current = shown.lastOrNull()
            if (current == null || current.page.key != next.key) {
                shown.add(Shown(next, if (still) now - PAGE_FADE_MS else now, playMs, direction()))
                while (shown.size > MAX_SHOWN) shown.removeAt(0)
            }
        }
        val top = shown.lastOrNull()
        if (!ready && top != null && top.page.key in loaded) {
            ready = true
            fadeMs = now
        }
        if (top != null && now - top.bornMs < PAGE_FADE_MS) fadeMs = now
        // Pages fully covered by the one fading in over them are let go.
        if (top != null && shown.size > 1 && now - top.bornMs >= PAGE_FADE_MS) {
            shown.removeRange(0, shown.size - 1)
            fadeMs = now
        }
        val index = pages.indexOfFirst { it.key == top?.page?.key }
        if (index >= 0 && index != reported) {
            reported = index
            onPage(index)
        }
    }

    /** The pan's side, alternating page to page. */
    private fun direction(): Int = if (turns++ % 2 == 0) 1 else -1
}

@Composable
internal fun rememberFullArtState(): FullArtState = remember { FullArtState() }

/**
 * Now Playing's colours while full art is out: the dark theme's, whatever the app's, since the
 * controls sit on the art's darkened blend. Always composed (with [on] false it passes the theme
 * through) so turning full art on or off never rebuilds the screen under it.
 */
@Composable
internal fun FullArtTheme(on: Boolean, content: @Composable () -> Unit) {
    val scheme = if (on) StashDarkColorScheme else MaterialTheme.colorScheme
    CompositionLocalProvider(
        LocalIsDarkTheme provides (on || LocalIsDarkTheme.current),
        LocalStashColors provides (if (on) StashExtendedColorsDark else LocalStashColors.current),
    ) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
    }
}

/**
 * Full art: Now Playing's art across the whole width and down behind the controls, the album's
 * pages taking turns as the song plays ([fullArtSchedule]), each panning slowly across what its
 * crop hides ([panCrop]) with a long crossfade between them. The art melts at its foot into a
 * blend of the same page, blurred and darkened, which runs to the bottom under the controls. It
 * grows out of the square art when turned on (and when Now Playing opens with it on) and shrinks
 * back into it when turned off.
 *
 * Drawn behind Now Playing's controls, which stay as they are, in white.
 *
 * @param active Full art is on.
 * @param visible Now Playing is settled at the top of the sheet: drawn only then.
 * @param artShown The art is up, not lyrics. With lyrics up only the blend stays, under them,
 *   so opening and closing lyrics never snaps the screen back to the square art.
 * @param running Now Playing is in sight: the pan's frame loop runs only then.
 * @param pages The front, then the back and the pages inside, those that are known.
 * @param startPage The page the square art shows: full art opens on it.
 * @param trackKey The playing song: a new one starts on its front.
 * @param origin The square art in window coordinates: full art grows from it and back into it.
 * @param originCornerPx The square art's corner radius.
 * @param onPage Told the index of the page on screen when it changes.
 * @param zoom How close the art shows the album: a square cover this many screen widths across.
 * @param panDpPerS How fast a page pans across what its crop hides; 0 holds it still, centred.
 * @param solidBlend The blend is a color from each page's bottom edge, not the page blurred.
 */
@Composable
internal fun FullArtLayer(
    state: FullArtState,
    active: Boolean,
    visible: Boolean,
    artShown: Boolean,
    running: Boolean,
    pages: List<FullArtPage>,
    startPage: Int,
    trackKey: Any?,
    positionMs: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    origin: () -> Rect?,
    originCornerPx: Float,
    onPage: (Int) -> Unit,
    zoom: Float,
    panDpPerS: Float,
    solidBlend: Boolean,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberNpReducedMotion()
    val target = active && visible && pages.isNotEmpty()
    LaunchedEffect(target, visible, reducedMotion, state.ready) {
        when {
            // Leaving the top of the sheet (or for lyrics): the square takes the art back at once.
            !visible -> state.grow.snapTo(0f)
            // Out only once its first page is in hand.
            target && !state.ready -> Unit
            reducedMotion -> state.grow.snapTo(if (target) 1f else 0f)
            else -> state.grow.animateTo(if (target) 1f else 0f, tween(GROW_MS, easing = FastOutSlowInEasing))
        }
    }
    val artAlpha = remember { Animatable(if (artShown) 1f else 0f) }
    LaunchedEffect(artShown, reducedMotion) {
        val to = if (artShown) 1f else 0f
        if (reducedMotion) artAlpha.snapTo(to) else artAlpha.animateTo(to, tween(if (artShown) ART_IN_MS else ART_OUT_MS))
    }
    if (!target && state.grow.value == 0f) return

    val schedule = remember(durationMs, pages.size) { fullArtSchedule(durationMs, pages.size) }
    val loaded = remember { mutableStateMapOf<String, LoadedPage>() }
    val failed = remember { mutableStateMapOf<String, Boolean>() }
    val currentOnPage by rememberUpdatedState(onPage)
    SideEffect {
        state.pages = pages
        state.schedule = schedule
        state.loaded = loaded
        state.onPage = { currentOnPage(it) }
    }
    val currentPosition by rememberUpdatedState(positionMs)
    val currentPlaying by rememberUpdatedState(isPlaying)
    val currentStart by rememberUpdatedState(startPage)

    // Opened (or reopened with the sheet): the page the square art was showing.
    LaunchedEffect(target) {
        if (target) {
            val now = withFrameMillis { it }
            state.pages = pages
            state.schedule = schedule
            state.open(now, currentPosition(), currentStart)
        }
    }
    // Another song: its album's front.
    var lastTrack by remember { mutableStateOf(trackKey) }
    LaunchedEffect(trackKey) {
        if (trackKey != lastTrack) {
            lastTrack = trackKey
            state.newSong(withFrameMillis { it })
        }
    }
    // The frame loop: while it's out and in sight.
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            withFrameMillis { now ->
                state.tick(now, currentPosition(), currentPlaying, failed.keys, reducedMotion)
            }
        }
    }

    // The pages in hand: those on screen, the front and the back, and the page after the one up
    // (the pages go in order). A page leaving is let go; Coil keeps its own copy while memory allows.
    val onScreen = state.shown.toList()
    val wanted = remember(pages, onScreen) {
        val at = pages.indexOfFirst { it.key == onScreen.lastOrNull()?.page?.key }
        val next = if (at >= 0 && pages.isNotEmpty()) pages[(at + 1) % pages.size] else null
        (onScreen.map { it.page } + pages.take(2) + listOfNotNull(next)).distinctBy { it.key }
    }
    wanted.forEach { page ->
        key(page.key) {
            PageLoader(page, onLoaded = { loaded[page.key] = it }, onFailed = { failed[page.key] = true })
            DisposableEffect(page.key) { onDispose { loaded.remove(page.key) } }
        }
    }

    LightStatusBarIcons(on = state.grow.value > 0.5f)

    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    val panSpeedPx = with(LocalDensity.current) { panDpPerS.dp.toPx() }
    val blur = with(LocalDensity.current) { BLEND_BLUR.toPx() }
    val topPage = state.shown.lastOrNull()?.page

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { layerOrigin = it.positionInWindow() },
    ) {
        // The blend: the page under the controls, stretched from a tiny copy and softened, or the
        // color of its bottom edge (Settings > Appearance > Full art blend). Redrawn only while a
        // page fades in.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = state.grow.value
                    renderEffect = if (!solidBlend && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BlurEffect(blur, blur, TileMode.Clamp)
                    } else {
                        null
                    }
                }
                .drawBehind {
                    val now = state.fadeMs
                    state.shown.forEachIndexed { index, shown ->
                        val page = loaded[shown.page.key] ?: return@forEachIndexed
                        val alpha = fadeAlpha(index, now, shown, reducedMotion)
                        if (alpha <= 0f) return@forEachIndexed
                        if (solidBlend) {
                            drawRect(page.solid, alpha = alpha)
                        } else {
                            val crop = coverCrop(page.small.width.toFloat(), page.small.height.toFloat(), size.width, size.height)
                            drawPage(page.small, crop, alpha)
                        }
                    }
                },
        )

        // The art: from the square to the full width, its foot melting into the blend.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = topPage?.description ?: "Album art"
                    if (pages.size > 1) {
                        customActions = listOf(
                            CustomAccessibilityAction("Next page") { state.turn(1); true },
                            CustomAccessibilityAction("Previous page") { state.turn(-1); true },
                        )
                    }
                }
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = artAlpha.value
                }
                .drawBehind {
                    val t = state.grow.value
                    val (full, extraZoom) = fullArtBox(size.width, size.height, zoom)
                    val from = origin()?.translate(-layerOrigin) ?: full
                    val stage = lerpRect(from, full, t)
                    val corner = originCornerPx * (1f - t)
                    val now = state.frameMs
                    val clip = Path().apply { addRoundRect(RoundRect(stage, CornerRadius(corner))) }
                    clipPath(clip) {
                        state.shown.forEachIndexed { index, shown ->
                            val page = loaded[shown.page.key] ?: return@forEachIndexed
                            val alpha = fadeAlpha(index, now, shown, reducedMotion)
                            if (alpha <= 0f) return@forEachIndexed
                            val w = page.image.width.toFloat()
                            val h = page.image.height.toFloat()
                            val square = coverCrop(w, h, from.width, from.height).translate(from.topLeft)
                            val panned = panCrop(
                                w, h, full.width, full.height,
                                elapsedS = if (reducedMotion) 0f else (state.playMs - shown.atPlayMs) / 1000f,
                                direction = shown.direction,
                                holdS = state.schedule.holdMs / 1000f,
                                speedPxPerS = if (reducedMotion) 0f else panSpeedPx,
                                zoom = extraZoom,
                            )
                            drawPage(page.image, lerpRect(square, panned, t), alpha)
                        }
                    }
                    // The foot melts away (none in the square).
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            ART_FADE_FROM to Color.Black,
                            1f to Color.Black.copy(alpha = 1f - t),
                            startY = stage.top,
                            endY = stage.bottom,
                        ),
                        topLeft = stage.topLeft,
                        size = stage.size,
                        blendMode = BlendMode.DstIn,
                    )
                },
        )

        // Shade: at the top under the top row, and from the art's middle down under the controls,
        // so white type reads on a white page.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .graphicsLayer { alpha = state.grow.value }
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent))),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = state.grow.value }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to Color.Transparent,
                        0.55f to Color.Black.copy(alpha = 0.5f),
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
        )

        // Discogs' API terms: "Data provided by Discogs." directly next to its data, linked to its
        // page there (the exact words are theirs). Quiet, so it sits in the art.
        val discogs = topPage?.discogsPage
        if (discogs != null) {
            val uriHandler = LocalUriHandler.current
            Text(
                text = "Data provided by Discogs",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 24.dp, top = 60.dp)
                    .graphicsLayer { alpha = state.grow.value * artAlpha.value }
                    .clickable(enabled = artShown) { runCatching { uriHandler.openUri(discogs) } },
            )
        }
    }
}

/**
 * Where full art's art sits in Now Playing's art slot: a sideways swipe there turns the page (the
 * pages go on from it). Stands in for the square art's own gestures while full art is out.
 */
@Composable
internal fun FullArtSwipe(state: FullArtState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        moved = change.position - down.position
                        if (abs(moved.x) > viewConfiguration.touchSlop && abs(moved.x) > abs(moved.y)) change.consume()
                        if (!change.pressed) break
                    }
                    if (abs(moved.x) > viewConfiguration.touchSlop * 2 && abs(moved.x) > abs(moved.y)) {
                        state.turn(if (moved.x < 0) 1 else -1)
                    }
                }
            },
    )
}

private fun fadeAlpha(index: Int, now: Long, shown: Shown, still: Boolean): Float =
    if (index == 0 || still) 1f else easeInOutSine((now - shown.bornMs).toFloat() / PAGE_FADE_MS)

/** [image] drawn into [dst] at any fraction of a pixel, so a slow pan never steps. */
private fun DrawScope.drawPage(image: ImageBitmap, dst: Rect, alpha: Float) {
    withTransform({
        translate(dst.left, dst.top)
        scale(dst.width / image.width, dst.height / image.height, Offset.Zero)
    }) {
        drawImage(image, alpha = alpha)
    }
}

/** Loads [page]'s image and its tiny copy for the blend: tried as the back cover's is, then given up on. */
@Composable
private fun PageLoader(page: FullArtPage, onLoaded: (LoadedPage) -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val currentOnLoaded by rememberUpdatedState(onLoaded)
    val currentOnFailed by rememberUpdatedState(onFailed)
    LaunchedEffect(page.key) {
        for (attempt in 1..IMAGE_TRIES) {
            try {
                val result = SingletonImageLoader.get(context).execute(page.request)
                val image = (result as? SuccessResult)?.image
                if (image != null && image.width > 0 && image.height > 0) {
                    val bitmap = image.toBitmap()
                    val (small, solid) = withContext(Dispatchers.Default) { smallCopy(bitmap).let { it to bottomEdge(it) } }
                    currentOnLoaded(LoadedPage(bitmap.asImageBitmap(), small.asImageBitmap(), solid))
                    return@LaunchedEffect
                }
                Log.w("FullArt", "page image, try $attempt: ${(result as? ErrorResult)?.throwable?.javaClass?.simpleName}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("FullArt", "page image, try $attempt", e)
            }
            if (attempt < IMAGE_TRIES) delay(IMAGE_RETRY_MS * attempt)
        }
        currentOnFailed()
    }
}

/** [bitmap] at [BLEND_DECODE_PX] across: stretched to the screen, it's already most of a blur. */
private fun smallCopy(bitmap: Bitmap): Bitmap {
    val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val h = (BLEND_DECODE_PX * readable.height.toFloat() / readable.width).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(readable, BLEND_DECODE_PX, h, true)
}

/** The average color along [small]'s bottom two rows, darkened as the solid blend under white type. */
private fun bottomEdge(small: Bitmap): Color {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0
    for (y in (small.height - 2).coerceAtLeast(0) until small.height) for (x in 0 until small.width) {
        val p = small.getPixel(x, y)
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
        n++
    }
    if (n == 0) return Color.Black
    val k = SOLID_BLEND_DARKEN / 255f
    return Color(r / n * k, g / n * k, b / n * k)
}

/** Light status bar icons while [on] (the art's shade is dark), the theme's own after. */
@Composable
private fun LightStatusBarIcons(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        if (on) controller?.isAppearanceLightStatusBars = false
        onDispose { if (on && before != null) controller.isAppearanceLightStatusBars = before }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
