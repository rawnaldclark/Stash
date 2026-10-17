package com.stash.feature.library.allsongs

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.stash.core.common.constants.StashConstants
import com.stash.core.data.db.dao.ScannedSongDao
import com.stash.core.data.db.entity.ScanFolderEntity
import com.stash.core.data.db.entity.ScannedSongEntity
import com.stash.core.data.prefs.StoragePreference
import com.stash.core.model.SCANNED_UNKNOWN_ARTIST
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * One folder's scan: songs [added], tags re-read for files that [updated] on disk, songs [removed]
 * because their file is gone, and whether some subfolder couldn't be listed ([incomplete]; nothing
 * was removed then).
 */
data class ScanResult(val added: Int, val updated: Int, val removed: Int, val incomplete: Boolean)

/** What deleting a song's file came to: [GONE] was already missing, [FAILED] (typically no write access) is still there. */
enum class FileDeletion { DELETED, GONE, FAILED }

/**
 * Walks a user-chosen SAF tree for playable audio files and syncs `scanned_songs` with it.
 * One ContentResolver query per directory (no per-file DocumentFile IPC); tags are read four files
 * at a time and saved in batches; a rescan reads tags only for new files and files whose size or
 * modified time moved.
 */
@Singleton
class AllSongsScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ScannedSongDao,
    private val storagePreference: StoragePreference,
    private val covers: ScannedCoverStore,
) {

    /** The folder's name as the provider shows it. A provider query, so off the main thread. */
    suspend fun displayName(tree: Uri): String = withContext(Dispatchers.IO) {
        val name = try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver
                .query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        name ?: tree.lastPathSegment ?: "Folder"
    }

    /** Deletes the document behind [uri]. Takes the string so callers (and their tests) need no Uri. */
    fun deleteFile(uri: String): FileDeletion = try {
        if (DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))) {
            FileDeletion.DELETED
        } else {
            FileDeletion.FAILED
        }
    } catch (e: FileNotFoundException) {
        FileDeletion.GONE
    } catch (e: Exception) {
        FileDeletion.FAILED
    }

    /** Stash's download folder when the user picked one (null: downloads stay in app storage). */
    suspend fun downloadTreeUri(): Uri? = storagePreference.externalTreeUri.first()

    /**
     * Why [tree] can't be added next to [folders], or null when it can (see [folderConflict]).
     * A URI that isn't a SAF tree can't be compared and is let through; its scan fails instead.
     */
    internal suspend fun conflictFor(tree: Uri, folders: List<ScanFolderEntity>): FolderConflict? {
        val candidate = treeRef(tree) ?: return null
        val added = folders.mapNotNull { f -> treeRef(Uri.parse(f.treeUri))?.let { it to f.displayName } }
        return folderConflict(candidate, added, downloadTreeUri()?.let(::treeRef))
    }

    /** Throws if the folder itself can't be read, so a missing SD card / revoked grant never wipes its songs. */
    suspend fun scan(folder: ScanFolderEntity): ScanResult = withContext(Dispatchers.IO) {
        val tree = Uri.parse(folder.treeUri)
        val skip = treeRef(tree)?.let { downloadSubtreeToSkip(it, downloadTreeUri()?.let(::treeRef)) }
        val listing = walk(tree, skip)
        val plan = planRescan(dao.getStamps(folder.id), listing.files, listing.complete)

        plan.gone.chunked(500).forEach { dao.deleteByUris(folder.id, it) }

        // Read and saved a batch at a time, not all at the end: on a big folder the songs show up
        // while the scan runs, and a scan stopped part way (Library closed) keeps every batch it
        // finished instead of starting over next time.
        plan.fresh.chunked(TAG_BATCH).forEach { batch ->
            dao.insertSongs(readEach(batch) { f -> readTags(folder.id, f, dateAdded = System.currentTimeMillis()) })
        }
        // Re-read in place: the row keeps its id (a queue holding the song still finds it) and
        // the day it was first found.
        plan.changed.chunked(TAG_BATCH).forEach { batch ->
            dao.updateSongs(
                readEach(batch) { (stamp, f) -> readTags(folder.id, f, dateAdded = stamp.dateAdded).copy(id = stamp.id) },
            )
        }
        fillMissingArt(folder.id)
        ScanResult(added = plan.fresh.size, updated = plan.changed.size, removed = plan.gone.size, incomplete = !listing.complete)
    }

    /** [read] for every item of [items], [TAG_READERS] files at a time (each is a retriever opening a file). */
    private suspend fun <T> readEach(items: List<T>, read: suspend (T) -> ScannedSongEntity): List<ScannedSongEntity> =
        coroutineScope {
            val sem = Semaphore(TAG_READERS)
            items.map { item -> async { sem.withPermit { read(item) } } }.awaitAll()
        }

    private class Listing(val files: List<ListedFile>, val complete: Boolean)

    /**
     * Every playable file under [tree], depth first, skipping hidden folders and [skipDocId] (the
     * download tree inside this one). A subfolder that can't be listed marks the listing
     * incomplete rather than looking empty, so a hiccup never reads as "those songs were deleted".
     */
    private suspend fun walk(tree: Uri, skipDocId: String?): Listing {
        val resolver = context.contentResolver
        val out = ArrayList<ListedFile>()
        val stack = ArrayList<String>()
        stack.add(DocumentsContract.getTreeDocumentId(tree))
        var isRoot = true
        var complete = true
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive() // leaving the screen mid-scan stops the walk
            val parentId = stack.removeAt(stack.lastIndex)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val cursor = try {
                resolver.query(children, COLUMNS, null, null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // SecurityException / IllegalArgumentException from the provider: same as no answer
            }
            if (cursor == null) {
                if (isRoot) throw IOException("Can't read folder")
                complete = false
                continue
            }
            isRoot = false
            cursor.use { c ->
                // A provider still fetching this folder (a network or cloud one) or one that hit an
                // error answers with what it has so far: a partial list, not a sign files were deleted.
                val extras = c.extras
                if (extras != null &&
                    (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) || extras.containsKey(DocumentsContract.EXTRA_ERROR))
                ) {
                    complete = false
                }
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".") && docId != skipDocId) stack.add(docId)
                        continue
                    }
                    val ext = playableAudioExtension(name, mime) ?: continue
                    // A size the provider doesn't report is 0 ("unknown") and kept; a reported
                    // empty or too-small file is a failed copy, not a song.
                    val sizeKnown = !c.isNull(3)
                    val size = if (sizeKnown) c.getLong(3) else 0L
                    if (sizeKnown && size < StashConstants.MIN_PLAYABLE_LOCAL_BYTES) continue
                    val lastModified = if (c.isNull(4)) 0L else c.getLong(4)
                    out.add(
                        ListedFile(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
                            name = name,
                            ext = ext,
                            size = size,
                            lastModified = lastModified,
                        ),
                    )
                }
            }
        }
        return Listing(out, complete)
    }

    private suspend fun readTags(folderId: Long, f: ListedFile, dateAdded: Long): ScannedSongEntity {
        coroutineContext.ensureActive()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        var art: ByteArray? = null
        var readOk = false
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(f.uri))
            readOk = true
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            art = r.embeddedPicture
        } catch (e: Exception) {
            // Unreadable tags: fall back to the filename below.
        } finally {
            runCatching { r.release() }
        }
        return ScannedSongEntity(
            folderId = folderId,
            uri = f.uri,
            title = title?.takeIf { it.isNotBlank() } ?: f.name.substringBeforeLast('.'),
            artist = artist?.takeIf { it.isNotBlank() } ?: SCANNED_UNKNOWN_ARTIST,
            album = album?.takeIf { it.isNotBlank() } ?: "",
            durationMs = duration,
            fileFormat = f.ext,
            fileSizeBytes = f.size,
            lastModified = f.lastModified,
            dateAdded = dateAdded,
            // "" = no cover embedded. NULL (unreadable file, or the cover couldn't be saved) is retried next scan.
            albumArtPath = if (readOk) covers.save(art) else null,
        )
    }

    /** Rows from before covers were scanned (album_art_path NULL): look for each one's cover, once. */
    private suspend fun fillMissingArt(folderId: Long) {
        // A row whose cover file is gone (a restore, the app's files moved) goes back to NULL.
        val present = covers.names()
        dao.getArtRefs(folderId).filter { it.art !in present }.map { it.id }
            .chunked(500).forEach { dao.clearAlbumArt(it) }

        val sem = Semaphore(TAG_READERS)
        coroutineScope {
            dao.getUnarted(folderId).map { row ->
                async {
                    sem.withPermit {
                        coroutineContext.ensureActive()
                        val art = try {
                            readArt(row.uri)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        // null: unreadable right now, or the cover couldn't be saved. Stays NULL, retried next scan.
                        if (art != null) dao.setAlbumArtPath(row.id, art)
                    }
                }
            }.awaitAll()
        }
    }

    private fun readArt(uri: String): String? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(uri))
            return covers.save(r.embeddedPicture)
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Deletes cover files no row points at any more (a removed song, a retagged one, a removed
     * folder). Call it with scans stopped: a scan writes a cover before its row is inserted.
     */
    suspend fun sweepArt() {
        withContext(Dispatchers.IO) {
            covers.sweep(dao.getArtPaths().toHashSet())
        }
    }

    private companion object {

        /** Files whose tags are read at once. */
        const val TAG_READERS = 4

        /** Files read and saved per batch: small enough to show progress, big enough to keep the readers busy. */
        const val TAG_BATCH = 100

        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /** [tree] as the folder rules compare it, or null when it isn't a SAF tree URI. */
        fun treeRef(tree: Uri): TreeRef? = try {
            val authority = tree.authority ?: return null
            TreeRef(authority, DocumentsContract.getTreeDocumentId(tree))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}