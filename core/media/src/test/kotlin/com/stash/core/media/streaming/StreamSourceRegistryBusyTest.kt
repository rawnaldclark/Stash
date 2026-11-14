package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.noteLosslessBusy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A lossy fallback served because Tidal was BUSY (its relay's 503 / 429 with a Retry-After) is
 * for this play only ([StreamUrl.forThisPlayOnly]); a fallback for a song Tidal does not have is
 * kept as before. And the second ask a look-ahead makes after waiting ([StreamSourceRegistry.resolveLosslessOnly])
 * asks the lossless sources only.
 *
 * The Tidal rung is a mock that says "busy" the way TidalClient.resolve does: on the walk's
 * [com.stash.data.download.lossless.LosslessBusyNote] (MockK runs coAnswers in the caller's coroutine).
 */
class StreamSourceRegistryBusyTest {

    private val busyUntil = System.currentTimeMillis() + 15_000

    /** What Tidal answers; each case sets it. */
    private var tidalAnswer: suspend () -> StreamUrl? = { null }

    private val opus = StreamUrl(url = "https://yt.example/a.webm", expiresAtMs = Long.MAX_VALUE / 2, codec = "opus", origin = "youtube")
    private val aac = StreamUrl(url = "https://jiosaavn.example/a.mp4", expiresAtMs = Long.MAX_VALUE / 2, codec = "aac", origin = "jiosaavn")
    private val flac = StreamUrl(url = "https://tidal.example/a.flac", expiresAtMs = Long.MAX_VALUE / 2, codec = "flac", bitsPerSample = 24, origin = "tidal")

    private val qbdlx: QbdlxStreamResolver = mockk { coEvery { resolve(any()) } returns null }
    private val tidal: TidalStreamResolver = mockk { coEvery { resolve(any()) } coAnswers { tidalAnswer() } }
    private val jiosaavn: JioSaavnStreamResolver = mockk { coEvery { resolve(any()) } returns null }
    private val youtube: YouTubeStreamResolver = mockk { coEvery { resolve(any(), any()) } returns opus }
    private val streamingPreference: StreamingPreference = mockk {
        coEvery { isForceQbdlxOnly() } returns false
        coEvery { isForceYouTubeFallback() } returns false
        coEvery { isSkipQobuz() } returns false
    }
    private val losslessPrefs: LosslessSourcePreferences = mockk { coEvery { enabledNow() } returns true }

    private val registry = StreamSourceRegistry(
        qbdlx, jiosaavn, youtube, streamingPreference,
        LosslessSourceHealth(), losslessPrefs, Dispatchers.IO, PlaybackDiagnosticsLog(),
        tidal = tidal,
    )

    private val busyTidal: suspend () -> StreamUrl? = {
        noteLosslessBusy(busyUntil)
        null
    }

    @Test fun `YouTube serving while Tidal is busy is for this play only, until Tidal may be asked again`() = runTest {
        tidalAnswer = busyTidal

        val result = registry.resolve(track())!!

        assertThat(result.origin).isEqualTo("youtube")
        assertThat(result.forThisPlayOnly).isTrue()
        assertThat(result.losslessBusyUntilMs).isEqualTo(busyUntil)
    }

    @Test fun `JioSaavn serving while Tidal is busy is for this play only too`() = runTest {
        tidalAnswer = busyTidal
        coEvery { jiosaavn.resolve(any()) } returns aac

        val result = registry.resolve(track())!!

        assertThat(result.origin).isEqualTo("jiosaavn")
        assertThat(result.forThisPlayOnly).isTrue()
    }

    @Test fun `a fallback for a song Tidal does not have is kept as before`() = runTest {
        tidalAnswer = { null } // a genuine miss: nothing is noted

        val result = registry.resolve(track())!!

        assertThat(result.origin).isEqualTo("youtube")
        assertThat(result.forThisPlayOnly).isFalse()
        assertThat(result.losslessBusyUntilMs).isNull()
    }

    @Test fun `Tidal serving is never marked`() = runTest {
        tidalAnswer = { flac }

        val result = registry.resolve(track())!!

        assertThat(result).isEqualTo(flac)
    }

    @Test fun `the second ask after a busy wait asks the lossless sources only`() = runTest {
        tidalAnswer = { flac }
        assertThat(registry.resolveLosslessOnly(track())).isEqualTo(flac)

        tidalAnswer = busyTidal
        assertThat(registry.resolveLosslessOnly(track(id = 8L))).isNull()

        coVerify(exactly = 0) { jiosaavn.resolve(any()) }
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
    }

    private fun track(id: Long = 7L) = TrackEntity(id = id, title = "Poor Jack", artist = "Danny Elfman", durationMs = 213_000)
}
