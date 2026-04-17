package com.stash.core.data.cache

import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumDetail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumCacheTest {

    private fun detail(id: String) = AlbumDetail(
        id = id, title = "T", artist = "A", artistId = null,
        thumbnailUrl = null, year = null, tracks = emptyList(), moreByArtist = emptyList(),
    )

    @Test
    fun `miss fetches and caches`() = runTest {
        val api = mock<YTMusicApiClient>()
        whenever(api.getAlbum(eq("X"))).thenReturn(detail("X"))
        val cache = AlbumCache(api)

        val result = cache.get("X")

        assertEquals("X", result.id)
        verify(api).getAlbum(eq("X"))
        verifyNoMoreInteractions(api)
    }

    @Test
    fun `hit within TTL returns cached without second network call`() = runTest {
        val api = mock<YTMusicApiClient>()
        whenever(api.getAlbum(eq("X"))).thenReturn(detail("X"))
        val cache = AlbumCache(api)

        val first = cache.get("X")
        val second = cache.get("X")

        assertSame(first, second)
        verify(api).getAlbum(eq("X"))
        verifyNoMoreInteractions(api)
    }

    @Test
    fun `hit past TTL refetches`() = runTest {
        val api = mock<YTMusicApiClient>()
        val first = detail("X")
        val second = first.copy(title = "T2")
        whenever(api.getAlbum(eq("X"))).thenReturn(first, second)

        // Inject a clock we can advance.
        var fakeNow = 0L
        val cache = object : AlbumCache(api) { override fun now() = fakeNow }

        val got1 = cache.get("X")
        fakeNow = AlbumCache.TTL_MS + 1
        val got2 = cache.get("X")

        assertEquals("T", got1.title)
        assertEquals("T2", got2.title)
        verify(api, times(2)).getAlbum(eq("X"))
    }

    @Test
    fun `invalidate evicts`() = runTest {
        val api = mock<YTMusicApiClient>()
        val a = detail("X")
        val b = a.copy(title = "T2")
        whenever(api.getAlbum(eq("X"))).thenReturn(a, b)
        val cache = AlbumCache(api)

        cache.get("X")
        cache.invalidate("X")
        val afterInvalidate = cache.get("X")

        assertEquals("T2", afterInvalidate.title)
    }

    @Test
    fun `concurrent gets for same key result in one fetch`() = runTest {
        val api = mock<YTMusicApiClient>()
        val hang = CompletableDeferred<AlbumDetail>()
        // getAlbum is suspend — use doSuspendableAnswer (NOT thenAnswer, which can't call .await).
        whenever(api.getAlbum(eq("X"))).doSuspendableAnswer { hang.await() }
        val cache = AlbumCache(api)

        val j1 = async { cache.get("X") }
        val j2 = async { cache.get("X") }
        hang.complete(detail("X"))

        j1.await(); j2.await()
        verify(api).getAlbum(eq("X"))
        verifyNoMoreInteractions(api)
    }
}
