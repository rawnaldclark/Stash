package com.stash.core.media.streaming

import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.bitchord.BitchordClient
import com.stash.data.download.lossless.bitchord.BitchordStream
import com.stash.data.download.lossless.bitchord.TidalLookup
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TidalStreamResolverTest {

    private val client: BitchordClient = mockk {
        coEvery { isConfigured() } returns true
        coEvery { resolve(any(), any()) } returns null
    }

    @Test
    fun `the Tidal AAC switch decides whether streaming asks for AAC`() = runTest {
        for (on in listOf(true, false)) {
            val prefs: LosslessSourcePreferences = mockk { coEvery { tidalAacFallbackNow() } returns on }
            TidalStreamResolver(client, prefs).resolve(TrackEntity(title = "Get Lucky", artist = "Daft Punk"))
            coVerify { client.resolve(any(), allowAac = on) }
        }
    }

    private val track = TrackEntity(title = "Get Lucky", artist = "Daft Punk")
    private val prefs: LosslessSourcePreferences = mockk { coEvery { tidalAacFallbackNow() } returns false }

    private fun stream(codec: String) = BitchordStream(
        url = "https://cdn.example/a.mp4", isDash = false, bitDepth = 0, sampleRateHz = 44_100,
        bitrateKbps = 320, coverArtUrl = null, expiresAtMs = Long.MAX_VALUE, codec = codec,
    )

    /** Prefer Tidal AAC asks in AAC download mode (aac=only), whatever the "AAC when no FLAC" switch says. */
    @Test
    fun `prefer-AAC asks the relay in aac=only mode and labels it Tidal AAC`() = runTest {
        coEvery { client.lookup(any(), allowAac = true, cacheOnly = false, aacOnly = true) } returns TidalLookup.Found(stream("aac"))

        val s = TidalStreamResolver(client, prefs).resolveAacOnly(track)!!

        assertThat(s.codec).isEqualTo("aac")
        assertThat(s.origin).isEqualTo("tidal")
        assertThat(s.bitrateKbps).isEqualTo(320)
        assertThat(s.bitsPerSample).isNull()
        coVerify(exactly = 1) { client.lookup(any(), allowAac = true, cacheOnly = false, aacOnly = true) }
    }

    /** A server that ignores aac=only answers FLAC: that saves no data, so the chain goes on to Qobuz. */
    @Test
    fun `prefer-AAC refuses a FLAC answer`() = runTest {
        coEvery { client.lookup(any(), allowAac = true, cacheOnly = false, aacOnly = true) } returns TidalLookup.Found(stream("flac"))
        assertThat(TidalStreamResolver(client, prefs).resolveAacOnly(track)).isNull()
    }

    @Test
    fun `prefer-AAC is a miss on busy, spent, no match and error`() = runTest {
        for (r in listOf(TidalLookup.Busy(1L), TidalLookup.Spent(1L), TidalLookup.NoMatch, TidalLookup.Failed, TidalLookup.Off)) {
            coEvery { client.lookup(any(), allowAac = true, cacheOnly = false, aacOnly = true) } returns r
            assertThat(TidalStreamResolver(client, prefs).resolveAacOnly(track)).isNull()
        }
    }

    @Test
    fun `prefer-AAC makes no request with no relay configured`() = runTest {
        coEvery { client.isConfigured() } returns false
        assertThat(TidalStreamResolver(client, prefs).resolveAacOnly(track)).isNull()
        coVerify(exactly = 0) { client.lookup(any(), any(), any(), any()) }
    }
}
