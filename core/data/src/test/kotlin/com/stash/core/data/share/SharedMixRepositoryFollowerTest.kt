package com.stash.core.data.share

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.mapper.toEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SharedMixRepositoryFollowerTest {
    private lateinit var db: StashDatabase
    private lateinit var server: MockWebServer
    private lateinit var music: MusicRepository
    private lateinit var repo: SharedMixRepository

    private fun doc(version: Int, vararg titles: String, name: String = "Ambient") = SharedMixDocument(
        id = "Kx7Qa2pL", version = version, name = name, sharedBy = "Rawn",
        tracks = titles.map { SharedTrack(it, "Artist") },
    )
    private fun docJson(d: SharedMixDocument) = ShareJson.encodeToString(SharedMixDocument.serializer(), d)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer().also { it.start() }
        music = mockk(relaxed = true)
        coEvery { music.ensureTrackPersisted(any()) } coAnswers {
            val t = firstArg<Track>()
            db.trackDao().findByCanonicalIdentity(t.title.lowercase(), t.artist.lowercase())?.id
                ?: db.trackDao().insert(t.toEntity().copy(canonicalTitle = t.title.lowercase(), canonicalArtist = t.artist.lowercase()))
        }
        // Real row, as MusicRepositoryImpl.createPlaylist makes it (a relaxed mock would return 0 → FK failure).
        coEvery { music.createPlaylist(any()) } coAnswers {
            db.playlistDao().insert(
                PlaylistEntity(name = firstArg(), source = MusicSource.BOTH, sourceId = "custom_${System.nanoTime()}",
                    type = PlaylistType.CUSTOM, syncEnabled = true),
            )
        }
        val api = ShareApiClient(OkHttpClient()).apply { baseUrl = server.url("/").toString().removeSuffix("/") }
        repo = SharedMixRepository(db, db.sharedMixDao(), db.playlistDao(), db.trackDao(), music, api, ApplicationProvider.getApplicationContext())
    }
    @After fun tearDown() { db.close(); server.shutdown() }

    private suspend fun titles(playlistId: Long) = db.playlistDao().getTracksForPlaylist(playlistId).map { it.title }

    @Test fun `follow creates a read-only BOTH playlist with download off, in order`() = runBlocking {
        val id = repo.follow(doc(1, "One", "Two"))
        val p = db.playlistDao().getById(id)!!
        assertThat(p.source).isEqualTo(MusicSource.BOTH)
        assertThat(p.type).isEqualTo(PlaylistType.CUSTOM)
        assertThat(p.sourceId).isEqualTo("share:Kx7Qa2pL")
        assertThat(p.syncEnabled).isFalse()
        assertThat(titles(id)).containsExactly("One", "Two").inOrder()
        assertThat(db.trackDao().getById(db.playlistDao().getTracksForPlaylist(id)[0].id)!!.source).isEqualTo(MusicSource.BOTH)
        assertThat(repo.follow(doc(1, "One", "Two"))).isEqualTo(id) // following twice is a no-op
    }

    @Test fun `a newer version is applied - adds, removes, reorders, renames`() = runBlocking {
        val id = repo.follow(doc(1, "One", "Two", "Three"))
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        server.enqueue(MockResponse().setBody(docJson(doc(2, "Three", "One", "Four", name = "Sleep"))))
        assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 1000L)).isEqualTo(FollowCheck.Updated)
        assertThat(titles(id)).containsExactly("Three", "One", "Four").inOrder()
        assertThat(db.playlistDao().getById(id)!!.name).isEqualTo("Sleep")
        assertThat(db.sharedMixDao().forPlaylist(id)!!.version).isEqualTo(2)
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 2000L)).isEqualTo(FollowCheck.UpToDate)
    }

    @Test fun `with download on, an update queues the new tracks`() = runBlocking {
        val id = repo.follow(doc(1, "One"))
        repo.setDownload(id, true)
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        server.enqueue(MockResponse().setBody(docJson(doc(2, "One", "Two"))))
        repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 1L)
        coVerify(exactly = 1) { music.queueDownloadsForPlaylist(id, false) } // on enable: a tap
        coVerify(exactly = 1) { music.queueDownloadsForPlaylist(id, true) } // after the update: background
    }

    @Test fun `only 410 converts to an ordinary playlist, repeated 404s never do`() = runBlocking {
        val id = repo.follow(doc(1, "One"))
        repeat(3) { i ->
            server.enqueue(MockResponse().setResponseCode(404))
            assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = i + 1L)).isEqualTo(FollowCheck.Unreachable)
            assertThat(db.sharedMixDao().forPlaylist(id)!!.status).isEqualTo(SharedMixEntity.STATUS_ACTIVE)
        }
        server.enqueue(MockResponse().setResponseCode(410))
        assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 9L)).isEqualTo(FollowCheck.Removed)
        val row = db.sharedMixDao().forPlaylist(id)!!
        assertThat(row.status).isEqualTo(SharedMixEntity.STATUS_REMOVED)
        assertThat(row.noticePending).isTrue()
        assertThat(repo.consumeRemovedNotice(id)).isEqualTo("Rawn stopped sharing this mix. You keep your copy.")
        assertThat(repo.consumeRemovedNotice(id)).isNull()
    }

    @Test fun `follow adopts an orphan share playlist left by an interrupted follow`() = runBlocking {
        val orphan = db.playlistDao().insert(
            PlaylistEntity(name = "Ambient", source = MusicSource.BOTH, sourceId = "share:Kx7Qa2pL", type = PlaylistType.CUSTOM, syncEnabled = false),
        )
        assertThat(repo.follow(doc(1, "One", "Two"))).isEqualTo(orphan)
        val row = db.sharedMixDao().forPlaylist(orphan)!!
        assertThat(row.role).isEqualTo(SharedMixEntity.ROLE_FOLLOWER)
        assertThat(row.shareId).isEqualTo("Kx7Qa2pL")
        assertThat(titles(orphan)).containsExactly("One", "Two").inOrder()
    }

    @Test fun `a 404 followed by an up-to-date check resets the missing count`() = runBlocking {
        val id = repo.follow(doc(1, "One"))
        server.enqueue(MockResponse().setResponseCode(404))
        repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 1L)
        assertThat(db.sharedMixDao().forPlaylist(id)!!.missingCount).isEqualTo(1)
        server.enqueue(MockResponse().setBody("""{"version":1}"""))
        assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 2L)).isEqualTo(FollowCheck.UpToDate)
        val row = db.sharedMixDao().forPlaylist(id)!!
        assertThat(row.missingCount).isEqualTo(0)
        assertThat(row.lastCheckedAt).isEqualTo(2L)
    }

    @Test fun `save a copy is an ordinary editable playlist with no shared row`() = runBlocking {
        val id = repo.saveCopy(doc(1, "One", "Two"))
        val p = db.playlistDao().getById(id)!!
        assertThat(p.sourceId).startsWith("custom_")
        assertThat(p.syncEnabled).isFalse()
        assertThat(titles(id)).containsExactly("One", "Two").inOrder()
        assertThat(db.sharedMixDao().forPlaylist(id)).isNull()
    }

    @Test fun `unfollow deletes only the tracks nothing else claims`() = runBlocking {
        coEvery { music.removePlaylist(any()) } coAnswers { db.playlistDao().delete(db.playlistDao().getById(firstArg<com.stash.core.model.Playlist>().id)!!) }
        val id = repo.follow(doc(1, "Loose", "Liked", "Other", "Downloaded", "Heard"))
        val ids = db.playlistDao().getTracksForPlaylist(id).associate { it.title to it.id }
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE tracks SET stash_liked_at = 1 WHERE id = ${ids["Liked"]}")
        sql.execSQL("UPDATE tracks SET is_downloaded = 1, file_path = '/x.flac' WHERE id = ${ids["Downloaded"]}")
        val other = db.playlistDao().insert(PlaylistEntity(name = "Mine", source = MusicSource.BOTH, sourceId = "custom_o", type = PlaylistType.CUSTOM))
        db.playlistDao().insertCrossRef(com.stash.core.data.db.entity.PlaylistTrackCrossRef(playlistId = other, trackId = ids["Other"]!!, position = 0))
        db.listeningEventDao().insert(com.stash.core.data.db.entity.ListeningEventEntity(trackId = ids["Heard"]!!, startedAt = 1L))

        repo.unfollow(id)

        assertThat(db.playlistDao().getById(id)).isNull()
        assertThat(db.trackDao().getById(ids["Loose"]!!)).isNull()
        for (kept in listOf("Liked", "Other", "Downloaded", "Heard")) assertThat(db.trackDao().getById(ids[kept]!!)).isNotNull()
    }

    @Test fun `an update deletes the dropped songs nothing else claims`() = runBlocking {
        val id = repo.follow(doc(1, "A", "B", "Liked"))
        val ids = db.playlistDao().getTracksForPlaylist(id).associate { it.title to it.id }
        db.openHelper.writableDatabase.execSQL("UPDATE tracks SET stash_liked_at = 1 WHERE id = ${ids["Liked"]}")
        // Every sync snapshots all memberships for "Undo last sync"; that must not count as a claim (device-found).
        db.openHelper.writableDatabase.execSQL("INSERT INTO sync_undo_memberships (sync_id, playlist_id, track_id, position, added_at, locally_added) SELECT 1, playlist_id, track_id, position, added_at, locally_added FROM playlist_tracks")
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        server.enqueue(MockResponse().setBody(docJson(doc(2, "A", "C"))))
        assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 1L)).isEqualTo(FollowCheck.Updated)
        assertThat(db.trackDao().getById(ids["B"]!!)).isNull()
        assertThat(db.trackDao().getById(ids["A"]!!)).isNotNull()
        assertThat(db.trackDao().getById(ids["Liked"]!!)).isNotNull()
        assertThat(titles(id)).containsExactly("A", "C").inOrder()
    }

    @Test fun `a newer document format is never applied`() = runBlocking {
        val id = repo.follow(doc(1, "One"))
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        server.enqueue(MockResponse().setBody(docJson(doc(2, "Two").copy(v = 2))))
        assertThat(repo.checkForUpdate(db.sharedMixDao().forPlaylist(id)!!, now = 1L)).isEqualTo(FollowCheck.Unreachable)
        assertThat(titles(id)).containsExactly("One")
        assertThat(db.sharedMixDao().forPlaylist(id)!!.version).isEqualTo(1)
    }
}
