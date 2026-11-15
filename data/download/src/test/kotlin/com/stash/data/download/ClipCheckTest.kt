package com.stash.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [clipOf]: the downloads' one "too short" rule ([isLosslessDurationDegraded]), on what a file's
 * audio really runs, against the song's length and against the file's own header.
 */
class ClipCheckTest {

    @Test
    fun `Tidal's preview of Linger - a 4 35 header over 0 30 of audio - is a clip`() {
        val clip = clipOf(headerMs = 274_773L, framesMs = 29_907L, expectedMs = 273_000L)!!

        assertThat(clip.playsMs).isEqualTo(29_907L)
        assertThat(clip.headerMs).isEqualTo(274_773L)
    }

    @Test
    fun `a clip of its own header is one even when the song's length is unknown`() {
        assertThat(clipOf(headerMs = 274_773L, framesMs = 29_907L, expectedMs = 0L)).isNotNull()
    }

    @Test
    fun `a whole song is kept`() {
        assertThat(clipOf(headerMs = 255_107L, framesMs = 255_107L, expectedMs = 255_000L)).isNull()
        // A packet walk ends at the last packet's start, a few ms short of the header: still the song.
        assertThat(clipOf(headerMs = 179_327L, framesMs = 179_304L, expectedMs = 179_347L)).isNull()
    }

    @Test
    fun `a genuinely short song is kept`() {
        // A 30 s interlude: short, but the song is that long and so is its header.
        assertThat(clipOf(headerMs = 30_000L, framesMs = 30_000L, expectedMs = 32_000L)).isNull()
    }

    @Test
    fun `unmeasured frames leave the header's word, as the backstop always judged`() {
        // A 30 s file whose header says 30 s, for a 4-minute song: refused as before.
        assertThat(clipOf(headerMs = 30_000L, framesMs = null, expectedMs = 240_000L)?.playsMs).isEqualTo(30_000L)
        // 0 is what a relaxed probe returns: not measured, never "0 ms long".
        assertThat(clipOf(headerMs = 240_000L, framesMs = 0L, expectedMs = 240_000L)).isNull()
    }

    @Test
    fun `nothing known refuses nothing`() {
        assertThat(clipOf(headerMs = null, framesMs = null, expectedMs = 240_000L)).isNull()
        assertThat(clipOf(headerMs = 0L, framesMs = 0L, expectedMs = 0L)).isNull()
    }
}
