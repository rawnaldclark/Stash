package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stash.data.lyrics.parser.LrcLine
import com.stash.data.lyrics.parser.TtmlLyrics
import kotlin.math.abs

/**
 * v0.9.36 Task 12 — renderers for the lyrics sheet.
 *
 * The sheet wrapper ([LyricsBottomSheet]) dispatches into one of these
 * composables based on [LyricsViewState]. Kept separate from the sheet
 * so the synced renderer's auto-scroll + tap-to-seek behaviour can be
 * tested visually (and, in future, swapped for a different presentation
 * without touching the sheet wiring).
 */

/**
 * Auto-scrolling LRC renderer. Centers the current line, dims surrounding
 * lines, taps to seek. Pauses auto-scroll for 5 seconds after the user
 * drags the list — without that grace period the auto-scroll fights any
 * attempt to look ahead/behind the playing line.
 *
 * The "current line" is the latest line whose timestamp is `<= positionMs`,
 * computed via [derivedStateOf] so we only recompose when the index
 * actually flips (typical position ticks at 250ms produce a flip every
 * line — usually a few seconds — not every tick).
 */
@Composable
fun LyricsSyncedRenderer(
    lines: List<LrcLine>,
    currentPositionMs: Long,
    onLineTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Non-null when word-synced TTML is available: renders per-syllable instead of per-line. */
    syllables: TtmlLyrics? = null,
    /** Playback state, so the word-synced clock stops extrapolating while paused/buffering. */
    isPlaying: Boolean = true,
    /** So the word-synced clock runs at the song's pace between position ticks. */
    playbackSpeed: Float = 1f,
) {
    if (syllables != null) {
        LyricsSyllableRenderer(syllables, currentPositionMs, onLineTap, modifier, isPlaying, playbackSpeed)
    } else {
        LyricsLineRenderer(lines, currentPositionMs, onLineTap, modifier)
    }
}

@Composable
private fun LyricsLineRenderer(
    lines: List<LrcLine>,
    currentPositionMs: Long,
    onLineTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // BUG FIX (post-T12): the previous version used `remember(lines) { derivedStateOf {
    //   lines.indexOfLast { it.timestampMs <= currentPositionMs }.coerceAtLeast(0)
    // } }`. derivedStateOf only re-runs when a snapshot-tracked read changes —
    // currentPositionMs is a plain Long parameter, not a Compose State, so the
    // lambda captured the value at first composition and never updated when the
    // parent re-emitted a new position. Result: currentIndex stuck at whatever
    // line was current at sheet-open time (usually 0), and the highlight didn't
    // track playback. remember(lines, currentPositionMs) { … } recomputes on
    // every position tick (~250ms) — cheap (single O(n) scan over the line list)
    // and reactive without needing derivedStateOf.
    val currentIndex = remember(lines, currentPositionMs) {
        currentLineIndex(lines, currentPositionMs)
    }

    // User-drag guard: any time the user manually scrolls, freeze the
    // auto-scroll for 5 seconds so they can browse without the current
    // line yanking them back. Tracked as a Long epoch-millis stamp so a
    // subsequent currentIndex change inside the grace window short-
    // circuits the animateScrollToItem call.
    var lastUserScrollAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            lastUserScrollAtMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(currentIndex, lines) {
        if (lines.isEmpty()) return@LaunchedEffect
        if (System.currentTimeMillis() - lastUserScrollAtMs > USER_SCROLL_GRACE_MS) {
            // Negative offset pulls the row up so the current line sits
            // roughly in the upper third of the visible area — matches
            // the Spotify-style "next-up" reading rhythm.
            runCatching {
                listState.animateScrollToItem(
                    index = currentIndex.coerceAtMost(lines.lastIndex),
                    scrollOffset = AUTO_SCROLL_OFFSET_PX,
                )
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val current = index == currentIndex
            // Spotify-style tiered dimming: current line full alpha + bold,
            // ±1 from current ~0.55, anything further ~0.28. Smooth alpha
            // animation so the highlight glides rather than snaps when
            // playback advances to the next line.
            val distance = abs(index - currentIndex)
            val targetAlpha = when {
                current -> 1.0f
                distance == 1 -> 0.55f
                else -> 0.28f
            }
            val animatedAlpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(durationMillis = 250),
                label = "lyrics-alpha-$index",
            )
            Text(
                text = line.text,
                style = if (current) {
                    MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.titleLarge
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = animatedAlpha),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLineTap(line.timestampMs) }
                    .padding(vertical = 8.dp, horizontal = 24.dp),
            )
        }
    }
}

/**
 * Plain-text fallback for tracks without an LRC body. Scrollable so the
 * full body is reachable even on short sheet heights.
 */
@Composable
fun LyricsPlainRenderer(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp, vertical = 24.dp),
    )
}

/** Loading state — spinner with a short caption. */
@Composable
internal fun CenteredSpinner(label: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Generic "nothing to render here" placard for [LyricsViewState.None],
 * [LyricsViewState.Instrumental], and [LyricsViewState.Error]. Optional
 * action button (typically "Retry") when [action] is non-null.
 */
@Composable
internal fun CenteredPlacard(
    label: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onAction) {
                    Text(action)
                }
            }
        }
    }
}

/**
 * Index of the lyric line being sung at [positionMs]: the latest line whose
 * timestamp is `<= positionMs`. Before the first timestamp (intro), coerces
 * `-1` to `0` so the first line shows early and the index can never go out
 * of bounds — for non-empty input. On an EMPTY list this still returns 0,
 * which is not a valid index: callers must `getOrNull`/guard for empty.
 * Single source of truth shared by [LyricsSyncedRenderer] and
 * [LiveLyricsBar] — the bar and the sheet must never disagree on the line.
 */
internal fun currentLineIndex(lines: List<LrcLine>, positionMs: Long): Int =
    lines.indexOfLast { it.timestampMs <= positionMs }.coerceAtLeast(0)

/**
 * Auto-scroll grace window after a user drag. Long enough that a quick
 * "let me look at the next verse" pan doesn't immediately get snapped
 * back, short enough that a forgotten-about scroll doesn't strand the
 * highlight off-screen.
 */
private const val USER_SCROLL_GRACE_MS = 5_000L

/**
 * Item-scroll offset for the auto-scroll target. Negative pulls the
 * highlighted row above center so the upcoming line is visible.
 */
private const val AUTO_SCROLL_OFFSET_PX = -200
