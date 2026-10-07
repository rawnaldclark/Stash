package com.stash.core.data.weblibrary

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What an export wrote. */
data class WebLibraryExportResult(val likes: Int, val playlists: Int, val plays: Int)

/**
 * "Export for Stash on the web" (Settings › Library & Storage): writes your likes, your own playlists and your
 * recent plays as the web player's library file ([WebLibraryFile], `stash-web-library` v1), to a file the user
 * picked (SAF create-document). Read-only on the database.
 *
 * What goes in: likes (Stash likes, plus the songs in your Spotify / YouTube Music Liked Songs), your own
 * playlists in order (a followed shared mix as a follow), the newest [WebLibraryFile.MAX_PLAYS] plays. Each song
 * as its portable description (title, artist, album, length, ISRC, Spotify id, YouTube id, an allowed cover).
 * What never goes in: Daily Mixes, Stash Mixes, the Downloads list, blocked songs, file paths, download state,
 * logins, settings, Last.fm data, the Community key.
 */
@Singleton
class WebLibraryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: StashDatabase,
) {

    /**
     * Builds the file from the library as it is now. [generator] names the writer inside the file.
     *
     * All five reads run in one transaction, so the file is one consistent snapshot: a sync finishing mid-export
     * can't leave a playlist and its songs out of step, and a big likes list read over several cursor windows
     * can't skip or repeat rows. That holds the database's write lock for the reads (about a second on a big
     * library); a play recorded in that moment waits, it isn't lost.
     */
    suspend fun collect(nowMs: Long, generator: String?): WebLibraryFile = withContext(Dispatchers.IO) {
        database.withTransaction { read(nowMs, generator) }
    }

    private suspend fun read(nowMs: Long, generator: String?): WebLibraryFile {
        val dao = database.webLibraryExportDao()

        val seenLikes = HashSet<Long>()
        val likes = dao.likes().mapNotNull { row ->
            if (!seenLikes.add(row.track.id)) return@mapNotNull null
            val added = row.track.dateAdded
            val song = WebLibraryFile.song(row.track.toTrack(), added) ?: return@mapNotNull null
            WebLibraryFile.Like(song, row.likedAt?.takeIf { it > 0 } ?: added.takeIf { it > 0 } ?: nowMs)
        }

        val follows = dao.followedMixes().associateBy { it.playlistId }
        val playlists = dao.playlists().map { p ->
            val rows = dao.playlistItems(p.id, WebLibraryFile.MAX_PLAYLIST_ITEMS)
            val created = p.dateAdded.toEpochMilli().takeIf { it > 0 } ?: nowMs
            val updated = rows.mapNotNull { it.addedAt }.maxOrNull()?.coerceAtLeast(created) ?: created
            WebLibraryFile.Playlist(
                id = "app-${p.id}",
                name = p.name.trim().ifEmpty { "Playlist" },
                items = rows.mapNotNull { WebLibraryFile.song(it.track.toTrack(), it.track.dateAdded) },
                createdAt = created,
                updatedAt = updated,
                follow = follows[p.id]?.let { WebLibraryFile.follow(it.shareId, it.version, it.sharedBy) },
            )
        }

        val history = dao.recentPlays(WebLibraryFile.MAX_PLAYS).mapNotNull { row ->
            if (row.playedAt <= 0) return@mapNotNull null
            val song = WebLibraryFile.song(row.track.toTrack(), row.track.dateAdded) ?: return@mapNotNull null
            WebLibraryFile.Play(song, row.playedAt)
        }

        return WebLibraryFile(
            exportedAt = WebLibraryFile.isoMillis(nowMs),
            generator = generator,
            likes = likes,
            playlists = playlists,
            history = history,
        )
    }

    /**
     * Writes the file to [targetUri] (a new document from the system file picker). The picker has already created
     * that document, so if the export fails or is cancelled (the user leaves the screen), the document is deleted
     * again: an empty `stash-library-….json` would only tell the web "That file isn't a Stash backup."
     */
    suspend fun export(targetUri: Uri): Result<WebLibraryExportResult> = withContext(Dispatchers.IO) {
        try {
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
            val file = collect(System.currentTimeMillis(), listOfNotNull("Stash for Android", version).joinToString(" "))
            ensureActive()
            // Streamed straight into the document: the text is never held whole in memory.
            val stream = context.contentResolver.openOutputStream(targetUri)
                ?: throw IllegalStateException("Could not open output stream for URI: $targetUri")
            val bytes = stream.use { raw ->
                val out = CountingOutputStream(raw.buffered(WRITE_BUFFER))
                WebLibraryFile.write(file, out)
                out.flush()
                out.count
            }
            val result = WebLibraryExportResult(likes = file.likes.size, playlists = file.playlists.size, plays = file.history.size)
            Log.i(TAG, "Wrote $bytes bytes: ${result.likes} likes, ${result.playlists} playlists, ${result.plays} plays")
            Result.success(result)
        } catch (e: CancellationException) {
            discard(targetUri)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Web library export failed", e)
            discard(targetUri)
            Result.failure(e)
        }
    }

    /** Deletes the half-made document at [uri]. Best effort: a failure here only leaves the file behind. */
    private fun discard(uri: Uri) {
        val resolver = context.contentResolver
        val deleted = try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(resolver, uri)
            } else {
                resolver.delete(uri, null, null) > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete the unfinished export", e)
            false
        }
        Log.i(TAG, if (deleted) "Deleted the unfinished export" else "The unfinished export may still be there")
    }

    /** Counts what passes through, for the log line. */
    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    private companion object {
        const val TAG = "WebLibraryExport"
        const val WRITE_BUFFER = 64 * 1024
    }
}
