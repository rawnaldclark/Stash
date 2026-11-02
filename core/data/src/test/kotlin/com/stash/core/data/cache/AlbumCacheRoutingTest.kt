package com.stash.core.data.cache

import com.stash.core.data.discography.QobuzAlbumFetcher
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Covers [AlbumCache]'s source routing (Task 10): a QOBUZ album loads via the
 * [QobuzAlbumFetcher] and never touches the YT API, and vice-versa. The
 * `"$source:$id"` keying keeps a numeric Qobuz id from colliding with a YT
 * browseId.
 */
class AlbumCacheRoutingTest {

    private fun detail(id: String, title: String) = AlbumDetail(
        id = id, title = title, artist = "A", artistId = null,
        thumbnailUrl = null, year = null, tracks = emptyList(), moreByArtist = emptyList(),
    )

    @Test
    fun `qobuz source routes to the fetcher, not the YT api`() = runTest {
        val api = mock<YTMusicApiClient>()
        val fetcher = mock<QobuzAlbumFetcher>()
        whenever(fetcher.getAlbum(eq("123"))).thenReturn(detail("123", "Loveless"))
        val cache = AlbumCache(api, fetcher)

        val result = cache.get("123", AlbumSource.QOBUZ)

        assertEquals("Loveless", result.title)
        verify(fetcher).getAlbum(eq("123"))
        verifyNoInteractions(api)
    }

    @Test
    fun `youtube source routes to the YT api, not the fetcher`() = runTest {
        val api = mock<YTMusicApiClient>()
        val fetcher = mock<QobuzAlbumFetcher>()
        whenever(api.getAlbum(eq("MPRE1"))).thenReturn(detail("MPRE1", "YT Album"))
        val cache = AlbumCache(api, fetcher)

        val result = cache.get("MPRE1", AlbumSource.YOUTUBE)

        assertEquals("YT Album", result.title)
        verify(api).getAlbum(eq("MPRE1"))
        verifyNoInteractions(fetcher)
    }

    @Test
    fun `same id under different sources are cached separately`() = runTest {
        // A numeric id could in principle appear as both a YT browseId and a
        // Qobuz album id; the composite key must keep them distinct.
        val api = mock<YTMusicApiClient>()
        val fetcher = mock<QobuzAlbumFetcher>()
        whenever(api.getAlbum(eq("42"))).thenReturn(detail("42", "yt"))
        whenever(fetcher.getAlbum(eq("42"))).thenReturn(detail("42", "qobuz"))
        val cache = AlbumCache(api, fetcher)

        assertEquals("yt", cache.get("42", AlbumSource.YOUTUBE).title)
        assertEquals("qobuz", cache.get("42", AlbumSource.QOBUZ).title)
        verify(api).getAlbum(eq("42"))
        verify(fetcher).getAlbum(eq("42"))
    }

    @Test
    fun `a YouTube playlist routes to the playlist read, and an empty one is a failed load asked again`() = runTest {
        val api = mock<YTMusicApiClient>()
        val fetcher = mock<QobuzAlbumFetcher>()
        val song = TrackSummary("H-zVEnHB3_g", "Your Other Life", "Lord Huron", "Long Lost", 240.0, null)
        // First no songs (YouTube didn't answer, or answered empty), then the playlist
        whenever(api.getPlaylistAlbum(eq("VLRD1")))
            .thenReturn(detail("VLRD1", ""))
            .thenReturn(detail("VLRD1", "Coffee Shop Blend").copy(tracks = listOf(song)))
        val cache = AlbumCache(api, fetcher)

        try {
            cache.get("VLRD1", AlbumSource.YOUTUBE_PLAYLIST)
            fail("an empty playlist must throw, so the page offers Retry")
        } catch (expected: IOException) {
            // the album page shows its error card with Retry
        }
        // Not kept: Retry asks again, and gets the songs
        assertEquals(listOf(song), cache.get("VLRD1", AlbumSource.YOUTUBE_PLAYLIST).tracks)
        verify(api, times(2)).getPlaylistAlbum(eq("VLRD1"))
        verify(api, never()).getAlbum(any())
        verifyNoInteractions(fetcher)
    }
}
