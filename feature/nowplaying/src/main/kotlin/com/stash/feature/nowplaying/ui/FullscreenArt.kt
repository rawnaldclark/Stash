package com.stash.feature.nowplaying.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** The crossfade from one page to the next. */
private const val PAGE_FADE_MS = 2_200L

/** The cover growing from its square to the whole screen, and back. */
private const val GROW_MS = 650

/** The controls fade out after this long untouched, while the song plays. */
private const val CONTROLS_IDLE_MS = 3_500L

/** The song's title shows this long when a song starts or fullscreen opens. */
private const val TITLE_CARD_MS = 6_000L

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

/** One page fullscreen art can show: the front first, then the back and the pages inside. */
@Immutable
internal class FullscreenPage(
    val key: String,
    val request: ImageRequest,
    /** What the controls call it: "Front", "Back", "Inside 3 of 8". */
    val label: String,
    /** What TalkBack reads for it. */
    val description: String,
    /** Its Discogs page when its image comes from Discogs (the credit links there), else null. */
    val discogsPage: String? = null,
)

/** A page's image in hand, with the colours at its top and bottom edges for the ground around it. */
private class LoadedPage(val image: ImageBitmap, val top: Color, val bottom: Color)

/** A page on screen: fading in from [bornMs] (frame time), its camera timed from [startMs] (song time). */
private class Shown(val page: FullscreenPage, val startMs: Long, val bornMs: Long, val direction: Int) {
    /** Where its camera stopped once the next page took over. */
    var frozen: Float? = null
}

/**
 * What fullscreen art shows and when: the page on screen and the ones fading out under it, the
 * song's clock (smoothed between the player's 250ms ticks so the camera never steps), and which
 * page the song has reached. Changes only on the frame loop and the gestures, both on the main
 * thread.
 */
private class FullscreenArtState {
    val shown = mutableStateListOf<Shown>()
    var songMs by mutableLongStateOf(0L)
    var frameMs by mutableLongStateOf(0L)
    var titleUntilMs by mutableLongStateOf(0L)
    var controlsShown by mutableStateOf(false)
    var lastTouchMs = 0L
    private var lastFrameMs = 0L
    private var settleUntilMs = 0L
    private var segment = -1
    private var offset = 0
    private var turns = 0
    private var pending: FullscreenPage? = null

    fun progress(s: Shown, schedule: FullscreenSchedule, still: Boolean): Float =
        if (still) 0f else s.frozen ?: ((songMs - s.startMs).toFloat() / schedule.holdMs).coerceIn(0f, 1f)

    /** The page [segment] shows: the schedule's, moved on by any page turned by hand. */
    fun pageFor(segment: Int, schedule: FullscreenSchedule, pages: List<FullscreenPage>): FullscreenPage =
        pages[(schedule.pageAt(segment) + offset).mod(pages.size)]

    /** Fullscreen opens on the front, whatever the song has reached; the pages go on from there. */
    fun open(now: Long, reported: Long, schedule: FullscreenSchedule, pages: List<FullscreenPage>) {
        songMs = reported
        lastFrameMs = now
        segment = schedule.segmentAt(reported)
        offset = (-schedule.pageAt(segment)).mod(pages.size)
        shown.clear()
        pending = null
        shown.add(Shown(pages[0], reported, now - PAGE_FADE_MS, direction()))
        titleUntilMs = now + TITLE_CARD_MS
        controlsShown = false
    }

    /** A new song: the front again, faded to over the page the last one ended on. */
    fun newSong(now: Long, schedule: FullscreenSchedule, pages: List<FullscreenPage>) {
        songMs = 0L
        settleUntilMs = now + NEW_SONG_SETTLE_MS
        segment = 0
        offset = 0
        pending = pageFor(segment, schedule, pages)
        titleUntilMs = now + TITLE_CARD_MS
    }

