package com.stash.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.auth.soundcloud.*
import com.stash.core.auth.store.EncryptedTokenStore
import com.stash.core.data.db.StashDatabase
import com.stash.core.model.PlaylistType
import com.stash.data.soundcloud.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SoundCloudPlaylistsFetcherTest {
    private lateinit var db: StashDatabase
    private lateinit var client: SoundCloudClient
    private lateinit var accounts: SoundCloudAccountManager
    private lateinit var fetcher: SoundCloudPlaylistsFetcher
    private val session = SoundCloudSession("7", "Listener", oauthToken = "secret", connectionId = "current")
    private val stored = MutableStateFlow<SoundCloudSession?>(session)
    private val own = SoundCloudPlaylist("11", "My set", "https://soundcloud.com/artist/sets/set", null, false)
    private val mix = SoundCloudPlaylist("soundcloud:system-playlists:your-moods:7:1", "Your Mix 1",
        "https://soundcloud.com/discover/sets/your-moods:7:1", null, true)
    private fun track(id: String) = SoundCloudTrack(id, "https://soundcloud.com/artist/song-$id",
        "Song $id", "Artist", 180000, null, null, false, emptyList(), null)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java).build()
        client = mockk()
        accounts = SoundCloudAccountManager(mockk<EncryptedTokenStore> {
            every { soundCloudSession } returns stored
            coEvery { clearSoundCloud() } answers { stored.value = null }
        }, client)
        fetcher = SoundCloudPlaylistsFetcher(client, accounts, db)
        coEvery { client.playlist(any(), "secret") } returns JsonObject(emptyMap())
        coEvery { client.playlistTracks(any(), "secret") } returns listOf(track("2"), track("1"))
    }
    @After fun cleanup() { db.close() }

    @Test fun `owned playlist snapshots retain account scope exact ids and service order`() = runTest {
        coEvery { client.ownPlaylists("7", "secret") } returns listOf(own)
        assertEquals(1, fetcher.fetchPlaylists(1, session).published)
        val p = db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(1).single()
        assertEquals(SoundCloudPlaylistIds.playlist("7", "11"), p.sourcePlaylistId)
        assertEquals(PlaylistType.CUSTOM, p.playlistType)
        assertEquals("current", p.soundCloudConnectionId)
        assertEquals(listOf("2", "1"), db.remoteSnapshotDao().getTrackSnapshotsByPlaylistId(p.id).map { it.soundCloudId })
    }

    @Test fun `personal mix snapshots use regenerating mix semantics`() = runTest {
        coEvery { client.personalMixes("7", "secret") } returns listOf(mix)
        assertEquals(1, fetcher.fetchMixes(1, session).published)
        val p = db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(1).single()
        assertEquals(PlaylistType.DAILY_MIX, p.playlistType)
        assertEquals(SoundCloudPlaylistIds.mix("7", mix.id), p.sourcePlaylistId)
    }

    @Test fun `public profile never requests authenticated mixes`() = runTest {
        assertTrue(fetcher.fetchMixes(1, session.copy(oauthToken = null)).skipped)
        coVerify(exactly = 0) { client.personalMixes(any(), any()) }
    }

    @Test fun `failed hydration publishes no partial playlist`() = runTest {
        coEvery { client.ownPlaylists("7", "secret") } returns listOf(own)
        coEvery { client.playlistTracks(any(), any()) } throws SoundCloudException(500)
        assertEquals(1, fetcher.fetchPlaylists(1, session).failed)
        assertTrue(db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(1).isEmpty())
    }

    @Test fun `healthy playlist is committed before fetching the next and failures remain isolated`() = runTest {
        coEvery { client.ownPlaylists("7", "secret") } returns listOf(own, own.copy(id = "22"))
        coEvery { client.playlist("22", "secret") } coAnswers {
            assertEquals(1, db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(1).size)
            throw SoundCloudException(500)
        }
        val result = fetcher.fetchPlaylists(1, session)
        assertEquals(1, result.published)
        assertEquals(1, result.failed)
        assertEquals(1, db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(1).size)
    }

    @Test fun `account changed while hydrating cannot publish old playlists`() = runTest {
        coEvery { client.ownPlaylists("7", "secret") } returns listOf(own)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { client.playlistTracks(any(), any()) } coAnswers { started.complete(Unit); release.await(); listOf(track("1")) }
        val result = async { fetcher.fetchPlaylists(1, session) }
        started.await(); accounts.disconnect(); release.complete(Unit)
        assertTrue(result.await().skipped)
        assertTrue(db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(1).isEmpty())
    }

    @Test fun `cancelled hydration propagates without writing snapshots`() = runTest {
        coEvery { client.ownPlaylists("7", "secret") } returns listOf(own)
        coEvery { client.playlistTracks(any(), any()) } throws CancellationException()
        try { fetcher.fetchPlaylists(1, session); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertTrue(db.remoteSnapshotDao().getPlaylistSnapshotsBySyncId(1).isEmpty())
    }

    @Test fun `account matching does not leak another account or link copies into management`() {
        assertTrue(SoundCloudPlaylistIds.belongsTo(SoundCloudPlaylistIds.mix("7", mix.id), "7"))
        assertTrue(SoundCloudPlaylistIds.belongsTo(SoundCloudPlaylistIds.playlist("7", "11"), "7"))
        assertFalse(SoundCloudPlaylistIds.belongsTo(SoundCloudPlaylistIds.playlist("77", "11"), "7"))
        assertFalse(SoundCloudPlaylistIds.belongsTo("custom_link_soundcloud_playlist_11", "7"))
    }
}
