package com.stash.feature.nowplaying.ui

import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.model.Track
import com.stash.data.lyrics.parser.LrcLine
import com.stash.data.lyrics.parser.LrcParser
import com.stash.data.lyrics.parser.TtmlLyrics
import com.stash.data.lyrics.parser.TtmlParser
import com.stash.data.lyrics.source.LyricsResult

/**
 * v0.9.36 Task 12 — Now Playing lyrics view state.
 *
 * Sealed hierarchy Now Playing's lyrics mode ([LyricsPane], via [lyricsBodyFor]) renders one-of:
 *  - [Loading]      Track has never been tried (`lyricsFetchedAt == null`).
 *                   The screen's subscription triggers a priority fetch and waits.
 *  - [Synced]       LRC parsed into a non-empty [LrcLine] list. Used by
 *                   [LyricsLineRenderer] / [LyricsSyllableRenderer] for the auto-scroll + tap-to-seek
 *                   experience. Carries [plainFallback] so the renderer can
 *                   degrade gracefully without re-fetching if it ever needs
 *                   to render plain text.
 *  - [Plain]        Un-timed lyrics only (or synced text that didn't parse
 *                   and there's a plain-text body to fall back to).
 *  - [Instrumental] Source confirmed the track has no lyrics. Distinct
 *                   from [None] because it's a positive answer, not a miss.
 *  - [None]         Either the fetch ran and every source definitively
 *                   answered "no lyrics" (`lyricsFetchedAt == 0L`), or the
 *                   row has no usable text. Lyrics mode shows a quiet "Try again" wired
 *                   to [LyricsPane]'s `onRetry`.
 *  - [Error]        The fetch FAILED (network/HTTP/parse — sources threw)
 *                   rather than missed. Not produced by these mappers: the
 *                   ViewModel emits it directly — for library tracks via the
 *                   failed-fetch overlay (stamp stays NULL so the track
 *                   remains retryable), for streaming tracks when
 *                   `resolveTransient` throws. Distinct from [None] so a
 *                   flaky connection reads "Couldn't load" + Retry, not the
 *                   false-permanent "No lyrics found".
 */
sealed interface LyricsViewState {
    object Loading : LyricsViewState
    data class Synced(
        val lines: List<LrcLine>,
        val plainFallback: String,
        /** Non-null when word-synced TTML is available; the syllable renderer keys off this. */
        val syllables: TtmlLyrics? = null,
    ) : LyricsViewState
    data class Plain(val text: String) : LyricsViewState
    object Instrumental : LyricsViewState
    object None : LyricsViewState
    data class Error(val retryable: Boolean) : LyricsViewState
}

/**
 * Pure derivation from the canonical pair (current [Track], observed
 * [LyricsEntity]?). Drives [LyricsPane]'s render via
 * [com.stash.feature.nowplaying.NowPlayingViewModel.lyricsViewState].
 *
 * Order is significant — the sentinel check on `lyricsFetchedAt` runs
 * first so a NULL stamp always renders Loading even if a stale row
 * lingers from a previous fetch (shouldn't happen, but defensive).
 */
internal fun lyricsViewStateFor(track: Track, row: LyricsEntity?): LyricsViewState = when {
    track.lyricsFetchedAt == null -> LyricsViewState.Loading
    track.lyricsFetchedAt == 0L -> LyricsViewState.None
    row?.instrumental == true -> LyricsViewState.Instrumental
    row?.syncedLrc != null -> {
        val synced = row.syncedLrc!!
        val lines = LrcParser.parse(synced)
        val plain = row.plainText
        when {
            lines.isNotEmpty() -> LyricsViewState.Synced(lines, plain.orEmpty(), row.ttml?.let(TtmlParser::parse))
            !plain.isNullOrBlank() -> LyricsViewState.Plain(plain)
            else -> LyricsViewState.None
        }
    }
    row != null && !row.plainText.isNullOrBlank() -> LyricsViewState.Plain(row.plainText!!)
    else -> LyricsViewState.None
}

/**
 * Result-only mapper for the streaming-track path. Streaming-mode tracks
 * have no persistent `tracks` row (id == 0L), so the ViewModel calls
 * [com.stash.data.lyrics.LyricsRepository.resolveTransient] directly and
 * feeds the raw [LyricsResult] through this mapper instead of going via
 * Room. Logic mirrors [lyricsViewStateFor] (instrumental / synced /
 * plain / none) minus the sentinel handling — a null result means
 * "miss," not "never tried."
 */
internal fun lyricsViewStateForResult(result: LyricsResult?): LyricsViewState = when {
    result == null -> LyricsViewState.None
    result.instrumental -> LyricsViewState.Instrumental
    result.syncedLrc != null -> {
        val synced = result.syncedLrc!!
        val lines = LrcParser.parse(synced)
        val plain = result.plainText
        when {
            lines.isNotEmpty() -> LyricsViewState.Synced(lines, plain.orEmpty(), result.ttml?.let(TtmlParser::parse))
            !plain.isNullOrBlank() -> LyricsViewState.Plain(plain)
            else -> LyricsViewState.None
        }
    }
    !result.plainText.isNullOrBlank() -> LyricsViewState.Plain(result.plainText!!)
    else -> LyricsViewState.None
}
