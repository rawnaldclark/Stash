package com.stash.core.data.library

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackAdoptionCandidate
import com.stash.data.download.files.FileOrganizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an adoption pass, for the Verify summary line. */
data class AdoptionResult(val scanned: Int, val adopted: Int)

/**
 * The "recognize files already on disk" pass the tester asked for: for
 * every DB track that's a candidate for download but isn't marked
 * downloaded yet, probes the current SAF tree for a matching file by
 * the same artist/album/title slug convention FileOrganizer uses to
 * write files, and adopts it in place of queuing a network download.
 *
 * Deliberately separate from [LibraryReconciliationUseCase] — that pass
 * verifies tracks the DB already believes are downloaded and repairs or
 * requeues them (network-capable); this pass discovers tracks the DB
 * doesn't know are downloaded yet (local-only, no auth required). Verify
 * should run both; a background sync chain only needs the former.
 *
 * Perf note: this used to call FileOrganizer.resolveExistingSafFile()
 * once per candidate, which re-walks `root -> artist -> album` via
 * DocumentFile.findFile() (itself a linear listFiles() scan) on every
 * call. At a few thousand candidates that meant re-listing the same
 * artist/album directories over and over through ContentResolver IPC —
 * strictly sequential, no concurrency — which could take longer than
 * downloading the same tracks over the network. Now the SAF tree is
 * walked exactly once up front (FileOrganizer.buildSafIndex()) and
 * candidates are resolved against that in-memory index with bounded
 * concurrency, mirroring the Semaphore(8) pattern TrackDownloadWorker
 * already uses for downloads.
 */
@Singleton
class AdoptExistingFilesUseCase @Inject constructor(
    private val trackDao: TrackDao,
    private val fileOrganizer: FileOrganizer,
) {
    suspend fun adopt(): AdoptionResult {
        val candidates = trackDao.getAdoptionCandidates()
        if (candidates.isEmpty()) return AdoptionResult(scanned = 0, adopted = 0)

        // One tree walk total, instead of one per candidate. If there's no
        // external SAF tree configured, there's nothing to adopt from.
        val index = fileOrganizer.buildSafIndex()
            ?: return AdoptionResult(scanned = candidates.size, adopted = 0)

        // Matching against the in-memory index is cheap CPU work with no
        // further IPC, so a modest concurrency limit is mainly to keep the
        // DB writes (markAsDownloaded / setFormatAndQuality) from piling up
        // faster than they can commit, not to hide IO latency the way the
        // download semaphore does.
        val semaphore = Semaphore(8)

        val adoptedCount = coroutineScope {
            candidates.map { c ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        adoptOne(index, c)
                    }
                }
            }.awaitAll().count { it }
        }

        return AdoptionResult(scanned = candidates.size, adopted = adoptedCount)
    }

    /** Resolves and adopts a single candidate. Returns true if it was adopted. */
    private suspend fun adoptOne(index: FileOrganizer.SafIndex, c: TrackAdoptionCandidate): Boolean {
        val match = fileOrganizer.resolveInIndex(
            index = index,
            artist = c.artist,
            album = c.album,
            title = c.title,
        ) ?: return false

        trackDao.markAsDownloaded(
            trackId = c.id,
            filePath = match.uri.toString(),
            fileSizeBytes = match.length(),
        )

        // Best-effort format stamp from the file extension — quality_kbps
        // stays 0 (unknown) since a bare SAF listing carries no bitrate;
        // QualityInfoBackfillWorker / the format backfill pick it up later
        // exactly like any other legacy row missing that metadata.
        val ext = match.name?.substringAfterLast('.', "")?.lowercase()
        if (!ext.isNullOrBlank()) {
            runCatching { trackDao.setFormatAndQuality(c.id, ext, 0) }
        }
        return true
    }
}