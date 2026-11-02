package com.stash.core.data.library.duplicates

import android.util.Log
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.fileUsedByAnotherTrack
import com.stash.core.data.db.entity.DuplicateDismissalEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackMergeLogEntity
import com.stash.core.data.files.LocalFileOps
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Library Health → Possible duplicates (plan 2026-11-01 §2 (c) step 5; owner decisions D2.2, D2.3,
 * D18): the pairs the automatic cleanup leaves to the user, each with a Merge and a "Not a
 * duplicate".
 *
 * A pair is two mergeable rows ([DuplicateRules.mayMergePair]: never a flagged wrong match, never
 * explicit against clean, never a local, cloud or SoundCloud song) with the same canonical words,
 * and one of [DuplicateKind]:
 *  - [DuplicateKind.TWO_UPLOADS]: one song by every rule, but both rows have ids, so they are two uploads or
 *    two listings (another video of a song downloaded from Spotify, an album Spotify lists twice).
 *    Two uploads can be two recordings that share a title, so these are offered, never merged by
 *    themselves (D2.2).
 *  - [DuplicateKind.TWO_FILES]: one song, and both rows hold a file. Merging removes one file, which needs
 *    the one confirmation (D18).
 *  - [DuplicateKind.VERSION_WORDS]: the same title and artist, lengths within 10 s, but different version
 *    words ("Song" and "Song (Live)"). Only the user can tell.
 *  - [DuplicateKind.SAME_SONG]: one song with nothing in the way: what the cleanup merges by itself, offered
 *    here only when it was skipped (a download was running) or before it ran.
 *
 * Each row appears at most once as the other half of a pair, next to the best row it pairs with
 * ([DuplicateRules.keeperOrder]: downloaded, then lossless, then more played), which is the
 * suggested keeper. A pair the user dismissed never comes back ([dismiss]).
 *
 * Everything here runs on the caller's dispatcher; call it off the main thread.
 */
