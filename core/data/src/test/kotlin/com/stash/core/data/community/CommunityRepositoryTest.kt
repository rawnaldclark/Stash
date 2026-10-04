package com.stash.core.data.community

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.share.ShareApiClient
import com.stash.core.data.share.SharePreference
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.model.community.CommunityMe
import com.stash.core.model.community.CommunityPost
import com.stash.core.model.community.PostTarget
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CommunityRepositoryTest {
    private lateinit var db: StashDatabase
    private lateinit var server: MockWebServer
    private lateinit var shared: SharedMixRepository
    private lateinit var repo: CommunityRepository
    private val prefs = mockk<SharePreference>(relaxed = true)
    private val key = "k".repeat(43)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        server = MockWebServer().also { it.start() }
        val base = server.url("/").toString().removeSuffix("/")
        shared = SharedMixRepository(
            db, db.sharedMixDao(), db.playlistDao(), db.trackDao(), mockk(relaxed = true),
            ShareApiClient(OkHttpClient()).apply { baseUrls = listOf(base) }, ApplicationProvider.getApplicationContext(),
        )
        repo = repository(keyStore(existingKey = key))
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    private fun keyStore(existingKey: String?) = mockk<CommunityKeyStore> {
        coEvery { key() } returns key
        coEvery { existingKey() } returns existingKey
    }

    private fun repository(keys: CommunityKeyStore, on: Boolean = true): CommunityRepository {
        val api = CommunityApiClient(OkHttpClient()).apply { baseUrls = listOf(server.url("/").toString().removeSuffix("/")) }
        val homeSections = mockk<HomeSectionsPreference> { every { communityOn } returns flowOf(on) }
        return CommunityRepository(api, keys, shared, db.playlistDao(), db.trackDao(), prefs, homeSections)
    }

    private suspend fun playlist(type: PlaylistType, songs: Int): Long {
        val id = db.playlistDao().insert(PlaylistEntity(name = "sad boy hours", source = MusicSource.BOTH, sourceId = "p_${type}_$songs", type = type))
        repeat(songs) { i ->
            val t = db.trackDao().insert(TrackEntity(title = "Song $i", artist = "A", youtubeId = "y_${type}_${songs}_$i", source = MusicSource.YOUTUBE))
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(id, t, position = i))
        }
        return id
    }

    @Test fun `a song is posted from its library row, not the player's copy`() = runBlocking<Unit> {
        val rowId = db.trackDao().insert(TrackEntity(
            title = "garden", artist = "Death Plus", isrc = "USX1", spotifyUri = "spotify:track:abc",
            albumArtUrl = "https://i.scdn.co/image/g", source = MusicSource.SPOTIFY,
        ))
        val playerCopy = Track(id = rowId, title = "garden", artist = "Death Plus", albumArtUrl = "file:///cache/x.jpg")
        val draft = repo.draft(PostTarget.Song(playerCopy)) as Draft.Ready
        assertThat(draft.kind).isEqualTo("song")
        assertThat(draft.tracks.single().isrc).isEqualTo("USX1")
        assertThat(draft.tracks.single().spotifyId).isEqualTo("abc")
        assertThat(draft.covers).containsExactly("https://i.scdn.co/image/g")
    }

    @Test fun `a song with no library row is posted as given`() = runBlocking {
        val draft = repo.draft(PostTarget.Song(Track(id = 999_999, title = "us", artist = "sincewhen", youtubeId = "yt1"))) as Draft.Ready
        assertThat(draft.tracks.single().youtubeId).isEqualTo("yt1")
    }

    @Test fun `playlists post as playlist, Daily and Stash mixes as mix`() = runBlocking {
        assertThat((repo.draft(PostTarget.Playlist(playlist(PlaylistType.CUSTOM, 2))) as Draft.Ready).kind).isEqualTo("playlist")
        assertThat((repo.draft(PostTarget.Playlist(playlist(PlaylistType.DAILY_MIX, 2))) as Draft.Ready).kind).isEqualTo("mix")
        assertThat((repo.draft(PostTarget.Playlist(playlist(PlaylistType.STASH_MIX, 2))) as Draft.Ready).kind).isEqualTo("mix")
    }

    @Test fun `an empty playlist and one over 500 songs can't be posted`() = runBlocking {
        assertThat(repo.draft(PostTarget.Playlist(playlist(PlaylistType.CUSTOM, 0)))).isEqualTo(Draft.Problem("This playlist is empty."))
        assertThat(repo.draft(PostTarget.Playlist(playlist(PlaylistType.CUSTOM, 501))))
            .isEqualTo(Draft.Problem("Too many songs to post (500 at most)."))
    }

    @Test fun `a playlist post keeps each song's art, and its name is cut to 100`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"AAAAAAAA"}"""))
        val id = db.playlistDao().insert(PlaylistEntity(name = "p".repeat(150), source = MusicSource.BOTH, sourceId = "art", type = PlaylistType.CUSTOM))
        val t = db.trackDao().insert(TrackEntity(title = "garden", artist = "Death Plus", youtubeId = "art1", albumArtUrl = "https://i.scdn.co/image/g", source = MusicSource.YOUTUBE))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(id, t, position = 0))
        val draft = repo.draft(PostTarget.Playlist(id)) as Draft.Ready
        assertThat(draft.tracks.single().artUrl).isEqualTo("https://i.scdn.co/image/g")
        repo.post(draft, "Sam")
        assertThat(server.takeRequest().body.readUtf8()).contains(""""title":"${"p".repeat(100)}"""")
    }

    @Test fun `titles are cut to 100 and names to 40 before sending, never mid-emoji, and the name is remembered`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"AAAAAAAA"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"BBBBBBBB"}"""))
        val rowId = db.trackDao().insert(TrackEntity(title = "t".repeat(300), artist = "A", youtubeId = "long1", source = MusicSource.YOUTUBE))
        val draft = repo.draft(PostTarget.Song(Track(id = rowId, title = "", artist = ""))) as Draft.Ready
        assertThat(repo.post(draft, "  " + "n".repeat(60))).isEqualTo(CommunityResult.Ok("AAAAAAAA"))
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains(""""title":"${"t".repeat(100)}"""")
        assertThat(body).contains(""""name":"${"n".repeat(40)}"""")
        assertThat(body).contains(""""track":{""")
        coVerify { prefs.setDisplayName("n".repeat(40)) }
        // The headphones are two chars; a plain take(40) would send the first one alone, as "?".
        repo.post(draft, "n".repeat(39) + "🎧")
        val emoji = server.takeRequest().body.readUtf8()
        assertThat(emoji).contains(""""name":"${"n".repeat(39)}"""")
        assertThat(emoji).doesNotContain("?")
    }

    @Test fun `a blank name is refused before anything is sent or saved`() = runBlocking {
        val draft = repo.draft(PostTarget.Song(Track(id = 999_999, title = "us", artist = "sincewhen"))) as Draft.Ready
        assertThat(repo.post(draft, "   ")).isEqualTo(CommunityResult.Rejected("bad_request"))
        assertThat(server.requestCount).isEqualTo(0)
        coVerify(exactly = 0) { prefs.setDisplayName(any()) }
    }

    @Test fun `a post and a take-down each bump the revision, a failed one doesn't`() = runBlocking {
        val draft = repo.draft(PostTarget.Song(Track(id = 999_999, title = "us", artist = "sincewhen"))) as Draft.Ready
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"AAAAAAAA"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"daily_limit"}"""))
        val before = repo.revision.value
        repo.post(draft, "Sam")
        repo.takeDown("AAAAAAAA")
        assertThat(repo.post(draft, "Sam")).isEqualTo(CommunityResult.Rejected("daily_limit"))
        assertThat(repo.revision.value).isEqualTo(before + 2)
    }

    @Test fun `every vote asks Home to reload`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"up":1,"down":0,"myVote":1}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"gone"}"""))
        val refusing = repository(keyStore(existingKey = key))
        val start = System.currentTimeMillis()
        assertThat(repo.vote("AAAAAAAA", 1)).isEqualTo(CommunityResult.Ok(VoteCounts(up = 1, down = 0, myVote = 1)))
        assertThat(repo.lastVoteAt).isAtLeast(start)
        // Set before sending, so a refused vote counts too.
        assertThat(refusing.vote("AAAAAAAA", 1)).isEqualTo(CommunityResult.Rejected("gone"))
        assertThat(refusing.lastVoteAt).isAtLeast(start)
    }

    @Test fun `Mine and the limits ask nothing before this phone has a key, and make none`() = runBlocking {
        val keys = keyStore(existingKey = null)
        val fresh = repository(keys)
        assertThat(fresh.mine()).isEqualTo(CommunityResult.Ok(emptyList<CommunityPost>()))
        assertThat(fresh.me()).isEqualTo(CommunityResult.Ok(CommunityMe(postsLeftToday = 2, spotsFree = 5, blocked = false)))
        assertThat(server.requestCount).isEqualTo(0)
        coVerify(exactly = 0) { keys.key() }
    }

    @Test fun `the feed and a post read without a key, and make none`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"posts":[]}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"gone"}"""))
        val keys = keyStore(existingKey = null)
        val fresh = repository(keys)
        fresh.feed(5); fresh.open("AAAAAAAA")
        repeat(2) { assertThat(server.takeRequest().getHeader(CommunityApiClient.KEY_HEADER)).isNull() }
        coVerify(exactly = 0) { keys.key() }
    }

    @Test fun `a take-down of a post that's already gone counts as done`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"gone"}"""))
        val before = repo.revision.value
        assertThat(repo.takeDown("AAAAAAAA")).isEqualTo(CommunityResult.Ok(Unit))
        assertThat(repo.revision.value).isEqualTo(before + 1)
    }

    @Test fun `while Community is off every call is refused before anything is sent, saved or made`() = runBlocking {
        // Every request would get an instant 204, so a missing guard fails at once (and takeDown would bump revision).
        server.dispatcher = QueueDispatcher().apply { setFailFast(MockResponse().setResponseCode(204)) }
        val keys = keyStore(existingKey = key)
        val off = repository(keys, on = false)
        val draft = off.draft(PostTarget.Song(Track(id = 999_999, title = "us", artist = "sincewhen"))) as Draft.Ready
        val results = listOf(
            off.feed(5), off.mine(), off.me(), off.open("AAAAAAAA"), off.vote("AAAAAAAA", 1), off.takeDown("AAAAAAAA"), off.post(draft, "Sam"),
        )
        assertThat(results).isEqualTo(List(7) { CommunityResult.Rejected("off") })
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(off.lastVoteAt).isEqualTo(0L)
        assertThat(off.revision.value).isEqualTo(0)
        coVerify(exactly = 0) { keys.key() }
        coVerify(exactly = 0) { prefs.setDisplayName(any()) }
    }
}
