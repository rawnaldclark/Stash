package com.stash.core.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The lossless badge names the file's real format: an imported WAV showed
 * "FLAC" in the Library list.
 */
class FlacBadgeTextTest {

    @Test
    fun `a FLAC track still reads FLAC`() {
        assertThat(flacBadgeText("flac", null, null)).isEqualTo("FLAC")
        assertThat(flacBadgeText("flac", 16, 44_100)).isEqualTo("FLAC")
        assertThat(flacBadgeText("flac", 24, 96_000)).isEqualTo("FLAC 24/96")
    }

    @Test
    fun `other lossless formats read as themselves`() {
        assertThat(flacBadgeText("wav", null, null)).isEqualTo("WAV")
        assertThat(flacBadgeText("wav", 24, 192_000)).isEqualTo("WAV 24/192")
        assertThat(flacBadgeText("alac", 16, 44_100)).isEqualTo("ALAC")
        assertThat(flacBadgeText("aiff", null, null)).isEqualTo("AIFF")
    }

    @Test
    fun `the stored format's case does not matter`() {
        assertThat(flacBadgeText("WAV", null, null)).isEqualTo("WAV")
        assertThat(flacBadgeText("Flac", null, null)).isEqualTo("FLAC")
    }
}
