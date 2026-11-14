package com.stash.core.data.db

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.YourDownloadsPlaylist
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * #761's one-time pass: the hand downloads made before every download the user chose joined Your
 * Downloads are put in it, once, and nothing else. Real Room, real prefs: it links what the data
 * marks as the user's own (and the unmarked taps from before v0.9.110), leaves a sync's songs and
 * songs with no trace to Refresh, never brings back a song the user took out, deletes nothing,
 * and runs once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OwnDownloadsBackfillTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        prefs = context.getSharedPreferences("own-downloads-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After fun tearDown() = db.close()

    private fun backfill(trackDao: TrackDao = db.trackDao()) = OwnDownloadsBackfill(trackDao, db.playlistDao(), prefs)

    @Test fun `links the user's own downloads once, and leaves everything else as it is`() = runTest {
        val mine = buildList {
            // A radio song downloaded from Now Playing: in no playlist, its row marked.
            add(song(1, downloaded = true).also { row(it, DownloadStatus.COMPLETED, asked = true) })
            // A song of a Spotify playlist downloaded from its ⋮: the playlist holds it for now.
            add(song(2, downloaded = true, source = MusicSource.SPOTIFY).also {
                row(it, DownloadStatus.COMPLETED, asked = true)
                member(playlist("sp-mix", PlaylistType.DAILY_MIX), it)
            })
            // A tap still waiting for a lossless copy: it lands later, already kept.
            add(song(3, downloaded = false).also { row(it, DownloadStatus.WAITING_FOR_LOSSLESS, asked = true) })
            // A tap from before v0.9.110 (unmarked, no sync behind it), now in no playlist.
            add(song(4, downloaded = true, source = MusicSource.SPOTIFY).also {
                row(it, DownloadStatus.COMPLETED, asked = false, createdAt = BEFORE_MARKS)
            })
        }
        val notMine = buildList {
            // A cancelled tap of a song that never landed: nothing to keep.
            add(song(10, downloaded = false).also { row(it, DownloadStatus.SKIPPED, asked = true) })
            // A sync's download a mix rotated out: Refresh is meant to take it.
            add(song(11, downloaded = true, source = MusicSource.SPOTIFY).also {
                row(it, DownloadStatus.COMPLETED, asked = false, syncId = syncRun())
            })
            // No trace left (Downloads › Clear history): can't be told from a rotated-out song.
            add(song(12, downloaded = true, source = MusicSource.SPOTIFY))
            // An unmarked discovery-partition row from after v0.9.110: a kept playlist's leftover.
            add(song(13, downloaded = true).also { row(it, DownloadStatus.COMPLETED, asked = false, createdAt = AFTER_MARKS) })
            // An unmarked old row of a song a Stash Mix discovered: discovery's, not a tap.
            add(song(14, downloaded = true).also {
                row(it, DownloadStatus.COMPLETED, asked = false, createdAt = BEFORE_MARKS)
                discovered(it)
            })
            // An unmarked old row of a song a playlist still holds: the playlist's.
            add(song(15, downloaded = true).also {
                row(it, DownloadStatus.COMPLETED, asked = false, createdAt = BEFORE_MARKS)
                member(playlist("custom-road", PlaylistType.CUSTOM), it)
            })
        }
        // A hand download the user took out of Your Downloads stays out.
        val takenOut = song(20, downloaded = true).also { row(it, DownloadStatus.COMPLETED, asked = true) }
        val yourDownloads = db.playlistDao().insert(YourDownloadsPlaylist.entity())
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(playlistId = yourDownloads, trackId = takenOut, position = 0, removedAt = Instant.now()),
        )
        val before = db.trackDao().getByIds(mine + notMine + takenOut).associate { it.id to it.isDownloaded }

        assertEquals(mine.size, backfill().runOnce())

        assertEquals(mine.toSet(), activeIn(yourDownloads))
        assertEquals(mine.size, db.playlistDao().getById(yourDownloads)!!.trackCount)
        assertNotNull("still taken out", db.playlistDao().getCrossRef(yourDownloads, takenOut)!!.removedAt)
        // Only links: every song is still there, as downloaded as it was.
        assertEquals(before, db.trackDao().getByIds(mine + notMine + takenOut).associate { it.id to it.isDownloaded })
        assertTrue(prefs.getBoolean(OwnDownloadsBackfill.DONE_KEY, false))

        // Once: a second launch does nothing, and the sweep's wait is one prefs read.
        assertNull(backfill().runOnce())
        assertTrue(backfill().ensureRan())
        // Idempotent if it runs again (a restored backup re-arms it): nothing new to link.
        backfill().rearm()
        assertEquals(0, backfill().runOnce())
        assertEquals(mine.toSet(), activeIn(yourDownloads))
    }

    @Test fun `with no Your Downloads yet it makes one only when there is something to put in it`() = runTest {
        song(30, downloaded = true, source = MusicSource.SPOTIFY).also { row(it, DownloadStatus.COMPLETED, asked = false, syncId = syncRun()) }

        assertEquals(0, backfill().runOnce())
        assertNull(db.playlistDao().findBySourceId(YourDownloadsPlaylist.SOURCE_ID))

        backfill().rearm()
        val tap = song(31, downloaded = true).also { row(it, DownloadStatus.COMPLETED, asked = true) }
        assertEquals(1, backfill().runOnce())
        val yourDownloads = db.playlistDao().findBySourceId(YourDownloadsPlaylist.SOURCE_ID)!!
        assertEquals(setOf(tap), activeIn(yourDownloads.id))
    }

    @Test fun `a failed pass reports it, sets no flag and runs again next time`() = runTest {
        val failing = mockk<TrackDao>(relaxed = true) {
            coEvery { ownDownloadsOutsideYourDownloads(any(), any()) } throws IllegalStateException("disk I/O error")
        }

        assertFalse(backfill(trackDao = failing).ensureRan())
        assertFalse(prefs.getBoolean(OwnDownloadsBackfill.DONE_KEY, false))
        assertTrue(backfill().ensureRan())
    }

    /**
     * Cheap on a big library: one pass over 10,000 songs, each with a queue row, in one query and
     * one transaction (prints how long it took). A per-song query or a full queue scan per song
     * would show up here as minutes, not a second.
     */
    @Test fun `ten thousand songs are one pass`() = runTest {
        val tracks = (1L..10_000L).map { id ->
            TrackEntity(
                id = id, title = "T$id", artist = "a", canonicalTitle = "t$id", canonicalArtist = "a",
                isDownloaded = true, filePath = "/music/$id.opus", source = MusicSource.SPOTIFY,
            )
        }
        db.trackDao().insertAll(tracks)
        // Every 10th one a tap, the rest a sync's downloads.
        val sync = syncRun()
        db.downloadQueueDao().insertAll(
            tracks.map {
                val tap = it.id % 10 == 0L
                DownloadQueueEntity(trackId = it.id, status = DownloadStatus.COMPLETED, userRequested = tap, syncId = if (tap) null else sync)
            },
        )

        val started = System.nanoTime()
        assertEquals(1_000, backfill().runOnce())
        println("OwnDownloadsBackfill over 10,000 songs: ${(System.nanoTime() - started) / 1_000_000} ms")
        val yourDownloads = db.playlistDao().findBySourceId(YourDownloadsPlaylist.SOURCE_ID)!!
        assertEquals(1_000, db.playlistDao().getById(yourDownloads.id)!!.trackCount)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private suspend fun song(id: Long, downloaded: Boolean, source: MusicSource = MusicSource.YOUTUBE): Long =
        db.trackDao().insert(
            TrackEntity(
                id = id, title = "T$id", artist = "a", canonicalTitle = "t$id", canonicalArtist = "a",
                isDownloaded = downloaded, filePath = if (downloaded) "/music/$id.opus" else null,
                isStreamable = true, source = source,
            ),
        )

    private suspend fun row(
        trackId: Long,
        status: DownloadStatus,
        asked: Boolean,
        syncId: Long? = null,
        createdAt: Instant = Instant.now(),
    ) {
        db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = trackId, status = status, userRequested = asked, syncId = syncId, createdAt = createdAt),
        )
    }

    private suspend fun syncRun(): Long =
        db.syncHistoryDao().insert(com.stash.core.data.db.entity.SyncHistoryEntity())

    private suspend fun playlist(sourceId: String, type: PlaylistType): Long =
        db.playlistDao().findBySourceId(sourceId)?.id
            ?: db.playlistDao().insert(PlaylistEntity(name = sourceId, source = MusicSource.SPOTIFY, sourceId = sourceId, type = type))

    private suspend fun member(playlistId: Long, trackId: Long) =
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, position = 0))

    private suspend fun discovered(trackId: Long) {
        val recipe = db.stashMixRecipeDao().insert(StashMixRecipeEntity(name = "Daily Discover"))
        db.discoveryQueueDao().insertIfNew(
            DiscoveryQueueEntity(recipeId = recipe, artist = "a", title = "T$trackId", seedArtist = "s", trackId = trackId, status = DiscoveryQueueEntity.STATUS_FAILED),
        )
    }

    private suspend fun activeIn(playlistId: Long): Set<Long> =
        db.playlistDao().getCrossRefsForPlaylist(playlistId).filter { it.removedAt == null }.map { it.trackId }.toSet()

    private companion object {
        val BEFORE_MARKS: Instant = Instant.ofEpochMilli(OwnDownloadsBackfill.UNMARKED_BEFORE_MS - 86_400_000L)
        val AFTER_MARKS: Instant = Instant.ofEpochMilli(OwnDownloadsBackfill.UNMARKED_BEFORE_MS + 86_400_000L)
    }
}
