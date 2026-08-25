package com.stash.data.lyrics.sidecar

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import com.stash.data.download.files.LibraryLayoutResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.9.36 sidecar `.lrc` writer.
 *
 * Writes `<basename>.lrc` next to the audio file on every successful
 * lyrics fetch, so external players (PowerAmp, VLC, Musicolet, etc.)
 * pick up the lyrics by convention without any Stash-specific
 * integration. Two storage targets are supported:
 *
 *  - **Internal storage** — `track.filePath` is an absolute filesystem
 *    path; the sidecar is written via plain `java.io.File` next to the
 *    audio (correct under every folder structure).
 *  - **SAF tree** — `track.filePath` starts with `content://`; the
 *    sidecar location is derived from the user's persisted external
 *    tree URI (from [StoragePreference], NOT from the audio URI —
 *    DocumentFile.fromTreeUri requires the tree ROOT, not a child),
 *    walked down through the SAME [LibraryLayoutResolver] location the
 *    download pipeline used (#198/#104: Artist/Album, Single folder,
 *    Per playlist), so the `.lrc` always lands in the directory the
 *    download created.
 *
 * Write failure is non-fatal for the Room state: [LyricsRepository]
 * wraps the throwing `write()` API in `runCatching`; only that path is best-effort
 * — the lyrics row + `tracks.lyrics_fetched_at` stamp are the source
 * of truth for the in-app reader; the sidecar is a courtesy.
 *
 * Body format (LRC convention):
 * ```
 * [ti:<title>]
 * [ar:<albumArtist or artist if blank>]
 * [al:<album>]                -- only when non-blank
 * [length:mm:ss]              -- only when durationMs > 0
 * [by:Stash]
 * <synced LRC body, or plain text if synced is missing>
 * ```
 *
 * Rejected with [IOException] when both bodies are null/blank
 * (the instrumental case). `LyricsRepository` already guards this for
 * the instrumental flag, but `write()` re-checks defensively so callers
 * can't accidentally create a header-only `.lrc` with no body.
 */
