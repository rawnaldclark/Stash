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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** The crossfade from one page to the next. */
private const val PAGE_FADE_MS = 2_200L

/**
 * A new song's front over the last song's page: quick, since the song changed (a skip is a
 * tap that wants an answer). The slow crossfade is for pages taking turns within a song.
 */
private const val SONG_FADE_MS = 300L

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
 * With the loops still (Remove animations, or Battery Saver) nothing pans or fades, so the clock
 * ticks this often instead of every frame: a page's cut lands within half a second of its time. So
 * it does whenever nothing moves: paused, or playing with nothing to pan (Full art pan off, or
 * lyrics up over the art), with no page fading in. A page turned by hand, a new song's front, a
 * page just loaded, full art opening, or the pan starting again (play resuming, the pan turned
 * back on, lyrics closed) wakes it at once ([FullArtState.wake]).
 */
internal const val STILL_TICK_MS = 500L

/** The longest step the clocks take in one tick: a frame's hiccup, or a still tick running late. */
private const val MAX_STEP_MS = 100L
private const val STILL_MAX_STEP_MS = 2 * STILL_TICK_MS

/**
 * Just after a song changes the player can still report the last one's position for a tick: for
 * this long, only a position near the start is believed.
 */
private const val NEW_SONG_SETTLE_MS = 1_000L
private const val NEW_SONG_START_MS = 3_000L

/**
 * The page full art opens on not in hand this long after opening (a slow back or inside page), the
 * art opens on the front instead: the square art just drew it, so it's in memory.
 */
internal const val FIRST_PAGE_GRACE_MS = 400L

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

/**
 * A page on screen: fading in from [bornMs] (frame time) over [fadeMs], its pan timed from
 * [atPlayMs] (playing time).
 */