    /** A page turned by hand ([step] 1 the next, -1 the one before): the pages go on from it. */
    fun turn(step: Int, now: Long, schedule: FullscreenSchedule, pages: List<FullscreenPage>, loaded: Map<String, LoadedPage>) {
        if (pages.size < 2) return
        val at = pages.indexOfFirst { it.key == shown.lastOrNull()?.page?.key }.coerceAtLeast(0)
        val next = (at + step).mod(pages.size)
        offset = (next - schedule.pageAt(segment.coerceAtLeast(0))).mod(pages.size)
        // Shown at once when it's in hand; else as soon as it loads.
        if (pages[next].key in loaded) {
            pending = null
            show(pages[next], songMs, now, schedule, still = false)
        } else {
            pending = pages[next]
        }
    }

    /** One frame: the clock moves, and the page the song has reached comes in once it's loaded. */
    fun tick(
        now: Long,
        reported: Long,
        playing: Boolean,
        schedule: FullscreenSchedule,
        pages: List<FullscreenPage>,
        loaded: Map<String, LoadedPage>,
        failed: Set<String>,
        still: Boolean,
    ) {
        val dt = if (lastFrameMs == 0L) 0L else (now - lastFrameMs).coerceIn(0L, 100L)
        lastFrameMs = now
        frameMs = now
        // The last song's position, still reported a moment after the change, is passed over.
        val believed = now >= settleUntilMs || reported < NEW_SONG_START_MS
        val position = when {
            !believed -> songMs + if (playing) dt else 0L
            playing -> (songMs + dt).let { if (abs(it - reported) > RESYNC_MS) reported else it }
            else -> reported
        }
        songMs = position

        val at = schedule.segmentAt(position)
        if (at != segment) {
            segment = at
            pending = pageFor(at, schedule, pages)
        }
        // A page that can't load is passed over for the one after it.
        var skips = 0
        while (pending?.key in failed && skips < pages.size) {
            offset++
            skips++
            pending = pageFor(segment, schedule, pages)
        }
        val next = pending
        if (next != null && next.key in loaded) {
            pending = null
            show(next, (segment * schedule.holdMs).coerceAtMost(position), now, schedule, still)
        }
        // Pages fully covered by the one fading in over them are let go.
        val top = shown.lastOrNull()
        if (top != null && shown.size > 1 && now - top.bornMs >= PAGE_FADE_MS) {
            shown.removeRange(0, shown.size - 1)
        }
        if (controlsShown && playing && now - lastTouchMs > CONTROLS_IDLE_MS) controlsShown = false
    }

    private fun show(page: FullscreenPage, startMs: Long, now: Long, schedule: FullscreenSchedule, still: Boolean) {
        val current = shown.lastOrNull()
        // The page already up stays, unless it's a new stretch of the song (a one-page album fades
        // to itself, its camera starting over).
        if (current != null && current.page.key == page.key && current.startMs == startMs) return
        if (current != null) current.frozen = progress(current, schedule, still)
        shown.add(Shown(page, startMs, if (still) now - PAGE_FADE_MS else now, direction()))
        while (shown.size > MAX_SHOWN) shown.removeAt(0)
    }

    /** The camera's side, alternating page to page. */
    private fun direction(): Int = if (turns++ % 2 == 0) 1 else -1
}

