package com.stash.feature.record.card

import android.os.SystemClock
import android.util.Log
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.db.dao.WebLibraryExportDao.OwnAlbumTotal
import com.stash.core.data.db.dao.WebLibraryExportDao.OwnHistoryStamp
import com.stash.feature.record.archive.OwnArchive
import com.stash.feature.record.archive.RecordArchiveSource
import com.stash.feature.record.archive.RecordSummary
import com.stash.feature.record.archive.RecordSummaryBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/** What Home's card is made from: the record's own numbers, and the albums played most (for the sleeve). */
data class CardData(val summary: RecordSummary, val albums: List<OwnAlbumTotal>)

/**
 * The card's numbers, worked out the way the record works out its own (the same rows, [OwnArchive], read by the page's rules,
 * [RecordSummaryBuilder]), so the card and the record never disagree.
 *
 * Read only when what they are made of changed, never because Home came back:
 * - the plays, skips or blocklist ([WebLibraryExportDao.ownHistoryStamp]): read once a burst settles ([SETTLE_MS]);
 * - the songs themselves ([WebLibraryExportDao.tracksWritten]: a rename, an album moved). A sync writes them many times a
 *   second, so these are read once the writes settle ([TRACKS_SETTLE_MS]) and at most once every [TRACKS_GAP_MS].
 * The result is kept with its stamp: a new Home after a while (the sharing stopped) reads nothing for the same history. Off the
 * main thread, one change at a time; one app-wide copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RecordCardSource internal constructor(
    private val stamps: Flow<OwnHistoryStamp>,
    private val tracks: Flow<Long>,
    private val archive: RecordArchiveSource,
    private val albums: suspend () -> List<OwnAlbumTotal>,
    scope: CoroutineScope,
    /** Where the history is read and summed: off the main thread, one thing at a time. */
    work: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    @Inject
    constructor(dao: WebLibraryExportDao) : this(
        stamps = dao.ownHistoryStamp(),
        tracks = dao.tracksWritten(),
        archive = OwnArchive(dao),
        albums = dao::ownAlbumTotals,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @Volatile private var kept: Pair<OwnHistoryStamp, CardData>? = null

    private sealed interface Change {
        class History(val stamp: OwnHistoryStamp) : Change
        data object Tracks : Change
    }

    /** The latest card data; the first as soon as it is read (or kept), each later one as above. */
    val data: Flow<CardData> = channelFlow {
        var latest: OwnHistoryStamp? = null
        // The songs changed since the last read: the next reads afresh even for a history it has kept
        var tracksDirty = false
        var lastTracksRead = Long.MIN_VALUE / 2
        // Waiting to ask for a read (cancelled and set again as writes come); the read itself is never cancelled by a write
        var timer: Job? = null
        var timerForHistory = false
        val reads = Channel<Unit>(Channel.CONFLATED)

        launch {
            for (go in reads) {
                val stamp = latest ?: continue
                val fresh = tracksDirty
                tracksDirty = false
                if (fresh) lastTracksRead = now()
                val data = kept?.takeIf { !fresh && it.first == stamp }?.second ?: read()?.also { kept = stamp to it }
                if (data != null) send(data)
            }
        }

        fun readIn(waitMs: Long, history: Boolean) {
            timer?.cancel()
            timerForHistory = history
            timer = launch {
                delay(waitMs)
                reads.send(Unit)
            }
        }

        merge(stamps.distinctUntilChanged().map { Change.History(it) }, tracks.drop(1).map { Change.Tracks }).collect { change ->
            when (change) {
                is Change.History -> {
                    val first = latest == null
                    latest = change.stamp
                    if (first) reads.send(Unit) else readIn(SETTLE_MS, history = true)
                }
                Change.Tracks -> {
                    tracksDirty = true
                    // A read for the history is coming soon anyway (and reads the songs too); else one once the writes settle
                    val historyComing = timerForHistory && timer?.isActive == true
                    if (latest != null && !historyComing) {
                        readIn(maxOf(TRACKS_SETTLE_MS, lastTracksRead + TRACKS_GAP_MS - now()), history = false)
                    }
                }
            }
        }
    }
        .flowOn(work)
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_MS), replay = 1)

    private suspend fun read(): CardData? = try {
        val summary = RecordSummaryBuilder()
        archive.stream(CHUNK) { rows, _ -> rows.forEach(summary::add) }
        CardData(summary.build(), albums())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The card keeps what it had; the next change reads again
        Log.w(TAG, "the card's plays couldn't be read: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "YourRecord"

        /** A burst of history changes is read once, this long after the last. */
        const val SETTLE_MS = 2_000L

        /** Songs written (a sync): read once they have been quiet this long... */
        const val TRACKS_SETTLE_MS = 5_000L

        /** ...and at most once in this long. */
        const val TRACKS_GAP_MS = 60_000L

        /** Kept listening this long after Home goes (a tab switch and back reads nothing). */
        const val STOP_MS = 60_000L

        private const val CHUNK = 5_000
    }
}
