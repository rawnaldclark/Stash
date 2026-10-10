package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plays from another device (`origin_device` set: a Stash on the web file or send, later the mirror) never feed Stash Mixes,
 * play statistics, auto-save or a scrobble destination (link-sync spec §7.2, owner's decision 4). One local and one remote
 * play of the same track: every query below must see only the local one.
 *
 * **The checklist.** A query added later that ranks taste, counts plays for a mix, or submits listens belongs in this test.
 * Queries that only show or export history (`WebLibraryExportDao.recentPlays`, History) include remote plays on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RemotePlaysDaoTest {

    private lateinit var db: StashDatabase
    private lateinit var events: ListeningEventDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        events = db.listeningEventDao()
    }

    @After fun tearDown() = db.close()

    private suspend fun fill() {
        db.trackDao().insert(
            TrackEntity(id = 1, title = "Borderline", artist = "Tame Impala", canonicalTitle = "borderline", canonicalArtist = "tame impala"),
        )
        db.trackDao().insert(
            TrackEntity(id = 2, title = "Runaway", artist = "Kanye West", canonicalTitle = "runaway", canonicalArtist = "kanye west"),
        )
        // Track 1: one play here. Track 2: only a play from the web, stamped later and marked complete, so a query that forgot
        // the filter would rank it first.
        events.insert(ListeningEventEntity(trackId = 1, startedAt = T, completedAt = T + 30_000))
        events.insert(ListeningEventEntity(trackId = 2, startedAt = T + 60_000, completedAt = T + 90_000, originDevice = "d_web00000000000"))
        events.insert(ListeningEventEntity(trackId = 2, startedAt = T + 70_000, completedAt = T + 95_000, originDevice = "d_web00000000000"))
    }

    @Test fun `mix and recommendation queries see only this phone's plays`() = runTest {
        fill()
        assertEquals(listOf(1L), events.topTracksSince(0).map { it.trackId })
        assertEquals(listOf(1L), events.getPlayCountsSince(0).map { it.trackId })
        assertEquals(listOf(1L), events.getPlayCountsSinceWithLatest(0).map { it.trackId })
        assertEquals(listOf(1L), events.getCompletionStatsSince(listOf(1L, 2L), 0).map { it.trackId })
        assertEquals(listOf(1L), events.getTrackIdsPlayedSince(0))
        assertEquals(listOf("Tame Impala"), events.getTopArtistsSince(0).map { it.artist })
        assertEquals(listOf(1L), events.getPlayedTrackIdsAmong(listOf(1L, 2L)))
        assertEquals(listOf("Borderline"), events.getTopTracksByLocalPlays(0, 10).map { it.title })
        assertEquals(0, events.distinctDaysCompletedFor(2, 0))
        val skips = db.trackSkipEventDao().getSkipStatsSince(listOf(1L, 2L), 0).associate { it.trackId to it.plays }
        assertEquals(mapOf(1L to 1, 2L to 0), skips)
    }

    @Test fun `auto-save and scrobble destinations never see them`() = runTest {
        fill()
        assertEquals(T + 30_000, events.observeMostRecentCompletion().first())
        assertNull(events.findByCompletedAt(T + 95_000))
        assertEquals(listOf(1L), events.pendingScrobbles().map { it.trackId })
        assertEquals(1, events.pendingScrobbleCount().first())
        assertEquals(listOf(1L), events.pendingYtScrobbles().map { it.trackId })
        assertEquals(1, events.pendingYtScrobbleCount().first())
        val submissions = db.listenSubmissionDao()
        assertEquals(listOf(1L), submissions.pendingFor("listenbrainz", 0).map { it.trackId })
        assertEquals(1, submissions.pendingCountFor("listenbrainz", 0).first())
    }

    @Test fun `play counts rebuilt from history count only this phone's plays`() = runTest {
        fill()
        events.backfillMissingTrackStats()
        val stats = db.query("SELECT id, play_count, last_played FROM tracks ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getLong(2))) }
        }
        assertEquals(listOf(Triple(1L, 1, T + 30_000), Triple(2L, 0, null)), stats)
    }

    @Test fun `history and export still show them`() = runTest {
        fill()
        assertEquals(listOf(2L, 2L, 1L), db.webLibraryExportDao().recentPlays(10).map { it.track.id })
    }

    private companion object {
        const val T = 1_759_838_400_000L
    }
}
