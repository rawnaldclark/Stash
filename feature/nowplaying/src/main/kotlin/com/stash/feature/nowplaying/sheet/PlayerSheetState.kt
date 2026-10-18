package com.stash.feature.nowplaying.sheet

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * How long Now Playing stays composed (parked, unplaced) after the player settles back to MINI, so a
 * quick re-open doesn't pay its first composition again. See the PR for the measurement behind it.
 */
const val KEEP_WARM_MS = 30_000L

/** A touch on the mini player warms Now Playing for at least this long, whatever [KEEP_WARM_MS] is. */
const val MIN_TOUCH_WARM_MS = 5_000L

/** Now Playing's big cover: where it sits on screen (px, in the root) and its corner radius (px). */
data class HeroArt(val rect: Rect, val cornerPx: Float)

/**
 * The player as one sheet: the mini player and Now Playing are two resting places of a single
 * progress value the finger drives ([progress]: 0 = MINI, 1 = FULL, 2 = QUEUE in phase B).
 *
 * The value is written by drags ([dragBy]) and by snap animations, one at a time: a touch during a
 * snap stops it where it is ([beginDrag]). Everything the player draws is a function of [progress]
 * (see [PlayerSheetMath]) read in graphicsLayer / draw / placement lambdas, so a frame of the drag
 * never recomposes anything. [settledAnchor] only changes when an animation ends, for the few
 * things that must recompose (the back handler, the scaffold's dialogs).
 *
 * Geometry ([miniCardRect], [heroArt], [screenSize]) is reported by the composables that own it,
 * in root pixels, as layout places them.
 *
 * Now Playing is composed only while [isWarm]: from the first touch on the mini player (or an
 * [expand]) until [keepWarmMs] after the player settles back to MINI.
 *
 * @param scope Runs the snaps and the keep-warm timer; its context must have a MonotonicFrameClock
 *   (a composition's scope does).
 */
