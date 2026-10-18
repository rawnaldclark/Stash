package com.stash.feature.nowplaying.sheet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/** The node's coordinates, kept so the gesture can work in root pixels (stable while the node moves). */
private class CoordinatesHolder {
    var coordinates: LayoutCoordinates? = null

    fun toRoot(local: Offset): Offset {
        val c = coordinates
        return if (c != null && c.isAttached) c.localToRoot(local) else local
    }
}

/**
 * Lets a vertical drag on this element move the player sheet, finger-tracked, and settle on release
 * (flick 500 dp/s or 100 dp, see [PlayerSheetMath.settleTarget]).
 *
 * - A down in the bottom dead zone (12 dp, or the system gesture area if taller) is the system's.
 * - Children keep what they claim first: the art's sideways swipe and double-tap, the seek bar,
 *   the lyrics list's scroll. Until the touch slop is crossed vertically this listens after them
 *   (Main pass) and gives up on anything they consumed or on a sideways move.
 * - Once it has the drag it takes every move before the children (Initial pass), so nothing below
 *   starts a gesture mid-drag, and a press or a tap underneath is cancelled.
 * - The first down warms Now Playing ([PlayerSheetState.warm]), so a drag finds it composed.
 * - Movement is measured in root pixels, so the sheet moving under the finger never feeds back.
 */
@Composable
fun Modifier.playerSheetDrag(state: PlayerSheetState, enabled: Boolean = true): Modifier {
    val density = LocalDensity.current
    val gestureBottom = WindowInsets.systemGestures.getBottom(density)
    val deadZonePx = maxOf(with(density) { PlayerSheetMath.DEAD_ZONE_DP.dp.toPx() }, gestureBottom.toFloat())
    val holder = remember { CoordinatesHolder() }
    val currentEnabled by rememberUpdatedState(enabled)
    return this
        .onGloballyPositioned { holder.coordinates = it }
        .pointerInput(state, deadZonePx) {
            detectSheetDrag(state, deadZonePx, { currentEnabled }, holder)
        }
}

private suspend fun PointerInputScope.detectSheetDrag(
    state: PlayerSheetState,
    deadZonePx: Float,
    enabled: () -> Boolean,
    holder: CoordinatesHolder,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (!enabled() || state.gesturesLocked) return@awaitEachGesture
    val downRoot = holder.toRoot(down.position)
    val screenHeight = state.screenSize.height
    if (screenHeight > 0 && downRoot.y >= screenHeight - deadZonePx) return@awaitEachGesture

    state.warm()
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, downRoot)
    val slop = viewConfiguration.touchSlop
    var pointer = down.id
    var last = downRoot

    // 1) Wait for a vertical move past the touch slop that nothing below claimed.
    var overSlop = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointer } ?: return@awaitEachGesture
        if (!change.pressed) return@awaitEachGesture // a tap: the clickable's
        if (change.isConsumed) return@awaitEachGesture // a child took it
        val pos = holder.toRoot(change.position)
        tracker.addPosition(change.uptimeMillis, pos)
        val dx = pos.x - downRoot.x
        val dy = pos.y - downRoot.y
        if (abs(dy) > slop && abs(dy) >= abs(dx)) {
            change.consume()
            overSlop = dy - sign(dy) * slop
            last = pos
            break
        }
        if (abs(dx) > slop) return@awaitEachGesture // sideways: not ours
    }

    // 2) Ours: track the finger until it lifts.
    state.beginDrag()
    state.dragBy(-overSlop)
    // The finger's last root positions (uptime ms, y), for which way it was going as it lifted.
    val recent = ArrayDeque<Pair<Long, Float>>()
    var settled = false
    try {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointer }
            if (change == null || !change.pressed) {
                // Our finger lifted; carry on with another one still down, as verticalDrag does.
                // Its velocity starts afresh: the two fingers' positions aren't one path.
                change?.consume()
                val other = event.changes.firstOrNull { it.pressed } ?: break
                pointer = other.id
                last = holder.toRoot(other.position)
                tracker.resetTracking()
                tracker.addPosition(other.uptimeMillis, last)
                recent.clear()
                recent.addLast(other.uptimeMillis to last.y)
                other.consume()
                continue
            }
            val pos = holder.toRoot(change.position)
            tracker.addPosition(change.uptimeMillis, pos)
            recent.addLast(change.uptimeMillis to pos.y)
            while (recent.size > RECENT_SAMPLES) recent.removeFirst()
            val dy = pos.y - last.y
            last = pos
            if (dy != 0f) state.dragBy(-dy)
            change.consume()
        }
        val velocity = tracker.calculateVelocity()
        settled = true
        state.endDrag(
            velocityUpDpS = -velocity.y / density,
            recentUpDp = -recentMovementPx(recent) / density,
        )
    } finally {
        if (!settled) state.cancelDrag()
    }
}

/** How many recent finger samples are kept for the lift's direction. */
private const val RECENT_SAMPLES = 8

/** How far back the lift's direction looks. */
private const val RECENT_WINDOW_MS = 20L

/**
 * The finger's movement (px, + = down) over its last [RECENT_WINDOW_MS]: from the newest sample
 * back to the oldest one inside the window (or the one just before it, if only one is inside).
 */
internal fun recentMovementPx(samples: Collection<Pair<Long, Float>>): Float {
    if (samples.size < 2) return 0f
    val list = samples.toList()
    val (endT, endY) = list.last()
    var start = list[list.size - 2]
    for (i in list.size - 2 downTo 0) {
        start = list[i]
        if (endT - list[i].first >= RECENT_WINDOW_MS) break
    }
    return endY - start.second
}

/**
 * Back while the player is open: lyrics mode first (a registered consumer), then FULL → MINI.
 *
 * Composed only while the player is open, and only from the frame after it opened: the NavHost
 * registers its own back callback during layout (Scaffold content is subcomposed), and the newest
 * registration wins, so this one must come after it every time the player opens.
 */
@Composable
fun PlayerSheetBackHandler(state: PlayerSheetState) {
    // Open, opening, closing or held by a finger: anything but resting at MINI. Back mid-close
    // must not pop the page under the moving player. Recomposes only when that flips.
    val open by remember(state) {
        derivedStateOf { state.targetAnchor != PlayerSheetAnchor.MINI || state.progress != 0f || state.isDragging }
    }
    if (!open) return
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        ready = true
    }
    if (ready) BackHandler { state.handleBack() }
}
