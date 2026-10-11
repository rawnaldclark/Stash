package com.stash.feature.nowplaying.ui

import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.36 Task 12 Step 2 — pure mapper for the lyrics view's state.
 *
 * Drives the [lyricsViewStateFor] derivation that the Now Playing
 * ViewModel uses to fold (current Track, observed LyricsEntity?) into
 * the sealed [LyricsViewState] the lyrics view renders.
 *
 * Sentinel semantics live on the Track row itself (`lyricsFetchedAt`):
 *   - NULL  → never tried → Loading (sheet will enqueue a priority fetch)
 *   - 0L    → tried, miss → None (allow Retry)
 *   - >0L   → success     → look at the LyricsEntity contents
 *
 * The mapper takes the domain [Track] rather than [TrackEntity] so the
 * ui package doesn't have to depend on the Room entity package (every
 * other type the Now Playing screen already touches is the domain
 * model). The `lyricsFetchedAt` field mirrors the entity exactly.
 */
class LyricsViewStateTest {

    @Test fun `track NULL stamp with no row maps to Loading`() {
        val state = lyricsViewStateFor(track = trackWithStamp(null), row = null)
        assertEquals(LyricsViewState.Loading, state)
    }

    @Test fun `track 0L stamp with no row maps to None`() {
        val state = lyricsViewStateFor(track = trackWithStamp(0L), row = null)
        assertEquals(LyricsViewState.None, state)
    }

    @Test fun `instrumental row maps to Instrumental`() {
        val row = sampleRow(instrumental = true)
        val state = lyricsViewStateFor(track = trackWithStamp(1L), row = row)
        assertEquals(LyricsViewState.Instrumental, state)
    }

    @Test fun `synced lyrics map to Synced with parsed lines`() {
        val row = sampleRow(
            syncedLrc = "[00:01.00]hello\n[00:02.50]world",
            plainText = "hello world",
        )
        val state = lyricsViewStateFor(track = trackWithStamp(1L), row = row)
        assertTrue("expected Synced, got $state", state is LyricsViewState.Synced)
        val synced = state as LyricsViewState.Synced
        assertEquals(2, synced.lines.size)
        assertEquals("hello world", synced.plainFallback)
    }

    @Test fun `plain-only row maps to Plain`() {
        val row = sampleRow(syncedLrc = null, plainText = "lyrics")
        val state = lyricsViewStateFor(track = trackWithStamp(1L), row = row)
        assertEquals(LyricsViewState.Plain("lyrics"), state)
    }

    @Test fun `synced parse failing with plain fallback maps to Plain`() {
        val row = sampleRow(
            syncedLrc = "junk that wont parse",
            plainText = "fallback",
        )
        val state = lyricsViewStateFor(track = trackWithStamp(1L), row = row)
        assertEquals(LyricsViewState.Plain("fallback"), state)
    }

    @Test fun `synced parse failing with no plain falls back to None`() {
        val row = sampleRow(syncedLrc = "junk", plainText = null)
        val state = lyricsViewStateFor(track = trackWithStamp(1L), row = row)
        assertEquals(LyricsViewState.None, state)
    }

    private fun trackWithStamp(stamp: Long?): Track = Track(
        id = 1L,
        title = "song",
        artist = "artist",
        lyricsFetchedAt = stamp,
    )

    private fun sampleRow(
        instrumental: Boolean = false,
        syncedLrc: String? = null,
        plainText: String? = null,
    ): LyricsEntity = LyricsEntity(
        trackId = 1L,
        plainText = plainText,
        syncedLrc = syncedLrc,
        instrumental = instrumental,
        language = null,
        source = "lrclib",
        sourceLyricsId = "1",
        fetchedAt = 1L,
    )
}
