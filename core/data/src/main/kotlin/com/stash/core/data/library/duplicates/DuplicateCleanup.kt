package com.stash.core.data.library.duplicates

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackMergeLogEntity
import com.stash.core.data.library.CanonicalRecomputeRunner
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one-time duplicate cleanup after schema 76 (plan 2026-11-01 §2 (c) step 4; owner decision
 * D2.1): only the certain cases, and never a file.
 *
 * It walks every id-less stub ([DuplicateRules.isStub]: no video id, no Spotify id, saved as
 * YouTube; on the owner's phone on 10-21, 5,480 of them, 5,258 in no playlist) and for each:
 *  1. **Merges it** into a row of the same song that has an id ([DuplicateRules.sameSong]: the
 *     library-copy rule of the duplicate guards, so the same take, explicit flags that agree, never
 *     a flagged row). The row that stays is the identified one, best by
 *     [DuplicateRules.keeperOrder] (downloaded first); everything the stub had moves to it
 *     ([TrackMerger]). A stub that holds the song's only file is the row that stays instead, and
 *     takes the other row's ids: the downloaded row is kept, and so is the identity.
 *     With no identified row, two stubs of one song by the same rule are merged into one (the one
 *     with the file, else the more played, else the older): discovery filed the same song once per
 *     mix (557 such pairs left on the 10-21 backup without this), as certain as the first case.
 *  2. **Leaves it** for Possible duplicates when both rows hold a file: removing a file needs the
 *     user's one confirmation (D18), so the automatic pass never deletes one.
 *  3. **Deletes it** when it has no twin and nothing uses it ([com.stash.core.data.db.dao.DuplicateDao.deleteIfUnusedStub]:
 *     no playlist, play, like, file, download, and not in a Stash Mix's pool of finds). A stub
 *     with plays or likes is kept, so Your record and History keep every listen.
 *  4. **Skips it** while a download for it waits or runs (a file may land on it).
 *
 * Every decision is made from a fresh read inside the transaction that acts on it, [BATCH_SIZE]
 * stubs per transaction, so a sync or a mix refresh that links a stub a moment earlier is seen and
 * respected. Resumable by being idempotent: a stopped run loses at most its open batch (rolled
 * back), and running again finds only what is left; a second run over a tidied library changes
 * nothing. The canonical re-key runs first, so stubs and rows compare in today's words.
 *
 * Every merge and deletion is written to `track_merge_log` (no undo; see [TrackMerger]).
 */
@Singleton
class DuplicateCleanup @Inject constructor(
    private val database: StashDatabase,
    private val merger: TrackMerger,
    private val state: DuplicateCleanupState,
    private val canonicalRecompute: CanonicalRecomputeRunner,
) {

    /** What a run did. */
    data class Summary(
        /** Stubs read. */
        val scanned: Int = 0,
        /** Rows merged into another row of their song. */
        val merged: Int = 0,
        /** Unused stubs deleted. */
        val removed: Int = 0,
        /** Stubs whose twin also holds a file: left for Possible duplicates. */
        val leftForReview: Int = 0,
        /** Stubs with a download waiting or running, or a merge [TrackMerger] refused. */
        val skipped: Int = 0,
        /** The run stopped between batches because the library got busy; the next run finishes it. */
        val stoppedEarly: Boolean = false,
    ) {
        operator fun plus(o: Summary) = Summary(
            scanned + o.scanned, merged + o.merged, removed + o.removed, leftForReview + o.leftForReview, skipped + o.skipped,
            stoppedEarly || o.stoppedEarly,
        )
    }

    private val mutex = Mutex()

    /**
     * Runs the cleanup unless it already ran on this install ([DuplicateCleanupState]); one run at a
     * time. A re-arm that lands during a run (a merged backup) makes it run again before returning.
     * A run that [yieldWhen] stops early is not marked done: what it did is kept for the notice,
     * and the next run finishes the rest.
     *
     * @param yieldWhen asked between batches; true stops the run there (a sync or a mix refresh
     *   started, and would trip over rows merged away under it).
     * @return what the last run did ([Summary.stoppedEarly] when it yielded), or null when it was
     *   already done.
     */
    suspend fun runIfPending(
        now: () -> Long = System::currentTimeMillis,
        batchSize: Int = BATCH_SIZE,
        yieldWhen: suspend () -> Boolean = { false },
    ): Summary? = mutex.withLock {
        var last: Summary? = null
        while (true) {
            val pass = state.pendingPass() ?: break
            canonicalRecompute.runIfPending()
            val summary = run(batchSize = batchSize, now = now, yieldWhen = yieldWhen)
            last = summary
            if (summary.stoppedEarly) {
                state.recordPartial(summary)
                break
            }
            if (!state.markDone(pass, summary, now())) {
                Log.i(TAG, "re-armed during the run; running again")
            }
        }
        last
    }

    /**
     * One pass over every stub (see [DuplicateCleanup]). Safe to call again: a tidied library gives
     * an empty [Summary] apart from [Summary.scanned]. Stops after a batch when [yieldWhen] says so.
     */
    suspend fun run(
        batchSize: Int = BATCH_SIZE,
        now: () -> Long = System::currentTimeMillis,
        yieldWhen: suspend () -> Boolean = { false },
    ): Summary {
        require(batchSize > 0) { "batchSize must be positive" }
        val dao = database.duplicateDao()
        Log.i(TAG, "checking ${dao.stubCount()} id-less stub(s)")
        var total = Summary()
        var cursor = 0L
        while (true) {
            coroutineContext.ensureActive()
            val batch = dao.stubsAfter(cursor, batchSize)
            if (batch.isEmpty()) break
            total += database.withTransaction {
                batch.fold(Summary(scanned = batch.size)) { acc, stub -> acc + tidy(stub.id, now()) }
            }
            cursor = batch.last().id
            if (batch.size < batchSize) break
            if (yieldWhen()) {
                Log.i(TAG, "the library got busy; stopping after id $cursor, the next run picks up the rest")
                total = total.copy(stoppedEarly = true)
                break
            }
        }
        Log.i(
            TAG,
            "done: ${total.scanned} stub(s) read, ${total.merged} merged, ${total.removed} unused removed, " +
                "${total.leftForReview} left for Possible duplicates (two files), ${total.skipped} skipped",
        )
        // TEMP diagnostic: is tracks_fts still consistent after the cleanup?
        try {
            database.openHelper.writableDatabase.query("PRAGMA integrity_check").use { c ->
                val rows = mutableListOf<String>()
                while (c.moveToNext() && rows.size < 5) rows += c.getString(0)
                Log.i(TAG, "post-cleanup integrity: ${rows.joinToString(" | ")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "post-cleanup integrity check failed to run", e)
        }
        return total
    }

    /** One stub, inside the batch's transaction, from a fresh read. */
    private suspend fun tidy(stubId: Long, now: Long): Summary {
        val trackDao = database.trackDao()
        val dao = database.duplicateDao()
        val stub = trackDao.getById(stubId) ?: return Summary()
        if (!DuplicateRules.isStub(stub) || !DuplicateRules.mayMerge(stub)) return Summary()
        if (dao.activeDownloadCount(stub.id) > 0) return Summary(skipped = 1)

        // A blank key ("(Intro)", no artist) names no song, and would read every local import.
        val sameSong = if (TrackCanonical.isBlankKey(stub.canonicalTitle, stub.canonicalArtist)) {
            emptyList()
        } else {
            trackDao.findAllByCanonicalIdentity(stub.canonicalTitle, stub.canonicalArtist)
                .filter { it.id != stub.id && DuplicateRules.sameSong(it, stub) }
        }
        // The row with an id first; with none, another stub of the song.
        val twin = sameSong.filter(DuplicateRules::isIdentified).minWithOrNull(DuplicateRules.keeperOrder)
            ?: sameSong.filter(DuplicateRules::isStub).minWithOrNull(DuplicateRules.keeperOrder)
        if (twin == null) {
            return if (dao.deleteIfUnusedStub(stub.id) == 1) {
                dao.log(orphanLine(stub, now))
                Summary(removed = 1)
            } else {
                Summary()
            }
        }
        val stubFile = DuplicateRules.holdsFile(stub)
        val twinFile = DuplicateRules.holdsFile(twin)
        if (stubFile && twinFile) return Summary(leftForReview = 1)
        val (keeper, loser) = when {
            stubFile -> stub to twin
            twinFile || DuplicateRules.isIdentified(twin) -> twin to stub
            // Two stubs, neither with a file: the more played, else the older.
            else -> listOf(stub, twin).sortedWith(DuplicateRules.keeperOrder).let { it[0] to it[1] }
        }
        val merged = merger.mergeInTransaction(
            keeperId = keeper.id, loserId = loser.id, reason = TrackMergeLogEntity.REASON_AUTO_MERGE, now = now,
        )
        return if (merged != null) Summary(merged = 1) else Summary(skipped = 1)
    }

    private fun orphanLine(stub: TrackEntity, now: Long) = TrackMergeLogEntity(
        at = now,
        reason = TrackMergeLogEntity.REASON_AUTO_ORPHAN,
        keptTrackId = null,
        removedTrackId = stub.id,
        title = stub.title,
        artist = stub.artist,
        detail = TrackMerger.rowDetail(stub).toString(),
    )

    companion object {
        private const val TAG = "DuplicateCleanup"

        /** Stubs per read and per transaction: ~28 short transactions for the owner's 5,480. */
        const val BATCH_SIZE = 200
    }
}