internal class Shown(
    val page: FullArtPage,
    val bornMs: Long,
    val atPlayMs: Long,
    val direction: Int,
    val fadeMs: Long = PAGE_FADE_MS,
)

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

    /**
     * The page on screen is in hand: the art grows out only then, so the square never leaves a gap.
     * Cleared when full art closes ([close]), so a reopen waits for its own page; a page that fails
     * or is slow at opening gives way to the front ([tick]), so it is always reached.
     */
    var ready by mutableStateOf(false)
        private set

    /** When it last opened (frame time): the page it opened on has [FIRST_PAGE_GRACE_MS] from here. */
    private var openedMs = 0L

    /** Time spent playing (it stands still while paused): the pan's clock. */
    internal var playMs = 0L
    private var songMs = 0L
    private var lastFrameMs = 0L
    private var settleUntilMs = 0L
    private var segment = -1
    private var offset = 0
    private var turns = 0
    private var pending: FullArtPage? = null

    /** The last tick found nothing moving: the loop rested, so this tick comes a still tick later. */
    private var resting = false

    /** [pending] is a new song's front: it fades in over [SONG_FADE_MS]. */
    private var pendingIsNewSong = false

    /** Wakes a still frame loop ([awaitWake]) at once: a page is pending, or one just loaded. */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
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
        if (pages.isEmpty()) return
        songMs = position
        lastFrameMs = now
        openedMs = now
        segment = schedule.segmentAt(position)
        val first = start.coerceIn(0, pages.size - 1)
        // Fresh, it's unready already ([close]). Turned back on before it had shrunk away, it stays
        // ready only if this page is in hand: never the square hidden over a page not drawn.
        if (pages[first].key !in loaded) ready = false
        showFirst(first, now)
        // A loop resting between still ticks would see the page (and grow the art out) up to
        // STILL_TICK_MS later: reopened while paused, the pages are usually in hand already.
        wake()
    }

    /** [index] on screen at once, nothing under it, the pages going on from it. */
    private fun showFirst(index: Int, now: Long) {
        offset = (index - schedule.pageAt(segment.coerceAtLeast(0))).mod(pages.size)
        shown.clear()
        pending = null
        shown.add(Shown(pages[index], now - PAGE_FADE_MS, playMs, direction()))
    }

    /**
     * Full art gone: shrunk back into the square, or Now Playing off the top of the sheet. The next
     * open starts unready, so it waits for its own first page instead of growing over an empty one.
     */
    fun close() {
        ready = false
        shown.clear()
        pending = null
        // The next open tells the square art its page again, whatever page this one ended on.
        reported = -1
    }

    /** A new song: its front, faded to over the page the last one ended on. */
    fun newSong(now: Long) {
        songMs = 0L
        settleUntilMs = now + NEW_SONG_SETTLE_MS
        segment = 0
        offset = 0
        pending = pageFor(0)
        pendingIsNewSong = true
        wake()
    }

    /** A page turned by hand ([step] 1 the next, -1 the one before): the pages go on from it. */
    fun turn(step: Int) {
        if (pages.size < 2) return
        val at = pages.indexOfFirst { it.key == shown.lastOrNull()?.page?.key }.coerceAtLeast(0)
        val next = (at + step).mod(pages.size)
        offset = (next - schedule.pageAt(segment.coerceAtLeast(0))).mod(pages.size)
        pending = pages[next]
        pendingIsNewSong = false
        wake()
    }

    /** Something for the frame loop to do now (a page pending or loaded): a still loop ticks at once. */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /**
     * The still frame loop's pause between ticks: [ms], or less when [wake]d. A page turned by hand
     * (or a new song's front) then cuts in at once rather than up to [STILL_TICK_MS] later.
     */
    suspend fun awaitWake(ms: Long) {
        withTimeoutOrNull(ms) { wakeups.receive() }
    }

    /**
     * One frame: the clocks move, and the page the song has reached comes in once it's loaded.
     * Returns whether the art moves on from here (it pans while playing, a page fades in): the
     * frame loop ticks every frame only then.
     *
     * @param pans The art is up and pans: Full art pan isn't off and lyrics aren't over it. Playing
     *   without it, nothing moves but a page fading in.
     */
    fun tick(
        now: Long,
        position: Long,
        playing: Boolean,
        failed: Set<String>,
        still: Boolean,
        pans: Boolean = true,
    ): Boolean {
        // Resting, a step is a still tick long and real: the clocks keep up with a song playing with
        // nothing to pan. Not when the pan starts again from a rest (play resuming): most of that
        // step was paused, and a frame's step is all the pan may jump.
        val maxStep = if (still || (resting && !(playing && pans))) STILL_MAX_STEP_MS else MAX_STEP_MS
        val dt = if (lastFrameMs == 0L) 0L else (now - lastFrameMs).coerceIn(0L, maxStep)
        lastFrameMs = now
        if (playing) playMs += dt
        // The last song's position, still reported a moment after the change, is passed over.
        val believed = now >= settleUntilMs || position < NEW_SONG_START_MS
        songMs = when {
            !believed -> songMs + if (playing) dt else 0L
            playing -> (songMs + dt).let { if (abs(it - position) > RESYNC_MS) position else it }
            else -> position
        }
        if (pages.isEmpty()) return movesOn(now, still, playing, pans, fading = false)

        val at = schedule.segmentAt(songMs)
        if (at != segment) {
            segment = at
            pending = pageFor(at)
            pendingIsNewSong = false
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
            val fade = if (pendingIsNewSong) SONG_FADE_MS else PAGE_FADE_MS
            pendingIsNewSong = false
            val current = shown.lastOrNull()
            if (current == null || current.page.key != next.key) {
                shown.add(Shown(next, if (still) now - fade else now, playMs, direction(), fade))
                while (shown.size > MAX_SHOWN) shown.removeAt(0)
            }
        }
        // Not out yet, the page it opened on failed, or isn't in hand FIRST_PAGE_GRACE_MS after
        // opening: it opens on the front instead (or the first page after it that hasn't failed),
        // so the art always comes out. Every page failed, the square art stays.
        val opening = shown.lastOrNull()
        if (!ready && opening != null && opening.page.key !in loaded &&
            (opening.page.key in failed || now - openedMs >= FIRST_PAGE_GRACE_MS)
        ) {
            val standIn = pages.indexOfFirst { it.key !in failed }
            if (standIn >= 0 && pages[standIn].key != opening.page.key) showFirst(standIn, now)
        }
        val top = shown.lastOrNull()
        if (!ready && top != null && top.page.key in loaded) {
            ready = true
            fadeMs = now
        }
        val fading = top != null && now - top.bornMs < top.fadeMs
        if (fading) fadeMs = now
        // Pages fully covered by the one fading in over them are let go.
        if (top != null && shown.size > 1 && now - top.bornMs >= top.fadeMs) {
            shown.removeRange(0, shown.size - 1)
            fadeMs = now
        }
        val index = pages.indexOfFirst { it.key == top?.page?.key }
        if (index >= 0 && index != reported) {
            reported = index
            onPage(index)
        }
        return movesOn(now, still, playing, pans, fading)
    }

    /**
     * Moves the frame clock, which the art is drawn on, when the art moves this frame, and says
     * whether it does. Still, it neither pans nor fades; paused, or playing with nothing to pan
     * ([pans] false), only a page fading in moves. Otherwise nothing is redrawn per tick (a cut, or
     * the covered pages let go after a fade, redraws by itself): a paused Now Playing drew every
     * frame for nothing, and so did one playing with the pan off or lyrics up.
     */
    private fun movesOn(now: Long, still: Boolean, playing: Boolean, pans: Boolean, fading: Boolean): Boolean {
        val moving = !still && ((playing && pans) || fading)
        if (moving) frameMs = now
        resting = !moving
        return moving
    }

    /** The pan's side, alternating page to page. */
    private fun direction(): Int = if (turns++ % 2 == 0) 1 else -1
}

