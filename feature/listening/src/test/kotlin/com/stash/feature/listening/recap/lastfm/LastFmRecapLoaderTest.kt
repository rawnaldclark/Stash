package com.stash.feature.listening.recap.lastfm

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.lastfm.recap.LastFmRecapAccount
import com.stash.core.data.lastfm.recap.LastFmRecapException
import com.stash.core.data.lastfm.recap.LastFmRecapRepository
import com.stash.core.data.lastfm.recap.LastFmRecapSpan
import com.stash.core.data.lastfm.recap.LastFmRecapUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.YearMonth

/**
 * The Last.fm recap's loader, and the owner rule it lives under: it never writes Stash's library
 * or listening. The DAOs are STRICT mocks with only their reads stubbed — any other call (every
 * TrackDao write, play-count bump or insert) throws — and confirmVerified proves nothing else
 * was called. The loader has no listening_events DAO at all.
 */
class LastFmRecapLoaderTest {

    private val trackDao = mockk<TrackDao>().also { coEvery { it.getAllForIntegrityScan() } returns listOf(LastFmFixtures.localTaro) }
    private val artistImages = mockk<ArtistImageDao>().also { every { it.observeAll() } returns flowOf(emptyList()) }
    private val repository = mockk<LastFmRecapRepository>().also { repo ->
        coEvery { repo.scrobbles(any(), any(), any(), any(), any()) } returns LastFmFixtures.scrobbles()
        coEvery { repo.durations(any(), any()) } returns emptyMap()
        coEvery { repo.genreIndex(any(), any()) } returns ArtistGenreIndex.EMPTY
    }

    private fun loader(user: LastFmRecapUser?) = LastFmRecapLoader(
        account = mockk<LastFmRecapAccount>().also { every { it.user } returns flowOf(user) },
        repository = repository,
        trackDao = trackDao,
        artistImages = artistImages,
        zone = { LastFmFixtures.zone },
    )

    @Test fun `builds the recap reading the library once, writing nothing`() = runTest {
        val load = loader(LastFmRecapUser("rawnclark", null)).load(LastFmFixtures.september, LastFmFixtures.today) {}
        val data = (load as LastFmRecapLoad.Ready).data
        assertThat(data.totals.plays).isEqualTo(30)
        assertThat(data.topSongs.first().track.id).isEqualTo(7L)
        coVerify(exactly = 1) { trackDao.getAllForIntegrityScan() }
        confirmVerified(trackDao)
        verify(exactly = 1) { artistImages.observeAll() }
        confirmVerified(artistImages)
    }

    @Test fun `asks for the period's months with the account's own session key`() = runTest {
        loader(LastFmRecapUser("rawnclark", "sk")).load(LastFmFixtures.september, LastFmFixtures.today) {}
        coVerify { repository.scrobbles("rawnclark", LastFmRecapSpan.Month(YearMonth.of(2026, 9)), LastFmFixtures.zone, "sk", any()) }
    }

    @Test fun `not connected - nothing read at all`() = runTest {
        assertThat(loader(null).load(LastFmFixtures.september, LastFmFixtures.today) {}).isEqualTo(LastFmRecapLoad.NotConnected)
        confirmVerified(trackDao, artistImages, repository)
    }

    @Test fun `a private profile is a calm failure, the library untouched`() = runTest {
        coEvery { repository.scrobbles(any(), any(), any(), any(), any()) } throws LastFmRecapException(LastFmRecapException.Reason.PRIVATE)
        val load = loader(LastFmRecapUser("hidden", null)).load(LastFmFixtures.september, LastFmFixtures.today) {}
        assertThat(load).isEqualTo(LastFmRecapLoad.Failed(LastFmRecapException.Reason.PRIVATE))
        confirmVerified(trackDao)
    }

    @Test fun `too few scrobbles - not enough, before any look-up`() = runTest {
        coEvery { repository.scrobbles(any(), any(), any(), any(), any()) } returns LastFmFixtures.scrobbles().take(5)
        val load = loader(LastFmRecapUser("u", null)).load(LastFmFixtures.september, LastFmFixtures.today) {}
        assertThat(load).isEqualTo(LastFmRecapLoad.NotEnough(5))
        coVerify(exactly = 0) { repository.durations(any(), any()) }
        confirmVerified(trackDao)
    }
}
