package com.stash.core.media.streaming

import com.stash.core.auth.TokenManager
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.soundcloud.SoundCloudClient
import com.stash.data.soundcloud.SoundCloudStream
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SoundCloudStreamResolverTest {
    private val client = mockk<SoundCloudClient>()
    private val tokens = mockk<TokenManager> { coEvery { getSoundCloudToken() } returns null }
    private val tracks = mockk<TrackDao>(relaxed = true)
    private val resolver = SoundCloudStreamResolver(client, tokens, tracks)
    private val track = TrackEntity(id = 7, title = "Remix", artist = "Artist", soundCloudId = "123")

    @Test fun `HLS previews are marked and their URL has a bounded cache lifetime`() = runTest {
        coEvery { client.stream("123", null) } returns SoundCloudStream("https://cf-media.sndcdn.com/song", "audio/mp4", true, true)
        val before = System.currentTimeMillis()
        val result = resolver.resolve(track)!!
        assertTrue(result.isHls)
        assertTrue(result.isPreview)
        assertEquals("soundcloud", result.origin)
        assertTrue(result.expiresAtMs in before..System.currentTimeMillis() + 300_000)
        coVerify { tracks.updateSoundCloudPreview(7, true) }
    }

    @Test fun `blocked upload returns no match`() = runTest {
        coEvery { client.stream(any(), any()) } throws java.io.IOException("blocked")
        assertNull(resolver.resolve(track))
    }

    @Test fun `cancellation propagates`() = runTest {
        coEvery { client.stream(any(), any()) } throws CancellationException()
        try { resolver.resolve(track); fail("cancellation swallowed") } catch (_: CancellationException) { }
    }
}
