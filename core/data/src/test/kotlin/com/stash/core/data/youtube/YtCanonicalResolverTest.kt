package com.stash.core.data.youtube

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.ytmusic.CanonicalLookup
import com.stash.data.ytmusic.CanonicalMatch
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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

    private fun found(videoId: String) = CanonicalLookup.Found(CanonicalMatch(videoId, thumbnailUrl = null))

    @Test
    fun `ATV track uses stored youtube_id without searching`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.ATV.name))

        assertEquals(found("abc123"), result)
        verify(innerTube, never()).searchCanonicalLookup(any(), any(), anyOrNull())
    }

    @Test
    fun `OMV track uses stored youtube_id without searching`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.OMV.name))

        assertEquals(found("abc123"), result)
        verify(innerTube, never()).searchCanonicalLookup(any(), any(), anyOrNull())
    }

    @Test
    fun `cached yt_canonical_video_id is used over searching`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(
            track(musicVideoType = MusicVideoType.UGC.name, ytCanonicalVideoId = "cached123")
        )

        assertEquals(found("cached123"), result)
        verify(innerTube, never()).searchCanonicalLookup(any(), any(), anyOrNull())
    }

    @Test
    fun `UGC track with no cache triggers search and caches result`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonicalLookup("Artist", "Song", null)).thenReturn(found("resolved456"))
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.UGC.name))

        assertEquals(found("resolved456"), result)
        verify(trackDao).updateYtCanonicalVideoId(1L, "resolved456")
    }

    @Test
    fun `UGC track with no canonical match returns NoMatch and does not cache`() = runTest {
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonicalLookup("Artist", "Song", null)).thenReturn(CanonicalLookup.NoMatch)
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = MusicVideoType.UGC.name))

        assertEquals(CanonicalLookup.NoMatch, result)
        verify(trackDao, never()).updateYtCanonicalVideoId(any(), any())
    }

    @Test
    fun `a search that did not answer is a failure, not a NoMatch`() = runTest {
        // Offline, 429 or 5xx: the scrobbler must keep the play queued, which it
        // can only do if this is told apart from "YouTube has no canonical version".
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonicalLookup("Artist", "Song", null)).thenReturn(CanonicalLookup.Failed)
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = null))

        assertEquals(CanonicalLookup.Failed, result)
        verify(trackDao, never()).updateYtCanonicalVideoId(any(), any())
    }

    @Test
    fun `null musicVideoType with stored youtube_id triggers search`() = runTest {
        // A track whose classification was never determined — treat like UGC.
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonicalLookup("Artist", "Song", null)).thenReturn(found("resolved789"))
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = null))

        assertEquals(found("resolved789"), result)
    }

    @Test
    fun `the track's parental advisory reaches the search`() = runTest {
        // It picks between YouTube's explicit and clean copies of the song.
        val trackDao = mock<TrackDao>()
        val innerTube = mock<InnerTubeClient>()
        whenever(innerTube.searchCanonicalLookup("Artist", "Song", false)).thenReturn(found("clean123"))
        val resolver = YtCanonicalResolver(trackDao, innerTube)

        val result = resolver.resolve(track(musicVideoType = null).copy(explicit = false))

        assertEquals(found("clean123"), result)
    }
}
