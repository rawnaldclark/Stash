package com.stash.feature.nowplaying.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.data.lyrics.parser.LrcLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/*
 * Renderers for Now Playing's lyrics mode. [LyricsPane] picks one per [LyricsBody]: word-synced
 * lines go to [LyricsSyllableRenderer], line-synced ones to [LyricsLineRenderer], unsynced text
 * to [LyricsPlainRenderer]. They draw straight on the ambient, in [LyricsLook]'s inks.
 */

/**
 * How the lyrics look, resolved once per size and theme by [LyricsPane]: the web player's
 * scale (Space Grotesk 700 at [fontSize], a 1.7em line pitch) in Now Playing's ink rather than
 * colorScheme.onSurface.
 */
@Immutable
internal data class LyricsLook(
    val fontSize: TextUnit,
    /** A sung or live word. */
    val bright: Color,
    /** A word not reached yet. */
    val dim: Color,
    /** Full ink, for the interlude dots. */
    val ink: Color,
    val sidePadding: Dp,
    /** Half the space between two lines (0.2em, so lines pitch at 1.7em). */
    val lineGap: Dp,
    /** Where the live line's top sits, from the top of the lyrics area. */
    val anchor: Dp,
    /** Room under the last line, so it too can rise to [anchor]. */
    val bottomRoom: Dp,
) {
    /** The bold display line at full size; [fit] scales it (the instrumental-break notes use a smaller one). */
    fun lineStyle(fit: Float = 1f): TextStyle = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize * fit,
        lineHeight = 1.3.em,
    )
}

/**
 * Line-synced lyrics: one bold line per LRC line, lit from its timestamp. Upcoming lines wait
 * dim at 51%, the live one is fully lit, sung ones stay lit at 49.7% (the web's line states),
 * and lines two or more away from the live one blur ([lyricBlurDp], API 31+ only: a no-op
 * below). Tap a line to seek to it.
 *
 * A blank line is an instrumental break ([lines] come through [linesWithBreaks], so only breaks
 * of 3 s or more are left): it draws as the ♪ row ([InstrumentalBreakNotes]), live like any line
 * while the break runs, so the sung line before it dims to "sung" as the music takes over.
 *
 * The "current line" is the latest line whose timestamp is `<= positionMs`; it is recomputed
 * per position tick (~250ms), a single O(n) scan.
 */
@Composable
internal fun LyricsLineRenderer(
    lines: List<LrcLine>,
    currentPositionMs: Long,
    onLineTap: (Long) -> Unit,
    look: LyricsLook,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    playbackSpeed: Float = 1f,
) {
    val listState = rememberLazyListState()
    // The line is chosen on the smoothed clock the word renderer uses, not the raw 250ms poll:
    // a line timed just after a poll lit up to 250ms after its vocal started. The clock is
    // snapshot state, so derivedStateOf reads it live and recomposes only when the line changes.
    // (A derivedStateOf over the plain currentPositionMs parameter once froze on the value
    // captured at first composition; reading the clock State avoids that.)
    val clock = rememberSmoothPositionMs(currentPositionMs, isPlaying, playbackSpeed)
    val currentIndexState = remember(lines) { derivedStateOf { currentLineIndex(lines, clock.value) } }
    val currentIndex = currentIndexState.value
    val active = rememberUpdatedState(currentIndex)
    FollowLiveLine(listState, active, lines.size)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = look.anchor, bottom = look.bottomRoom),
    ) {
        itemsIndexed(lines) { index, line ->
            val lit = index <= currentIndex
            val color by animateColorAsState(if (lit) look.bright else look.dim, tween(LINE_STATE_MS), label = "line-ink")
            val alpha by animateFloatAsState(
                targetValue = when {
                    index == currentIndex -> 1f
                    index < currentIndex -> SUNG_ALPHA
                    else -> UPCOMING_ALPHA
                },
                animationSpec = tween(LINE_STATE_MS),
                label = "line-alpha",
            )
            val blur by animateFloatAsState(
                lyricBlurDp(abs(index - currentIndex), look.fontSize.value),
                tween(BLUR_FADE_MS),
                label = "line-blur",
            )
            val rowModifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { this.alpha = alpha }
                .then(if (blur > 0.25f) Modifier.blur(blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = LYRIC_LINE_CLICK_LABEL,
                ) {
                    onLineTap(line.timestampMs)
                }
                .padding(horizontal = look.sidePadding, vertical = look.lineGap)
            if (line.text.isBlank()) {
                val gap = remember(lines, index) { instrumentalBreakAt(lines, index) }
                val noteStyle = remember(look) { look.lineStyle(BREAK_NOTE_FIT) }
                InstrumentalBreakNotes(
                    style = noteStyle,
                    color = color,
                    live = index == currentIndex,
                    // Read in draw: the notes come up as the break runs without recomposing.
                    progress = { gap?.progress(clock.value) },
                    spacing = with(LocalDensity.current) { (look.fontSize * BREAK_NOTE_SPACING).toDp() },
                    modifier = rowModifier,
                )
            } else {
                // Full size on every line: a line longer than the width wraps between words.
                val style = remember(look) { look.lineStyle() }
                Text(text = line.text, style = style, color = color, modifier = rowModifier)
            }
        }
    }
}

/**
 * The ♪ row for an instrumental break: three notes that come up one by one as the break runs
 * and breathe gently while it is [live] (Apple Music's dots, Spotify's ♪). Under Remove
 * animations they hold still and only come up. Not live, the notes simply sit in [color], and
 * whatever row holds them dims them like a line. TalkBack reads "Instrumental break".
 *
 * Shared by the line-synced view and both live bars; [breakNoteAlpha] holds the rules.
 */
