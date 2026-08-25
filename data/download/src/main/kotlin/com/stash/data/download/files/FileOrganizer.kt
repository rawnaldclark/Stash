package com.stash.data.download.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages file paths for downloaded music.
 *
 * Supports two storage destinations:
 *  - **Internal** (default): tracks live under `context.filesDir/music/…`.
 *    Pure `java.io.File` API. Tracks are deleted when the app is uninstalled
 *    and are not visible to other apps.
 *  - **External** (user-chosen via SAF): when the user picks an SD card or
 *    USB-OTG folder via `ACTION_OPEN_DOCUMENT_TREE`, new downloads are
 *    written to that location via `ContentResolver` / `DocumentFile`. These
 *    files survive app uninstall and are accessible to other apps + over
 *    USB/PC — users can take their library anywhere.
 *
 * Within either destination, the folder structure follows the user's
 * [LibraryLayout] preference (#198/#104): Artist/Album (classic default),
 * Single folder, or Per playlist. ALL path computation goes through
 * [LibraryLayoutResolver]; this class never slugs artist/album by hand so
 * writers and readers cannot drift.
 *
 * yt-dlp always writes to the internal cache (`getTempDir()`); the
 * destination switch happens in [commitDownload] after the download
 * completes, which copies the temp file to the final location and cleans up.
 */
@Singleton
class FileOrganizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storagePreference: StoragePreference,
    private val trackDao: TrackDao,
) {
    /** Root directory for all internally-stored downloaded music files. */
    private val musicDir: File get() = File(context.filesDir, "music").also { it.mkdirs() }

    /**
     * The absolute internal music root, exposed so bulk coordinators
     * (reorganize / move-library gating) can classify stored paths as
     * internal vs SAF without duplicating the `filesDir/music` convention.
     */
    fun internalMusicRoot(): File = musicDir

    /**
     * The persisted library layout, defaulting safely when DataStore read
     * fails. One-call convenience for bulk passes that want a consistent
     * layout for their whole duration (adoption, reorganize).
     */
    suspend fun currentLayout(): LibraryLayout =
        runCatching { storagePreference.libraryLayout.first() }.getOrDefault(LibraryLayout.DEFAULT)

    /**
     * Resolves a track's destination under the CURRENT library layout,
     * including the owning-playlist lookup when the layout is
     * [LibraryLayout.PLAYLIST] and a [trackId] is known. Every writer
     * (downloads, moves, reorganize, sidecars) resolves through here.
     */
    suspend fun resolveLocation(
        artist: String,
        album: String?,
        title: String,
        trackId: Long? = null,
        layout: LibraryLayout? = null,
    ): LibraryLayoutResolver.ResolvedLocation {
        val effectiveLayout = layout ?: currentLayout()
        val playlistName = if (effectiveLayout == LibraryLayout.PLAYLIST && trackId != null) {
            runCatching { trackDao.getFirstPlaylistNameForTrack(trackId) }.getOrNull()
        } else {
            null
        }
        return LibraryLayoutResolver.resolve(effectiveLayout, artist, album, title, playlistName)
    }

    /**
     * Returns the internal directory for a specific artist/album combination
     * under [layout]. Only meaningful for internal-storage destinations;
     * SAF downloads compute their own path inside [commitDownload].
     * Defaults to the classic Artist/Album shape — live downloads should go
     * through [commitDownload]/[resolveLocation], which honor the pref.
     */
    fun getTrackDir(artist: String, album: String?, layout: LibraryLayout = LibraryLayout.DEFAULT): File {
        val location = LibraryLayoutResolver.resolve(layout, artist, album, title = "", playlistName = null)
        val dir = if (location.segments.isEmpty()) musicDir else File(musicDir, location.segments.joinToString("/"))
        return dir.also { it.mkdirs() }
    }

    /**
     * Returns the internal file path for a downloaded track under [layout].
     * Prefer [commitDownload] which automatically handles both internal and
     * SAF destinations plus the live layout preference; this getter is
     * retained for callers that only need the classic target path.
     */
    fun getTrackFile(
        artist: String,
        album: String?,
        title: String,
        format: String = "opus",
        layout: LibraryLayout = LibraryLayout.DEFAULT,
    ): File {
        val location =
            LibraryLayoutResolver.resolve(layout, artist, album, title, playlistName = null)
        return File(getTrackDir(artist, album, layout), "${location.baseName}.$format")
    }

    /** Temporary download directory inside the cache. Cleaned by the OS as needed. */
    fun getTempDir(): File = File(context.cacheDir, "downloads").also { it.mkdirs() }

    /**
     * One-pass existence checker: whether a track's file still exists, and
     * (for SAF) a fresh URI to persist if the stored one was stale.
     * [FileExistenceChecker.exists]'s `filePath` is only consulted for the
     * internal-storage fast path; for SAF paths the location is re-derived
     * via [LibraryLayoutResolver.lookupCandidates] against a [buildSafIndex]
     * built lazily ONCE per session — per-track findFile walks were the
     * "stuck on Checking library" hang (#429). A missing tree grant fails
     * SAFE (files reported present) so it can never mass-reset the library.
     *
     * Used by library reconciliation (missing-file detection) via
     * [com.stash.core.data.library.FileExistenceSessionFactory]. Open a
     * fresh session per pass.
     */
    fun existenceSession(): com.stash.core.data.library.FileExistenceChecker =
        object : com.stash.core.data.library.FileExistenceChecker {
            private val lock = kotlinx.coroutines.sync.Mutex()
            private var built = false
            private var snapshot: IndexSnapshot? = null

            override suspend fun exists(
                trackId: Long,
                artist: String,
                album: String?,
                title: String,
                filePath: String,
            ): com.stash.core.data.library.FileExistenceResult {
                if (!filePath.startsWith("content://")) {
                    return com.stash.core.data.library.FileExistenceResult(exists = File(filePath).exists())
                }
                val snap = lock.withLock {
                    if (!built) {
                        val layout = runCatching { storagePreference.libraryLayout.first() }
                            .getOrNull() ?: LibraryLayout.DEFAULT
                        val index = buildSafIndex()
                        if (index == null) {
                            android.util.Log.w(
                                "FileOrganizer",
                                "existenceSession: content:// paths present but no SAF tree grant — " +
                                    "treating files as present (cannot verify; a false 'missing' here " +
                                    "would reset the whole library to undownloaded)",
                            )
                        }
                        // Fail SAFE on a missing/revoked tree grant: report the file as
                        // existing rather than triggering a library-wide reset + redownload
                        // storm the first sync after a re-grant lapse.
                        snapshot = index?.let { IndexSnapshot(it, layout) }
                        built = true
                    }
                    snapshot
                } ?: return com.stash.core.data.library.FileExistenceResult(exists = true)

                val playlistName = if (snap.layout == LibraryLayout.PLAYLIST) {
                    runCatching { trackDao.getFirstPlaylistNameForTrack(trackId) }.getOrNull()
                } else {
                    null
                }
                val match = resolveInIndex(
                    index = snap.index,
                    layout = snap.layout,
                    artist = artist,
                    album = album,
                    title = title,
                    playlistName = playlistName,
                ) ?: return com.stash.core.data.library.FileExistenceResult(exists = false)
                val freshUri = match.uri.toString()
                return com.stash.core.data.library.FileExistenceResult(
                    exists = true,
                    // Only report a "resolved" path when it actually differs — avoids
                    // a needless DB write on the common case where the URI was fine.
                    resolvedFilePath = freshUri.takeIf { it != filePath },
                )
            }
        }

    /** Index + the layout it was built under, frozen for one bulk pass. */
    private data class IndexSnapshot(val index: SafIndex, val layout: LibraryLayout)

    /**
    * Pre-built index of the current SAF tree, so a bulk pass (like
    * [com.stash.core.data.library.AdoptExistingFilesUseCase]) can look up
    * any track in O(1) instead of re-walking directories via
    * [DocumentFile.findFile] for every single candidate.
    *
    * [DocumentFile.findFile] is not a direct lookup: internally it calls
    * `listFiles()` on the parent and linearly scans for a name match. Doing
    * that per-candidate against thousands of tracks means re-listing the same
    * directories over and over via ContentResolver IPC — the actual cost that
    * was making adoption slower than downloading the same tracks over the
    * network. Building this index means the tree is walked exactly once
    * regardless of candidate count.
    *
    * Since #198/#104 the tree is NOT guaranteed to be a strict two-level
    * `<artist>/<album>` hierarchy anymore (flat single-folder libraries,
    * per-playlist folders, mixed layouts mid-reorganize), so the walk is
    * fully recursive and keyed by slash-joined relative directory paths
    * ([SafIndex.byDirKey]). A whole-tree filename index ([SafIndex.byFileName])
    * backs the last-resort unique-name match for locations whose owning
    * playlist can't be re-derived.
    */
    suspend fun buildSafIndex(): SafIndex? {
        val treeUri = storagePreference.externalTreeUri.first() ?: return null
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null

        val byDirKey = LinkedHashMap<String, AlbumIndex>(256)
        val byFileName = HashMap<String, MutableList<DocumentFile>>(512)
        indexTree(root, "", byDirKey, byFileName)
        return SafIndex(byDirKey, byFileName)
    }

    /** Recursive worker behind [buildSafIndex]. Depth is directory nesting (≤4 in practice). */
    private fun indexTree(
        dir: DocumentFile,
        dirKey: String,
        byDirKey: MutableMap<String, AlbumIndex>,
        byFileName: MutableMap<String, MutableList<DocumentFile>>,
    ) {
        val subDirs = ArrayList<DocumentFile>()
        val filesByName = LinkedHashMap<String, DocumentFile>()
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                subDirs += child
            } else if (child.isFile) {
                val name = child.name ?: continue
                filesByName[name] = child
                byFileName.getOrPut(name) { ArrayList(1) }.add(child)
            }
        }
        byDirKey[dirKey] = AlbumIndex(dir, filesByName)
        for (sub in subDirs) {
            val subName = sub.name ?: continue
            indexTree(sub, if (dirKey.isEmpty()) subName else "$dirKey/$subName", byDirKey, byFileName)
        }
    }

    /**
    * O(1) lookup against a pre-built [SafIndex]. Probes every location
    * [LibraryLayoutResolver.lookupCandidates] derives for the track — the
    * current-layout spot first, then the legacy/nested and flat spots for
    * files downloaded before a layout switch — with zero further SAF IPC
    * once the index is built. A final whole-tree unique-filename match
    * covers playlist-owned directories when the owning playlist couldn't be
    * re-derived (deterministic: ambiguous duplicates match nothing rather
    * than guessing wrong).
    */
    fun resolveInIndex(
        index: SafIndex,
        layout: LibraryLayout,
        artist: String,
        album: String?,
        title: String,
        playlistName: String? = null,
        knownFormat: String? = null,
    ): DocumentFile? {
        val formats = knownFormat?.let { listOf(it) } ?: KNOWN_AUDIO_FORMATS
        val candidates = LibraryLayoutResolver.lookupCandidates(
            layout, artist, album, title, playlistName, formats,
        )
        for (candidate in candidates) {
            val filesByName = index.byDirKey[candidate.dirKey]?.filesByName ?: continue
            for (name in candidate.candidateFileNames) {
                filesByName[name]?.let { return it }
            }
        }
        // Last resort: exactly one file somewhere in the tree carries a
        // candidate name. Unique ⇒ unambiguous; multiple hits stay unmatched
        // (reporting "missing" for a genuinely ambiguous pair is safer than
        // adopting the wrong song's file).
        for (candidate in candidates) {
            for (name in candidate.candidateFileNames) {
                index.byFileName[name]?.singleOrNull()?.let { return it }
            }
        }
        return null
    }

    /** In-memory index built once per bulk pass by [buildSafIndex]. */
    class SafIndex(
        /** Relative slash-joined dir key ("" = tree root) → its listing. */
        val byDirKey: Map<String, AlbumIndex>,
        /** Whole-tree filename → every document carrying it (usually 1). */
        val byFileName: Map<String, List<DocumentFile>>,
    )

    /** Pre-listed contents of a single directory. */
    class AlbumIndex(val dir: DocumentFile, val filesByName: Map<String, DocumentFile>)

    /** Directory for cached album artwork files. */
    fun getAlbumArtDir(): File = File(context.cacheDir, "albumart").also { it.mkdirs() }

    /** Returns the file path for a specific album's artwork. */
    fun getAlbumArtFile(albumId: String): File = File(getAlbumArtDir(), "$albumId.jpg")

    /**
     * Calculates the total storage consumed by internally-downloaded files.
     * Internal music dir only; does not include SAF-targeted files —
     * see [computeMusicLibrarySize] for storage-mode-aware totals.
     */
    fun getTotalStorageBytes(): Long =
        musicDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Storage-mode-aware total music size on disk. Walks the right place
     * based on the user's [StoragePreference]:
     *  - Internal mode (default): walks `filesDir/music`
     *  - SAF mode (user picked an external folder): walks the persisted
     *    tree URI via [DocumentFile.fromTreeUri]
     *
     * Returns the sum of every file under that root regardless of DB state.
     * Used by Home to display Storage truthfully on legacy libraries where
     * `tracks.file_size_bytes` is unreliable (many older download paths
     * left it at 0 and the backfill couldn't recover it).
     *
     * Returns `LibrarySizeBreakdown(total, lossless, losslessCount)` so the
     * caller doesn't have to walk the tree three times for three numbers.
     */
    suspend fun computeMusicLibrarySize(): LibrarySizeBreakdown {
        val externalUri = storagePreference.externalTreeUri.first()
        return if (externalUri != null) {
            walkSafTree(externalUri)
        } else {
            walkInternalDir()
        }
    }

    private fun walkInternalDir(): LibrarySizeBreakdown {
        var total = 0L
        var lossless = 0L
        var losslessCount = 0
        musicDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val size = file.length()
            total += size
            if (file.extension.lowercase() in LOSSLESS_EXTENSIONS) {
                lossless += size
                losslessCount++
            }
        }
        return LibrarySizeBreakdown(total, lossless, losslessCount)
    }

    /**
     * Walks a SAF tree URI counting/sizing every file recursively. SAF
     * is slower than `File.walkTopDown` because every node is a
     * ContentResolver query — we go through DocumentFile to keep the
     * path-tolerant API. Suspended + IO-dispatched at the call-site
     * (HomeViewModel) so the cost stays off the main thread.
     */
    private fun walkSafTree(treeUri: Uri): LibrarySizeBreakdown {
        var total = 0L
        var lossless = 0L
        var losslessCount = 0
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return LibrarySizeBreakdown(0, 0, 0)
        val stack = ArrayDeque<DocumentFile>().apply { addLast(root) }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isDirectory) {
                node.listFiles().forEach { stack.addLast(it) }
            } else if (node.isFile) {
                val size = node.length()
                total += size
                val name = node.name?.lowercase().orEmpty()
                val ext = name.substringAfterLast('.', "")
                if (ext in LOSSLESS_EXTENSIONS) {
                    lossless += size
                    losslessCount++
                }
            }
        }
        return LibrarySizeBreakdown(total, lossless, losslessCount)
    }

    private companion object {
        // Mirrors `LibraryViewModel.LOSSLESS_CODECS` and
        // `core/ui/.../FlacBadge.kt`. Kept duplicated rather than reaching
        // across the data → ui boundary; the set is short and stable.
        private val LOSSLESS_EXTENSIONS = setOf(
            "flac", "alac", "wav", "ape", "tta", "wv", "aiff",
        )
        // Mirrors the format switch in mimeTypeFor(). Used by
        // resolveInIndex's extension-probe when the caller doesn't
        // know the track's format ahead of time.
        private val KNOWN_AUDIO_FORMATS = listOf("opus", "m4a", "flac", "mp3", "ogg", "wav")
    }

    /**
     * Moves [tempFile] (written by yt-dlp to the cache) into its final
     * destination and returns the path/URI to store in
     * [com.stash.core.model.Track.filePath]. Deletes [tempFile] on success.
     *
     * If the user has chosen an external SAF folder via Settings, the file
     * is copied there through `ContentResolver` and the returned path is a
     * `content://` URI string. ExoPlayer + MediaPlayer + MediaMetadataRetriever
     * all accept content URIs natively so playback works without further
     * changes. Otherwise the file is moved into internal storage and the
     * returned path is the absolute File path (existing behaviour).
     *
     * The sub-destination inside the root follows the current [LibraryLayout]
     * (#198/#104); when the layout is Per-playlist and [trackId] is known,
     * the owning playlist is looked up so the file lands in its folder.
     */
    suspend fun commitDownload(
        tempFile: File,
        artist: String,
        album: String?,
        title: String,
        format: String,
        trackId: Long? = null,
    ): CommittedTrack {
        val size = tempFile.length()
        val externalTree = storagePreference.externalTreeUri.first()
        return if (externalTree == null) {
            val location = resolveLocation(artist, album, title, trackId)
            val dir = if (location.segments.isEmpty()) {
                musicDir
            } else {
                File(musicDir, location.segments.joinToString("/")).also { it.mkdirs() }
            }
            val finalFile = File(dir, "${location.baseName}.$format")
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
            CommittedTrack(finalFile.absolutePath, size)
        } else {
            val location = resolveLocation(artist, album, title, trackId)
            val safUriString = writeToSafTree(tempFile, externalTree, location, format)
            tempFile.delete()
            CommittedTrack(safUriString, size)
        }
    }

    /**
     * Writes [tempFile] into the user's SAF tree at [location]'s relative
     * path, creating intermediate directories on demand. Returns the created
     * document's URI as a string. Throws if the tree URI is stale (permission
     * revoked) or I/O fails — caller should log and leave the temp file in
     * place for retry.
     */
    private fun writeToSafTree(
        tempFile: File,
        treeUri: Uri,
        location: LibraryLayoutResolver.ResolvedLocation,
        format: String,
    ): String {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Could not open SAF tree; permission may have been revoked: $treeUri")
        var cursor = root
        for (segment in location.segments) {
            cursor = cursor.findOrCreateDir(segment)
        }
        val filename = "${location.baseName}.$format"
        // Overwrite: delete any existing file with the same name before creating.
        cursor.findFile(filename)?.delete()
        val target = cursor.createFile(mimeTypeFor(format), filename)
            ?: error("Could not create SAF file '$filename' under ${cursor.uri}")
        tempFile.inputStream().use { input ->
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                input.copyTo(output)
            } ?: error("Could not open SAF output stream for '$filename'")
        }
        return target.uri.toString()
    }

    private fun DocumentFile.findOrCreateDir(name: String): DocumentFile =
        findFile(name)?.takeIf { it.isDirectory }
            ?: createDirectory(name)
            ?: error("Could not create SAF directory '$name' under $uri")

    private fun mimeTypeFor(format: String): String = when (format.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    /**
     * Result of a successful [commitDownload]. `filePath` is either an
     * absolute `java.io.File` path (internal storage) or a `content://…`
     * URI string (SAF target); `sizeBytes` is the track's size at the
     * moment it was written.
     */
    data class CommittedTrack(
        val filePath: String,
        val sizeBytes: Long,
    )
}

/**
 * One-walk breakdown of the music library on disk. Returned from
 * [FileOrganizer.computeMusicLibrarySize] so consumers get total + lossless
 * sizes + lossless count without three separate tree walks.
 */
data class LibrarySizeBreakdown(
    val totalBytes: Long,
    val losslessBytes: Long,
    val losslessFileCount: Int,
)