/**
 * Fullscreen art: the album's pages over the whole screen, paced by the song. The front grows out
 * of Now Playing's art square to fill the screen, then the pages follow one another as the song
 * plays ([fullscreenSchedule]), each starting whole and moving slowly in ([fullscreenCamera]), with
 * a long crossfade between them. Untouched, it is only the art and a thin line along the bottom, a
 * segment per page, filling as the song plays; the song's title shows for a moment as each song
 * starts. A tap brings the controls; a sideways swipe turns the page, and the pages go on from
 * there. The status and navigation bars hide while it's up (a swipe from the edge shows them).
 *
 * @param open The toggle: fullscreen art is up.
 * @param visible Now Playing is fully open (the sheet at its top): drawn only then.
 * @param pages The front, then the back and the pages inside, those that are known.
 * @param trackKey The playing song: a new one starts at the front again.
 * @param origin Now Playing's art square in window coordinates: it grows from there and shrinks back.
 * @param originCornerPx The art square's corner radius.
 * @param topBar Now Playing's top row, drawn over the art with the controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FullscreenArtLayer(
    open: Boolean,
    visible: Boolean,
    pages: List<FullscreenPage>,
    trackKey: Any?,
    title: String,
    subtitle: String,
    positionMs: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    canSkip: Boolean,
    origin: () -> Rect?,
    originCornerPx: Float,
    accent: () -> Color,
    onAccent: () -> Color,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberNpReducedMotion()
    val grow = remember { Animatable(0f) }
    val target = open && visible && pages.isNotEmpty()
    LaunchedEffect(target, reducedMotion) {
        val to = if (target) 1f else 0f
        if (reducedMotion) grow.snapTo(to) else grow.animateTo(to, tween(GROW_MS, easing = FastOutSlowInEasing))
    }
    if (!target && grow.value == 0f) return

    val state = remember { FullscreenArtState() }
    val schedule = remember(durationMs, pages.size) { fullscreenSchedule(durationMs, pages.size) }
    val loaded = remember { mutableStateMapOf<String, LoadedPage>() }
    val failed = remember { mutableStateMapOf<String, Boolean>() }

    val currentPages by rememberUpdatedState(pages)
    val currentSchedule by rememberUpdatedState(schedule)
    val currentPosition by rememberUpdatedState(positionMs)
    val currentPlaying by rememberUpdatedState(isPlaying)

    // Opened (or reopened with the sheet): the front, as the art square showed it.
    LaunchedEffect(target) {
        if (target) state.open(withFrameMillis { it }, currentPosition(), currentSchedule, currentPages)
    }
    // Another song: the front of its album again.
    var lastTrack by remember { mutableStateOf(trackKey) }
    LaunchedEffect(trackKey) {
        if (trackKey != lastTrack) {
            lastTrack = trackKey
            state.newSong(withFrameMillis { it }, currentSchedule, currentPages)
        }
    }
    // The frame loop: only while it's up or going.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { now ->
                state.tick(
                    now = now,
                    reported = currentPosition(),
                    playing = currentPlaying,
                    schedule = currentSchedule,
                    pages = currentPages,
                    loaded = loaded,
                    failed = failed.keys,
                    still = reducedMotion,
                )
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

    HideSystemBars(hidden = target && grow.value == 1f)

    val controls by animateFloatAsState(if (state.controlsShown) 1f else 0f, tween(300), label = "fullscreenControls")
    val titleCard by remember { derivedStateOf { state.frameMs < state.titleUntilMs } }
    val titleAlpha by animateFloatAsState(if (titleCard) 1f else 0f, tween(600), label = "fullscreenTitle")
    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    val shownPage = state.shown.lastOrNull()?.page
    val statusInset = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val navInset = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()

    fun touch() {
        state.lastTouchMs = state.frameMs
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { layerOrigin = it.positionInWindow() }
            // Gone the moment the sheet leaves the top: its travelling cover carries the art.
            .graphicsLayer { alpha = if (visible) 1f else 0f },
    ) {
        // The art, drawn on the frame clock: the pages, the camera on each, the fade between them,
        // and the square it grows from.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = shownPage?.description ?: "Album art"
                    customActions = listOf(
                        CustomAccessibilityAction(if (state.controlsShown) "Hide controls" else "Show controls") {
                            state.controlsShown = !state.controlsShown
                            touch()
                            true
                        },
                        CustomAccessibilityAction("Next page") {
                            state.turn(1, state.frameMs, schedule, pages, loaded); true
                        },
                        CustomAccessibilityAction("Previous page") {
                            state.turn(-1, state.frameMs, schedule, pages, loaded); true
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var moved = Offset.Zero
                        var fingers = 1
                        while (true) {
                            val event = awaitPointerEvent()
                            fingers = maxOf(fingers, event.changes.count { it.pressed })
                            event.changes.forEach { it.consume() }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            moved = change.position - down.position
                            if (!change.pressed) break
                        }
                        val slop = viewConfiguration.touchSlop
                        when {
                            fingers > 1 -> Unit
                            abs(moved.x) > slop * 2 && abs(moved.x) > abs(moved.y) -> {
                                state.turn(if (moved.x < 0) 1 else -1, state.frameMs, currentSchedule, currentPages, loaded)
                            }
                            moved.getDistance() < slop -> {
                                state.controlsShown = !state.controlsShown
                                touch()
                            }
                        }
                    }
                }
                .drawBehind {
                    val t = grow.value
                    val full = Rect(Offset.Zero, size)
                    val from = origin()?.translate(-layerOrigin) ?: full
                    val stage = lerpRect(from, full, t)
                    val corner = originCornerPx * (1f - t)
                    val clip = Path().apply { addRoundRect(RoundRect(stage, CornerRadius(corner))) }
                    clipPath(clip) {
                        val now = state.frameMs
                        state.shown.forEachIndexed { index, shown ->
                            val page = loaded[shown.page.key] ?: return@forEachIndexed
                            val alpha = if (index == 0 || reducedMotion) 1f else ((now - shown.bornMs).toFloat() / PAGE_FADE_MS).let(::easeInOutSine)
                            if (alpha <= 0f) return@forEachIndexed
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to page.top, 0.4f to page.top, 0.6f to page.bottom, 1f to page.bottom,
                                    startY = stage.top, endY = stage.bottom,
                                ),
                                topLeft = stage.topLeft,
                                size = stage.size,
                                alpha = alpha,
                            )
                            val w = page.image.width.toFloat()
                            val h = page.image.height.toFloat()
                            val crop = coverCrop(w, h, from.width, from.height)
                            val camera = fullscreenCamera(w, h, size.width, size.height, state.progress(shown, schedule, reducedMotion), shown.direction)
                            val drawn = lerpRect(crop, camera, t).translate(stage.topLeft)
                            withTransform({
                                translate(drawn.left, drawn.top)
                                scale(drawn.width / w, drawn.height / h, Offset.Zero)
                            }) {
                                drawImage(page.image, alpha = alpha)
                            }
                        }
                    }
                },
        )

        // Everything over the art comes in with the full screen, not while it grows.
        val chromeIn = ((grow.value - 0.6f) / 0.4f).coerceIn(0f, 1f)

        // A shade at the top under the controls, and at the bottom under the line and the title,
        // so white type reads on a white page.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .graphicsLayer { alpha = chromeIn * controls }
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(420.dp)
                .graphicsLayer { alpha = chromeIn * (0.65f + 0.35f * controls) }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Black.copy(alpha = 0.7f),
                        1f to Color.Black.copy(alpha = 0.8f),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = navInset + 24.dp)
                .graphicsLayer { alpha = chromeIn },
        ) {
            val discogs = shownPage?.discogsPage
            if (discogs != null) {
                val uriHandler = LocalUriHandler.current
                Text(
                    text = "Data provided by Discogs",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .clickable { runCatching { uriHandler.openUri(discogs) } },
                )
            }
            Column(modifier = Modifier.graphicsLayer { alpha = maxOf(titleAlpha, controls) }) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 24f),
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 24f),
                    ),
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            PageLine(state, schedule, pages.size, controls = { controls })
            // The times, the page and the transport open out under the line on a tap, lifting it.
            Column(modifier = Modifier.revealHeight { controls }) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val seconds by remember { derivedStateOf { state.songMs / 1000L } }
                    Text(
                        text = formatClock(seconds),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.width(64.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    PageTurnButton(Icons.Default.ChevronLeft, "Previous page", enabled = state.controlsShown && pages.size > 1) {
                        state.turn(-1, state.frameMs, schedule, pages, loaded)
                        touch()
                    }
                    Text(
                        text = shownPage?.label.orEmpty(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(96.dp),
                    )
                    PageTurnButton(Icons.Default.ChevronRight, "Next page", enabled = state.controlsShown && pages.size > 1) {
                        state.turn(1, state.frameMs, schedule, pages, loaded)
                        touch()
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (durationMs > 0L) "-" + formatClock((durationMs / 1000L - seconds).coerceAtLeast(0L)) else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(64.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canSkip) {
                        IconButton(onClick = { onPrevious(); touch() }, enabled = state.controlsShown, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                    IconButton(
                        onClick = { onPlayPause(); touch() },
                        enabled = state.controlsShown,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .drawBehind { drawRect(accent()) },
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = onAccent(),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    if (canSkip) {
                        IconButton(onClick = { onNext(); touch() }, enabled = state.controlsShown, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }
            }
        }

        // Now Playing's own top row, over the art while the controls are up.
        if (controls > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusInset)
                    .padding(horizontal = 24.dp)
                    .graphicsLayer { alpha = chromeIn * controls },
            ) {
                topBar()
            }
        }
    }
}

/**
 * The line along the bottom: a segment per page the song shows, filling as it plays (one line for
 * a one-page album). Thin while untouched; thicker with the controls. None for a song of unknown
 * length.
 */
