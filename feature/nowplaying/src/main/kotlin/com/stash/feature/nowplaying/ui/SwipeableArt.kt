package com.stash.feature.nowplaying.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long the "−10 s" / "+10 s" indicator stays after the last double-tap. */
private const val SEEK_INDICATOR_HOLD_MS = 650L

/** How long the cover takes to turn over. */
private const val FLIP_MS = 450

/** The flip's virtual camera, in multiples of the density: far enough that the near edge never fills the screen. */
private const val FLIP_CAMERA_DISTANCE = 12f

/**
 * The Now Playing album art with its gestures: swipe sideways for the next / previous song,
 * double-tap the left or right third to seek back / forward 10 s, and tap to turn the cover over
 * to the album's back ([back]) and again to turn it back. While the back is up the swipe is the
 * back's own (it pages through the inside), not a skip: the cover is in hand, being looked through.
 *
 * The swipe is [rememberSwipeToSkip] (shared with the mini player): the art follows the finger,
 * skips on a third of its width or a flick through the same [onNext] / [onPrevious] the transport
 * buttons call, and springs back short of that or toward a side with nothing there ([canNext] /
 * [canPrevious]). Double-taps in quick succession add up ("+20 s"), each from the previous tap's
 * target ([nextSeekBurst]).
 *
 * A tap flips the moment it lifts in the middle third, where no double-tap seeks
 * ([flipsOnFirstTap]); on a seek third it flips once the double-tap window has passed with no
 * second tap, unless it follows a seek run ([loneSeekTapFlips]). An album with no back has no tap
 * action, so the double-tap detector delays nothing there. The cover turns face up again for
 * another album ([flipKey]). A Listen Together listener can't skip or seek, but can still flip:
 * the flip is theirs alone.
 *
 * Every gesture has a TalkBack custom action on the art (Previous song, Next song, Back / Forward
 * 10 seconds, Show back / front cover), merged with the cover's description into one node. With
 * the system's animations removed nothing slides, fades or turns: the skip simply happens, the seek
 * pill appears and goes without a fade, and the cover shows its other face at once.
 *
 * @param trackKey Identifies the playing song; a change ends the post-skip wait.
 * @param gesturesEnabled False for a Listen Together listener, who can neither skip nor seek the
 *   room's song: no gestures and no actions.
 * @param canSeek Whether double-tap seeking applies (a known duration).
 * @param positionMs The player's position, read only when a double-tap or an action lands.
 * @param flipKey Identifies the playing album: a change shows the new album's front.
 * @param back The album's back (its cover and the pages inside), drawn with the [Modifier] it is
 *   handed; null when the album has no back cover (or it hasn't loaded), and the art then doesn't
 *   flip.
 * @param modifier The cover's shared-element modifier (it morphs into lyrics mode's header cover).
 * @param onShowBackChange Told when the back is turned up (true) and down.
 * @param art The cover itself, drawn with the [Modifier] it is handed.
 */