@Composable
internal fun InstrumentalBreakNotes(
    style: TextStyle,
    color: Color,
    live: Boolean,
    /** How far through the break the song is (0..1), or null for an outro. Read in draw only. */
    progress: () -> Float?,
    spacing: Dp,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    val still = rememberNpLoopsStill()
    // Only a live, moving row runs a transition: a song's other breaks cost nothing per frame.
    val breath: State<Float>? = if (live && !still) {
        rememberInfiniteTransition(label = "break-breath").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BREAK_BREATH_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "break-breath-phase",
        )
    } else {
        null
    }
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = INSTRUMENTAL_BREAK_DESCRIPTION },
        horizontalArrangement = Arrangement.spacedBy(spacing, alignment),
    ) {
        for (note in 0 until BREAK_NOTES) {
            Text(
                text = "♪",
                style = style,
                color = color,
                modifier = Modifier.graphicsLayer {
                    this.alpha = if (live) breakNoteAlpha(note, progress(), breath?.value) else 1f
                },
            )
        }
    }
}

/**
 * Unsynced lyrics: the whole text in a calm scroll (the web's 16/1.7 display face, a size up
 * for a phone held at arm's length), lit, no highlight to follow.
 */
@Composable
internal fun LyricsPlainRenderer(text: String, look: LyricsLook, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = PLAIN_FONT,
            lineHeight = 1.7.em,
        ),
        color = look.bright,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Start below the top fade, so the first line is fully opaque at rest.
            .padding(start = look.sidePadding, end = look.sidePadding, top = LYRICS_FADE_TOP + 8.dp, bottom = LYRICS_FADE_BOTTOM),
    )
}

/**
 * Keeps the live line at [LyricsLook.anchor], gliding there over 400ms on the web's
 * cubic-bezier(.4, 0, .2, 1) when the line changes.
 *
 * A finger on the list lets the user read ahead or back: following pauses while they drag and
 * for [USER_SCROLL_GRACE_MS] after they let go, then the list glides back to the live line on
 * its own, even when the line hasn't changed meanwhile. Only real drags count: our own scroll
 * also sets isScrollInProgress, so the drag interactions are what's watched.
 */
@Composable
internal fun FollowLiveLine(listState: LazyListState, activeIndex: State<Int>, itemCount: Int) {
    var dragging by remember { mutableStateOf(false) }
    var lastDragEndAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> dragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragging = false
                    lastDragEndAt = System.currentTimeMillis()
                }
            }
        }
    }
    LaunchedEffect(listState, itemCount) {
        if (itemCount == 0) return@LaunchedEffect
        var placed = false
        snapshotFlow { Triple(activeIndex.value, dragging, lastDragEndAt) }.collectLatest { (index, drag, endAt) ->
            if (drag) return@collectLatest
            val target = index.coerceIn(0, itemCount - 1)
            // Opening onto a song already playing: start at the live line, don't scroll there.
            if (!placed) {
                placed = true
                listState.scrollToItem(target)
                return@collectLatest
            }
            val wait = endAt + USER_SCROLL_GRACE_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            runCatching { listState.glideTo(target) }
        }
    }
}

/**
 * Brings item [index] to the top of the content area (the anchor, given the list's top padding).
 * Item offsets are measured from the end of that padding, so a visible item is [offset] away.
 */
private suspend fun LazyListState.glideTo(index: Int) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
    } else if (item.offset != 0) {
        animateScrollBy(item.offset.toFloat(), tween(FOLLOW_MS, easing = FastOutSlowInEasing))
    }
}

/**
 * Index of the lyric line being sung at [positionMs]: the latest line whose
 * timestamp is `<= positionMs`. Before the first timestamp (intro), coerces
 * `-1` to `0` so the first line shows early and the index can never go out
 * of bounds — for non-empty input. On an EMPTY list this still returns 0,
 * which is not a valid index: callers must `getOrNull`/guard for empty.
 * Single source of truth shared by [LyricsLineRenderer] and
 * [LiveLyricsBar] — the bar and the lyrics view must never disagree on the line.
 */
internal fun currentLineIndex(lines: List<LrcLine>, positionMs: Long): Int =
    lines.indexOfLast { it.timestampMs <= positionMs }.coerceAtLeast(0)

/**
 * Follow pauses this long after the user lets go of the list. Long enough that a quick "let me
 * look at the next verse" pan isn't snapped back, short enough that a forgotten scroll doesn't
 * strand the live line off-screen.
 */
private const val USER_SCROLL_GRACE_MS = 5_000L

/** The web column's move to a new line. */
private const val FOLLOW_MS = 400

/** A line's state change (dim, live, sung). */
internal const val LINE_STATE_MS = 300

/** The distance-blur crossfade. */
internal const val BLUR_FADE_MS = 150

/** Line alpha by state, from the app's word renderer (which the web copied). */
internal const val UPCOMING_ALPHA = 0.51f
internal const val SUNG_ALPHA = 0.497f

private val PLAIN_FONT = 18.sp

/** The ♪ row's notes against a lyric line: a touch smaller, so the row reads as a pause. */
private const val BREAK_NOTE_FIT = 0.8f

/** Space between the ♪ row's notes, as a share of the lyric size. */
private const val BREAK_NOTE_SPACING = 0.45f

/** One slow breath of the ♪ row (a resting pulse, not a beat). */
private const val BREAK_BREATH_MS = 2_400

/** TalkBack's action on a lyric line ("Double-tap to play from this line"): tap-to-seek. */
internal const val LYRIC_LINE_CLICK_LABEL = "Play from this line"

/** The edge fades over the lyrics area: the web's 24px top, 120px bottom. */
internal val LYRICS_FADE_TOP = 24.dp
internal val LYRICS_FADE_BOTTOM = 120.dp
