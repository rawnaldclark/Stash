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
 * [FileExistenceChecker.exists]'s trackId lets the Per-playlist library
 * layout (#198) re-derive the owning playlist exactly like the download
 * path did.
 *
 * suspend because the SAF-tree lookup needs the current tree URI from
 * DataStore.
 */
/**
 * Opens a [FileExistenceChecker] scoped to ONE bulk pass. The session
 * builds the SAF tree index at most once (lazily, on the first
 * `content://` lookup) and serves every subsequent check from memory —
 * per-track `DocumentFile.findFile` walks against a 1000+-track library
 * were the "stuck on Checking library" hang in #429. Open a fresh
 * session per pass; holding one across passes would serve stale disk
 * state.
 */
fun interface FileExistenceSessionFactory {
    fun open(): FileExistenceChecker
}

/**
 * Port for the "recognize files already on disk" adoption pass
 * (AdoptExistingFilesUseCase in :data:download — same module-inversion reason as
 * [FileExistenceChecker]). Returns how many tracks were adopted. Self-gating:
 * free when there are no undownloaded candidates or no external tree grant.
 */
fun interface FileAdopter {
    suspend fun adoptAll(): Int
}

fun interface FileExistenceChecker {
    suspend fun exists(
        trackId: Long,
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