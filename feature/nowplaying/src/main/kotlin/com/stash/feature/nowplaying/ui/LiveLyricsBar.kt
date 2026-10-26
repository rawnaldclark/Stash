package com.stash.feature.nowplaying.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.data.lyrics.parser.LrcLine
import com.stash.data.lyrics.parser.TtmlLyrics

/**
 * What the live-lyrics bar renders for a given [LyricsViewState]:
 *  - [Live]   synced lyrics — the current line, accent-colored, advancing
 *             with playback.
 *  - [Static] plain-only lyrics — a dim "View lyrics ♪" tap target (the
 *             ~15% of lyric hits with no timing data).
 *  - [Hidden] nothing to offer (no lyrics / instrumental / loading / error)
 *             — the bar row is absent entirely; it animates in when a fetch
 *             lands with lyrics.
 */
internal sealed interface LiveBarMode {
    data class Live(val lines: List<LrcLine>, val syllables: TtmlLyrics? = null) : LiveBarMode
    data object Static : LiveBarMode
    data object Hidden : LiveBarMode
}

internal fun liveBarModeFor(state: LyricsViewState, liveEnabled: Boolean): LiveBarMode = when (state) {
    // Live line only when the user opted in; otherwise synced tracks get the
    // same quiet "View lyrics ♪" bar as plain ones — lyrics stay one tap away
    // without the ticking line pulling focus from the music. The lines are the
    // lyrics view's own (breaks folded by [linesWithBreaks]), so the two always
    // agree on the live line, ♪ row included.
    is LyricsViewState.Synced ->
        if (liveEnabled) LiveBarMode.Live(linesWithBreaks(state.lines), state.syllables) else LiveBarMode.Static
    is LyricsViewState.Plain -> LiveBarMode.Static
    else -> LiveBarMode.Hidden
}

/** Near-black scrim base — matches `AmbientBackground.BaseDark`. */
private val BarBase = Color(0xFF06060C)

/** Light-theme scrim base — matches `AmbientBackground.BaseLight`. */
private val BarBaseLight = Color(0xFFF4F1FA)

/** Light-theme ink for the Static "View lyrics ♪" line. */
private val BarInkLight = Color(0xFF241C36)

/** Space between the bar's ♪ notes during an instrumental break. */
internal val BarNoteSpacing = 10.dp

/**
 * Live synced-lyrics bar pinned at the bottom of Now Playing, where the
 * MiniPlayer sits on every other screen. Renders per [LiveBarMode]:
 * the currently-sung line in the album accent (Live), a dim "View lyrics ♪"
 * (Static), or nothing (Hidden). Tapping switches Now Playing to its lyrics view.
 *
 * The backdrop is a translucent scrim — the screen's full-bleed
 * [AmbientBackground] animates beneath, so the Now Playing atmosphere flows
 * through the bar; the vertical gradient only guarantees text contrast.
 *
 * @param accentColor The album's text colour (LocalAlbumChrome's ink), already crossfading.
 * @param transparent No scrim at all: over full art, whose blend already darkens the bottom, a
 *   scrim band only breaks the art.
 */
@Composable
fun LiveLyricsBar(
    state: LyricsViewState,
    /** Read inside the bar's content only, so the 250ms tick doesn't recompose Now Playing. */
    currentPositionMs: () -> Long,
    accentColor: Color,
    liveEnabled: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    playbackSpeed: Float = 1f,
    transparent: Boolean = false,
) {
    // remember(state, liveEnabled): the 250ms position ticks recompose this
    // composable every tick; without the cache each tick would re-allocate
    // a Live() wrapper.
    val mode = remember(state, liveEnabled) { liveBarModeFor(state, liveEnabled) }
    AnimatedVisibility(
        visible = mode != LiveBarMode.Hidden,
        enter = fadeIn(tween(400)) + expandVertically(tween(400)),
        exit = fadeOut(tween(250)) + shrinkVertically(tween(250)),
        modifier = modifier,
    ) {
        // Scrim + static ink follow the resolved theme so the bar reads on
        // both the dark ambient and the light pastel wash.
        val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val scrimBase = if (darkTheme) BarBase else BarBaseLight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Open lyrics", onClick = onTap)
                .then(
                    if (transparent) {
                        Modifier
                    } else {
                        Modifier.background(
                            Brush.verticalGradient(
                                0f to scrimBase.copy(alpha = 0.30f),
                                1f to scrimBase.copy(alpha = 0.65f),
                            ),
                        )
                    },
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val positionMs = currentPositionMs()
            when (mode) {
                is LiveBarMode.Live -> if (mode.syllables != null) {
                    WordSyncedBarLine(
                        lyrics = mode.syllables,
                        currentPositionMs = positionMs,
                        isPlaying = isPlaying,
                        accent = accentColor,
                        playbackSpeed = playbackSpeed,
                    )
                } else {
                    // On the smoothed clock, as the lyrics view: on the raw 250ms poll a line
                    // could light up to 250ms after its vocal.
                    val clock = rememberSmoothPositionMs(positionMs, isPlaying, playbackSpeed)
                    val indexState = remember(mode.lines) {
                        derivedStateOf { currentLineIndex(mode.lines, clock.value) }
                    }
                    val index = indexState.value
                    val line = mode.lines.getOrNull(index)?.text.orEmpty()
                    val gap = remember(mode.lines, index) { instrumentalBreakAt(mode.lines, index) }
                    val style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        shadow = Shadow(
                            color = accentColor.copy(alpha = 0.55f),
                            blurRadius = 18f,
                        ),
                    )
                    AnimatedContent(
                        targetState = line,
                        transitionSpec = {
                            (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 3 })
                                .togetherWith(
                                    fadeOut(tween(250)) + slideOutVertically(tween(250)) { -it / 3 },
                                )
                        },
                        label = "liveBarLine",
                    ) { text ->
                        if (text.isBlank()) {
                            // An instrumental break (3 s or longer: shorter blanks never reach
                            // the bar) — the ♪ row, breathing until the singing is back.
                            InstrumentalBreakNotes(
                                style = style,
                                color = accentColor,
                                live = true,
                                progress = { gap?.progress(clock.value) },
                                spacing = BarNoteSpacing,
                                alignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text = text,
                                style = style,
                                color = accentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                LiveBarMode.Static -> Text(
                    text = "View lyrics ♪",
                    style = MaterialTheme.typography.titleSmall,
                    color = (if (darkTheme) Color.White else BarInkLight).copy(alpha = 0.45f),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiveBarMode.Hidden -> Unit
            }
        }
    }
}
