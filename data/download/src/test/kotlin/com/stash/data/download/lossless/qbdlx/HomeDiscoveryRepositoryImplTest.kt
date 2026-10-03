package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.model.AlbumSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class HomeDiscoveryRepositoryImplTest {
    private val client = mockk<QbdlxApiClient>()
    private lateinit var repo: HomeDiscoveryRepositoryImpl

    @Before fun setup() {
        repo = HomeDiscoveryRepositoryImpl(client)
    }

    @Test fun `newReleases maps items to AlbumSummary with QOBUZ source`() = runTest {
        coEvery { client.getFeaturedAlbums("new-releases-full", null, any()) } returns
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

    /**
     * #481: Top Albums lists singles too (live 2026-10-03: six of 50 best-sellers had
     * one or two tracks), and Qobuz sends no release type. The track count is how the
     * album screen tells a single from an album.
     */
    @Test fun `rows carry the release's track count, and none when Qobuz sends none`() = runTest {
        coEvery { client.getFeaturedAlbums("best-sellers", null, any()) } returns listOf(
            QbdlxAlbumItem(id = "s1", title = "Good Luck, Babe!", artist = QbdlxPerformer(name = "Chappell Roan"), tracks_count = 1),
            QbdlxAlbumItem(id = "a1", title = "T", artist = QbdlxPerformer(name = "AR")),
        )

        val out = repo.topAlbums(genreId = null)

        assertThat(out.map { it.trackCount }).containsExactly(1, null).inOrder()
    }

    @Test fun `second call within TTL is served from cache`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any()) } returns emptyList()
        repo.topAlbums(null); repo.topAlbums(null)
        coVerify(exactly = 1) { client.getFeaturedAlbums("best-sellers", null, any()) }
    }

    @Test fun `different genre is a separate cache key`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any()) } returns emptyList()
        repo.topAlbums(null); repo.topAlbums(112)
        coVerify(exactly = 2) { client.getFeaturedAlbums("best-sellers", any(), any()) }
    }

    @Test fun `network error yields empty list, not a throw`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any()) } throws QbdlxApiException(500)
        assertThat(repo.newReleases(null)).isEmpty()
    }

    @Test fun `auth failure yields empty list and OK status (no token concept)`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any()) } throws QbdlxAuthException(401)
        assertThat(repo.status.value).isEqualTo(com.stash.core.model.discovery.QobuzDiscoveryStatus.OK)
        assertThat(repo.newReleases(null)).isEmpty()
        assertThat(repo.status.value).isEqualTo(com.stash.core.model.discovery.QobuzDiscoveryStatus.OK)
    }

    @Test fun `IOException sets NO_INTERNET`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any()) } throws java.io.IOException("offline")
        assertThat(repo.newReleases(null)).isEmpty()
        assertThat(repo.status.value).isEqualTo(com.stash.core.model.discovery.QobuzDiscoveryStatus.NO_INTERNET)
    }

    @Test fun `an offline failure is not cached - the next call retries and recovers`() = runTest {
        coEvery { client.getFeaturedAlbums(any(), any(), any()) } throws java.io.IOException("offline") andThen emptyList()
        assertThat(repo.newReleases(null)).isEmpty()
        assertThat(repo.status.value).isEqualTo(com.stash.core.model.discovery.QobuzDiscoveryStatus.NO_INTERNET)
        assertThat(repo.newReleases(null)).isEmpty() // retried, not served from cache
        assertThat(repo.status.value).isEqualTo(com.stash.core.model.discovery.QobuzDiscoveryStatus.OK)
        coVerify(exactly = 2) { client.getFeaturedAlbums("new-releases-full", null, any()) }
    }

    @Test fun `browsePlaylists passes offset+limit through and maps`() = runTest {
        coEvery { client.getFeaturedPlaylists(133, 30, 60) } returns listOf(
            QbdlxPlaylistItem(id = 9, name = "P", owner = QbdlxOwner("Qobuz"), tracks_count = 12, images300 = listOf("i")),
        )
        val out = repo.browsePlaylists(genreId = 133, offset = 60, limit = 30)
        assertThat(out.single().id).isEqualTo("9")
        assertThat(out.single().trackCount).isEqualTo(12)
    }
}