@Stable
class PlayerSheetState(
    initialAnchor: PlayerSheetAnchor = PlayerSheetAnchor.MINI,
    private val scope: CoroutineScope,
    private val keepWarmMs: Long = KEEP_WARM_MS,
) {
    private val value = mutableFloatStateOf(initialAnchor.value)

    /** The sheet's position: 0 = MINI, 1 = FULL. Read it only in layout / draw lambdas. */
    val progress: Float get() = value.floatValue

    /** The anchor the sheet last came to rest at. Changes only when a snap ends. */
    var settledAnchor by mutableStateOf(initialAnchor)
        private set

    /** Where the sheet is going: the current snap's target, the drag's start anchor, or [settledAnchor]. */
    var targetAnchor by mutableStateOf(initialAnchor)
        private set

    /** A finger is moving the sheet. */
    var isDragging by mutableStateOf(false)
        private set

    /** Whether Now Playing is composed. */
    var isWarm by mutableStateOf(initialAnchor != PlayerSheetAnchor.MINI)
        private set

    /** Locks every drag (phase B: while a queue row is being reordered). */
    var gesturesLocked by mutableStateOf(false)

    /** Whether the queue anchor exists (phase B). Off in phase A: a drag up from FULL rubber-bands. */
    var queueEnabled: Boolean = false

    // -- Geometry, in root px. --

    /** The mini player's card, at rest (no press scale, no dip). Null while it isn't on screen. */
    var miniCardRect: Rect? by mutableStateOf(null)

    /** Now Playing's big cover (or lyrics mode's header cover). Null until it is first laid out. */
    var heroArt: HeroArt? by mutableStateOf(null)

    /** The screen size [heroArt] was measured for: a rotation makes it stale. */
    var heroMeasuredFor: IntSize by mutableStateOf(IntSize.Zero)

    /** The whole player area (the root). */
    var screenSize: IntSize by mutableStateOf(IntSize.Zero)

    /** px per dp, and the system navigation bar's bottom inset (px), for the fallbacks below. */
    var density: Float = 1f
    var navBarBottomPx: Float = 0f

    /** Whether there's a bottom navigation (tab bar) under the mini player: the actuation distance. */
    var hasBottomNav: Boolean = true

    /** Snaps without animating (the system's animations are off). */
    var reducedMotion: Boolean = false

    /** Right-to-left layout: the mini player's cover sits at the card's right end. */
    var isRtl: Boolean = false

    /** The travelling cover has something to draw; until then the mini player keeps its own. */
    var travellerReady by mutableStateOf(false)

    /** A light haptic tick, fired when a drag that passed the actuation distance lands. */
    var onSnapHaptic: (() -> Unit)? = null

    // -- Gesture bookkeeping. --
    private var raw = initialAnchor.value
    private var startRaw = initialAnchor.value
    private var startProgress = initialAnchor.value

    private var animJob: Job? = null
    private var coolJob: Job? = null
    private val backConsumers = ArrayList<() -> Boolean>()

    /** The progress at the top anchor (FULL in phase A, QUEUE once it exists). */
    private val topAnchor: Float get() = if (queueEnabled) PlayerSheetAnchor.QUEUE.value else PlayerSheetAnchor.FULL.value
    private val maxProgress: Float get() = if (queueEnabled) PlayerSheetMath.MAX_PROGRESS else PlayerSheetMath.MAX_PROGRESS_PHASE_A

    /** The mini player's rect, or where it would be when it isn't on screen (no song, selection mode). */
    fun effectiveMiniCardRect(): Rect = miniCardRect ?: fallbackMiniRect()

    /**
     * Where the mini player sits when it isn't laid out: 60 dp tall, 8 dp from the sides and from
     * the top of an 80 dp tab bar over the system bar.
     */
    fun fallbackMiniRect(): Rect {
        val w = screenSize.width.toFloat()
        val h = screenSize.height.toFloat()
        val margin = 8f * density
        val bottom = h - navBarBottomPx - (if (hasBottomNav) 80f * density else 0f) - margin
        return Rect(margin, bottom - 60f * density, w - margin, bottom)
    }

    /**
     * The mini player's cover: 44 dp, 8 dp in from the card's top start corner (MiniPlayer's layout;
     * the right end in RTL). Derived from the card's resting rect rather than measured, so a press,
     * a swipe in progress or the dip can never leave the cover's start point off by their offset.
     */
    fun miniArtRect(): Rect {
        val card = effectiveMiniCardRect()
        val inset = 8f * density
        val side = 44f * density
        val left = if (isRtl) card.right - inset - side else card.left + inset
        return Rect(Offset(left, card.top + inset), Size(side, side))
    }

    /** The big cover, or an estimate (centred square under the top row) before it was first laid out. */
    fun effectiveHeroArt(): HeroArt = heroArt ?: run {
        val w = screenSize.width.toFloat()
        val side = (w - 48f * density).coerceAtMost(375f * density)
        val top = screenSize.height * 0.15f
        HeroArt(Rect(Offset((w - side) / 2f, top), Size(side, side)), PlayerSheetMath.HERO_ART_CORNER_DP * density)
    }

    /** The whole screen, the card's FULL rect. */
    fun screenRect(): Rect = Rect(0f, 0f, screenSize.width.toFloat(), screenSize.height.toFloat())

    /** The distance (px) the card's top travels from MINI to FULL: what a 1:1 finger moves. */
    fun travelPx(): Float = effectiveMiniCardRect().top.coerceAtLeast(1f)

    private val actuationDp: Float
        get() = if (hasBottomNav) PlayerSheetMath.ACTUATION_DP else PlayerSheetMath.ACTUATION_NO_NAV_DP

    // ---------------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------------

    /** Opens Now Playing (fire and forget). */
    fun expand() {
        launchSnap(PlayerSheetAnchor.FULL)
    }

    /** Back to the mini player (fire and forget). */
    fun collapse() {
        launchSnap(PlayerSheetAnchor.MINI)
    }

    /** Animates to [anchor] and returns when it is there (or the snap was interrupted). */
    suspend fun animateTo(anchor: PlayerSheetAnchor) {
        launchSnap(anchor).join()
    }

    /** Jumps to [anchor] with no animation (a restore, a tab switch). */
    fun snapTo(anchor: PlayerSheetAnchor) {
        animJob?.cancel()
        isDragging = false
        value.floatValue = anchor.value
        raw = anchor.value
        targetAnchor = anchor
        settledAnchor = anchor
        if (anchor != PlayerSheetAnchor.MINI) {
            coolJob?.cancel()
            isWarm = true
        } else {
            scheduleCoolDown()
        }
    }

    /** A finger touched the mini player: compose Now Playing now, so a drag finds it ready. */
    fun warm() {
        if (isWarm) {
            // Already warm: a touch at rest restarts the grace period, it never shortens it.
            if (isAtRest(PlayerSheetAnchor.MINI)) scheduleCoolDown(touchGraceMs)
            return
        }
        isWarm = true
        scheduleCoolDown(touchGraceMs)
    }

    /** A touch that never became a drag or a tap-to-open keeps the player warm at least this long. */
    private val touchGraceMs: Long get() = maxOf(keepWarmMs, MIN_TOUCH_WARM_MS)

    /** True when nothing moves and the sheet sits at [anchor]. */
    fun isAtRest(anchor: PlayerSheetAnchor): Boolean =
        !isDragging && targetAnchor == anchor && settledAnchor == anchor && value.floatValue == anchor.value

    // ---------------------------------------------------------------------------------------------
    // Drag (called by playerSheetDrag)
    // ---------------------------------------------------------------------------------------------

    /** A drag starts: stops any snap where it is, and records where the drag began. */
    fun beginDrag() {
        animJob?.cancel()
        coolJob?.cancel()
        isWarm = true
        isDragging = true
        val p = value.floatValue
        startProgress = p
        raw = p
        startRaw = p
        targetAnchor = PlayerSheetMath.startAnchorOf(p)
    }

    /** Moves the sheet by [deltaUpPx] (positive = finger moving up), with a rubber band past the ends. */
    fun dragBy(deltaUpPx: Float) {
        if (!isDragging) return
        raw += deltaUpPx / travelPx()
        value.floatValue = PlayerSheetMath.rubberBand(raw, topAnchor, PlayerSheetMath.MIN_PROGRESS, maxProgress)
    }

    /**
     * The finger lifted at [velocityUpDpS] (positive = up): snap to wherever [PlayerSheetMath.settleTarget]
     * says, with a haptic tick when the drag passed the actuation distance.
     */
    fun endDrag(velocityUpDpS: Float, recentUpDp: Float = velocityUpDpS) {
        if (!isDragging) return
        val velocity = PlayerSheetMath.releaseVelocity(velocityUpDpS, recentUpDp)
        isDragging = false
        val distanceDp = (raw - startRaw) * travelPx() / density
        val start = PlayerSheetMath.startAnchorOf(startProgress)
        val target = PlayerSheetMath.settleTarget(
            start = start,
            distanceDp = distanceDp,
            velocityDpS = velocity,
            actuationDp = actuationDp,
            queueEnabled = queueEnabled,
        )
        launchSnap(target, haptic = abs(distanceDp) > actuationDp && target != start)
    }

    /** The drag was cancelled (the gesture node left): settle where the sheet already leans. */
    fun cancelDrag() = endDrag(0f, 0f)

    // ---------------------------------------------------------------------------------------------
    // Back
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers [consumer], asked first on Back (the newest first); it returns true when it handled
     * Back (Now Playing leaving lyrics mode). Returns the unregister call.
     */
    fun registerBackConsumer(consumer: () -> Boolean): () -> Unit {
        backConsumers += consumer
        return { backConsumers -= consumer }
    }

    /** Back: a registered consumer first, then QUEUE → FULL → MINI. False at MINI (not ours). */
    fun handleBack(): Boolean {
        for (i in backConsumers.indices.reversed()) {
            if (backConsumers[i]()) return true
        }
        return when (targetAnchor) {
            PlayerSheetAnchor.QUEUE -> { launchSnap(PlayerSheetAnchor.FULL); true }
            PlayerSheetAnchor.FULL -> { collapse(); true }
            // Already closing: Back is ours (the page under the moving player stays), nothing to do.
            PlayerSheetAnchor.MINI -> when {
                value.floatValue <= 0f && animJob?.isActive != true -> false
                animJob?.isActive == true -> true
                else -> { collapse(); true }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private fun launchSnap(target: PlayerSheetAnchor, haptic: Boolean = false): Job {
        animJob?.cancel()
        coolJob?.cancel()
        isDragging = false
        targetAnchor = target
        val wasWarm = isWarm
        if (target != PlayerSheetAnchor.MINI) isWarm = true
        val job = scope.launch {
            // Opening a cold player: let Now Playing compose in the next frame before the slide
            // starts, so its first composition isn't a dropped frame of the animation.
            if (!wasWarm) androidx.compose.runtime.withFrameNanos { }
            val from = value.floatValue
            val to = target.value
            if (!reducedMotion && from != to) {
                animate(
                    initialValue = from,
                    targetValue = to,
                    animationSpec = tween(PlayerSheetMath.SNAP_MS, easing = PlayerSheetMath.easingFor(target)),
                ) { v, _ -> value.floatValue = v }
            }
            value.floatValue = to
            raw = to
            settledAnchor = target
            if (haptic) onSnapHaptic?.invoke()
            if (target == PlayerSheetAnchor.MINI) scheduleCoolDown()
        }
        animJob = job
        return job
    }

    private fun scheduleCoolDown(afterMs: Long = keepWarmMs) {
        coolJob?.cancel()
        coolJob = scope.launch {
            if (afterMs > 0) delay(afterMs)
            if (isAtRest(PlayerSheetAnchor.MINI)) isWarm = false
        }
    }

    companion object {
        /** Saves where the sheet was going (a rotation mid-snap restores at its destination, unanimated). */
        fun saver(scope: CoroutineScope, keepWarmMs: Long = KEEP_WARM_MS): Saver<PlayerSheetState, String> = Saver(
            save = { it.targetAnchor.name },
            restore = { name ->
                val anchor = PlayerSheetAnchor.entries.firstOrNull { it.name == name } ?: PlayerSheetAnchor.MINI
                // Phase A has no QUEUE: a saved one (from a later build) opens at FULL.
                PlayerSheetState(if (anchor == PlayerSheetAnchor.QUEUE) PlayerSheetAnchor.FULL else anchor, scope, keepWarmMs)
            },
        )
    }
}

/** The player sheet, restored across rotation and process death at its anchor. */
@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    return rememberSaveable(saver = PlayerSheetState.saver(scope)) { PlayerSheetState(PlayerSheetAnchor.MINI, scope) }
}

/** The app's player sheet; null outside the scaffold (previews, tests). */
val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetState?> { null }