@Composable
internal fun rememberFullArtState(): FullArtState = remember { FullArtState() }

/**
 * Where full art's grow heads: 1 out of the square, 0 back into it, null held where it is. Out only
 * once the page on screen is in hand ([FullArtState.ready]), so the square never leaves a gap; back
 * in (turned off, or Now Playing leaving the top of the sheet) at any time.
 */
internal fun fullArtGrowTo(target: Boolean, visible: Boolean, ready: Boolean): Float? = when {
    !visible -> 0f
    target && !ready -> null
    target -> 1f
    else -> 0f
}

/**
 * Now Playing's colours while full art is out: the dark theme's, whatever the app's, since the
 * controls sit on the art's darkened blend, wearing the song's accent as the rest of the app does
 * (it used to be the literal dark scheme, so full art turned the accents back to the idle colour).
 * Always composed (with [on] false it passes the theme through) so turning full art on or off never
 * rebuilds the screen under it.
 */
@Composable
internal fun FullArtTheme(on: Boolean, content: @Composable () -> Unit) {
    val dark = com.stash.core.ui.ambient.rememberDarkAccentScheme(StashDarkColorScheme)
    val scheme = if (on) dark else MaterialTheme.colorScheme
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
 * @param cycle Settings' Cycle album art: off, the pages don't turn by themselves (a swipe still turns them).
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
    cycle: Boolean,
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
    // Growing out and the art's fade are one-shot transitions: they snap only with "Remove
    // animations". The cycle (the frame loop, pans and page fades) is a loop: it also stands still
    // under Battery Saver.
    val reducedMotion = rememberNpReducedMotion()
    val loopsStill = rememberNpLoopsStill()
    val target = active && visible && pages.isNotEmpty()
    LaunchedEffect(target, visible, reducedMotion, state.ready) {
        // Held (null) until its first page is in hand.
        val to = fullArtGrowTo(target, visible, state.ready) ?: return@LaunchedEffect
        // Leaving the top of the sheet (or for lyrics): the square takes the art back at once.
        if (!visible || reducedMotion) {
            state.grow.snapTo(to)
        } else {
            state.grow.animateTo(to, tween(GROW_MS, easing = FastOutSlowInEasing))
        }
    }
    val artAlpha = remember { Animatable(if (artShown) 1f else 0f) }
    LaunchedEffect(artShown, reducedMotion) {
        val to = if (artShown) 1f else 0f
        if (reducedMotion) artAlpha.snapTo(to) else artAlpha.animateTo(to, tween(if (artShown) ART_IN_MS else ART_OUT_MS))
    }
    if (!target && state.grow.value == 0f) return
    // Leaving (shrunk back into the square, or Now Playing off the top of the sheet): the next open
    // starts unready, so it never grows over a page not yet in hand.
    DisposableEffect(state) { onDispose { state.close() } }

    val schedule = remember(durationMs, pages.size, cycle) { fullArtSchedule(durationMs, pages.size, cycle) }
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
    // The open waits a frame: by then the song (and its pages) may have changed.
    val currentPages by rememberUpdatedState(pages)
    val currentSchedule by rememberUpdatedState(schedule)
    // Nothing to pan with Full art pan off, or with lyrics up over the (invisible) art.
    val pans = panDpPerS > 0f && artShown
    val currentPans by rememberUpdatedState(pans)

    // Opened (or reopened with the sheet): the page the square art was showing.
    LaunchedEffect(target) {
        if (target) {
            val now = withFrameMillis { it }
            state.pages = currentPages
            state.schedule = currentSchedule
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
    // The frame loop: while it's out and in sight. It ticks every frame only while the art moves
    // (a pan while playing, a page fading in). Otherwise (motion still, paused, or nothing to pan)
    // every STILL_TICK_MS: the pages only cut, so nothing needs the display's refresh rate.
    LaunchedEffect(running, loopsStill) {
        if (!running) return@LaunchedEffect
        while (true) {
            val moving = withFrameMillis { now ->
                state.tick(now, currentPosition(), currentPlaying, failed.keys, loopsStill, currentPans)
            }
            if (!moving) state.awaitWake(STILL_TICK_MS)
        }
    }
    // The pan starting again (play resuming, the pan turned back on, lyrics closed): a loop waiting
    // out a still tick picks it up at once.
    LaunchedEffect(isPlaying, pans) {
        if (isPlaying && pans) state.wake()
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
            PageLoader(
                page,
                onLoaded = { loaded[page.key] = it; state.wake() },
                onFailed = { failed[page.key] = true; state.wake() },
            )
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
                        val alpha = fadeAlpha(index, now, shown, loopsStill)
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
                            val alpha = fadeAlpha(index, now, shown, loopsStill)
                            if (alpha <= 0f) return@forEachIndexed
                            val w = page.image.width.toFloat()
                            val h = page.image.height.toFloat()
                            val square = coverCrop(w, h, from.width, from.height).translate(from.topLeft)
                            val panned = panCrop(
                                w, h, full.width, full.height,
                                elapsedS = if (loopsStill) 0f else (state.playMs - shown.atPlayMs) / 1000f,
                                direction = shown.direction,
                                holdS = state.schedule.holdMs / 1000f,
                                speedPxPerS = if (loopsStill) 0f else panSpeedPx,
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

private fun fadeAlpha(index: Int, now: Long, shown: Shown, still: Boolean): Float {
    if (index == 0 || still) return 1f
    val t = ((now - shown.bornMs).toFloat() / shown.fadeMs).coerceIn(0f, 1f)
    // A new song's front lands fast and settles (ease-out); pages taking turns breathe in and out.
    return if (shown.fadeMs < PAGE_FADE_MS) 1f - (1f - t) * (1f - t) * (1f - t) else easeInOutSine(t)
}

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
