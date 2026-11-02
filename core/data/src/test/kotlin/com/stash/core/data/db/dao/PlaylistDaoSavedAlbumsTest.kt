package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.album.SavedAlbums
import com.stash.core.data.album.toSavedAlbum
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
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
 * Library › Albums over a real database (owner, 2026-11-02): only saved albums are listed, each
 * with its songs counted and in album order; the Playlists pickers leave them out; and Spotify's
 * "missing playlist" cleanup never hides one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoSavedAlbumsTest {

    private lateinit var db: StashDatabase
    private val dao get() = db.playlistDao()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() { db.close() }

    // ── Saved albums only ───────────────────────────────────────────────

    @Test fun `Albums lists saved albums only, from every service`() = runTest {
        val youtubeSynced = playlist("Nevermind", MusicSource.YOUTUBE, "MPREb_nevermind")
        val savedInStash = playlist("13 Songs", MusicSource.BOTH, "album:youtube:MPREb_13")
        val savedQobuz = playlist("Repeater", MusicSource.BOTH, "album:qobuz:0060")
        val pastedSpotify = playlist("Red Medicine", MusicSource.BOTH, "album:spotify:4m28")
        playlist("My mix", MusicSource.BOTH, "custom_1")
        playlist("Qobuz playlist", MusicSource.BOTH, "album:qobuz_playlist:9")
        playlist("Spotify playlist", MusicSource.SPOTIFY, "37i9dQZF1")
        playlist("Daily Mix 1", MusicSource.SPOTIFY, "album:spotify:mix", type = PlaylistType.DAILY_MIX)
        playlist("Hidden album", MusicSource.BOTH, "album:qobuz:gone", active = false)

        val ids = dao.observeSavedAlbums().first().map { it.id }

        assertThat(ids).containsExactly(youtubeSynced, savedInStash, savedQobuz, pastedSpotify)
    }

    /** [SAVED_ALBUM] and [SavedAlbums.isSavedAlbum] must agree on every row, or a tab disagrees with a picker. */
    @Test fun `the SQL rule and the Kotlin rule agree`() = runTest {
        val rows = listOf(
            Triple(MusicSource.YOUTUBE, "MPREb_a", PlaylistType.CUSTOM),
            Triple(MusicSource.BOTH, "MPREb_b", PlaylistType.CUSTOM),
            Triple(MusicSource.BOTH, "album:youtube:MPREb_c", PlaylistType.CUSTOM),
            Triple(MusicSource.BOTH, "ALBUM:qobuz:d", PlaylistType.CUSTOM),
            Triple(MusicSource.SPOTIFY, "album:spotify:e", PlaylistType.CUSTOM),
            Triple(MusicSource.BOTH, "album:qobuz_playlist:f", PlaylistType.CUSTOM),
            Triple(MusicSource.BOTH, "custom_link_yt_g", PlaylistType.CUSTOM),
            Triple(MusicSource.YOUTUBE, "PLh", PlaylistType.CUSTOM),
            Triple(MusicSource.YOUTUBE, "MPREb_i", PlaylistType.DAILY_MIX),
            Triple(MusicSource.BOTH, "album:youtube:j", PlaylistType.STASH_MIX),
        )
        val expected = rows.filter { (source, id, type) -> SavedAlbums.isSavedAlbum(type, source, id) }
            .map { it.second }
        rows.forEach { (source, id, type) -> playlist(id, source, id, type = type) }

        assertThat(dao.observeSavedAlbums().first().map { it.sourceId }).containsExactlyElementsIn(expected)
    }

    // ── Counts and order ────────────────────────────────────────────────

    @Test fun `an album's songs are counted as its page lists them`() = runTest {
        val album = playlist("13 Songs", MusicSource.BOTH, "album:youtube:MPREb_13")
        track(1, "Waiting Room", downloaded = true)
        track(2, "Bulldog Front", downloaded = true)
        track(3, "Bad Mouth")      // queued for download
        track(4, "Burning")        // its download failed
        track(5, "Give Me the Cure") // removed from the album
        track(6, "Suggestion")     // blocked
        listOf(1L, 2L, 3L, 4L, 5L, 6L).forEachIndexed { i, id -> link(album, id, position = i) }
        dao.softDeleteTrackFromPlaylist(album, 5L)
        db.trackBlocklistDao().insert(TrackBlocklistEntity(canonicalKey = "fugazi|suggestion", artist = "Fugazi", title = "Suggestion", blockedAt = 0L, blockedFrom = "OTHER"))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = 3L, status = DownloadStatus.PENDING))
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = 4L, status = DownloadStatus.FAILED))

        val row = dao.observeSavedAlbums().first().single()

        assertThat(row.trackCount).isEqualTo(4)
        assertThat(row.downloadedCount).isEqualTo(2)
        assertThat(row.downloadingCount).isEqualTo(1)
    }

    @Test fun `an album lists its songs in album order, whatever order they arrived in`() = runTest {
        val album = playlist("13 Songs", MusicSource.YOUTUBE, "MPREb_13")
        // Liked first (newest date, highest id), then the album saved: ids and dates run backwards.
        track(30, "Waiting Room", dateAdded = 300)
        track(20, "Bulldog Front", dateAdded = 200)
        track(10, "Bad Mouth", dateAdded = 100)
        link(album, 10L, position = 2)
        link(album, 30L, position = 0)
        link(album, 20L, position = 1)

        val page = db.trackDao().getByPlaylist(album, includeStreamable = true).first().map { it.title }
        val card = dao.observeSavedAlbums().first().single().toSavedAlbum()

        assertThat(page).containsExactly("Waiting Room", "Bulldog Front", "Bad Mouth").inOrder()
        assertThat(card.songKeys).containsExactly("waiting room", "bulldog front", "bad mouth")
        assertThat(card.artist).isEqualTo("Fugazi")
    }

    @Test fun `a song is in an album once, however often it is added`() = runTest {
        val album = playlist("13 Songs", MusicSource.BOTH, "album:qobuz:13")
        track(1, "Burning")
        link(album, 1L, position = 0)
        link(album, 1L, position = 7)

        assertThat(dao.observeSavedAlbums().first().single().trackCount).isEqualTo(1)
    }

    // ── Not a playlist ──────────────────────────────────────────────────

    @Test fun `Add to playlist never offers a saved album`() = runTest {
        val mine = playlist("Road trip", MusicSource.BOTH, "custom_1")
        val qobuzPlaylist = playlist("Qobuz picks", MusicSource.BOTH, "album:qobuz_playlist:9")
        val spotifyPlaylist = playlist("Discover", MusicSource.SPOTIFY, "37i9dQZF1", syncEnabled = true)
        playlist("13 Songs", MusicSource.BOTH, "album:youtube:MPREb_13")
        playlist("Nevermind", MusicSource.YOUTUBE, "MPREb_nevermind", syncEnabled = true)

        assertThat(dao.getUserCreatedPlaylists().first().map { it.id }).containsExactly(mine, qobuzPlaylist)
        assertThat(dao.getPickablePlaylists().first().map { it.id }).containsExactly(mine, qobuzPlaylist, spotifyPlaylist)
    }

    @Test fun `Spotify's missing-playlist cleanup never hides a saved album`() = runTest {
        val gone = playlist("Old playlist", MusicSource.SPOTIFY, "37i9old")
        val album = playlist("Red Medicine", MusicSource.SPOTIFY, "album:spotify:4m28")

        val hidden = dao.deactivateMissingSpotifyCustomPlaylists(currentSourceIds = listOf("37i9new"), hasCurrentIds = true)

        assertThat(hidden).isEqualTo(1)
        assertThat(dao.getById(gone)!!.isActive).isFalse()
        assertThat(dao.getById(album)!!.isActive).isTrue()
    }

    @Test fun `a copy already on the phone, linked beside its song, counts as that song`() = runTest {
        val album = playlist("13 Songs", MusicSource.BOTH, "album:youtube:MPREb_13")
        track(1, "Waiting Room", downloaded = true)
        track(2, "Bulldog Front")
        track(3, "Bad Mouth")
        listOf(1L, 2L, 3L).forEachIndexed { i, id -> link(album, id, position = i) }
        // Download found Bulldog Front already on the phone as another row and linked it there.
        track(20, "Bulldog Front", downloaded = true)
        link(album, 20L, position = 1)
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = 3L, status = DownloadStatus.IN_PROGRESS))

        val row = dao.observeSavedAlbums().first().single()
        assertThat(row.trackCount).isEqualTo(3)
        assertThat(row.downloadedCount).isEqualTo(2)
        assertThat(row.downloadingCount).isEqualTo(1)
        assertThat(dao.observeSavedAlbumSlots(album).first().map { it.trackId to it.position })
            .containsExactly(1L to 0, 2L to 1, 20L to 1, 3L to 2)

        // The last song lands: every song is on the phone, though song 2's own row never downloads.
        db.trackDao().markAsDownloaded(3L, "/music/3.opus", 1L)
        assertThat(dao.observeSavedAlbums().first().single().toSavedAlbum().onPhone).isTrue()
        // A playlist that isn't an album has no slots.
        val mine = playlist("Road trip", MusicSource.BOTH, "custom_1")
        link(mine, 1L, position = 0)
        assertThat(dao.observeSavedAlbumSlots(mine).first()).isEmpty()
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun playlist(
        name: String,
        source: MusicSource,
        sourceId: String,
        type: PlaylistType = PlaylistType.CUSTOM,
        active: Boolean = true,
        syncEnabled: Boolean = false,
    ): Long = dao.insert(
        PlaylistEntity(name = name, source = source, sourceId = sourceId, type = type, isActive = active, syncEnabled = syncEnabled),
    )

    private suspend fun track(id: Long, title: String, downloaded: Boolean = false, dateAdded: Long = id) {
        db.trackDao().insert(
            TrackEntity(
                id = id,
                title = title,
                artist = "Fugazi",
                album = "13 Songs",
                canonicalTitle = title.lowercase(),
                canonicalArtist = "fugazi",
                isDownloaded = downloaded,
                filePath = if (downloaded) "/music/$id.opus" else null,
                dateAdded = Instant.ofEpochMilli(dateAdded),
            ),
        )
    }

    private suspend fun link(playlistId: Long, trackId: Long, position: Int) {
        dao.insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = position))
    }
}
