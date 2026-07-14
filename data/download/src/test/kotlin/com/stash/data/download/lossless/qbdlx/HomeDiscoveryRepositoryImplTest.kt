package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.model.AlbumSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class HomeDiscoveryRepositoryImplTest {
    private val client = mockk<QbdlxApiClient>()
    private val store = mockk<QbdlxCredentialStore>(relaxed = true)
    private lateinit var repo: HomeDiscoveryRepositoryImpl

    @Before fun setup() {
        coEvery { store.activeToken() } returns "tok"
        repo = HomeDiscoveryRepositoryImpl(client, store)
    }

    @Test fun `newReleases maps items to AlbumSummary with QOBUZ source`() = runTest {
        coEvery { client.getFeaturedAlbums("new-releases-full", null, "tok", any()) } returns
            listOf(
                QbdlxAlbumItem(
                    id = "a1", title = "T", artist = QbdlxPerformer(name = "AR"),
                    image = QbdlxImage(large = "L"), release_date_original = "2026-01-02",
                ),
            )
        val out = repo.newReleases(genreId = null)
        assertThat(out.single().id).isEqualTo("a1")
        assertThat(out.single().source).isEqualTo(AlbumSource.QOBUZ)
        assertThat(out.single().year).isEqualTo("2026")
        assertThat(out.single().thumbnailUrl).isEqualTo("L")
    }

    @Test fun `second call within TTL is served from cache`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any(), any()) } returns emptyList()
        repo.topAlbums(null); repo.topAlbums(null)
        coVerify(exactly = 1) { client.getFeaturedAlbums("best-sellers", null, "tok", any()) }
    }

    @Test fun `different genre is a separate cache key`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any(), any()) } returns emptyList()
        repo.topAlbums(null); repo.topAlbums(112)
        coVerify(exactly = 2) { client.getFeaturedAlbums("best-sellers", any(), "tok", any()) }
    }

    @Test fun `network error yields empty list, not a throw`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any(), any()) } throws QbdlxApiException(500)
        assertThat(repo.newReleases(null)).isEmpty()
    }

    @Test fun `401 rotates the token then retries once`() = runTest {
        coEvery { store.activeToken() } returnsMany listOf("dead", "live")
        coEvery { client.getFeaturedAlbums(any(), any(), "dead", any()) } throws QbdlxAuthException(401)
        coEvery { client.getFeaturedAlbums(any(), any(), "live", any()) } returns emptyList()
        repo.newReleases(null)
        verify { store.markDead("dead") }
    }

    @Test fun `no live token yields empty list`() = runTest {
        coEvery { store.activeToken() } returns null
        assertThat(repo.communityPlaylists(null)).isEmpty()
    }

    @Test fun `browsePlaylists passes offset+limit through and maps`() = runTest {
        coEvery { client.getFeaturedPlaylists(133, "tok", 30, 60) } returns listOf(
            QbdlxPlaylistItem(id = 9, name = "P", owner = QbdlxOwner("Qobuz"), tracks_count = 12, images300 = listOf("i")),
        )
        val out = repo.browsePlaylists(genreId = 133, offset = 60, limit = 30)
        assertThat(out.single().id).isEqualTo("9")
        assertThat(out.single().trackCount).isEqualTo(12)
    }
}
