package com.stash.data.download.clips

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.RedownloadKeepingFile
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The one-time check of existing downloads for a clip saved as the song (0.9.125): real files, a
 * real database and a real DataStore; only WorkManager's hand-off is a mock.
 *
 * The clip is the shape "Linger" took on the Pixel 5 (fixture README): 29.907 s of frames under a
 * header that says 274.773 s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ExistingClipCheckTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: StashDatabase
    private lateinit var context: Context
    private val enqueuer = mockk<SingleTrackDownloadEnqueuer>(relaxed = true)

    /** Every path the check read, in order. */
    private val reads = mutableListOf<String>()

    /** Runs before each read; a test makes it stop the run, or change the library under it. */
    private var beforeRead: (String) -> Unit = {}

    private lateinit var state: ExistingClipCheckState
    private lateinit var check: ExistingClipCheck

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        state = newState("clip_check")
        check = newCheck(state)
    }

    @After fun tearDown() { db.close() }

    private fun newState(name: String) =
        ExistingClipCheckState(PreferenceDataStoreFactory.create { File(tmp.root, "$name.preferences_pb") })

    private fun newCheck(state: ExistingClipCheckState): ExistingClipCheck {
        val real = FlacTailReader(context)
        val reader = FlacLengthsReader { path -> reads += path; beforeRead(path); real.read(path) }
        val redownload = RedownloadKeepingFile(db, db.trackDao(), db.downloadQueueDao(), DownloadJobRegistry(), enqueuer)
        return ExistingClipCheck(db.trackDao(), reader, redownload, state)
    }

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/flac/tidal-preview-shape.flac")) { "missing flac/tidal-preview-shape.flac" }
            .use { it.readBytes() }

    /** The fixture with a header that tells the truth: 322 frames of 4096 samples, 29.907 s. */
    private fun honest(): ByteArray = fixture().also {
        val total = 322L * 4096
        it[21] = ((it[21].toInt() and 0xF0) or ((total shr 32).toInt() and 0x0F)).toByte()
        it[22] = (total shr 24).toByte()
        it[23] = (total shr 16).toByte()
        it[24] = (total shr 8).toByte()
        it[25] = total.toByte()
    }

    private fun file(bytes: ByteArray): File = tmp.newFile("${System.nanoTime()}.flac").apply { writeBytes(bytes) }

    private suspend fun downloaded(
        title: String,
        file: File,
        durationMs: Long,
        source: MusicSource = MusicSource.SPOTIFY,
    ): Long = db.trackDao().insert(
        TrackEntity(
            title = title, artist = "The Cranberries",
            canonicalTitle = title.lowercase(), canonicalArtist = "the cranberries",
            source = source,
            durationMs = durationMs,
            filePath = file.absolutePath,
            fileFormat = "flac",
            isDownloaded = true,
        ),
    )

    private fun queueRows(trackId: Long): Int =
        db.query("SELECT COUNT(*) FROM download_queue WHERE track_id = ?", arrayOf(trackId)).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun `a download that is a clip of the song downloads again as the person's own request, its file kept`() = runTest {
        val clip = file(fixture())
        val id = downloaded("Linger", clip, durationMs = 274_773)
        // The song's old row from the download that saved the clip: reused, as a tap reuses it.
        val oldRow = db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = id, searchQuery = "The Cranberries - Linger", status = DownloadStatus.COMPLETED),
        )

        val summary = check.runIfPending()!!

        assertThat(summary.queued).isEqualTo(1)
        val track = db.trackDao().getById(id)!!
        assertThat(track.isDownloaded).isFalse() // handed back for the download...
        assertThat(track.filePath).isEqualTo(clip.absolutePath) // ...keeping its file until the new one lands
        assertThat(clip.exists()).isTrue()
        val row = db.downloadQueueDao().getByTrackId(id)!!
        assertThat(row.id).isEqualTo(oldRow)
        assertThat(row.status).isEqualTo(DownloadStatus.PENDING)
        assertThat(row.userRequested).isTrue() // no cleanup of a playlist's downloads drops it
        coVerify(exactly = 1) { enqueuer.enqueue(oldRow) }
        assertThat(state.pendingNotice.first()).isEqualTo(1)
    }

    @Test
    fun `a whole download is left alone, whether or not the song's length is known`() = runTest {
        val short = downloaded("Intro", file(honest()), durationMs = 29_907) // a short song, all of it
        val unknown = downloaded("Untitled", file(honest()), durationMs = 0)

        val summary = check.runIfPending()!!

        assertThat(summary.checked).isEqualTo(2)
        assertThat(summary.queued).isEqualTo(0)
        for (id in listOf(short, unknown)) {
            assertThat(db.trackDao().getById(id)!!.isDownloaded).isTrue()
            assertThat(queueRows(id)).isEqualTo(0)
        }
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
        assertThat(state.pendingNotice.first()).isNull()
    }

    @Test
    fun `it runs once`() = runTest {
        downloaded("Linger", file(fixture()), durationMs = 274_773)
        check.runIfPending()!!
        reads.clear()

        // A clip that turns up after the check reached the end is not its business.
        val later = downloaded("Dreams", file(fixture()), durationMs = 271_000)

        assertThat(check.runIfPending()).isNull()
        assertThat(reads).isEmpty()
        assertThat(db.trackDao().getById(later)!!.isDownloaded).isTrue()
        // Startup no longer enqueues it (returns before WorkManager is touched).
        assertThat(ExistingClipCheckWorker.enqueueIfPending(context, state)).isFalse()
    }

    @Test
    fun `run again from the start, it finds nothing new and queues nothing twice`() = runTest {
        val id = downloaded("Linger", file(fixture()), durationMs = 274_773)
        check.runIfPending()!!

        // Its state lost (a corrupt file is replaced by an empty one): the check runs once more.
        val again = newCheck(newState("clip_check_reset")).runIfPending()!!

        assertThat(again.queued).isEqualTo(0)
        assertThat(queueRows(id)).isEqualTo(1)
        coVerify(exactly = 1) { enqueuer.enqueue(any()) }
    }

    @Test
    fun `a run the system stopped carries on after the last page it finished, counting each song once`() = runTest {
        check.pageSize = 1
        val first = downloaded("Zombie", file(honest()), durationMs = 29_907)
        val clip = downloaded("Linger", file(fixture()), durationMs = 274_773)
        val third = downloaded("Dreams", file(honest()), durationMs = 29_907)
        val thirdPath = db.trackDao().getById(third)!!.filePath!!
        var stops = 1
        beforeRead = { path -> if (path == thirdPath && stops-- > 0) throw CancellationException("the app was killed") }

        try {
            check.runIfPending()
            fail("the run should have stopped")
        } catch (e: CancellationException) {
            // stopped on the third song
        }
        assertThat(state.isDone()).isFalse()
        reads.clear()

        val summary = check.runIfPending()!!

        assertThat(reads).containsExactly(thirdPath) // the first two pages are not read again
        assertThat(summary.queued).isEqualTo(1)
        assertThat(queueRows(clip)).isEqualTo(1)
        assertThat(db.trackDao().getById(first)!!.isDownloaded).isTrue()
        assertThat(state.pendingNotice.first()).isEqualTo(1)
    }

    @Test
    fun `a stop between handing a song back and giving its download to WorkManager is made good`() = runTest {
        val clip = downloaded("Linger", file(fixture()), durationMs = 274_773)
        // The hand-back's writes landed, the enqueue did not (the app was killed in between).
        state.recordQueued(clip)
        db.trackDao().markForReplacement(clip)
        val row = db.downloadQueueDao().insert(
            DownloadQueueEntity(trackId = clip, searchQuery = "The Cranberries - Linger", userRequested = true),
        )

        val summary = check.runIfPending()!!

        coVerify { enqueuer.enqueue(row) }
        assertThat(summary.queued).isEqualTo(1)
        assertThat(queueRows(clip)).isEqualTo(1)
    }

    @Test
    fun `a song whose file is gone is passed over, and the rest are still checked`() = runTest {
        // "Inner City Blues" on the Pixel 5 rig: the row says downloaded, no file is there.
        val gone = downloaded("Inner City Blues", File(tmp.root, "inner-city-blues.flac"), durationMs = 335_000)
        val clip = downloaded("Linger", file(fixture()), durationMs = 274_773)

        val summary = check.runIfPending()!!

        assertThat(summary.unreadable).isEqualTo(1)
        assertThat(summary.queued).isEqualTo(1)
        assertThat(db.trackDao().getById(gone)!!.isDownloaded).isTrue() // not this check's to change
        assertThat(queueRows(gone)).isEqualTo(0)
        assertThat(db.trackDao().getById(clip)!!.isDownloaded).isFalse()
        assertThat(state.isDone()).isTrue()
    }

    @Test
    fun `the person's own files are never read or fetched again`() = runTest {
        val imported = downloaded("Linger (my rip)", file(fixture()), durationMs = 274_773, source = MusicSource.LOCAL)
        val cloud = downloaded("Reckoner", file(fixture()), durationMs = 290_000, source = MusicSource.CLOUD)

        val summary = check.runIfPending()!!

        assertThat(summary.checked).isEqualTo(0)
        assertThat(reads).isEmpty()
        for (id in listOf(imported, cloud)) assertThat(db.trackDao().getById(id)!!.isDownloaded).isTrue()
    }

    @Test
    fun `a clip whose download the person removed meanwhile is not downloaded again`() = runTest {
        val clip = downloaded("Linger", file(fixture()), durationMs = 274_773)
        beforeRead = { runBlocking { db.trackDao().clearDownloadState(clip) } }

        val summary = check.runIfPending()!!

        assertThat(summary.queued).isEqualTo(0)
        assertThat(queueRows(clip)).isEqualTo(0)
        assertThat(state.pendingNotice.first()).isNull()
    }
}
