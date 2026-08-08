package com.stash.core.data.library

/**
 * Abstraction over on-disk file existence checks. Defined here in
 * :core:data (rather than importing FileOrganizer directly) because
 * :data:download depends on :core:data, not the reverse — a :core:data
 * class can never hold a direct FileOrganizer reference.
 *
 * Takes artist/album/title (not just the stored path) because a cached
 * content:// URI can go stale across reinstall even when the real file
 * is present — the real implementation re-derives the file's location
 * from the current SAF tree grant using the same slug convention used
 * to write it, instead of trusting the old URI. See FileOrganizer.
 *
 * suspend because the SAF-tree lookup needs the current tree URI from
 * DataStore.
 */
fun interface FileExistenceChecker {
    suspend fun exists(
        artist: String,
        album: String?,
        title: String,
        filePath: String,
    ): FileExistenceResult
}

/**
 * @property exists Whether the file was found, by either the fast path
 *   (internal storage — filePath checked directly) or the slug-walk
 *   fallback (SAF — filePath ignored, re-derived from artist/album/title).
 * @property resolvedFilePath Non-null only when the SAF walk found the
 *   file under a URI different from the one passed in — i.e. the stored
 *   URI was stale. Callers should persist this back to heal the DB row
 *   so future checks don't re-hit the same staleness.
 */
data class FileExistenceResult(
    val exists: Boolean,
    val resolvedFilePath: String? = null,
)