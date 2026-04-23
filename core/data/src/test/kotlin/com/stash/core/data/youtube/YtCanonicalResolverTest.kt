package com.stash.core.data.youtube

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class YtCanonicalResolverTest {

    private fun track(
        id: Long = 1L,
        youtubeId: String? = "abc123",
        musicVideoType: String? = null,
        ytCanonicalVideoId: String? = null,
    ) = TrackEntity(
        id = id, title = "Song", artist = "Artist",
        youtubeId = youtubeId, musicVideoType = musicVideoType,
        ytCanonicalVideoId = ytCanonicalVideoId,
    )

    @Test
    fun `ATV track uses stored youtube_id without searching`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.ATV.name))

        assertEquals("abc123", result)
        verify(innerTube, never()).searchCanonical(any(), any())
    }

    @Test
    fun `OMV track uses stored youtube_id without searching`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.OMV.name))

        assertEquals("abc123", result)
        verify(innerTube, never()).searchCanonical(any(), any())
    }

    @Test
    fun `cached yt_canonical_video_id is used over searching`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(
            track(musicVideoType = MusicVideoType.UGC.name, ytCanonicalVideoId = "cached123")
        )

        assertEquals("cached123", result)
        verify(innerTube, never()).searchCanonical(any(), any())
    }

    @Test
    fun `UGC track with no cache triggers search and caches result`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonical("Artist", "Song")).thenReturn("resolved456")
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.UGC.name))

        assertEquals("resolved456", result)
        verify(trackDao).updateYtCanonicalVideoId(1L, "resolved456")
    }

    @Test
    fun `UGC track with no canonical match returns null and does not cache`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonical("Artist", "Song")).thenReturn(null)
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.UGC.name))

        assertNull(result)
        verify(trackDao, never()).updateYtCanonicalVideoId(any(), any())
    }

    @Test
    fun `null musicVideoType with stored youtube_id triggers search`() = runTest {
        // A track whose classification was never determined — treat like UGC.
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonical("Artist", "Song")).thenReturn("resolved789")
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = null))

        assertEquals("resolved789", result)
    }
}
