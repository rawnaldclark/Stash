package com.stash.core.media.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.SCANNED_ID_BASE
import com.stash.core.model.Track
import org.junit.Test

/** Where a Recap card's song plays from ([recapSourceFor]) and where in it ([recapStartPositionMs]). */
class RecapSourceTest {

    private val downloaded = Track(
        id = 7, title = "T", artist = "A", durationMs = 200_000,
        filePath = "/music/7.flac", isDownloaded = true, youtubeId = "yt7",
    )
    private val streamOnly = Track(
        id = 8, title = "T", artist = "A", album = "Al", durationMs = 200_000,
        youtubeId = "yt8", isrc = "ISRC8", isStreamable = true,
    )

    private fun choose(
        track: Track,
        localPlayable: Boolean = true,
        online: Boolean = true,
        allowed: Boolean = true,
    ) = recapSourceFor(track, localPlayable, online, allowed)

    @Test
    fun `a downloaded song plays from its file, even offline`() {
        assertThat(choose(downloaded, online = false))
            .isEqualTo(RecapSourceChoice.Play(RecapSource.Local("/music/7.flac")))
    }

    @Test
    fun `a downloaded song whose file is gone streams instead`() {
        assertThat(choose(downloaded, localPlayable = false)).isEqualTo(RecapSourceChoice.Stream)
    }

    @Test
    fun `a song with no file has to stream`() {
        assertThat(choose(streamOnly)).isEqualTo(RecapSourceChoice.Stream)
    }

    @Test
    fun `offline, a stream is skipped`() {
        assertThat(choose(streamOnly, online = false))
            .isEqualTo(RecapSourceChoice.Skip(RecapSkipReason.OFFLINE))
    }

    @Test
    fun `on mobile data with cellular streaming off, a stream is skipped`() {
        assertThat(choose(streamOnly, allowed = false))
            .isEqualTo(RecapSourceChoice.Skip(RecapSkipReason.CELLULAR_REFUSED))
    }

    @Test
    fun `an unavailable song is skipped`() {
        val unavailable = streamOnly.copy(isStreamable = false, isStreamableCheckedAt = 1L)
        assertThat(choose(unavailable)).isEqualTo(RecapSourceChoice.Skip(RecapSkipReason.UNAVAILABLE))
    }

    @Test
    fun `an All Songs file plays from its file or not at all, never a stream matched by name`() {
        val scanned = Track(id = SCANNED_ID_BASE - 5, title = "T", artist = "A", filePath = "content://x/5")
        assertThat(choose(scanned)).isEqualTo(RecapSourceChoice.Play(RecapSource.Local("content://x/5")))
        assertThat(choose(scanned, localPlayable = false))
            .isEqualTo(RecapSourceChoice.Skip(RecapSkipReason.LOCAL_FILE_MISSING))
    }

    @Test
    fun `start point is about a third in`() {
        assertThat(recapStartPositionMs(240_000, 0.3f)).isEqualTo(72_000)
    }

    @Test
    fun `start point leaves at least 25 seconds`() {
        assertThat(recapStartPositionMs(30_000, 0.3f)).isEqualTo(5_000)
        assertThat(recapStartPositionMs(100_000, 0.9f)).isEqualTo(75_000)
    }

    @Test
    fun `short or unknown-length songs start at the top`() {
        assertThat(recapStartPositionMs(0, 0.3f)).isEqualTo(0)
        assertThat(recapStartPositionMs(20_000, 0.3f)).isEqualTo(0)
        assertThat(recapStartPositionMs(25_000, 0.3f)).isEqualTo(0)
    }

    @Test
    fun `a bad fraction can't land outside the song`() {
        assertThat(recapStartPositionMs(240_000, -1f)).isEqualTo(0)
        assertThat(recapStartPositionMs(240_000, Float.NaN)).isEqualTo(0)
        assertThat(recapStartPositionMs(240_000, 5f)).isEqualTo(215_000)
    }
}
