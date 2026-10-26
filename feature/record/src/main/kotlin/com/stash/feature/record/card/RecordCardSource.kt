package com.stash.feature.record.card

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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** What Home's card is made from: the record's own numbers, and the albums played most (for the sleeve). */
data class CardData(val summary: RecordSummary, val albums: List<OwnAlbumTotal>)

/**
 * The card's numbers, worked out the way the record works out its own (the same rows, [OwnArchive], read by the page's rules,
 * [RecordSummaryBuilder]), so the card and the record never disagree.
 *
 * Read only when the history changed: the plays, skips and blocklist stamp ([WebLibraryExportDao.ownHistoryStamp]) wakes it,
 * a burst of changes (a song ending, its skip, the next starting) is read once after it settles ([SETTLE_MS]), and the result
 * is kept with its stamp, so Home coming back, or a new Home after a while, reads nothing again unless the stamp moved. Off the
 * main thread; one app-wide copy.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Singleton
class RecordCardSource internal constructor(
    private val stamps: Flow<OwnHistoryStamp>,
    private val archive: RecordArchiveSource,
    private val albums: suspend () -> List<OwnAlbumTotal>,
    scope: CoroutineScope,
) {
    @Inject
    constructor(dao: WebLibraryExportDao) : this(
        stamps = dao.ownHistoryStamp(),
        archive = OwnArchive(dao),
        albums = dao::ownAlbumTotals,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @Volatile private var kept: Pair<OwnHistoryStamp, CardData>? = null

    /** The latest card data; the first as soon as it is read (or kept), each later one after the history settles. */
    val data: Flow<CardData> = flow {
        var first = true
        emitAll(
            stamps.distinctUntilChanged().debounce {
                if (first) {
                    first = false
                    0L
                } else {
                    SETTLE_MS
                }
            },
        )
    }
        .mapLatest { stamp -> kept?.takeIf { it.first == stamp }?.second ?: read()?.also { kept = stamp to it } }
        .filterNotNull()
        .flowOn(Dispatchers.Default)
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

        /** Kept listening this long after Home goes (a tab switch and back reads nothing). */
        const val STOP_MS = 60_000L

        private const val CHUNK = 5_000
    }
}
