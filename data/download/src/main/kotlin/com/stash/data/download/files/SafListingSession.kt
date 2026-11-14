package com.stash.data.download.files

import androidx.documentfile.provider.DocumentFile

/**
 * Pass-scoped cache of SAF directory listings: relative dir key → that
 * directory's children by name.
 *
 * `DocumentFile.findFile()` is not a lookup — it runs a full
 * `listFiles()` children query over binder IPC and linearly scans the
 * result. A whole-library pass that finds one file per track in a
 * shared folder (Single-folder layout: every file sits in the tree root)
 * re-queries and re-scans the whole folder PER TRACK: with n tracks that
 * is n queries of an n-row cursor — the O(n²) that made a reorganize take
 * minutes per file on a big library. Caching each touched folder's
 * listing collapses it to one query per unique folder.
 *
 * The pass must keep the cache true while it mutates the tree: every
 * create goes through [noteCreated] and every delete through [noteDeleted]
 * (a stale miss would race a later create into the provider's
 * "name (1)" auto-rename). Concurrency with writers OUTSIDE the pass is
 * handled at the call sites by verifying a fresh document's name — see
 * [MoveLibraryCoordinator] and [ReorganizeLibraryCoordinator].
 *
 * Not thread-safe: a pass is one coroutine (the coordinator's loop), and
 * `LibraryRewriteGate` excludes the other whole-library pass.
 */
internal class SafListingSession {

    private val listings = HashMap<String, HashMap<String, DocumentFile>>(64)

    /**
     * [dir]'s children by name, queried once per dir. [dirKey] is the
     * relative path the caller derives (same key the directory caches in the
     * coordinators use), so `dir` itself is only needed on the first call.
     */
    fun listingOf(dirKey: String, dir: DocumentFile): MutableMap<String, DocumentFile> =
        listings.getOrPut(dirKey) {
            HashMap<String, DocumentFile>(dir.listFiles().size).apply {
                for (child in dir.listFiles()) {
                    child.name?.let { put(it, child) }
                }
            }
        }

    /** Cached child lookup, with a fallback for names the cache never saw. */
    fun find(dirKey: String, dir: DocumentFile, name: String): DocumentFile? =
        listingOf(dirKey, dir)[name]

    /** Records a document the pass created in the cached directory [dirKey]. */
    fun noteCreated(dirKey: String, name: String, doc: DocumentFile) {
        listings[dirKey]?.put(name, doc)
    }

    /** Records a document the pass deleted from the cached directory [dirKey]. */
    fun noteDeleted(dirKey: String, name: String) {
        listings[dirKey]?.remove(name)
    }

    /** Drops [dirKey]'s cached listing so the next lookup queries fresh. */
    fun invalidate(dirKey: String) {
        listings.remove(dirKey)
    }

    /**
     * [find] after [invalidate]: re-reads the directory from the provider.
     * The claim-retry path uses this — a rename the pass didn't make means
     * the cache is stale and the next claim must be decided on disk truth.
     */
    fun findFresh(dirKey: String, dir: DocumentFile, name: String): DocumentFile? {
        invalidate(dirKey)
        return find(dirKey, dir, name)
    }
}
