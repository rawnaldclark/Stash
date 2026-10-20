package com.stash.feature.nowplaying

import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackQualityTextTest {

    private fun track(
        format: String,
        origin: String? = null,
        bits: Int? = null,
        rate: Int? = null,
        kbps: Int = 0,
        filePath: String? = null,
    ) = Track(
        id = 1L, title = "t", artist = "a", fileFormat = format, bitsPerSample = bits,
        sampleRateHz = rate, qualityKbps = kbps, streamOrigin = origin, filePath = filePath,
    )

    @Test fun `a qbdlx stream says via Qobuz`() {
        assertEquals(
            "FLAC · 24-bit/96.0 kHz · via Qobuz",
            trackQualityText(track("flac", origin = "qbdlx", bits = 24, rate = 96_000)),
        )
    }

    @Test fun `a Tidal AAC stream says via Tidal`() {
        assertEquals("AAC · 320 kbps · via Tidal", trackQualityText(track("aac", origin = "tidal", kbps = 320)))
    }

    @Test fun `lossy fallbacks keep naming their service`() {
        assertEquals("AAC · via JioSaavn", trackQualityText(track("aac", origin = "jiosaavn")))
        assertEquals("OPUS · via YouTube", trackQualityText(track("opus", origin = "youtube")))
    }

    @Test fun `a downloaded file names no service`() {
        assertEquals(
            "FLAC · 16-bit/44.1 kHz",
            trackQualityText(track("flac", bits = 16, rate = 44_100, filePath = "/music/a.flac")),
        )
    }
}
