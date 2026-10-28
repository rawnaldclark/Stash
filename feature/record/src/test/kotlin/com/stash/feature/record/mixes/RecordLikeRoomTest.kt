package com.stash.feature.record.mixes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RecordTasteEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.share.SharedTrack
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.bridge.RecordMatch
import com.stash.feature.record.taste.SongStat
import com.stash.feature.record.taste.Taste
import com.stash.feature.record.taste.TasteRows
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Like on "Not in your Stash yet", on a real (in-memory) library, the way the app writes it: the song (only words from the
 * history) gets a stream-only row through the hand-off's matcher, Stash's Liked Songs gains it, it leaves the list at once and
 * its heart fills, nothing is queued to download and nothing is marked saved to Spotify; Unlike brings it back to the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordLikeRoomTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val words = ResourceMixWords(context)
    private lateinit var db: StashDatabase
    private lateinit var liked: StashLikedPlaylistRepository
    private lateinit var match: RecordMatch

    /** What Stash's own adds would have queued to download (a kept-offline playlist queues its songs). */
    private var queued = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        val music = mockk<MusicRepository>()
        // Listen Together's exact persist, as MusicRepositoryImpl does it: a row found by an id, else a new stream-only row
        // under its canonical words (what the matcher's words lookup finds it by next time)
        coEvery { music.ensureExactTrackPersisted(any()) } coAnswers {
            val s = firstArg<SharedTrack>()
            s.spotifyId?.let { db.trackDao().findBySpotifyUri("spotify:track:$it") }?.id ?: db.trackDao().insert(
                TrackEntity(
                    title = s.title, artist = s.artist, album = s.album ?: "", spotifyUri = s.spotifyId?.let { "spotify:track:$it" },
                    isStreamable = true, source = MusicSource.BOTH,
                    canonicalTitle = TrackCanonical.title(s.title), canonicalArtist = TrackCanonical.artist(s.artist),
                ),
            )
        }
        // The repository's add as it is (MusicRepositoryImpl.addTrackToPlaylist): the song in, and a kept-offline playlist queued
        coEvery { music.addTrackToPlaylist(any(), any()) } coAnswers {
            val (track, pl) = firstArg<Long>() to secondArg<Long>()
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = pl, trackId = track, position = db.playlistDao().getNextPosition(pl), locallyAdded = true))
            if (db.playlistDao().getById(pl)?.keepOffline == true) queued++
        }
        coEvery { music.removeTrackFromPlaylist(any(), any()) } coAnswers {
            db.playlistDao().softDeleteTrackFromPlaylist(secondArg(), firstArg())
        }
        liked = StashLikedPlaylistRepository(db.playlistDao(), db.trackDao(), music)
        val matcher = HandoffSongMatcher(db.trackDao(), music, db)
        match = RecordMatch(byId = db.trackDao()::getById, matchAll = { matcher.matchAll(it) }, keepCover = db.trackDao()::fillMissingAlbumArtUrl)
    }

    @After fun tearDown() = db.close()

    private fun stat(title: String, sid: String?) = SongStat(
        key = SongKey.songKey(title, "Lost Band"), title = title, artist = "Lost Band", sid = sid, starts = 12, plays = 12, full = 12,
        chosen = 0, skips = 0, years = 2, listenedMs = 12 * 200_000, first = 1_600_000_000_000, last = 1_650_000_000_000, peak = 2, peakAt = 0,
        weight = 1.0, depth = 1.0, avoid = false,
    )

    private suspend fun seed() {
        val songs = listOf(stat("Lost 01", "sid01"), stat("Lost 02", null), stat("Lost 03", "sid03"))
        val t = Taste(
            computedAt = 1_700_000_000_000, first = 1_577_836_800_000, last = 1_700_000_000_000, plays = 36, listenedMs = 0, artists = 1,
            songs = songs, eras = emptyList(), constants = emptyList(), lost = songs.map { it.key }, obsessions = emptyList(), found = null,
            years = emptyList(), topArtists = emptyList(), seedArtists = emptyList(), yearTops = emptyList(),
        )
        db.recordTasteDao().replace(RecordTasteEntity(v = t.v, computedAt = t.computedAt, body = TasteRows.body(t)), TasteRows.songs(t))
    }

    private fun mixes(): RecordMixes {
        val dao = db.webLibraryExportDao()
        return RecordMixes(
            tastes = db.recordTasteDao(),
            between = { _, _ -> emptyList() },
            own = RecordArchiveSource { _, emit -> emit(emptyList(), true) },
            library = { RecordMixes.keptLibrary(dao) },
            libraryStamp = dao.libraryStamp(),
            libraryStampNow = { dao.libraryStampNow() },
            stashLikes = { dao.stashLikes().map(RecordMixes::songOf) },
            io = EmptyCoroutineContext,
        )
    }

    private fun count(sql: String): Int = db.query(sql, null).use { it.moveToFirst(); it.getInt(0) }

    @Test fun `Like on a lost song - Liked Songs gains it, it leaves the list, nothing downloads, nothing goes to Spotify`() = runTest(UnconfinedTestDispatcher()) {
        seed()
        val mixes = mixes()
        val actions = testSongActions(rows = match::rows, like = liked::add, unlike = liked::remove, scope = backgroundScope)
        val before = mixes.now(words)!!.lost
        assertThat(before.map { it.title }).containsExactly("Lost 01", "Lost 02", "Lost 03").inOrder()
        val downloadsBefore = count("SELECT COUNT(*) FROM download_queue")

        assertThat(actions.like(before[2].recordSong("https://cdn/lost3.jpg")).await()).isTrue()

        // Liked Songs (Stash's own) holds it: a stream-only row, the history's Spotify id and the cover kept on it
        val likedSongs = db.playlistDao().findBySourceId("stash_liked_songs")!!
        assertThat(likedSongs.type).isEqualTo(PlaylistType.STASH_LIKED)
        val row = db.trackDao().findBySpotifyUri("spotify:track:sid03")!!
        assertThat(db.playlistDao().getCrossRef(likedSongs.id, row.id)).isNotNull()
        assertThat(row.isDownloaded).isFalse()
        assertThat(row.albumArtUrl).isEqualTo("https://cdn/lost3.jpg")
        assertThat(row.stashLikedAt).isNotNull()
        // never written to Spotify, nothing queued to download
        assertThat(row.spotifySavedAt).isNull()
        assertThat(count("SELECT COUNT(*) FROM download_queue")).isEqualTo(downloadsBefore)
        assertThat(queued).isEqualTo(0)
        // it leaves "Not in your Stash yet", and its heart is filled
        assertThat(mixes.now(words)!!.lost.map { it.title }).containsExactly("Lost 01", "Lost 02").inOrder()
        assertThat(mixes.likes.first().has(before[2].identity())).isTrue()

        // Unlike: out of Liked Songs, back on the list
        assertThat(actions.unlike(listOf(row.id)).await()).isTrue()
        assertThat(mixes.now(words)!!.lost.map { it.title }).containsExactly("Lost 01", "Lost 02", "Lost 03").inOrder()
        assertThat(mixes.likes.first().has(before[2].identity())).isFalse()
    }

    @Test fun `a song with no Spotify id is liked by its words, once`() = runTest(UnconfinedTestDispatcher()) {
        seed()
        val mixes = mixes()
        val actions = testSongActions(rows = match::rows, like = liked::add, scope = backgroundScope)
        val two = mixes.now(words)!!.lost[1]
        assertThat(actions.like(two.recordSong(null)).await()).isTrue()
        assertThat(actions.like(two.recordSong(null)).await()).isTrue()
        assertThat(count("SELECT COUNT(*) FROM tracks WHERE title = 'Lost 02'")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM playlist_tracks pt JOIN playlists p ON p.id = pt.playlist_id WHERE p.type = 'STASH_LIKED'")).isEqualTo(1)
        assertThat(mixes.now(words)!!.lost.map { it.title }).containsExactly("Lost 01", "Lost 03").inOrder()
    }

    @Test fun `a song in one of your playlists isn't on the list, and isn't a like`() = runTest(UnconfinedTestDispatcher()) {
        seed()
        val id = db.trackDao().insert(TrackEntity(title = "Lost 01", artist = "Lost Band", isStreamable = true, source = MusicSource.BOTH))
        val pl = db.playlistDao().insert(com.stash.core.data.db.entity.PlaylistEntity(name = "Mine", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = pl, trackId = id, position = 0))
        val mixes = mixes()
        val now = mixes.now(words)!!
        assertThat(now.lost.map { it.title }).containsExactly("Lost 02", "Lost 03").inOrder()
        assertThat(mixes.likes.first().has(com.stash.core.model.weblink.SongRef("Lost 01", "Lost Band"))).isFalse()
    }
}
