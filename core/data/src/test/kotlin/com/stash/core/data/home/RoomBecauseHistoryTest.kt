package com.stash.core.data.home

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.common.TrackCanonical
import com.stash.core.data.blocklist.BlocklistKey
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.TrackMatcher
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [RoomBecauseHistory]'s reads that decide what a "Because you like" row holds, against a real (in-memory) database:
 * a song counts as known only once it's been heard, and a blocked song is found by its words or by its video.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomBecauseHistoryTest {

    private lateinit var db: StashDatabase
    private lateinit var history: RoomBecauseHistory
    private val matcher = TrackMatcher()
    private val now = 1_800_000_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        history = RoomBecauseHistory(db.listeningEventDao(), db.trackSkipEventDao(), db.trackDao(), db.trackBlocklistDao(), matcher)
    }

    @After fun tearDown() = db.close()

    private fun song(videoId: String, title: String, artist: String) = TrackSummary(videoId, title, artist, null, 200.0, null, musicVideoType = "ATV")

    /** A library row for [title] by [artist] (with [videoId] when given), heard [plays] times; returns its id. */
    private suspend fun inLibrary(id: Long, title: String, artist: String, videoId: String? = null, plays: Int = 0): Long {
        db.trackDao().insert(
            TrackEntity(
                id = id, title = title, artist = artist, youtubeId = videoId,
                canonicalTitle = TrackCanonical.title(title), canonicalArtist = TrackCanonical.artist(artist),
            ),
        )
        repeat(plays) { db.listeningEventDao().insert(ListeningEventEntity(trackId = id, startedAt = now - 1_000L * (it + 1))) }
        return id
    }

    @Test
    fun `a song you have counts as known only once it's been heard, by its video or its words`() = runTest {
        val related = listOf(
            song("v1", "Take On Me", "a-ha"),
            song("v2", "Africa", "Toto"),
            song("v3", "Running Up That Hill", "Kate Bush"),
            song("v4", "Don't Dream It's Over", "Crowded House"),
        )
        // Synced and never played: still new to you
        inLibrary(1, "Take On Me", "a-ha", videoId = "v1")
        // Heard, matched by video
        inLibrary(2, "Africa", "Toto", videoId = "v2", plays = 3)
        // Heard under another video (a duplicate row of the song): matched by its words
        inLibrary(3, "Running Up That Hill (A Deal with God)", "Kate Bush", videoId = "other", plays = 1)
        assertEquals(setOf("v2", "v3"), history.known(related))
        // Nothing of the page in the library: nothing known
        assertEquals(emptySet<String>(), history.known(listOf(song("v9", "Never Heard", "Nobody"))))
    }

    @Test
    fun `a blocked song is found by its words or its video, and words that name nothing block nothing`() = runTest {
        val related = listOf(
            song("v1", "Take On Me (2015 Remaster)", "a-ha"),
            song("v2", "Africa", "Toto"),
            song("v3", "Running Up That Hill", "Kate Bush"),
            song("v4", "(Intro)", "Somebody"),
        )
        assertEquals(emptySet<String>(), history.blocked(related))
        // Blocked by its words (any version: the key is the loose canonical form)
        db.trackBlocklistDao().insert(
            TrackBlocklistEntity(BlocklistKey.of("a-ha", "Take On Me", matcher), "a-ha", "Take On Me", blockedAt = now, blockedFrom = "NOW_PLAYING"),
        )
        // Blocked by its video, under words of its own
        db.trackBlocklistDao().insert(
            TrackBlocklistEntity("toto|africa (live)", "Toto", "Africa (Live)", youtubeId = "v2", blockedAt = now, blockedFrom = "NOW_PLAYING"),
        )
        // A blank-titled entry ("(Intro)" keys as "somebody|") must not block every such title
        db.trackBlocklistDao().insert(
            TrackBlocklistEntity("somebody|", "Somebody", "(Intro)", blockedAt = now, blockedFrom = "OTHER"),
        )
        assertEquals(setOf("v1", "v2"), history.blocked(related))
    }
}
