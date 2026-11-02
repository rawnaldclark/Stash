package com.stash.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackMergeLogEntity
import com.stash.core.data.library.duplicates.TrackMerger
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A listen or a like on a stream whose row was merged into another lands on the row that stays
 * ([MusicRepositoryImpl.ensureTrackPersisted] through `track_aliases`), and never makes the merged
 * duplicate again: by the merged-away video, and by the merged-away row id a saved queue still holds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MusicRepositoryMergedAliasTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `a stream of the merged-away video saves nothing new`() = runTest {
        val (kept, _) = mergedPair()

        val id = repo().ensureTrackPersisted(Track(title = "Starting Over", artist = ARTIST, youtubeId = "otherVideo1"))

        assertEquals(kept, id)
        assertEquals(1, count())
    }

    @Test fun `a queue item that still holds the merged-away row id saves nothing new`() = runTest {
        val (kept, gone) = mergedPair()

        val id = repo().ensureTrackPersisted(Track(id = gone, title = "Starting Over", artist = ARTIST))

        assertEquals(kept, id)
        assertEquals(1, count())
    }

    private suspend fun mergedPair(): Pair<Long, Long> {
        val kept = row(youtubeId = "handVideo01", file = "/music/starting-over.flac")
        val gone = row(youtubeId = "otherVideo1")
        assertNotNull(TrackMerger(db).merge(kept, gone, TrackMergeLogEntity.REASON_USER_MERGE))
        return kept to gone
    }

    private fun count(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tracks").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun row(youtubeId: String, file: String? = null): Long = db.trackDao().insert(
        TrackEntity(
            title = "Starting Over", artist = ARTIST, durationMs = 304_000L, youtubeId = youtubeId,
            source = MusicSource.YOUTUBE, filePath = file, isDownloaded = file != null,
            canonicalTitle = TrackCanonical.title("Starting Over"), canonicalArtist = TrackCanonical.artist(ARTIST),
        ),
    )

    private fun repo() = MusicRepositoryImpl(
        context = context,
        trackDao = db.trackDao(),
        playlistDao = db.playlistDao(),
        syncHistoryDao = mockk(relaxed = true),
        downloadQueueDao = db.downloadQueueDao(),
        discoveryQueueDao = mockk(relaxed = true),
        blocklistGuard = mockk(relaxed = true),
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        libraryScopePreference = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true),
        syncPreferencesManager = mockk(relaxed = true),
        singleTrackDownloadEnqueuer = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        sharedMixDao = mockk(relaxed = true),
        downloadJobs = com.stash.core.data.sync.DownloadJobRegistry(),
    )

    private companion object {
        const val ARTIST = "LSD and the Search for God"
    }
}
