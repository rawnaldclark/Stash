package com.stash.core.data.library

import com.stash.core.data.db.dao.TrackDao
import com.stash.data.download.files.FileOrganizer
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
 */
@Singleton
class AdoptExistingFilesUseCase @Inject constructor(
    private val trackDao: TrackDao,
    private val fileOrganizer: FileOrganizer,
) {
    suspend fun adopt(): AdoptionResult {
        val candidates = trackDao.getAdoptionCandidates()
        var adopted = 0
        for (c in candidates) {
            val match = fileOrganizer.resolveExistingSafFile(
                artist = c.artist,
                album = c.album,
                title = c.title,
            ) ?: continue

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
            adopted++
        }
        return AdoptionResult(scanned = candidates.size, adopted = adopted)
    }
}