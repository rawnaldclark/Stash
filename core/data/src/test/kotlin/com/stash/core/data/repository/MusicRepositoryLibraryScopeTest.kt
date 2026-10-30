package com.stash.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.LibraryScopePreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The owner's rule for the Library's Artists and Albums: the Download switch
 * ([StreamingPreference.enabled]) decides what they list. Stream only (true) = every song,
 * Download (false) = downloaded songs only. Covers the repository flows the tabs, the artist and
 * album pages and the card actions read, over a real Room database so the SQL and the credit
 * filter are exercised together, and checks that an open flow follows a flip of the switch.
 *
 * Stream only's "In your library" filter ([LibraryScopePreference.inLibraryOnly]) narrows the
 * same flows to exactly the songs the Songs tab lists; Download ignores it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositoryLibraryScopeTest {

    private lateinit var db: StashDatabase
    private val streamOnly = MutableStateFlow(false)
    private val inLibraryOnly = MutableStateFlow(false)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `artist page lists downloaded songs in Download and every song in Stream only`() = runTest {
        insert(1L, artist = "Drake", album = "Scorpion", downloaded = true)
        insert(2L, artist = "Drake, Future", album = "WATTBA", downloaded = false)
        insert(3L, artist = "Someone Else", album = "Mix", albumArtist = "Drake", downloaded = false)
        insert(4L, artist = "Drakeo the Ruler", album = "X", downloaded = true) // LIKE hit, not a credit

        streamOnly.value = false
        assertEquals(listOf(1L), repo().getTracksByArtist("Drake").first().map { it.id })

        streamOnly.value = true
        assertEquals(setOf(1L, 2L, 3L), repo().getTracksByArtist("Drake").first().map { it.id }.toSet())
    }

    @Test fun `album page lists the album's songs crediting the card's artist, by the same rule`() = runTest {
        insert(1L, artist = "Metro Boomin, Future", album = "HEROES & VILLAINS", downloaded = true)
        insert(2L, artist = "Metro Boomin, Travis Scott", album = "heroes & villains", downloaded = false)
        insert(3L, artist = "Don Toliver", album = "HEROES & VILLAINS", downloaded = false) // no credit
        insert(4L, artist = "Metro Boomin", album = "NOT ALL HEROES", downloaded = true) // other album

        streamOnly.value = false
        assertEquals(
            listOf(1L),
            repo().getTracksByAlbum("HEROES & VILLAINS", "Metro Boomin").first().map { it.id },
        )

        streamOnly.value = true
        assertEquals(
            setOf(1L, 2L),
            repo().getTracksByAlbum("HEROES & VILLAINS", "Metro Boomin").first().map { it.id }.toSet(),
        )
    }

    @Test fun `artist and album tabs count by the same rule`() = runTest {
        insert(1L, artist = "Drake", album = "Scorpion", downloaded = true)
        insert(2L, artist = "Drake", album = "Scorpion", downloaded = false)
        insert(3L, artist = "Drake", album = "Views", downloaded = false)

        streamOnly.value = false
        assertEquals(listOf(1), repo().getAllArtists().first().map { it.trackCount })
        assertEquals(listOf("Scorpion" to 1), repo().getAllAlbums().first().map { it.album to it.trackCount })

        streamOnly.value = true
        assertEquals(listOf(3), repo().getAllArtists().first().map { it.trackCount })
        assertEquals(
            setOf("Scorpion" to 2, "Views" to 1),
            repo().getAllAlbums().first().map { it.album to it.trackCount }.toSet(),
        )
    }

    @Test fun `an open artist page follows a flip of the switch`() = runTest {
        insert(1L, artist = "Drake", album = "Scorpion", downloaded = true)
        insert(2L, artist = "Drake", album = "Views", downloaded = false)
        streamOnly.value = false

        val seen = mutableListOf<Set<Long>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo().getTracksByArtist("Drake").collect { tracks -> seen += tracks.map { it.id }.toSet() }
        }
        awaitSize(seen, 1)
        streamOnly.value = true
        awaitSize(seen, 2)
        job.cancel()

        assertEquals(listOf(setOf(1L), setOf(1L, 2L)), seen)
    }

    // ── "In your library" (Stream only) ─────────────────────────────────

    /**
     * Drake songs, one per way a song can sit in the app: 1 downloaded, 2 in a synced Liked
     * playlist, 3 only in Daily Discover, 4 only behind a share link, 5 in a synced playlist but
     * blocked, 6 in no playlist at all.
     */
    private suspend fun insertEveryKindOfSong() {
        insert(1L, artist = "Drake", album = "Scorpion", downloaded = true)
        (2L..6L).forEach { insert(it, artist = "Drake", album = "Scorpion", downloaded = false) }
        addToPlaylist(2L, playlist(type = PlaylistType.LIKED_SONGS, syncEnabled = true))
        addToPlaylist(3L, playlist(type = PlaylistType.DAILY_MIX, syncEnabled = true))
        addToPlaylist(4L, playlist(sourceId = "share:abc123", syncEnabled = true))
        addToPlaylist(5L, playlist(syncEnabled = true))
        db.trackBlocklistDao().insert(
            TrackBlocklistEntity(
                canonicalKey = "drake|title 5",
                artist = "Drake",
                title = "Title 5",
                blockedAt = 1_000L,
                blockedFrom = "CONTEXT_MENU",
            ),
        )
    }

    @Test fun `Everything lists every song, In your library only the songs Songs lists`() = runTest {
        insertEveryKindOfSong()
        streamOnly.value = true

        inLibraryOnly.value = false
        assertEquals((1L..6L).toSet(), repo().getTracksByArtist("Drake").first().map { it.id }.toSet())
        assertEquals((1L..6L).toSet(), repo().getTracksByAlbum("Scorpion", "Drake").first().map { it.id }.toSet())

        inLibraryOnly.value = true
        val songsTab = repo().getLibraryTracksIncludingOnline().first().map { it.id }.toSet()
        assertEquals(setOf(1L, 2L), songsTab)
        assertEquals(songsTab, repo().getTracksByArtist("Drake").first().map { it.id }.toSet())
        assertEquals(songsTab, repo().getTracksByAlbum("Scorpion", "Drake").first().map { it.id }.toSet())
    }

    @Test fun `a card counts what its page lists under either choice`() = runTest {
        insertEveryKindOfSong()
        streamOnly.value = true

        for (narrowed in listOf(false, true)) {
            inLibraryOnly.value = narrowed
            val artistCard = repo().getAllArtists().first().filter { it.artist == "Drake" }.sumOf { it.trackCount }
            val albumCard = repo().getAllAlbums().first().single { it.album == "Scorpion" }.trackCount
            assertEquals("artist, inLibraryOnly=$narrowed", repo().getTracksByArtist("Drake").first().size, artistCard)
            assertEquals("album, inLibraryOnly=$narrowed", repo().getTracksByAlbum("Scorpion", "Drake").first().size, albumCard)
        }
        assertEquals(2, repo().getAllArtists().first().single().trackCount)
    }

    @Test fun `Download ignores the In your library choice`() = runTest {
        insertEveryKindOfSong()
        streamOnly.value = false

        for (narrowed in listOf(false, true)) {
            inLibraryOnly.value = narrowed
            assertEquals(listOf(1L), repo().getTracksByArtist("Drake").first().map { it.id })
            assertEquals(listOf(1L), repo().getTracksByAlbum("Scorpion", "Drake").first().map { it.id })
            assertEquals(listOf(1), repo().getAllArtists().first().map { it.trackCount })
            assertEquals(listOf(1), repo().getAllAlbums().first().map { it.trackCount })
        }
    }

    @Test fun `an open artist page follows the In your library choice`() = runTest {
        insertEveryKindOfSong()
        streamOnly.value = true
        inLibraryOnly.value = false

        val seen = mutableListOf<Set<Long>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo().getTracksByArtist("Drake").collect { tracks -> seen += tracks.map { it.id }.toSet() }
        }
        awaitSize(seen, 1)
        inLibraryOnly.value = true
        awaitSize(seen, 2)
        job.cancel()

        assertEquals(listOf((1L..6L).toSet(), setOf(1L, 2L)), seen)
    }

    /**
     * A new install's playlists are all switched off. Songs used to require the switch, so a
     * full first sync left Songs empty while Playlists listed everything (2026-10-29).
     */
    @Test fun `a song in a switched-off playlist is in Songs`() = runTest {
        insert(7L, artist = "Mazzy Star", album = "So Tonight", downloaded = false)
        addToPlaylist(7L, playlist(syncEnabled = false))
        streamOnly.value = true

        assertEquals(listOf(7L), repo().getLibraryTracksIncludingOnline().first().map { it.id })
    }

    /** Taken out of the library from the account's playlist page: its songs leave Songs too. */
    @Test fun `a song only in a playlist taken out of the library is not in Songs`() = runTest {
        insert(8L, artist = "Mazzy Star", album = "So Tonight", downloaded = false)
        val playlistId = db.playlistDao().insert(playlist())
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = 8L))
        db.playlistDao().setUserHidden(playlistId, true)
        streamOnly.value = true

        assertEquals(emptyList<Long>(), repo().getLibraryTracksIncludingOnline().first().map { it.id })
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun playlist(
        type: PlaylistType = PlaylistType.CUSTOM,
        sourceId: String = "pl_${System.nanoTime()}",
        syncEnabled: Boolean = false,
    ) = PlaylistEntity(name = "Playlist", source = MusicSource.SPOTIFY, sourceId = sourceId, type = type, syncEnabled = syncEnabled)

    private suspend fun addToPlaylist(trackId: Long, playlist: PlaylistEntity) {
        val playlistId = db.playlistDao().insert(playlist)
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId))
    }

    /** Room emits off the test scheduler; poll briefly for the expected emission. */
    private suspend fun awaitSize(seen: List<*>, size: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (seen.size < size && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(size, seen.size)
    }

    private suspend fun insert(
        id: Long,
        artist: String,
        album: String,
        downloaded: Boolean,
        albumArtist: String = "",
    ) {
        db.trackDao().insert(
            TrackEntity(
                id = id,
                title = "Title $id",
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                canonicalTitle = "title $id",
                canonicalArtist = artist.lowercase(),
                isDownloaded = downloaded,
                filePath = if (downloaded) "/music/$id.flac" else null,
                dateAdded = java.time.Instant.ofEpochMilli(id),
            ),
        )
    }

    private fun repo() = MusicRepositoryImpl(
        context = mockk(relaxed = true),
        trackDao = db.trackDao(),
        playlistDao = db.playlistDao(),
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk<StreamingPreference> { every { enabled } returns streamOnly },
        libraryScopePreference = mockk<LibraryScopePreference> { every { inLibraryOnly } returns this@MusicRepositoryLibraryScopeTest.inLibraryOnly },
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = mockk(relaxed = true),
        downloadJobs = com.stash.core.data.sync.DownloadJobRegistry(),
    )
}
