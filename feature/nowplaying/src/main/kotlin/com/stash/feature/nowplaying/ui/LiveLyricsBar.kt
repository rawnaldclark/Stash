package com.stash.feature.nowplaying.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import com.stash.core.ui.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stash.data.lyrics.parser.LrcLine

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
    data class Live(val lines: List<LrcLine>) : LiveBarMode
    data object Static : LiveBarMode
    data object Hidden : LiveBarMode
}

internal fun liveBarModeFor(state: LyricsViewState): LiveBarMode = when (state) {
    is LyricsViewState.Synced -> LiveBarMode.Live(state.lines)
    is LyricsViewState.Plain -> LiveBarMode.Static
    else -> LiveBarMode.Hidden
}

/** Near-black scrim base — matches `AmbientBackground.BaseDark`. */
private val BarBase = Color(0xFF06060C)

/**
 * Live synced-lyrics bar pinned at the bottom of Now Playing, where the
 * MiniPlayer sits on every other screen. Renders per [LiveBarMode]:
 * the currently-sung line in the album accent (Live), a dim "View lyrics ♪"
 * (Static), or nothing (Hidden). Tapping opens the full lyrics sheet.
 *
 * The backdrop is a translucent scrim — the screen's full-bleed
 * [AmbientBackground] animates beneath, so the Now Playing atmosphere flows
 * through the bar; the vertical gradient only guarantees text contrast.
 *
 * @param accentColor `uiState.vibrantColor` — the same accent
 *        [GlowingProgressBar] uses, NOT `dominantColor`.
 */
@Composable
fun LiveLyricsBar(
    state: LyricsViewState,
    currentPositionMs: Long,
    accentColor: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // remember(state): the 250ms position ticks recompose this composable
    // every tick; without the cache each tick would re-allocate a Live() wrapper.
    val mode = remember(state) { liveBarModeFor(state) }
    AnimatedVisibility(
        visible = mode != LiveBarMode.Hidden,
        enter = fadeIn(tween(400)) + expandVertically(tween(400)),
        exit = fadeOut(tween(250)) + shrinkVertically(tween(250)),
        modifier = modifier,
    ) {
        // 800ms accent crossfade on track change — matches AmbientBackground's
        // CROSSFADE_MS so the whole surface recolors as one.
        val animAccent by animateColorAsState(
            targetValue = accentColor,
            animationSpec = tween(800),
            label = "liveBarAccent",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.cd_open_lyrics), onClick = onTap)
                .background(
                    Brush.verticalGradient(
                        0f to BarBase.copy(alpha = 0.30f),
                        1f to BarBase.copy(alpha = 0.65f),
                    ),
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (mode) {
                is LiveBarMode.Live -> {
                    val index = remember(mode.lines, currentPositionMs) {
                        currentLineIndex(mode.lines, currentPositionMs)
                    }
                    val line = mode.lines.getOrNull(index)?.text.orEmpty()
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
                        Text(
                            // LRC bodies often carry empty-text lines for
                            // instrumental breaks — show a quiet note glyph
                            // rather than a blank bar.
                            text = text.ifBlank { "♪" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                shadow = Shadow(
                                    color = animAccent.copy(alpha = 0.55f),
                                    blurRadius = 18f,
                                ),
                            ),
                            color = animAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                LiveBarMode.Static -> Text(
                    text = stringResource(R.string.action_view_lyrics),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.45f),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                LiveBarMode.Hidden -> Unit
            }
        }
    }
}
