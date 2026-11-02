package com.stash.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.LibraryScopePreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Library › Albums as the repository hands it to the tab (owner, 2026-11-02), over a real
 * database: saved albums only, one card for an album saved on two services, and in Download mode
 * (fully offline) only an album whose songs are all on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositorySavedAlbumsTest {

    private lateinit var db: StashDatabase
    private val streamOnly = MutableStateFlow(true)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() { db.close() }

    private val fugazi = listOf("Waiting Room", "Bulldog Front", "Bad Mouth", "Burning", "Give Me the Cure")

    @Test fun `an album saved on YouTube Music and in Stash is one card, the copy on the phone kept`() = runTest {
        // YouTube Music's saved copy, synced and downloaded.
        val synced = album("13 Songs", MusicSource.YOUTUBE, "MPREb_13", fugazi, firstTrackId = 1, downloaded = true)
        // The same album saved again from its Qobuz page, newer and not downloaded.
        album("13 Songs", MusicSource.BOTH, "album:qobuz:13", fugazi, firstTrackId = 10, dateAdded = 99)
        // The owner's own playlist and a mix-only song never make a card.
        db.playlistDao().insert(PlaylistEntity(name = "Road trip", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM))

        val cards = repo().getLibraryAlbums().first()

        assertThat(cards.map { it.playlistId }).containsExactly(synced)
        assertThat(cards.single().trackCount).isEqualTo(5)
        assertThat(cards.single().artist).isEqualTo("Fugazi")
        // The album page still finds both copies.
        assertThat(repo().observeSavedAlbums().first()).hasSize(2)
    }

    @Test fun `Download mode lists an album only once every song is on the phone`() = runTest {
        val complete = album("Repeater", MusicSource.BOTH, "album:qobuz:rep", fugazi, firstTrackId = 1, downloaded = true)
        val half = album("In on the Kill Taker", MusicSource.BOTH, "album:qobuz:kill", listOf("Facet Squared", "Public Witness"), firstTrackId = 10)
        db.trackDao().markAsDownloaded(10L, "/music/10.opus", 1L) // one of two on the phone
        val downloading = album("Red Medicine", MusicSource.BOTH, "album:qobuz:red", listOf("Do You Like Me", "Bed for the Scraping"), firstTrackId = 20)
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = 21L, status = DownloadStatus.IN_PROGRESS))
        db.trackDao().markAsDownloaded(20L, "/music/20.opus", 1L)

        streamOnly.value = false
        val download = repo().getLibraryAlbums().first()
        streamOnly.value = true
        val stream = repo().getLibraryAlbums().first()

        // Owner, 2026-11-02: an album still downloading waits until it is complete.
        assertThat(download.map { it.playlistId }).containsExactly(complete)
        assertThat(stream.map { it.playlistId }).containsExactly(complete, half, downloading)
    }

    @Test fun `a merged card carries both copies, and a hidden synced copy leaves the tab`() = runTest {
        val synced = album("13 Songs", MusicSource.YOUTUBE, "MPREb_13", fugazi, firstTrackId = 1)
        val saved = album("13 Songs", MusicSource.BOTH, "album:youtube:MPREb_13", fugazi, firstTrackId = 10, dateAdded = 99)

        assertThat(repo().getLibraryAlbums().first().single().copyIds).containsExactly(synced, saved)

        repo().hidePlaylist(synced)
        val hidden = db.playlistDao().getById(synced)!!
        assertThat(hidden.userHidden).isTrue()
        assertThat(hidden.isActive).isFalse()
        assertThat(repo().getLibraryAlbums().first().single().copyIds).containsExactly(saved)
    }

    @Test fun `a playlist's page lists every row, only an album's page folds copies`() = runTest {
        val mine = db.playlistDao().insert(PlaylistEntity(name = "Road trip", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM))
        listOf(1L to "Waiting Room", 2L to "Waiting Room").forEach { (id, title) ->
            db.trackDao().insert(TrackEntity(id = id, title = title, artist = "Fugazi", canonicalTitle = title.lowercase(), canonicalArtist = "fugazi"))
        }
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = mine, trackId = 1L, position = 0))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = mine, trackId = 2L, position = 0))

        // Not an album: its two rows stay two rows (the slot fold never runs).
        assertThat(repo().getSongsByPlaylist(mine).first().map { it.id }).containsExactly(1L, 2L)
    }

    @Test fun `the Add to playlist list has no saved albums`() = runTest {
        album("13 Songs", MusicSource.BOTH, "album:youtube:MPREb_13", fugazi, firstTrackId = 1)
        val mine = db.playlistDao().insert(PlaylistEntity(name = "Road trip", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM))

        assertThat(repo().getUserCreatedPlaylists().first().map { it.id }).containsExactly(mine)
        assertThat(repo().getPickablePlaylists().first().map { it.id }).containsExactly(mine)
    }

    @Test fun `an album lists each song once after Download links a copy already on the phone`() = runTest {
        val album = album("Repeater", MusicSource.BOTH, "album:qobuz:rep", fugazi, firstTrackId = 1)
        // Download found two songs already on the phone as other rows and linked them where the songs sit.
        listOf(2 to 101L, 4 to 102L).forEach { (position, copyId) ->
            val title = fugazi[position]
            db.trackDao().insert(
                TrackEntity(
                    id = copyId, title = title, artist = "Fugazi", canonicalTitle = title.lowercase(),
                    canonicalArtist = "fugazi", isDownloaded = true, filePath = "/music/$copyId.opus",
                ),
            )
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = album, trackId = copyId, position = position))
        }

        streamOnly.value = true
        val online = repo().getShownTracksByPlaylist(album).first()
        assertThat(online.map { it.title }).containsExactlyElementsIn(fugazi).inOrder()
        assertThat(online.map { it.id }).containsExactly(1L, 2L, 101L, 4L, 102L).inOrder()
        // Saves, syncs and download removal still see every row.
        assertThat(repo().getTracksByPlaylist(album).first()).hasSize(7)

        // The other three land: Download mode lists the album, its page every song once.
        listOf(1L, 2L, 4L).forEach { db.trackDao().markAsDownloaded(it, "/music/$it.opus", 1L) }
        streamOnly.value = false
        assertThat(repo().getLibraryAlbums().first().map { it.playlistId }).containsExactly(album)
        assertThat(repo().getShownTracksByPlaylist(album).first().map { it.title }).containsExactlyElementsIn(fugazi).inOrder()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** A saved album holding [songs] in order, track ids from [firstTrackId]. */
    private suspend fun album(
        name: String,
        source: MusicSource,
        sourceId: String,
        songs: List<String>,
        firstTrackId: Long,
        downloaded: Boolean = false,
        dateAdded: Long = 1,
    ): Long {
        val playlistId = db.playlistDao().insert(
            PlaylistEntity(
                name = name, source = source, sourceId = sourceId, type = PlaylistType.CUSTOM,
                dateAdded = Instant.ofEpochMilli(dateAdded),
            ),
        )
        songs.forEachIndexed { i, title ->
            val id = firstTrackId + i
            db.trackDao().insert(
                TrackEntity(
                    id = id,
                    title = title,
                    artist = "Fugazi",
                    album = name,
                    canonicalTitle = title.lowercase(),
                    canonicalArtist = "fugazi",
                    isDownloaded = downloaded,
                    filePath = if (downloaded) "/music/$id.opus" else null,
                ),
            )
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = id, position = i))
        }
        return playlistId
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
        libraryScopePreference = mockk<LibraryScopePreference> { every { inLibraryOnly } returns MutableStateFlow(false) },
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = mockk(relaxed = true),
        downloadJobs = com.stash.core.data.sync.DownloadJobRegistry(),
    )
}
