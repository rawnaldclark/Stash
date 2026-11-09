package com.stash.core.data.community

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
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
import com.stash.core.model.share.SharedTrack
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
    private val heard = mockk<CommunityHeard>(relaxed = true) {
        coEvery { beforeVote(any()) } returns true
        coEvery { forget(any()) } returns true
    }

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

    private fun repository(keys: CommunityKeyStore, on: Boolean = true, marks: CommunityHeard = heard, http: OkHttpClient = OkHttpClient()): CommunityRepository {
        val api = CommunityApiClient(http).apply { baseUrls = listOf(server.url("/").toString().removeSuffix("/")) }
        val homeSections = mockk<HomeSectionsPreference> { every { communityOn } returns flowOf(on) }
        val history = CommunityListenHistory(db.trackDao(), db.listeningEventDao())
        return CommunityRepository(api, keys, shared, db.playlistDao(), db.trackDao(), prefs, homeSections, marks, history)
    }

    /** This phone's real marks over [http], kept in a file of their own; [scope] runs their background sends. */
    private fun realMarks(keys: CommunityKeyStore, http: OkHttpClient, scope: CoroutineScope): CommunityHeard {
        val file = File.createTempFile("community_heard", ".preferences_pb").also { it.delete(); it.deleteOnExit() }
        val api = CommunityApiClient(http).apply { baseUrls = listOf(server.url("/").toString().removeSuffix("/")) }
        return CommunityHeard(PreferenceDataStoreFactory.create { file }, api, keys, MutableStateFlow(true), scope)
    }

    @Test fun `a Worker without the heard route can't want a listen, so the vote goes and the mark stays queued`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val keys = keyStore(existingKey = key)
        val marks = realMarks(keys, OkHttpClient(), scope)
        val repo = repository(keys, marks = marks)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}""")) // the listen's own send
        marks.mark("AAAAAAAA")
        scope.coroutineContext.job.children.forEach { it.join() }
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}""")) // again before the vote
        server.enqueue(MockResponse().setBody("""{"up":1,"down":0,"myVote":1}"""))
        assertThat(repo.vote("AAAAAAAA", 1)).isEqualTo(CommunityResult.Ok(VoteCounts(up = 1, down = 0, myVote = 1)))
        assertThat(List(3) { server.takeRequest().path }).containsExactly(
            "/v1/community/posts/AAAAAAAA/heard", "/v1/community/posts/AAAAAAAA/heard", "/v1/community/posts/AAAAAAAA/vote",
        ).inOrder()
        // Still queued: the next read sends it.
        server.enqueue(MockResponse().setResponseCode(204))
        marks.retry()
        scope.coroutineContext.job.children.forEach { it.join() }
        assertThat(server.takeRequest().path).isEqualTo("/v1/community/posts/AAAAAAAA/heard")
    }

    @Test fun `a heard mark that times out holds the vote back`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val keys = keyStore(existingKey = key)
        val slow = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        val marks = realMarks(keys, slow, scope)
        val repo = repository(keys, marks = marks, http = slow)
        server.enqueue(MockResponse().setResponseCode(503)) // the listen's own send fails: it waits
        marks.mark("AAAAAAAA")
        scope.coroutineContext.job.children.forEach { it.join() }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertThat(repo.vote("AAAAAAAA", 1)).isEqualTo(CommunityResult.Rejected(CommunityRepository.VOTE_NOT_NOW))
        assertThat(server.requestCount).isEqualTo(2) // the two heard sends; no vote
        assertThat(marks.ids.first()).containsExactly("AAAAAAAA")
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

    @Test fun `a vote whose listen can't reach the Worker yet isn't sent, and says try again`() = runBlocking<Unit> {
        coEvery { heard.beforeVote("AAAAAAAA") } returns false
        assertThat(repo.vote("AAAAAAAA", 1)).isEqualTo(CommunityResult.Rejected(CommunityRepository.VOTE_NOT_NOW))
        assertThat(server.requestCount).isEqualTo(0)
        // Taking a vote back needs no listen.
        server.enqueue(MockResponse().setBody("""{"up":0,"down":0,"myVote":0}"""))
        assertThat(repo.vote("AAAAAAAA", 0)).isEqualTo(CommunityResult.Ok(VoteCounts(up = 0, down = 0, myVote = 0)))
        coVerify(exactly = 1) { heard.beforeVote("AAAAAAAA") }
    }

    @Test fun `listen_first forgets a mark the Worker had, and keeps one it hadn't (try again)`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"listen_first"}"""))
        assertThat(repo.vote("AAAAAAAA", -1)).isEqualTo(CommunityResult.Rejected(CommunityRepository.LISTEN_FIRST))
        coVerify { heard.forget("AAAAAAAA") }
        coEvery { heard.forget("BBBBBBBB") } returns false
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"listen_first"}"""))
        assertThat(repo.vote("BBBBBBBB", 1)).isEqualTo(CommunityResult.Rejected(CommunityRepository.VOTE_NOT_NOW))
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

    // -- Heard from the listening history ----------------------------------------------------------------------------

    /** The song as this phone's library has it, and a listen of it that counted, started at [at]. */
    private suspend fun listenedTo(title: String, artist: String, youtubeId: String? = null, at: Long) {
        val id = db.trackDao().insert(TrackEntity(
            title = title, artist = artist, youtubeId = youtubeId, source = MusicSource.YOUTUBE,
            canonicalTitle = TrackCanonical.title(title), canonicalArtist = TrackCanonical.artist(artist),
        ))
        db.listeningEventDao().insert(ListeningEventEntity(trackId = id, startedAt = at, completedAt = at + 120_000))
    }

    private val posted = 1_762_000_000_000L
    private val hour = 3_600_000L
    private val gardenPost = CommunityPost(
        id = "AAAAAAAA", kind = "song", title = "garden", name = "Sam", count = 1, artist = "Death Plus",
        createdAt = posted, up = 1, down = 0, track = SharedTrack("garden", "Death Plus", youtubeId = "9Vz"),
    )

    @Test fun `the owner's case - a song played in full from the library hours after it was posted counts, and is marked`() = runBlocking<Unit> {
        listenedTo("garden", "Death Plus", youtubeId = "9Vz", at = posted + 3 * hour)
        assertThat(repo.heardFromHistory(gardenPost)).isTrue()
        coVerify(exactly = 1) { heard.mark("AAAAAAAA") }
        assertThat(server.requestCount).isEqualTo(0) // the opened post carries its song
    }

    @Test fun `a song played only before the post went up doesn't count, and nothing is marked`() = runBlocking<Unit> {
        listenedTo("garden", "Death Plus", youtubeId = "9Vz", at = posted - hour)
        assertThat(repo.heardFromHistory(gardenPost)).isFalse()
        coVerify(exactly = 0) { heard.mark(any()) }
    }

    @Test fun `a list's song post is found by its title and artist without asking the Worker`() = runBlocking<Unit> {
        listenedTo("Garden", "Death Plus", youtubeId = "anotherUpload", at = posted + hour)
        assertThat(repo.heardFromHistory(gardenPost.copy(track = null))).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
        coVerify(exactly = 1) { heard.mark("AAAAAAAA") }
    }

    @Test fun `a list's playlist post is opened once for its songs`() = runBlocking<Unit> {
        listenedTo("T2", "B", youtubeId = "y2", at = posted + hour)
        server.enqueue(MockResponse().setBody(
            """{"post":{"id":"BBBBBBBB","kind":"playlist","title":"sad boy hours","name":"Maya","count":2,"covers":[],"createdAt":$posted,"up":0,"down":0,""" +
                """"tracks":[{"t":"T1","a":"A","yt":"y1"},{"t":"T2","a":"B","yt":"y2"}]}}""",
        ))
        val summary = CommunityPost(id = "BBBBBBBB", kind = "playlist", title = "sad boy hours", name = "Maya", count = 2, createdAt = posted, up = 0, down = 0)
        assertThat(repo.heardFromHistory(summary)).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().path).isEqualTo("/v1/community/posts/BBBBBBBB")
        coVerify(exactly = 1) { heard.mark("BBBBBBBB") }
    }

    @Test fun `your own post, or one that can't be opened, is not heard from history`() = runBlocking<Unit> {
        listenedTo("garden", "Death Plus", youtubeId = "9Vz", at = posted + hour)
        assertThat(repo.heardFromHistory(gardenPost.copy(mine = true))).isFalse()
        server.enqueue(MockResponse().setResponseCode(503))
        val summary = CommunityPost(id = "BBBBBBBB", kind = "playlist", title = "p", name = "Maya", count = 2, createdAt = posted, up = 0, down = 0)
        assertThat(repo.heardFromHistory(summary)).isFalse()
        coVerify(exactly = 0) { heard.mark(any()) }
    }
}
