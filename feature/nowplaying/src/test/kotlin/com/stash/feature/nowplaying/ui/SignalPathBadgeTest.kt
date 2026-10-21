package com.stash.feature.nowplaying.ui

import com.stash.core.media.output.SignalQuality
import com.stash.core.media.output.SignalVerdict
import com.stash.feature.nowplaying.ui.SignalPathBadge.badge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The word the live verdict adds to the end of the Now Playing quality line.
 * Resampling is told only inside the signal-path sheet, so it adds nothing here.
 */
class SignalPathBadgeTest {

    private fun verdict(quality: SignalQuality, headline: String) = SignalVerdict(quality, headline, emptyList())

    @Test fun `resampled adds no badge to the quality line`() {
        assertNull(verdict(SignalQuality.RESAMPLED, "Resampled to 48 kHz by Android").badge())
    }

    @Test fun `bit-perfect and hi-res keep their badges`() {
        assertEquals("Bit-perfect", verdict(SignalQuality.BIT_PERFECT, "Bit-perfect").badge())
        assertEquals("Hi-Res", verdict(SignalQuality.LOSSLESS, "Hi-Res, not resampled").badge())
    }

    @Test fun `plain lossless, enhanced and lossy add nothing`() {
        assertNull(verdict(SignalQuality.LOSSLESS, "Lossless, not resampled").badge())
        assertNull(verdict(SignalQuality.ENHANCED, "Lossless + your EQ").badge())
        assertNull(verdict(SignalQuality.LOSSY, "Lossy source").badge())
    }
}