@Composable
private fun PageLine(state: FullscreenArtState, schedule: FullscreenSchedule, pageCount: Int, controls: () -> Float) {
    if (schedule.endless) return
    val segments = if (pageCount <= 1) 1 else schedule.pages.size
    val holdMs = if (pageCount <= 1) schedule.holdMs * schedule.pages.size else schedule.holdMs
    Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
        val gap = 3.dp.toPx()
        val segmentW = (size.width - gap * (segments - 1)) / segments
        val h = (2f + 2f * controls()).dp.toPx()
        val y = (size.height - h) / 2f
        val radius = CornerRadius(h / 2f)
        val played = state.songMs
        for (i in 0 until segments) {
            val x = i * (segmentW + gap)
            drawRoundRect(Color.White.copy(alpha = 0.34f), Offset(x, y), Size(segmentW, h), radius)
            val fill = ((played - i * holdMs).toFloat() / holdMs).coerceIn(0f, 1f)
            if (fill > 0f) drawRoundRect(Color.White.copy(alpha = 0.95f), Offset(x, y), Size(segmentW * fill, h), radius)
        }
    }
}

@Composable
private fun PageTurnButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = description, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
    }
}

/** Measures its content whole but takes only [fraction] of its height, fading it in as it opens. */
private fun Modifier.revealHeight(fraction: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val f = fraction().coerceIn(0f, 1f)
    layout(placeable.width, (placeable.height * f).roundToInt()) {
        placeable.placeWithLayer(0, 0) { alpha = f }
    }
}

