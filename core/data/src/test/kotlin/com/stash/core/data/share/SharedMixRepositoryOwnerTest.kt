package com.stash.core.data.share

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
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
class SharedMixRepositoryOwnerTest {
    private lateinit var db: StashDatabase
    private lateinit var server: MockWebServer
    private lateinit var repo: SharedMixRepository
    private var playlistId = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer().also { it.start() }
        val api = ShareApiClient(OkHttpClient()).apply { baseUrl = server.url("/").toString().removeSuffix("/") }
        repo = SharedMixRepository(db, db.sharedMixDao(), db.playlistDao(), db.trackDao(), mockk<MusicRepository>(relaxed = true), api, ApplicationProvider.getApplicationContext())
        playlistId = db.playlistDao().insert(PlaylistEntity(name = "Ambient", source = MusicSource.BOTH, sourceId = "custom_1"))
        val t1 = db.trackDao().insert(TrackEntity(title = "One", artist = "A", isrc = "I1", albumArtUrl = "https://i.scdn.co/image/1", source = MusicSource.SPOTIFY))
        val t2 = db.trackDao().insert(TrackEntity(title = "Two", artist = "B", source = MusicSource.YOUTUBE, youtubeId = "y2", albumArtUrl = "https://evil.example/2.jpg"))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, t1, position = 0))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, t2, position = 1))
    }
    @After fun tearDown() { db.close(); server.shutdown() }

    @Test fun `share creates the link and stores an OWNER row with key and hash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        val out = repo.share(playlistId, name = "Sleep", sharedBy = "Rawn", autoUpdate = true)
        assertThat(out).isEqualTo(ShareResult.Ok("https://stash-share.rawnaldclark.workers.dev/m/Kx7Qa2pL"))
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"name\":\"Sleep\"")
        assertThat(body).contains("\"t\":\"One\"")
        assertThat(body).contains("\"covers\":[\"https://i.scdn.co/image/1\"]")
        assertThat(body).doesNotContain("evil.example") // off-list cover hosts never leave the phone
        val row = db.sharedMixDao().forPlaylist(playlistId)!!
        assertThat(row.role).isEqualTo(SharedMixEntity.ROLE_OWNER)
        assertThat(row.editKey).hasLength(43)
        assertThat(row.contentHash).isNotEmpty()
    }

    @Test fun `publish sends only when the content changed, and 410 marks REMOVED`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        repo.share(playlistId, "Sleep", null, true); server.takeRequest()
        assertThat(repo.publishIfChanged(db.sharedMixDao().forPlaylist(playlistId)!!)).isEqualTo(PublishOutcome.Unchanged)
        val t3 = db.trackDao().insert(TrackEntity(title = "Three", artist = "C", source = MusicSource.BOTH))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, t3, position = 2))
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        assertThat(repo.publishIfChanged(db.sharedMixDao().forPlaylist(playlistId)!!)).isEqualTo(PublishOutcome.Published)
        assertThat(server.takeRequest().method).isEqualTo("PUT")
        assertThat(db.sharedMixDao().forPlaylist(playlistId)!!.version).isEqualTo(2)
        db.playlistDao().updateName(playlistId, "ignored") // only the shared name matters
        val t4 = db.trackDao().insert(TrackEntity(title = "Four", artist = "D", source = MusicSource.BOTH))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, t4, position = 3))
        server.enqueue(MockResponse().setResponseCode(410))
        assertThat(repo.publishIfChanged(db.sharedMixDao().forPlaylist(playlistId)!!)).isEqualTo(PublishOutcome.Removed)
        assertThat(db.sharedMixDao().forPlaylist(playlistId)!!.status).isEqualTo(SharedMixEntity.STATUS_REMOVED)
    }

    private suspend fun addTrack(title: String, position: Int) {
        val t = db.trackDao().insert(TrackEntity(title = title, artist = "Z", source = MusicSource.BOTH))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId, t, position = position))
    }

    @Test fun `a publish from a stale snapshot never brings back a stopped share`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        repo.share(playlistId, "Sleep", null, true); server.takeRequest()
        val snapshot = db.sharedMixDao().forPlaylist(playlistId)!!
        db.sharedMixDao().delete(playlistId) // Stop sharing lands while the publish is in flight
        addTrack("Three", 2)
        server.enqueue(MockResponse().setBody("""{"version":2}"""))
        assertThat(repo.publishIfChanged(snapshot)).isEqualTo(PublishOutcome.Published)
        assertThat(db.sharedMixDao().forPlaylist(playlistId)).isNull()
    }

    @Test fun `an owner 404 is retried, never marked REMOVED`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        repo.share(playlistId, "Sleep", null, true); server.takeRequest()
        addTrack("Three", 2)
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(repo.publishIfChanged(db.sharedMixDao().forPlaylist(playlistId)!!)).isEqualTo(PublishOutcome.Failed)
        assertThat(db.sharedMixDao().forPlaylist(playlistId)!!.status).isEqualTo(SharedMixEntity.STATUS_ACTIVE)
    }

    @Test fun `stop sharing deletes remotely and locally`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        repo.share(playlistId, "Sleep", null, true); server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204))
        repo.stopSharing(playlistId)
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
        assertThat(db.sharedMixDao().forPlaylist(playlistId)).isNull()
    }

    @Test fun `buildDocument withArt keeps each song's allowed art link, and the default leaves art out`() = runBlocking {
        assertThat(repo.buildDocument(playlistId, "Ambient", null).tracks.map { it.artUrl }).containsExactly(null, null)
        assertThat(repo.buildDocument(playlistId, "Ambient", null, withArt = true).tracks.map { it.artUrl })
            .containsExactly("https://i.scdn.co/image/1", null).inOrder()
    }

    @Test fun `buildDocument cuts the shared-by name to 40 without splitting an emoji`() = runBlocking {
        assertThat(repo.buildDocument(playlistId, "Ambient", sharedBy = "n".repeat(39) + "🎧").sharedBy).isEqualTo("n".repeat(39))
    }
}
