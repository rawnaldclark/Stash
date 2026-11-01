package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import com.stash.core.common.TrackCanonical
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Stash Mix recommendation reuses the library's row for the song, not only a downloaded one
 * (duplicates, cause 2). Only downloaded rows used to count, so every rediscovery of a song the
 * user streams but never downloaded filed another id-less stub: nine rows for "Starting Over" on
 * the owner's phone, up to twelve for one song. Real `tracks` table; the queue and recipe are mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashDiscoveryWorkerReuseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk {
        coEvery { isBlocked(any(), any(), any(), any()) } returns false
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mockkObject(DiscoveryDownloadWorker.Companion)
        coEvery { discoveryQueueDao.deleteStalePending(any()) } returns 0
        coEvery { discoveryQueueDao.findRecipesAtWeeklyCap(any(), any()) } returns emptyList()
        coEvery { discoveryQueueDao.getRecipesWithPending(any()) } returns listOf(RECIPE_ID)
        coEvery { discoveryQueueDao.countRecentCompletedForRecipe(RECIPE_ID, any()) } returns 0
        coEvery { recipeDao.getById(RECIPE_ID) } returns StashMixRecipeEntity(
            id = RECIPE_ID, name = "Daily Discover", playlistId = 100L, isBuiltin = true, isActive = true,
        )
    }

    @After fun tearDown() {
        unmockkObject(DiscoveryDownloadWorker.Companion)
        db.close()
    }

    @Test fun `a song the user streams is reused, not stubbed again`() = runTest {
        // A YouTube stream the user liked: streamable, never downloaded.
        val streamed = row("Starting Over", youtubeId = "Ikn6Z2fxMs4", streamable = true)

        discover(entryId = 1L, title = "Starting Over")

        assertEquals(listOf(streamed), allIds())
        coVerify { discoveryQueueDao.updateStatus(id = 1L, status = DiscoveryQueueEntity.STATUS_DONE, trackId = streamed, completedAt = any(), errorMessage = null) }
    }

    @Test fun `a song rediscovered again and again gets one stub`() = runTest {
        discover(entryId = 1L, title = "Starting Over")
        discover(entryId = 2L, title = "Starting Over")
        discover(entryId = 3L, title = "Starting Over (Official Audio)")

        val rows = allIds()
        assertEquals("one stub for the song", 1, rows.size)
        coVerify { discoveryQueueDao.updateStatus(id = 3L, status = DiscoveryQueueEntity.STATUS_DONE, trackId = rows.single(), completedAt = any(), errorMessage = null) }
    }

    @Test fun `a downloaded copy is preferred over an older streamed row`() = runTest {
        row("Starting Over", youtubeId = "video000001", streamable = true)
        val downloaded = row("Starting Over", youtubeId = "video000002", file = "/music/starting-over.flac")

        discover(entryId = 1L, title = "Starting Over")

        coVerify { discoveryQueueDao.updateStatus(id = 1L, status = DiscoveryQueueEntity.STATUS_DONE, trackId = downloaded, completedAt = any(), errorMessage = null) }
        assertEquals(2, allIds().size)
    }

    @Test fun `another version, or a row the mix can't play, is not reused`() = runTest {
        val live = row("Starting Over (Live)", youtubeId = "video000001", streamable = true)
        // A synced Spotify song waiting for its download: neither downloaded nor streamable, so the
        // mix (which links downloaded or streamable rows) could never show it.
        val waiting = row("Starting Over", spotifyUri = "spotify:track:1", streamable = false)

        discover(entryId = 1L, title = "Starting Over")

        val stub = allIds().single { it != live && it != waiting }
        assertNotEquals(live, stub)
        val inserted = db.trackDao().getById(stub)!!
        assertEquals(MusicSource.YOUTUBE, inserted.source)
        assertEquals(true, inserted.isStreamable)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private suspend fun discover(entryId: Long, title: String) {
        coEvery { discoveryQueueDao.getPendingForRecipe(RECIPE_ID, any()) } returns listOf(
            DiscoveryQueueEntity(id = entryId, recipeId = RECIPE_ID, artist = ARTIST, title = title, seedArtist = "Slowdive"),
        )
        newWorker().doWork()
    }

    private suspend fun row(
        title: String,
        youtubeId: String? = null,
        spotifyUri: String? = null,
        file: String? = null,
        streamable: Boolean = false,
    ): Long = db.trackDao().insert(
        TrackEntity(
            title = title, artist = ARTIST, youtubeId = youtubeId, spotifyUri = spotifyUri,
            source = if (spotifyUri != null) MusicSource.SPOTIFY else MusicSource.YOUTUBE,
            filePath = file, isDownloaded = file != null, isStreamable = streamable,
            canonicalTitle = TrackCanonical.title(title), canonicalArtist = TrackCanonical.artist(ARTIST),
        ),
    )

    private suspend fun allIds(): List<Long> = db.trackDao().getAllForIntegrityScan().map { it.id }.sorted()

    private fun newWorker(): StashDiscoveryWorker = StashDiscoveryWorker(
        mockk(relaxed = true), mockk<WorkerParameters>(relaxed = true),
        discoveryQueueDao, db.trackDao(), recipeDao,
        TrackMatcher(), blocklistGuard, mockk(relaxed = true),
    )

    private companion object {
        const val RECIPE_ID = 1L
        const val ARTIST = "LSD and the Search for God"
    }
}