private fun formatClock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** Loads [page]'s image and reads its edge colours: tried as the back cover's is, then given up on. */
@Composable
private fun PageLoader(page: FullscreenPage, onLoaded: (LoadedPage) -> Unit, onFailed: () -> Unit) {
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
                    val (top, bottom) = withContext(Dispatchers.Default) { edgeColors(bitmap) }
                    currentOnLoaded(LoadedPage(bitmap.asImageBitmap(), top, bottom))
                    return@LaunchedEffect
                }
                Log.w("FullscreenArt", "page image, try $attempt: ${(result as? ErrorResult)?.throwable?.javaClass?.simpleName}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("FullscreenArt", "page image, try $attempt", e)
            }
            if (attempt < IMAGE_TRIES) delay(IMAGE_RETRY_MS * attempt)
        }
        currentOnFailed()
    }
}

/**
 * The average colours along [bitmap]'s top and bottom edges: the ground above and below a page
 * smaller than the screen, so it reads as the print running on. Black when the pixels can't be read.
 */
private fun edgeColors(bitmap: Bitmap): Pair<Color, Color> = try {
    val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val small = Bitmap.createScaledBitmap(readable, 24, 24, true)
    fun rows(from: Int, to: Int): Color {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in from until to) for (x in 0 until small.width) {
            val p = small.getPixel(x, y)
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            n++
        }
        return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
    rows(0, 2) to rows(small.height - 2, small.height)
} catch (e: Exception) {
    Log.w("FullscreenArt", "couldn't read a page's edges", e)
    Color.Black to Color.Black
}

/** The status and navigation bars hidden while [hidden] (a swipe from the edge shows them a moment). */
@Composable
private fun HideSystemBars(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (hidden && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (hidden) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