@Composable
internal fun SwipeableArt(
    trackKey: Any?,
    gesturesEnabled: Boolean,
    canNext: Boolean,
    canPrevious: Boolean,
    canSeek: Boolean,
    positionMs: () -> Long,
    durationMs: Long,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    flipKey: Any?,
    back: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
    onShowBackChange: (Boolean) -> Unit = {},
    art: @Composable (Modifier) -> Unit,
) {
    val reducedMotion = rememberNpReducedMotion()

    // The double-tap handler outlives recompositions (pointerInput keeps its lambda), so what it
    // reads that can change goes through updated state.
    val currentCanSeek by rememberUpdatedState(canSeek)
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val currentOnSeekTo by rememberUpdatedState(onSeekTo)
    val currentGesturesEnabled by rememberUpdatedState(gesturesEnabled)
    val currentCanFlip by rememberUpdatedState(back != null)
    val currentFlipKey by rememberUpdatedState(flipKey)

    // The album whose back is up, null for the front: held by album, so another album's song shows
    // its own front, and kept across songs of the same album.
    var backUpFor by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(flipKey) { if (backUpFor != flipKey) backUpFor = null }
    val showBack = back != null && flipKey != null && backUpFor == flipKey
    val currentOnShowBackChange by rememberUpdatedState(onShowBackChange)
    LaunchedEffect(showBack) { currentOnShowBackChange(showBack) }
    fun flip() {
        backUpFor = if (backUpFor == currentFlipKey) null else currentFlipKey
    }

    // No skipping while the back is up: its own swipe turns its pages.
    val swipe = rememberSwipeToSkip(
        trackKey = trackKey,
        enabled = gesturesEnabled && !showBack,
        canNext = canNext,
        canPrevious = canPrevious,
        onNext = onNext,
        onPrevious = onPrevious,
    )

    // The turn, 0 (front) to 180 (back). A new album starts face up, without turning.
    val turn = remember(flipKey) { Animatable(0f) }
    LaunchedEffect(turn, showBack, reducedMotion) {
        val target = if (showBack) 180f else 0f
        if (reducedMotion) turn.snapTo(target) else turn.animateTo(target, tween(FLIP_MS, easing = FastOutSlowInEasing))
    }
    val backFacing by remember(turn) { derivedStateOf { turn.value > 90f } }

    // The double-tap run on screen ("+20 s"); kept after it hides so the fade-out keeps its text.
    var burst by remember { mutableStateOf<SeekBurst?>(null) }
    var burstVisible by remember { mutableStateOf(false) }
    LaunchedEffect(burst) {
        if (burst != null) {
            delay(SEEK_INDICATOR_HOLD_MS)
            burstVisible = false
        }
    }

    fun doubleTapSeek(xPx: Float, widthPx: Float) {
        val direction = doubleTapSeekDirection(xPx, widthPx)
        if (currentCanSeek && direction != 0) {
            val next = nextSeekBurst(
                previous = burst,
                direction = direction,
                nowMs = SystemClock.uptimeMillis(),
                positionMs = currentPositionMs(),
                durationMs = currentDurationMs,
            )
            currentOnSeekTo(next.targetMs)
            burst = next
            burstVisible = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(swipe.gesture)
            .then(
                if (!gesturesEnabled && back == null) {
                    Modifier
                } else {
                    // detectTapGestures with onTap would hold every tap for the double-tap window;
                    // this waits only where a double-tap can seek.
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            up.consume()
                            val width = size.width.toFloat()
                            val seeks = currentGesturesEnabled && currentCanSeek
                            if (flipsOnFirstTap(currentCanFlip, seeks, up.position.x, width)) {
                                flip()
                                return@awaitEachGesture
                            }
                            if (!currentGesturesEnabled) return@awaitEachGesture
                            val secondDown = awaitSecondDown(up)
                            if (secondDown == null) {
                                if (loneSeekTapFlips(currentCanFlip, burst, SystemClock.uptimeMillis())) flip()
                                return@awaitEachGesture
                            }
                            secondDown.consume()
                            val secondUp = waitForUpOrCancellation() ?: return@awaitEachGesture
                            secondUp.consume()
                            doubleTapSeek(secondUp.position.x, width)
                        }
                    }
                },
            )
            // One node for TalkBack: the cover's description with every gesture as an action.
            .semantics(mergeDescendants = true) {
                val actions = buildList {
                    if (gesturesEnabled) {
                        if (canPrevious) add(CustomAccessibilityAction("Previous song") { onPrevious(); true })
                        if (canNext) add(CustomAccessibilityAction("Next song") { onNext(); true })
                        if (canSeek) {
                            add(
                                CustomAccessibilityAction("Back 10 seconds") {
                                    onSeekTo(seekStepMs(positionMs(), durationMs, -SEEK_STEP_MS)); true
                                },
                            )
                            add(
                                CustomAccessibilityAction("Forward 10 seconds") {
                                    onSeekTo(seekStepMs(positionMs(), durationMs, SEEK_STEP_MS)); true
                                },
                            )
                        }
                    }
                    if (back != null) {
                        add(CustomAccessibilityAction(if (showBack) "Show front cover" else "Show back cover") { flip(); true })
                    }
                }
                if (actions.isNotEmpty()) customActions = actions
            },
        contentAlignment = Alignment.Center,
    ) {
        // The cover turns about its vertical axis; past the edge-on midpoint the back is drawn, its
        // own half turn undoing the mirror.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(swipe.content)
                .graphicsLayer {
                    rotationY = turn.value
                    cameraDistance = FLIP_CAMERA_DISTANCE * density
                },
            contentAlignment = Alignment.Center,
        ) {
            val backFace = back
            if (backFacing && backFace != null) {
                backFace(Modifier.graphicsLayer { rotationY = 180f })
            } else {
                art(Modifier)
            }
        }

        val shown = burst
        if (shown != null) {
            AnimatedVisibility(
                visible = burstVisible,
                enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(120)),
                exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(220)),
                modifier = Modifier
                    .align(if (shown.direction < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(1f / 3f)
                    // Visual feedback for a touch gesture; TalkBack's own seek actions say what they did.
                    .clearAndSetSemantics { },
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = seekBurstLabel(shown),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        // On the cover, not the ambient: a dark pill with white text reads on any art
                        // in either theme.
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The second tap of a double-tap: a press within the double-tap window of [firstUp], and no sooner
 * than the minimum gap (as detectTapGestures waits for it). Null when none comes in time.
 */
private suspend fun AwaitPointerEventScope.awaitSecondDown(firstUp: PointerInputChange): PointerInputChange? =
    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
        val minUptime = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
        var change: PointerInputChange
        do {
            change = awaitFirstDown()
        } while (change.uptimeMillis < minUptime)
        change
    }
