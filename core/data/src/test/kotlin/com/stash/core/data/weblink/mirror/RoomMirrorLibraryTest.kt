package com.stash.core.data.weblink.mirror

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.data.weblink.merge.PlayRec
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mirror's writes into a real in-memory library (spec §7.1, §7.2): a mirrored like is a Stash like and an unlike clears only
 * a Stash like; a play from another device lands in History with its origin and never moves play counts or the mix inputs; a
 * mirrored playlist is written in order and starts no downloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomMirrorLibraryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var lib: RoomMirrorLibrary
    private lateinit var music: MusicRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        music = mockk<MusicRepository>()
        // As MusicRepositoryImpl.ensureExactTrackPersisted does: an existing row by YouTube id, Spotify id or ISRC (its ISRC
        // backfilled when missing), else a new stream-only row.
        coEvery { music.ensureExactTrackPersisted(any()) } coAnswers {
            val s = firstArg<SharedTrack>()
            val t = db.trackDao()
            val existing = s.youtubeId?.let { t.findByYoutubeId(it) }
                ?: s.spotifyId?.let { t.findBySpotifyUri("spotify:track:$it") }
                ?: s.isrc?.let { t.findByIsrc(it) }
            if (existing != null) {
                s.isrc?.let { t.backfillIsrcIfMissing(existing.id, it) }
                existing.id
            } else {
                t.insert(TrackEntity(title = s.title, artist = s.artist, isrc = s.isrc, youtubeId = s.youtubeId, spotifyUri = s.spotifyId?.let { "spotify:track:$it" }, isStreamable = true, canonicalTitle = canonTitle(s.title), canonicalArtist = canonArtist(s.artist)))
            }
        }
        coEvery { music.addTrackToPlaylist(any(), any()) } coAnswers {
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = secondArg(), trackId = firstArg(), position = db.playlistDao().getNextPosition(secondArg())))
        }
        coEvery { music.removeTrackFromPlaylist(any(), any()) } coAnswers {
            db.playlistDao().removeTrackFromPlaylist(secondArg(), firstArg())
        }
        lib = RoomMirrorLibrary(db, HandoffSongMatcher(db.trackDao(), music, db), StashLikedPlaylistRepository(db.playlistDao(), db.trackDao(), music))
    }

    @After fun tearDown() = db.close()

    @Test fun `SoundCloud account playlists mirror read only while imported copies remain editable`() = runTest {
        val synced = db.playlistDao().insert(PlaylistEntity(name = "My set", source = MusicSource.SOUNDCLOUD,
            sourceId = "soundcloud_playlist_7_11", type = PlaylistType.CUSTOM))
        val imported = db.playlistDao().insert(PlaylistEntity(name = "Imported set", source = MusicSource.SOUNDCLOUD,
            sourceId = "custom_link_soundcloud_playlist_11", type = PlaylistType.CUSTOM))
        val member = track("Member")
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(synced, member, 0))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(imported, member, 0))

        assertThat(lib.playlist(synced)!!.ro).isTrue()
        assertThat(lib.playlists().single { it.id == synced }.ro).isTrue()
        assertThat(lib.playlist(imported)!!.ro).isFalse()
    }

    @Test fun `SoundCloud uploads reach mirror as phone only without playable substitute identifiers`() = runTest {
        val id = db.trackDao().insert(TrackEntity(title = "Exact remix", artist = "Artist",
            source = MusicSource.SOUNDCLOUD, soundCloudId = "123", soundCloudPermalink = "https://soundcloud.com/artist/remix",
            spotifyUri = "spotify:track:other", youtubeId = "AAAAAAAAAAA", stashLikedAt = 5L,
            canonicalTitle = "exact remix", canonicalArtist = "artist"))
        val pid = db.playlistDao().insert(PlaylistEntity(name = "Set", source = MusicSource.SOUNDCLOUD,
            sourceId = "soundcloud_playlist_7_11", type = PlaylistType.CUSTOM))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(pid, id, 0))

        val song = lib.playlist(pid)!!.items.single()
        assertThat(song.phoneOnly).isTrue()
        assertThat(song.spotifyId).isNull()
        assertThat(song.youtubeId).isNull()
        assertThat(lib.likes().single().s).isEqualTo(song)
    }

    private suspend fun track(title: String, stashLiked: Boolean = false) = db.trackDao().insert(
        TrackEntity(title = title, artist = "Artist", stashLikedAt = if (stashLiked) 5L else null, source = MusicSource.SPOTIFY, isStreamable = true, canonicalTitle = canonTitle(title), canonicalArtist = "artist"),
    )

    // -- A browser's live changes, sent on through the phone's switches ------------------------------------------------------

    /** Stands for the phone's switches: records the likes handed on, and says whether YouTube history is on. */
    private class Onward(var historySince: Long? = 0L) : PhoneOnward {
        val likes = mutableListOf<Pair<Long, Boolean>>()
        override suspend fun like(trackId: Long, liked: Boolean) {
            likes += trackId to liked
        }
        override suspend fun youTubeHistorySince(): Long? = historySince
    }

    private fun sendingLib(onward: PhoneOnward) = RoomMirrorLibrary(
        db, HandoffSongMatcher(db.trackDao(), music, db), StashLikedPlaylistRepository(db.playlistDao(), db.trackDao(), music),
        MemoryAliasStore(), onward,
    ) { NOW }

    private fun idOf(title: String): Long =
        db.query("SELECT id FROM tracks WHERE title = ?", arrayOf(title)).use { it.moveToFirst(); it.getLong(0) }

    private suspend fun titleOf(id: Long) = db.trackDao().getById(id)!!.title

    @Test fun `a browser's live like goes on only when new here, an unlike only for a Stash like, a merge never`() = runTest {
        val onward = Onward()
        val lib = sendingLib(onward)
        track("Already here", stashLiked = true)
        val spotifyLiked = db.playlistDao().insert(PlaylistEntity(name = "Liked Songs", source = MusicSource.SPOTIFY, sourceId = "spotify_liked", type = PlaylistType.LIKED_SONGS))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = spotifyLiked, trackId = track("From Spotify"), position = 0))

        // A first merge, a snapshot: nothing marked, nothing goes
        lib.setLikes(on = listOf(song("Merged")), off = listOf(song("Already here")), at = 98)
        assertThat(onward.likes).isEmpty()
        track("Already here 2", stashLiked = true)

        val marked = setOf(song("New one"), song("Already here 2"))
        lib.setLikes(on = listOf(song("New one"), song("Already here 2"), song("Not marked")), off = emptyList(), at = 99, sendOn = marked)
        // A Stash like already here went on when it was hearted here
        assertThat(onward.likes).containsExactly(idOf("New one") to true)

        lib.setLikes(on = emptyList(), off = listOf(song("New one"), song("From Spotify")), at = 100, sendOn = setOf(song("New one"), song("From Spotify")))
        // The Spotify-only like stays liked here and there
        assertThat(onward.likes).containsExactly(idOf("New one") to true, idOf("New one") to false).inOrder()
        assertThat(lib.likes().map { it.s.title }).containsExactly("From Spotify", "Already here 2", "Merged", "Not marked")
    }

    @Test fun `a browser's live play under an hour old is owed to YouTube history while that is on, and nothing else is`() = runTest {
        val onward = Onward(historySince = NOW - 10 * HOUR)
        val lib = sendingLib(onward)
        val web = "d_web000000000001"
        lib.addPlays(listOf(PlayRec(song("Fresh"), NOW - 30 * MINUTE, web), PlayRec(song("Too old"), NOW - 2 * HOUR, web)), live = true)
        lib.addPlays(listOf(PlayRec(song("Merged"), NOW - 20 * MINUTE, web))) // a first merge or a snapshot
        onward.historySince = null // the switch turned off
        lib.addPlays(listOf(PlayRec(song("Switch off"), NOW - 10 * MINUTE, web)), live = true)
        onward.historySince = NOW - 5 * MINUTE // turned on again since
        lib.addPlays(listOf(PlayRec(song("Before the switch"), NOW - 10 * MINUTE, web)), live = true)

        val events = db.listeningEventDao()
        assertThat(events.pendingYtScrobbles(sinceMs = 0).map { titleOf(it.trackId) }).containsExactly("Fresh")
        assertThat(db.query("SELECT COUNT(*) FROM listening_events", null).use { it.moveToFirst(); it.getInt(0) }).isEqualTo(5)
        // Never to Last.fm from here: the web scrobbles its own plays
        assertThat(events.pendingScrobbles()).isEmpty()
    }

    @Test fun `a mirrored like is a Stash like, and an unlike leaves a Spotify like alone`() = runTest {
        val spotifyLiked = db.playlistDao().insert(PlaylistEntity(name = "Liked Songs", source = MusicSource.SPOTIFY, sourceId = "spotify_liked", type = PlaylistType.LIKED_SONGS))
        val s = track("From Spotify")
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = spotifyLiked, trackId = s, position = 0))
        track("Here", stashLiked = true)

        assertThat(lib.likes().map { it.s.title to it.external }).containsExactly("From Spotify" to true, "Here" to false)
        lib.setLikes(on = listOf(song("New one")), off = listOf(song("From Spotify"), song("Here")), at = 99)
        assertThat(lib.likes().map { it.s.title }).containsExactly("From Spotify", "New one")
        assertThat(lib.likes().single { it.s.title == "New one" }.external).isFalse()
    }

    @Test fun `two songs that share a YouTube id stay two songs (the mirror never likes one in place of the other)`() = runTest {
        db.trackDao().insert(TrackEntity(title = "Nights", artist = "Frank Ocean", youtubeId = "dQw4w9WgXcQ", stashLikedAt = 5L, isStreamable = true, canonicalTitle = "nights", canonicalArtist = "frank ocean"))
        lib.setLikes(on = listOf(com.stash.core.data.weblink.handoff.WireSong("Pink + White", "Frank Ocean", refs = mapOf("youtube" to "dQw4w9WgXcQ"))), off = emptyList(), at = 99)
        assertThat(lib.likes().map { it.s.title }).containsExactly("Nights", "Pink + White")
    }

    @Test fun `B1 - a song whose YouTube id names another song with no ISRC gets its own row, and the other song keeps no ISRC`() = runTest {
        val nights = db.trackDao().insert(TrackEntity(title = "Nights", artist = "Frank Ocean", youtubeId = "dQw4w9WgXcQ", stashLikedAt = 5L, isStreamable = true, canonicalTitle = "nights", canonicalArtist = "frank ocean"))
        val pink = com.stash.core.data.weblink.handoff.WireSong("Pink + White", "Frank Ocean", isrc = "USUM71607009", refs = mapOf("youtube" to "dQw4w9WgXcQ"))
        lib.setLikes(on = listOf(pink), off = emptyList(), at = 99)
        assertThat(db.trackDao().getById(nights)!!.isrc).isNull()
        assertThat(lib.likes().map { it.s.title }).containsExactly("Nights", "Pink + White")
        // The next diff against a base holding both sends nothing.
        val base = listOf(
            com.stash.core.data.weblink.merge.LikeRec(com.stash.core.data.weblink.handoff.WireSong("Nights", "Frank Ocean"), true, com.stash.core.model.weblink.Hlc(1, 0, "d_00000000")),
            com.stash.core.data.weblink.merge.LikeRec(pink, true, com.stash.core.model.weblink.Hlc(1, 0, "d_00000000")),
        )
        assertThat(com.stash.core.data.weblink.merge.LikesMerge.diff(base, lib.likes(), com.stash.core.model.weblink.Hlc(2, 0, "d_00000000"))).isEmpty()
        // A later song with that ISRC lands on Pink + White, never on Nights.
        lib.setLikes(on = listOf(com.stash.core.data.weblink.handoff.WireSong("Pink + White", "Frank Ocean", isrc = "USUM71607009")), off = emptyList(), at = 100)
        assertThat(lib.likes()).hasSize(2)
    }

    @Test fun `S4, R-S3 - a loosely matched row is one song here, and keeps the web's identity both ways`() = runTest {
        val a = db.trackDao().insert(TrackEntity(title = "Song A", artist = "The Band", youtubeId = "AAAAAAAAAAA", isStreamable = true, canonicalTitle = "song a", canonicalArtist = "the band"))
        val web = com.stash.core.data.weblink.handoff.WireSong("Song A - Remastered 2011", "The Band feat. Someone", refs = mapOf("youtube" to "AAAAAAAAAAA"))
        val h = com.stash.core.model.weblink.Hlc(1, 0, "d_00000000")
        lib.setLikes(on = listOf(web), off = emptyList(), at = 99)
        assertThat(db.query("SELECT COUNT(*) FROM tracks", null).use { it.moveToFirst(); it.getInt(0) }).isEqualTo(1)
        assertThat(db.trackDao().getById(a)!!.stashLikedAt).isNotNull()
        // Read back as the web's song: the next diff sends no second like.
        assertThat(lib.likes().map { it.s.title }).containsExactly("Song A - Remastered 2011")
        val base = listOf(com.stash.core.data.weblink.merge.LikeRec(web, true, h))
        assertThat(com.stash.core.data.weblink.merge.LikesMerge.diff(base, lib.likes(), h)).isEmpty()
        // An unlike from the web finds the row.
        lib.setLikes(on = emptyList(), off = listOf(web), at = 100)
        assertThat(db.trackDao().getById(a)!!.stashLikedAt).isNull()
        // An unlike here goes back as the web's song.
        lib.setLikes(on = listOf(web), off = emptyList(), at = 101)
        StashLikedPlaylistRepository(db.playlistDao(), db.trackDao(), music).remove(a)
        assertThat(com.stash.core.data.weblink.merge.LikesMerge.diff(base, lib.likes(), h).map { it.s.title to it.on }).containsExactly("Song A - Remastered 2011" to false)
        // A mirrored playlist entry reads back as the web's song too: nothing to push over the web's details.
        val put = lib.putPlaylist(null, "m_BBBBBBBBBBBBBBBB", "P", listOf(web))
        assertThat(lib.playlist(put.id)!!.items.map { it.title to it.artist }).containsExactly("Song A - Remastered 2011" to "The Band feat. Someone")
    }

    // ------------------------------------------------------------------------------------------------ final check F-B1

    private val variant = com.stash.core.data.weblink.handoff.WireSong("Song A - Remastered 2011", "The Band", refs = mapOf("youtube" to "AAAAAAAAAAA"))
    private val own = com.stash.core.data.weblink.handoff.WireSong("Song A", "The Band", refs = mapOf("youtube" to "AAAAAAAAAAA"))
    private val hlc = com.stash.core.model.weblink.Hlc(1, 0, "d_00000000")

    private suspend fun rowA() = db.trackDao().insert(
        TrackEntity(title = "Song A", artist = "The Band", youtubeId = "AAAAAAAAAAA", isStreamable = true, canonicalTitle = "song a", canonicalArtist = "the band"),
    )

    private fun trackCount() = db.query("SELECT COUNT(*) FROM tracks", null).use { it.moveToFirst(); it.getInt(0) }

    @Test fun `F-B1 - a liked row never takes a variant's identity - no unlike of it, no like nobody made`() = runTest {
        val a = rowA()
        lib.setLikes(on = listOf(own), off = emptyList(), at = 1) // Song A liked and mirrored as itself
        val before = trackCount()
        lib.setLikes(on = listOf(variant), off = emptyList(), at = 2) // a web like of the variant with A's video
        assertThat(trackCount()).isEqualTo(before + 1) // a row of its own
        assertThat(lib.likes().map { it.s.title }).containsExactly("Song A", "Song A - Remastered 2011")
        val base = listOf(com.stash.core.data.weblink.merge.LikeRec(own, true, hlc), com.stash.core.data.weblink.merge.LikeRec(variant, true, hlc))
        assertThat(com.stash.core.data.weblink.merge.LikesMerge.diff(base, lib.likes(), hlc)).isEmpty()
        assertThat(db.trackDao().getById(a)!!.title).isEqualTo("Song A")
    }

    @Test fun `F-B1 - a played row never takes a variant's identity - its plays are not sent again`() = runTest {
        val a = rowA()
        db.listeningEventDao().recordCompletedListen(ListeningEventEntity(trackId = a, startedAt = 1_000, completedAt = 1_200))
        val ownBefore = lib.ownPlays(0).map { com.stash.core.data.weblink.merge.PlaysMerge.playId(it.s, it.playedAt) }
        lib.addPlays(listOf(PlayRec(variant, 5_000, "d_web000000000001"))) // a web play of the variant
        assertThat(lib.ownPlays(0).map { com.stash.core.data.weblink.merge.PlaysMerge.playId(it.s, it.playedAt) }).isEqualTo(ownBefore)
    }

    @Test fun `F-B1 - a playlist of A and its variant onto one row stays A and the variant, nothing to push`() = runTest {
        rowA()
        val put = lib.putPlaylist(null, "m_CCCCCCCCCCCCCCCC", "Mix", listOf(own, variant))
        assertThat(put.held).containsExactly(true, true).inOrder()
        assertThat(lib.playlist(put.id)!!.items.map { it.title }).containsExactly("Song A", "Song A - Remastered 2011").inOrder()
    }

    @Test fun `F-B1 - a row in a playlist never takes a variant's identity`() = runTest {
        val a = rowA()
        val other = lib.putPlaylist(null, "m_DDDDDDDDDDDDDDDD", "Other", listOf(own))
        lib.setLikes(on = listOf(variant), off = emptyList(), at = 2)
        assertThat(lib.playlist(other.id)!!.items.map { it.title }).containsExactly("Song A")
        assertThat(db.trackDao().getById(a)!!.stashLikedAt).isNull()
    }

    @Test fun `F-B1 - a row already standing for the variant never takes the original too`() = runTest {
        val a = rowA()
        lib.addPlays(listOf(PlayRec(variant, 5_000, "d_web000000000001"))) // no history: the row stands for the variant
        lib.setLikes(on = listOf(own), off = emptyList(), at = 2)          // then the original arrives
        assertThat(db.trackDao().getById(a)!!.stashLikedAt).isNull()
        assertThat(lib.likes().map { it.s.title }).containsExactly("Song A")
        lib.setLikes(on = listOf(own), off = emptyList(), at = 3)          // again: the same second row, no third
        assertThat(trackCount()).isEqualTo(2)
    }

    @Test fun `F-N9 - an alias goes with its row, and when the row's words become another song`() = runTest {
        val a = rowA()
        lib.addPlays(listOf(PlayRec(variant, 5_000, "d_web000000000001")))
        db.query("UPDATE tracks SET title = 'Something Else' WHERE id = $a", null).use { it.moveToFirst() }
        db.listeningEventDao().recordCompletedListen(ListeningEventEntity(trackId = a, startedAt = 9_000, completedAt = 9_200))
        assertThat(lib.ownPlays(0).map { it.s.title }).containsExactly("Something Else")
    }

    @Test fun `L-N5 - a deleted row's alias is pruned`() = runTest {
        val store = MemoryAliasStore()
        val lib = RoomMirrorLibrary(db, HandoffSongMatcher(db.trackDao(), music, db), StashLikedPlaylistRepository(db.playlistDao(), db.trackDao(), music), store)
        val a = rowA()
        lib.addPlays(listOf(PlayRec(variant, 5_000, "d_web000000000001")))
        assertThat(store.keys()).containsExactly(a)
        db.query("DELETE FROM listening_events", null).use { it.moveToFirst() }
        db.trackDao().delete(db.trackDao().getById(a)!!)
        lib.setLikes(on = listOf(com.stash.core.data.weblink.handoff.WireSong("Other", "Someone")), off = emptyList(), at = 3)
        assertThat(store.keys()).isEmpty()
    }

    @Test fun `L-N5 - a variant listed before the original takes a row with no history, the original gets its own`() = runTest {
        val a = rowA()
        val put = lib.putPlaylist(null, "m_EEEEEEEEEEEEEEEE", "Mix", listOf(variant, own))
        assertThat(put.held).containsExactly(true, true).inOrder()
        assertThat(lib.playlist(put.id)!!.items.map { it.title }).containsExactly("Song A - Remastered 2011", "Song A").inOrder()
        val rows = db.playlistDao().getOrderedTrackIdsForPlaylist(put.id)
        assertThat(rows.first()).isEqualTo(a) // the variant took the row and its video
        assertThat(db.trackDao().getById(rows.last())!!.youtubeId).isNull() // the original: a row of its own, no id taken
        assertThat(trackCount()).isEqualTo(2)
    }

    @Test fun `plays from another device go to History with their origin, once, and never touch play counts or mix inputs`() = runTest {
        val mine = track("Mine")
        db.listeningEventDao().recordCompletedListen(ListeningEventEntity(trackId = mine, startedAt = 1_000, completedAt = 1_200))
        lib.addPlays(listOf(PlayRec(song("Mine"), 2_000, "d_web000000000001"), PlayRec(song("Other"), 3_000, "d_web000000000001")))
        lib.addPlays(listOf(PlayRec(song("Other"), 3_000, "d_web000000000001"))) // again: skipped
        val events = db.query("SELECT origin_device, scrobbled FROM listening_events ORDER BY started_at", null).use { c ->
            buildList { while (c.moveToNext()) add((c.getString(0) ?: "own") to c.getInt(1)) }
        }
        assertThat(events).containsExactly("own" to 0, "d_web000000000001" to 1, "d_web000000000001" to 1).inOrder()
        assertThat(db.trackDao().getById(mine)!!.playCount).isEqualTo(1)
        // What Stash Mixes read sees only this phone's own play.
        assertThat(db.listeningEventDao().getPlayCountsSince(0).associate { it.trackId to it.plays }).containsExactly(mine, 1)
        assertThat(lib.ownPlays(0).map { it.s.title to it.playedAt }).containsExactly("Mine" to 1_000L)

        lib.dropPlaysFrom("d_web000000000001", 2_500)
        assertThat(db.query("SELECT COUNT(*) FROM listening_events", null).use { it.moveToFirst(); it.getInt(0) }).isEqualTo(2)
    }

    @Test fun `a mirrored playlist is written in order, renamed and replaced in place, and set not to download`() = runTest {
        track("1")
        track("2")
        val put = lib.putPlaylist(null, "m_AAAAAAAAAAAAAAAA", "Night drive", listOf(song("2"), song("1"), song("Brand new"), song("1")))
        val id = put.id
        // A repeat is held once here; the engine carries the other (review B2).
        assertThat(put.held).containsExactly(true, true, true, false).inOrder()
        val p = db.playlistDao().getById(id)!!
        assertThat(p.sourceId).isEqualTo("custom_sync_m_AAAAAAAAAAAAAAAA")
        assertThat(p.syncEnabled).isFalse()
        assertThat(lib.playlist(id)!!.items.map { it.title }).containsExactly("2", "1", "Brand new").inOrder()
        assertThat(lib.playlists().map { it.name }).contains("Night drive")

        assertThat(lib.putPlaylist(id, "m_AAAAAAAAAAAAAAAA", "Night drive 2", listOf(song("1"))).id).isEqualTo(id)
        assertThat(lib.playlist(id)!!.let { it.name to it.items.map { s -> s.title } }).isEqualTo("Night drive 2" to listOf("1"))
        lib.deletePlaylist(id)
        assertThat(lib.playlist(id)).isNull()
        assertThat(db.trackDao().getById(1)).isNotNull() // the songs stay
    }

    @Test fun `a Spotify-synced playlist is read-only for the mirror`() = runTest {
        val sp = db.playlistDao().insert(PlaylistEntity(name = "Discover Weekly", source = MusicSource.SPOTIFY, sourceId = "37i9dQZEVXcJ", type = PlaylistType.CUSTOM, syncEnabled = true))
        val mine = db.playlistDao().insert(PlaylistEntity(name = "Mine", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM, syncEnabled = true))
        val byId = lib.playlists().associateBy { it.id }
        assertThat(byId.getValue(sp).ro).isTrue()
        assertThat(byId.getValue(mine).ro).isFalse()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        /** The library's canonical_title / canonical_artist: the one loose rule every writer uses. */
        fun canonTitle(s: String) = com.stash.core.common.TrackCanonical.title(s)
        fun canonArtist(s: String) = com.stash.core.common.TrackCanonical.artist(s)
    }
}
