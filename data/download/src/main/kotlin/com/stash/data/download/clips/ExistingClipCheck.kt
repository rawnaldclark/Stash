package com.stash.data.download.clips

import android.util.Log
import com.stash.core.data.db.dao.DownloadedFlacRow
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.files.RedownloadKeepingFile
import com.stash.data.download.clipOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one-time check of songs downloaded before 0.9.125 for a clip saved as the song.
 *
 * Before 0.9.125 a download's length checks read the file's header, and a Tidal preview remuxed to
 * FLAC keeps the whole song's header: "Linger" on the Pixel 5 said 4:34 and stopped at 0:30. The
 * downloads now read where the audio really ends ([clipOf], #908). This reads it once for the
 * songs already on the phone: each downloaded FLAC's tail ([FlacLengthsReader], a few frames, not
 * the file), and a file whose audio runs 35 s or less AND under half its header's length or the
 * song's known length (the #888 rule, through [clipOf]) is downloaded again
 * ([RedownloadKeepingFile]): as the person's own request, the old file kept until the new one lands.
 *
 * What it leaves alone:
 *  - the person's own files: imported ('LOCAL'), from their cloud ('CLOUD'), All Songs scans
 *    ([TrackDao.downloadedFlacsAfter] never lists them);
 *  - a file it can't settle: gone, unreadable, not really a FLAC, or an end it can't confirm. Never
 *    a reason to download again, and never a file deleted;
 *  - AAC and other lossy files: an MP4's length lives in its sample tables at the front, not in
 *    frames at the end, so this tail read can't measure it; finding a short one means walking the
 *    file's packets, which is not cheap enough to do for a whole library at once.
 *
 * Cheap on a large library: pages of [PAGE_SIZE] rows, read off the main thread, one small read per
 * file. A stop (the app killed, a constraint lost) loses at most the page under way: the cursor in
 * [ExistingClipCheckState] moves after each page, and the next run carries on from there.
 */
@Singleton
class ExistingClipCheck @Inject constructor(
    private val trackDao: TrackDao,
    private val reader: FlacLengthsReader,
    private val redownload: RedownloadKeepingFile,
    private val state: ExistingClipCheckState,
) {

    /** What a run that reached the end did. */
    data class Summary(val checked: Int, val unreadable: Int, val queued: Int)

    /** Rows per page; a test makes pages of one to stop between them. */
    internal var pageSize: Int = PAGE_SIZE

    /**
     * Runs the check unless it already reached the end on this install.
     *
     * @return what this run did, or null when the check was already done.
     */
    suspend fun runIfPending(): Summary? = withContext(Dispatchers.IO) {
        if (state.isDone()) return@withContext null
        // Songs an earlier run handed back: one stopped between the hand-back and giving the download
        // to WorkManager left its song waiting with no job behind it (made good below).
        val carried = state.queued()
        var cursor = state.cursor()
        var checked = 0
        var unreadable = 0
        while (true) {
            val page = trackDao.downloadedFlacsAfter(cursor, pageSize)
            if (page.isEmpty()) break
            for (row in page) {
                currentCoroutineContext().ensureActive()
                checked++
                if (!checkOne(row)) unreadable++
            }
            cursor = page.last().id
            state.advance(cursor)
            if (page.size < pageSize) break
        }
        // This run gave its own songs to WorkManager as it handed them back; an earlier run's are given
        // over again (a no-op for the ones already on their way).
        carried.forEach { redownload.resumeQueued(it) }
        val queued = state.queued()
        state.markDone()
        Log.i(TAG, "checked $checked downloaded FLACs ($unreadable not readable); ${queued.size} downloading again")
        Summary(checked = checked, unreadable = unreadable, queued = queued.size)
    }

    /**
     * Checks [row]'s file and hands a clip back for a download.
     *
     * @return false when the file could not be read (gone, not a FLAC, no end it can confirm).
     */
    private suspend fun checkOne(row: DownloadedFlacRow): Boolean {
        val lengths = reader.read(row.filePath) ?: return false
        val clip = clipOf(lengths.headerMs, lengths.framesMs, row.durationMs) ?: return true
        // Written first: a stop before the hand-back checks this song again (the cursor is not past it).
        state.recordQueued(row.id)
        when (redownload.queue(row.id)) {
            RedownloadKeepingFile.Outcome.NOT_DOWNLOADED -> state.forgetQueued(row.id)
            else -> Log.i(
                TAG,
                "track ${row.id}: the file plays ${clip.playsMs} ms (header ${clip.headerMs} ms, song ${clip.expectedMs} ms); downloading it again",
            )
        }
        return true
    }

    internal companion object {
        private const val TAG = "ExistingClipCheck"

        /** Rows per page: small enough that a stop loses little, large enough that 10k songs are 50 queries. */
        const val PAGE_SIZE = 200
    }
}
