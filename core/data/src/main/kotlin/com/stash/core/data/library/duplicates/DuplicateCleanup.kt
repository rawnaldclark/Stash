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
    ) {
        operator fun plus(o: Summary) = Summary(
            scanned + o.scanned, merged + o.merged, removed + o.removed, leftForReview + o.leftForReview, skipped + o.skipped,
        )
    }

    private val mutex = Mutex()

    /**
     * Runs the cleanup unless it already ran on this install ([DuplicateCleanupState]); one run at a
     * time. A re-arm that lands during a run (a merged backup) makes it run again before returning.
     *
     * @return what the last run did, or null when it was already done.
     */
    suspend fun runIfPending(now: () -> Long = System::currentTimeMillis): Summary? = mutex.withLock {
        var last: Summary? = null
        while (true) {
            val pass = state.pendingPass() ?: break
            canonicalRecompute.runIfPending()
            val summary = run(now = now)
            last = summary
            if (!state.markDone(pass, summary, now())) {
                Log.i(TAG, "re-armed during the run; running again")
            }
        }
        last
    }

    /**
     * One pass over every stub (see [DuplicateCleanup]). Safe to call again: a tidied library gives
     * an empty [Summary] apart from [Summary.scanned].
     */
    suspend fun run(batchSize: Int = BATCH_SIZE, now: () -> Long = System::currentTimeMillis): Summary {
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
        }
        Log.i(
            TAG,
            "done: ${total.scanned} stub(s) read, ${total.merged} merged, ${total.removed} unused removed, " +
                "${total.leftForReview} left for Possible duplicates (two files), ${total.skipped} skipped",
        )
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
        val twin = if (TrackCanonical.isBlankKey(stub.canonicalTitle, stub.canonicalArtist)) {
            null
        } else {
            trackDao.findAllByCanonicalIdentity(stub.canonicalTitle, stub.canonicalArtist)
                .filter { it.id != stub.id && DuplicateRules.isIdentified(it) && DuplicateRules.sameSong(it, stub) }
                .minWithOrNull(DuplicateRules.keeperOrder)
        }
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
        val merged = if (stubFile) {
            merger.mergeInTransaction(keeperId = stub.id, loserId = twin.id, reason = TrackMergeLogEntity.REASON_AUTO_MERGE, now = now)
        } else {
            merger.mergeInTransaction(keeperId = twin.id, loserId = stub.id, reason = TrackMergeLogEntity.REASON_AUTO_MERGE, now = now)
        }
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