@Singleton
class PossibleDuplicates @Inject constructor(
    private val database: StashDatabase,
    private val merger: TrackMerger,
    private val localFileOps: LocalFileOps,
) {

    /** What a Merge did. */
    sealed interface MergeResult {
        /** Merged; [removedFile] when the extra file was deleted from the phone. */
        data class Merged(val keptId: Long, val removedId: Long, val removedFile: Boolean) : MergeResult

        /** Nothing changed: the pair is no longer mergeable (a row is gone, flagged, downloading, or would lose its only file). */
        data object Refused : MergeResult

        /**
         * Nothing changed: the merge would now delete a file the user wasn't asked about (a
         * download finished after the list was read). Ask "Remove the extra copy?", then merge
         * again with `confirmed`.
         */
        data object NeedsConfirmation : MergeResult

        /**
         * Nothing changed: the row to keep says it has a file, but that file isn't on the phone,
         * while the other row's is. Keeping it would leave the song with no audio: keep the other.
         */
        data object KeptFileMissing : MergeResult
    }

    /**
     * Every pair to offer now, in library order (artist, then title). Reads the rows that share
     * canonical words once (index-backed, chunked); pairing is per key, so it stays cheap on a
     * large library.
     */
    suspend fun find(): List<DuplicatePair> {
        val dao = database.duplicateDao()
        val keys = dao.sharedCanonicalKeys()
        if (keys.isEmpty()) return emptyList()
        val dismissed = dao.allDismissals().mapTo(HashSet()) { it.trackA to it.trackB }
        return dao.rowsWithCanonicalKeys(keys)
            .filter(DuplicateRules::mayMerge)
            .groupBy { it.canonicalTitle to it.canonicalArtist }
            .values
            .flatMap { pairsIn(it, dismissed) }
            .sortedWith(compareBy({ it.keeper.artist.lowercase() }, { it.keeper.title.lowercase() }, { it.other.id }))
    }

    /** "Not a duplicate": [a] and [b] are never offered as a pair again. */
    suspend fun dismiss(a: Long, b: Long, now: Long = System.currentTimeMillis()) {
        if (a == b) return
        database.duplicateDao().dismiss(listOf(DuplicateDismissalEntity.of(a, b, now)))
    }

    /**
     * Merges [otherId] into [keeperId] ([TrackMerger]) and, when both hold a different file and the
     * user said yes to "Remove the extra copy?" ([confirmed]), deletes the merged-away row's file.
     *
     * The rows are read again here, not trusted from the list on screen: a download that finished
     * since the list was read gives [MergeResult.NeedsConfirmation] instead of a silent delete, and
     * a row to keep whose file isn't really on the phone (a stale "downloaded" mark) gives
     * [MergeResult.KeptFileMissing] instead of a merge that leaves the song without audio. The file
     * goes only after the rows are merged, only while the kept row's own file is on the phone, and
     * only if no other row still plays from it: a failure leaves at worst a leftover file, never a
     * row without audio.
     */
    suspend fun merge(
        keeperId: Long,
        otherId: Long,
        confirmed: Boolean,
        now: Long = System.currentTimeMillis(),
    ): MergeResult {
        val trackDao = database.trackDao()
        val keeper = trackDao.getById(keeperId) ?: return MergeResult.Refused
        val other = trackDao.getById(otherId) ?: return MergeResult.Refused
        val otherFile = other.filePath?.takeIf { DuplicateRules.holdsFile(other) && it.isNotBlank() }
        if (otherFile != null && DuplicateRules.holdsFile(keeper)) {
            val keptOnPhone = localFileOps.exists(keeper.filePath)
            val otherOnPhone = !localFileOps.isSameFile(otherFile, keeper.filePath) && localFileOps.exists(otherFile)
            if (otherOnPhone && !keptOnPhone) return MergeResult.KeptFileMissing
            if (otherOnPhone && !confirmed) return MergeResult.NeedsConfirmation
        }
        val merged = merger.merge(keeperId, otherId, TrackMergeLogEntity.REASON_USER_MERGE, now)
            ?: return MergeResult.Refused
        val path = merged.removed.filePath?.takeIf { DuplicateRules.holdsFile(merged.removed) && it.isNotBlank() }
        val removedFile = confirmed && path != null &&
            !localFileOps.isSameFile(path, merged.kept.filePath) &&
            localFileOps.exists(merged.kept.filePath) &&
            !trackDao.fileUsedByAnotherTrack(path, merged.removed.id) &&
            deleteQuietly(path)
        return MergeResult.Merged(keptId = merged.kept.id, removedId = merged.removed.id, removedFile = removedFile)
    }

    private fun deleteQuietly(path: String): Boolean = try {
        localFileOps.delete(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "couldn't delete the extra copy; it stays on the phone", e)
        false
    }

    companion object {
        private const val TAG = "PossibleDuplicates"

        /** The pairs within one canonical key's rows (see [PossibleDuplicates]). */
        internal fun pairsIn(rows: List<TrackEntity>, dismissed: Set<Pair<Long, Long>>): List<DuplicatePair> {
            if (rows.size < 2) return emptyList()
            val ranked = rows.sortedWith(DuplicateRules.keeperOrder)
            val pairs = ArrayList<DuplicatePair>()
            for (i in 1 until ranked.size) {
                val other = ranked[i]
                for (j in 0 until i) {
                    val keeper = ranked[j]
                    if ((minOf(keeper.id, other.id) to maxOf(keeper.id, other.id)) in dismissed) continue
                    val kind = kindOf(keeper, other) ?: continue
                    pairs += DuplicatePair(keeper, other, kind)
                    break
                }
            }
            return pairs
        }

        /** Why [a] and [b] are offered, or null when they aren't a possible duplicate. */
        internal fun kindOf(a: TrackEntity, b: TrackEntity): DuplicateKind? = when {
            DuplicateRules.sameSong(a, b) -> when {
                DuplicateRules.holdsFile(a) && DuplicateRules.holdsFile(b) -> DuplicateKind.TWO_FILES
                DuplicateRules.isIdentified(a) && DuplicateRules.isIdentified(b) -> DuplicateKind.TWO_UPLOADS
                else -> DuplicateKind.SAME_SONG
            }
            DuplicateRules.mightBeSameSong(a, b) -> DuplicateKind.VERSION_WORDS
            else -> null
        }
    }
}

/** Why a pair is offered in Possible duplicates (see [PossibleDuplicates]). */
enum class DuplicateKind { SAME_SONG, TWO_UPLOADS, TWO_FILES, VERSION_WORDS }

/** One offered pair: [keeper] is the suggested row to keep, [other] the one that would go. */
data class DuplicatePair(val keeper: TrackEntity, val other: TrackEntity, val kind: DuplicateKind) {
    /** Both rows hold a different file: a merge removes one, after the one confirmation. */
    val removesAFile: Boolean
        get() = DuplicateRules.holdsFile(keeper) && DuplicateRules.holdsFile(other) && keeper.filePath != other.filePath

    /** Whether the user may keep [row]: when only one of the two holds a file, that one must stay. */
    fun mayKeep(row: TrackEntity): Boolean =
        DuplicateRules.holdsFile(row) || !(DuplicateRules.holdsFile(keeper) || DuplicateRules.holdsFile(other))
}
