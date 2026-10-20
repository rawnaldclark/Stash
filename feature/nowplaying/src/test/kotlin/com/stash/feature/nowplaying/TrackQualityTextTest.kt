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

    /** The quality line never names Qobuz or Tidal: the signal-path sheet does. */
    @Test fun `a Qobuz stream names no service`() {
        assertEquals(
            "FLAC · 24-bit/96.0 kHz",
            trackQualityText(track("flac", origin = "qbdlx", bits = 24, rate = 96_000)),
        )
    }

    @Test fun `a Tidal stream names no service`() {
        assertEquals("AAC · 320 kbps", trackQualityText(track("aac", origin = "tidal", kbps = 320)))
        assertEquals(
            "FLAC · 16-bit/44.1 kHz",
            trackQualityText(track("flac", origin = "tidal", bits = 16, rate = 44_100)),
        )
    }

    @Test fun `lossy fallbacks keep the old suffixes`() {
        assertEquals("AAC · via JioSaavn", trackQualityText(track("aac", origin = "jiosaavn")))
        assertEquals("OPUS · 160 kbps · via YT", trackQualityText(track("opus", origin = "youtube", kbps = 160)))
    }

    @Test fun `a downloaded file names no service`() {
        assertEquals(
            "FLAC · 16-bit/44.1 kHz",
            trackQualityText(track("flac", bits = 16, rate = 44_100, filePath = "/music/a.flac")),
        )
    }

    @Test fun `a downloaded file's extension beats a stale format`() {
        assertEquals("FLAC · 900 kbps", trackQualityText(track("opus", kbps = 900, filePath = "/music/a.flac")))
    }

    @Test fun `a blank format with no file renders no line`() {
        assertEquals(null, trackQualityText(track("")))
    }
}
