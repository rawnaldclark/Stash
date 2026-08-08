package com.stash.data.download.jiosaavn

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class JioSaavnResolverTest {
    private val client: JioSaavnClient = mockk()
    private val limiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)

    @Test
    fun `exact playable result becomes 320kbps AAC in an m4a container`() = runTest {
        ready()
        coEvery { client.search(any(), any()) } returns JioSaavnSearchOutcome.Success(listOf(song()))
        coEvery { client.isPlayable320(any()) } returns JioSaavnProbeOutcome.Playable

        val result = JioSaavnResolver(client, limiter).resolve(query(), bypassRateLimit = true)

        assertThat(result).isNotNull()
        assertThat(result!!.sourceId).isEqualTo(JioSaavnResolver.SOURCE_ID)
        assertThat(result.format.codec).isEqualTo("aac")
        assertThat(result.format.fileExtension).isEqualTo("m4a")
        assertThat(result.format.bitrateKbps).isEqualTo(320)
        coVerify { limiter.reportSuccess(JioSaavnResolver.SOURCE_ID) }
    }

    @Test
    fun `fabricated 320 url that fails media probe falls through`() = runTest {
        ready()
        coEvery { client.search(any(), any()) } returns JioSaavnSearchOutcome.Success(listOf(song()))
        coEvery { client.isPlayable320(any()) } returns JioSaavnProbeOutcome.Unavailable

        assertThat(JioSaavnResolver(client, limiter).resolve(query(), true)).isNull()
        coVerify(exactly = 0) { limiter.reportFailure(JioSaavnResolver.SOURCE_ID) }
        coVerify { limiter.reportSuccess(JioSaavnResolver.SOURCE_ID) }
    }

    @Test
    fun `probe transport failure records provider failure`() = runTest {
        ready()
        coEvery { client.search(any(), any()) } returns JioSaavnSearchOutcome.Success(listOf(song()))
        coEvery { client.isPlayable320(any()) } returns JioSaavnProbeOutcome.Failure("HTTP 503")

        assertThat(JioSaavnResolver(client, limiter).resolve(query(), true)).isNull()
        coVerify { limiter.reportFailure(JioSaavnResolver.SOURCE_ID) }
    }

    @Test
    fun `probe rate limit records backoff`() = runTest {
        ready()
        coEvery { client.search(any(), any()) } returns JioSaavnSearchOutcome.Success(listOf(song()))
        coEvery { client.isPlayable320(any()) } returns JioSaavnProbeOutcome.RateLimited

        assertThat(JioSaavnResolver(client, limiter).resolve(query(), true)).isNull()
        coVerify { limiter.reportRateLimited(JioSaavnResolver.SOURCE_ID) }
    }

    @Test
    fun `rate limited search records backoff and returns null`() = runTest {
        ready()
        coEvery { client.search(any(), any()) } returns JioSaavnSearchOutcome.RateLimited

        assertThat(JioSaavnResolver(client, limiter).resolve(query(), true)).isNull()
        coVerify { limiter.reportRateLimited(JioSaavnResolver.SOURCE_ID) }
    }

    private fun ready() {
        coEvery { limiter.stateOf(JioSaavnResolver.SOURCE_ID) } returns
            RateLimitState(4.0, 0, false, 0, 0)
        coEvery { limiter.acquire(JioSaavnResolver.SOURCE_ID) } returns true
    }

    private fun query() = TrackQuery("Arijit Singh", "Kesariya", album = "Brahmastra", durationMs = 268_000L)

    private fun song() = JioSaavnSong(
        id = "rjkrTnma",
        name = "Kesariya",
        duration = 268,
        explicitContent = false,
        album = JioSaavnAlbum("Brahmastra"),
        artists = JioSaavnArtists(listOf(JioSaavnArtist("Arijit Singh"))),
        image = listOf(JioSaavnImage("500x500", "https://c.saavncdn.com/cover.jpg")),
        downloadUrl = listOf(JioSaavnMediaLink("320kbps", "https://aac.saavncdn.com/song_320.mp4")),
    )
}
