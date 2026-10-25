package com.stash.core.data.sync.workers

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmApiException
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmEnrichmentBackoff
import com.stash.core.data.lastfm.LastFmTrackInfo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * [ArtBackfillWorker] asks Last.fm only about rows a lookup can fill: never
 * about voice notes and "Unknown Artist" files, under the plain title for
 * YouTube uploads, not again for rows it already couldn't fill, and not at all
 * on a run with nothing new. Real matches still fill.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ArtBackfillWorkerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var storeScope: CoroutineScope
    private lateinit var backoff: LastFmEnrichmentBackoff
    private lateinit var schedule: ArtBackfillSchedule
    private val api = mockk<LastFmApiClient>()
    private val asked = mutableListOf<Pair<String, String>>()
    private var now = 1_800_000_000_000L

    /** What Last.fm answers per (artist, title); anything else is "not found". */
    private val answers = mutableMapOf<Pair<String, String>, Result<LastFmTrackInfo>>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        backoff = LastFmEnrichmentBackoff(
            PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "backoff.preferences_pb") },
        )
        schedule = ArtBackfillSchedule(
            PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "schedule.preferences_pb") },
        )
        coEvery { api.getTrackInfo(any(), any(), any(), any()) } answers {
            val key = firstArg<String>() to secondArg<String>()
            synchronized(asked) { asked += key }
            answers[key] ?: Result.failure(LastFmApiException("6", "Track not found"))
        }
    }

    @After fun tearDown() {
        storeScope.cancel()
        db.close()
    }

    private fun worker() = TestListenableWorkerBuilder<ArtBackfillWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                ArtBackfillWorker(
                    appContext, workerParameters, db.trackDao(), api,
                    LastFmCredentials(apiKey = "k", apiSecret = "s"),
                    mockk<AudioDurationExtractor>(relaxed = true),
                    backoff, schedule,
                ).also { w -> w.clock = { now } }
        })
        .build()

    private fun info(image: String?, durationMs: Long?, album: String? = null) = LastFmTrackInfo(
        mbid = null, durationMs = durationMs, listeners = 0, playcount = 0,
        userPlaycount = null, userLoved = null, bestImageUrl = image, tags = emptyList(),
        albumTitle = album, albumArtist = album?.let { "Album Artist" },
    )

    private suspend fun downloaded(artist: String, title: String, youtubeId: String? = null): Long =
        db.trackDao().insertAll(
            listOf(
                TrackEntity(
                    title = title, artist = artist, isDownloaded = true, youtubeId = youtubeId,
                    filePath = "/music/$title.opus", durationMs = 200_000,
                ),
            ),
        ).single()

    private suspend fun stub(artist: String, title: String): Long =
        db.trackDao().insertAll(listOf(TrackEntity(title = title, artist = artist, isStreamable = true))).single()

    private suspend fun art(id: Long) = db.trackDao().getById(id)!!.albumArtUrl

    @Test fun `voice notes are never looked up and real matches still fill`() = runBlocking<Unit> {
        val voiceNote = downloaded("Unknown Artist", "AUD-20240101-WA0001")
        val upload = downloaded("Billie Eilish", "Bad Guy (Official Video)")
        val deepCut = downloaded("Nobody", "Missing", youtubeId = "dQw4w9WgXcQ")
        answers["Billie Eilish" to "Bad Guy"] = Result.success(info("https://img/badguy.png", 194_000))

        worker().doWork()

        assertThat(asked).containsExactly("Billie Eilish" to "Bad Guy", "Nobody" to "Missing")
        assertThat(art(upload)).isEqualTo("https://img/badguy.png")
        assertThat(art(deepCut)).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg")
        assertThat(art(voiceNote)).isNull()
        assertThat(backoff.backedOff(LastFmEnrichmentBackoff.Pass.ART_BACKFILL, now)).containsExactly(voiceNote)
    }

    @Test fun `a song with no album gets Last fm's, asked about once, and an album it has is kept`() = runBlocking<Unit> {
        // Already has art and a duration: only the album pass looks at it.
        val bare = db.trackDao().insertAll(
            listOf(
                TrackEntity(
                    title = "Roygbiv", artist = "Boards of Canada", isStreamable = true,
                    albumArtUrl = "https://img/mhtrtc.png", durationMs = 139_000,
                ),
            ),
        ).single()
        // A stub the stream-only pass asks about: the album pass reuses that answer.
        val stub = stub("Dead Kennedys", "Police Truck")
        val tagged = db.trackDao().insertAll(
            listOf(TrackEntity(title = "Kid A", artist = "Radiohead", album = "Kid A", isStreamable = true, albumArtUrl = "https://img/kida.png", durationMs = 1)),
        ).single()
        answers["Boards of Canada" to "Roygbiv"] = Result.success(info(null, null, album = "Music Has the Right to Children"))
        answers["Dead Kennedys" to "Police Truck"] =
            Result.success(info("https://img/gmc.png", 170_000, album = "Give Me Convenience or Give Me Death"))
        answers["Radiohead" to "Kid A"] = Result.success(info(null, null, album = "Some Compilation"))

        worker().doWork()

        val dao = db.trackDao()
        assertThat(dao.getById(bare)!!.album).isEqualTo("Music Has the Right to Children")
        assertThat(dao.getById(bare)!!.albumArtist).isEqualTo("Album Artist")
        assertThat(dao.getById(stub)!!.album).isEqualTo("Give Me Convenience or Give Me Death")
        assertThat(dao.getById(tagged)!!.album).isEqualTo("Kid A")
        assertThat(asked.count { it == ("Dead Kennedys" to "Police Truck") }).isEqualTo(1)
        assertThat(asked).doesNotContain("Radiohead" to "Kid A")
    }

    @Test fun `a second run with nothing new asks nothing`() = runBlocking<Unit> {
        stub("Nobody", "Missing")
        downloaded("Unknown Artist", "PTT-20240101-WA0002")

        worker().doWork()
        val first = asked.size
        now += 60_000 // a minute later: the next cold start
        worker().doWork()

        assertThat(first).isEqualTo(1)
        assertThat(asked.size).isEqualTo(first)
    }

    @Test fun `a new stub is served at once and the old misses are not re-asked`() = runBlocking<Unit> {
        stub("Nobody", "Missing")
        worker().doWork()
        assertThat(asked).containsExactly("Nobody" to "Missing")

        now += 60_000
        val fresh = stub("Real", "Song")
        answers["Real" to "Song"] = Result.success(info("https://img/song.png", 180_000))
        worker().doWork()

        assertThat(asked).containsExactly("Nobody" to "Missing", "Real" to "Song").inOrder()
        assertThat(art(fresh)).isEqualTo("https://img/song.png")
        assertThat(db.trackDao().getById(fresh)!!.durationMs).isEqualTo(180_000)
    }

    @Test fun `after the interval a backed-off row still waits for its back-off`() = runBlocking<Unit> {
        stub("Nobody", "Missing")
        worker().doWork()

        now += ArtBackfillSchedule.MIN_INTERVAL_MS + 1
        worker().doWork()

        assertThat(asked).containsExactly("Nobody" to "Missing")
    }

    @Test fun `a transient failure is not backed off and is asked again next run`() = runBlocking<Unit> {
        val id = stub("Real", "Song")
        answers["Real" to "Song"] = Result.failure(IOException("offline"))

        worker().doWork()
        assertThat(backoff.backedOff(LastFmEnrichmentBackoff.Pass.ART_BACKFILL, now)).isEmpty()

        now += ArtBackfillSchedule.MIN_INTERVAL_MS + 1
        answers["Real" to "Song"] = Result.success(info("https://img/song.png", 180_000))
        worker().doWork()

        assertThat(asked).containsExactly("Real" to "Song", "Real" to "Song")
        assertThat(art(id)).isEqualTo("https://img/song.png")
    }

    @Test fun `a stub answered without a duration is backed off`() = runBlocking<Unit> {
        val id = stub("Real", "Song")
        answers["Real" to "Song"] = Result.success(info("https://img/song.png", null))

        worker().doWork()

        assertThat(art(id)).isEqualTo("https://img/song.png")
        assertThat(backoff.backedOff(LastFmEnrichmentBackoff.Pass.ART_BACKFILL, now)).containsExactly(id)
    }

    @Test fun `both enqueue paths share one unique work`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        try {
            val wm = WorkManager.getInstance(context)

            ArtBackfillWorker.enqueueOneTime(context)
            ArtBackfillWorker.enqueueOneTime(context) // KEEP: still one
            assertThat(wm.getWorkInfosForUniqueWork(ArtBackfillWorker.WORK_NAME).get()).hasSize(1)

            ArtBackfillWorker.enqueueFromMixRefresh(context) // appended after it, never alongside
            val infos = wm.getWorkInfosForUniqueWork(ArtBackfillWorker.WORK_NAME).get()
            assertThat(infos).hasSize(2)
            assertThat(infos.count { it.state == WorkInfo.State.BLOCKED }).isEqualTo(1)

            val retired = wm.getWorkInfosForUniqueWork("stash_art_backfill_mix_refresh").get()
            assertThat(retired.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }).isTrue()
        } finally {
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