@Singleton
class LyricsSidecarWriter @Inject constructor(
    private val trackDao: TrackDao,
    @ApplicationContext private val context: Context,
    private val storagePreference: StoragePreference,
) {

    /**
     * Writes the `.lrc` sidecar for [trackId] using [lyrics].
     *
     * Fails when:
     *   - Both `syncedLrc` and `plainText` are null/blank (instrumental).
     *   - The track row is gone (deleted mid-flight).
     *   - The track has no [TrackEntity.filePath] (legacy / sync-only row).
     *   - The SAF tree URI is unset on a `content://` filePath (the user
     *     swapped storage modes; we can't infer the tree root from the
     *     child URI, so writing fails rather than guessing).
     *
     * Throws on disk/SAF I/O failure so [LyricsRepository] can
     * `runCatching` it as non-fatal.
     */
    suspend fun write(trackId: Long, lyrics: LyricsEntity) {
        if (lyrics.syncedLrc.isNullOrBlank() && lyrics.plainText.isNullOrBlank()) fail("No lyrics body for track $trackId")
        val track = trackDao.getById(trackId) ?: fail("Track $trackId no longer exists")
        val path = track.filePath ?: fail("Track $trackId has no downloaded file")
        val body = buildLrcBody(track, lyrics)
        if (path.startsWith("content://")) {
            writeSafSidecar(track, body)
        } else {
            writeFilesystemSidecar(path, body)
        }
    }

    private fun writeFilesystemSidecar(audioPath: String, body: String) {
        val audio = File(audioPath)
        val parent = audio.parentFile ?: run {
            Log.w(TAG, "Cannot resolve parent directory for $audioPath; sidecar skipped")
            throw IOException("Cannot resolve parent directory for $audioPath")
        }
        val sidecar = File(parent, "${audio.nameWithoutExtension}.lrc")
        sidecar.writeText(body, Charsets.UTF_8)
    }

    private suspend fun writeSafSidecar(track: TrackEntity, body: String) {
        // IMPORTANT: DocumentFile.fromTreeUri expects the STORAGE TREE
        // ROOT URI, not the audio file's content URI. Reading the
        // child's URI would yield "permission denied" or worse. The
        // tree URI lives in [StoragePreference]; if it's unset on a
        // `content://` path the user has swapped storage modes since
        // download and we can't recover, so this writer throws.
        val treeUri: Uri = storagePreference.externalTreeUri.first() ?: run {
            Log.w(
                TAG,
                "Track ${track.id} has SAF filePath but no externalTreeUri persisted; sidecar skipped",
            )
            throw IOException("Missing SAF tree for track ${track.id}")
        }
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: run {
            Log.w(TAG, "DocumentFile.fromTreeUri returned null for $treeUri; sidecar skipped")
            throw IOException("Invalid SAF tree $treeUri")
        }
        // The sidecar contract is "next to the audio", so the audio's OWN
        // directory wins over anything re-derived. A track downloaded under
        // one layout stays put until Reorganize runs, and re-deriving from
        // the CURRENT preference would drop the .lrc in an empty folder while
        // the audio sat elsewhere — invisible to every external player.
        // Falls back to the layout resolver when the provider's document ids
        // don't follow the `<volume>:<path>` convention we can decode.
        val beside = safLocationBesideAudio(treeUri, track.filePath)
        val segments: List<String>
        val baseName: String
        if (beside != null) {
            segments = beside.first
            baseName = beside.second
        } else {
            // track.artist (not albumArtist) matches what commitDownload
            // slugged into the directory names (#198/#104).
            val layout = runCatching { storagePreference.libraryLayout.first() }
                .getOrDefault(LibraryLayout.DEFAULT)
            val playlistName =
                if (layout == LibraryLayout.PLAYLIST) {
                    runCatching { trackDao.getFirstPlaylistNameForTrack(track.id) }.getOrNull()
                } else {
                    null
                }
            val location = LibraryLayoutResolver.resolve(
                layout,
                artist = track.artist,
                album = track.album.takeIf { it.isNotBlank() },
                title = track.title,
                playlistName = playlistName,
            )
            segments = location.segments
            baseName = location.baseName
        }
        var cursor = tree
        for (segment in segments) {
            cursor = findOrCreateDir(cursor, segment) ?: fail("Could not create directory '$segment'")
        }
        val filename = "$baseName.lrc"
        val existing = cursor.findFile(filename)
        val target = existing ?: cursor.createFile(LRC_MIME, filename) ?: run {
            Log.w(TAG, "Could not create SAF sidecar '$filename' under ${cursor.uri}")
            throw IOException("Could not create SAF sidecar $filename")
        }
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
        } ?: fail("Could not open SAF output stream for sidecar ${target.uri}")
    }


    /**
     * Decode a SAF audio document uri into (directory segments relative to
     * the picked tree, filename without extension) so the sidecar can be
     * written in the audio's ACTUAL directory.
     *
     * Mirrors the `<volume>:<path>` decode used by the reorganize pass.
     * Returns null whenever the ids don't parse or the document doesn't sit
     * under the tree — the caller then falls back to the layout resolver.
     */
    private fun safLocationBesideAudio(treeUri: Uri, docUriString: String?): Pair<List<String>, String>? {
        if (docUriString.isNullOrBlank() || !docUriString.startsWith("content://")) return null
        return try {
            val baseRel = DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':', "")
            val docRel = DocumentsContract.getDocumentId(Uri.parse(docUriString)).substringAfter(':', "")
            if (!docRel.startsWith(baseRel, ignoreCase = true)) return null
            val parts = docRel.substring(baseRel.length).split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) null
            else parts.dropLast(1) to parts.last().substringBeforeLast('.')
        } catch (t: Throwable) {
            Log.d(TAG, "Sidecar location undecodable for $docUriString: ${t.message}")
            null
        }
    }

    /**
     * SAF-side `findOrCreateDir`. Mirrors the helper in `FileOrganizer`
     * but returns `null` (logged) instead of throwing — sidecar failure
     * is converted to IOException by its caller.
     */
    private fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.takeIf { it.isDirectory }?.let { return it }
        val created = parent.createDirectory(name)
        if (created == null) {
            Log.w(TAG, "Could not create SAF dir '$name' under ${parent.uri}")
        }
        return created
    }

    private fun buildLrcBody(track: TrackEntity, lyrics: LyricsEntity): String = buildString {
        appendLine("[ti:${track.title}]")
        appendLine("[ar:${track.albumArtist.ifBlank { track.artist }}]")
        if (track.album.isNotBlank()) appendLine("[al:${track.album}]")
        if (track.durationMs > 0) {
            val sec = (track.durationMs / 1000).toInt()
            appendLine("[length:${sec / 60}:%02d]".format(sec % 60))
        }
        appendLine("[by:Stash]")
        append(lyrics.syncedLrc?.takeUnless(String::isBlank) ?: lyrics.plainText.orEmpty())
    }

    private fun fail(message: String): Nothing = throw IOException(message).also { Log.w(TAG, message) }

    private companion object {
        private const val TAG = "LyricsSidecarWriter"
        // LRC has no official MIME type; mirror the de-facto convention
        // used by other lyric-aware Android apps. The provider creating
        // the document is allowed to coerce this to text/plain if it
        // doesn't recognise the value — the sidecar still functions.
        private const val LRC_MIME = "application/x-lrc"
    }
}
