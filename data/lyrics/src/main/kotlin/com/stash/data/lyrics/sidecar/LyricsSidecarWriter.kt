package com.stash.data.lyrics.sidecar

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StoragePreference
import com.stash.data.download.files.FileOrganizerSlugs
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
 *    path; the sidecar is written via plain `java.io.File`.
 *  - **SAF tree** — `track.filePath` starts with `content://`; the
 *    sidecar location is derived from the user's persisted external
 *    tree URI (from [StoragePreference], NOT from the audio URI —
 *    DocumentFile.fromTreeUri requires the tree ROOT, not a child),
 *    and the slug-walked DocumentFile mirror of the download layout
 *    (`<artistSlug>/<albumSlug>/<titleSlug>.lrc`) is created on
 *    demand. Slug semantics come from
 *    [FileOrganizerSlugs.slugify] so the sidecar always lands in the
 *    same directory the download created.
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
        val artistName = track.albumArtist.ifBlank { track.artist }
        val artistDir = findOrCreateDir(tree, FileOrganizerSlugs.slugify(artistName))
            ?: fail("Could not create artist directory")
        val albumSlug = if (track.album.isNotBlank()) {
            FileOrganizerSlugs.slugify(track.album)
        } else {
            "singles"
        }
        val albumDir = findOrCreateDir(artistDir, albumSlug) ?: fail("Could not create album directory")
        val filename = "${FileOrganizerSlugs.slugify(track.title)}.lrc"
        val existing = albumDir.findFile(filename)
        val target = existing ?: albumDir.createFile(LRC_MIME, filename) ?: run {
            Log.w(TAG, "Could not create SAF sidecar '$filename' under ${albumDir.uri}")
            throw IOException("Could not create SAF sidecar $filename")
        }
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
        } ?: fail("Could not open SAF output stream for sidecar ${target.uri}")
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
