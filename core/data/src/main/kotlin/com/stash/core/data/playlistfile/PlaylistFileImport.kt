package com.stash.core.data.playlistfile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.stash.core.data.link.LinkImporter
import com.stash.core.data.link.LinkKind
import com.stash.core.data.link.LinkPreview
import com.stash.core.model.weblink.SongKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Why a playlist file can't be brought in (the preview screen words each). */
enum class PlaylistFileProblem {
    /** The document couldn't be opened or read: it is gone, or the app holding it failed. */
    CANT_OPEN,

    /** It was read, and nothing in it names a song (a title and its artist). */
    NOTHING_TO_READ,
}

sealed interface PlaylistFileLoad {
    data class Ok(val preview: LinkPreview) : PlaylistFileLoad
    data class Failed(val problem: PlaylistFileProblem) : PlaylistFileLoad
}

/**
 * "+" › Import a playlist file (issue #61): the document the listener picked, read ([PlaylistFileParser]) into the preview a
 * pasted link opens, so it is added as a link's playlist is ([LinkImporter.add]: songs matched by identity or words, blocked
 * songs left out, nothing removed). Its playlist is found again by its name, so importing the same export again (a newer one,
 * or "road_trip (1).csv") adds only what the playlist lacks.
 */
@Singleton
class PlaylistFileImport @Inject constructor(@ApplicationContext private val context: Context) {

    /** Reads the document at [uri] (the system file picker's); never throws but a cancellation. */
    suspend fun load(uri: String): PlaylistFileLoad = withContext(Dispatchers.IO) {
        try {
            val document = Uri.parse(uri)
            val name = displayName(document)
            val start = context.contentResolver.openInputStream(document)?.use { readStart(it) }
                ?: return@withContext PlaylistFileLoad.Failed(PlaylistFileProblem.CANT_OPEN)
            val file = PlaylistFileParser.parse(start.bytes, name, start.truncated)
            Log.i(TAG, "read ${file.format}: ${file.songs.size} songs${if (file.cut) " (cut)" else ""}")
            if (file.songs.isEmpty()) {
                PlaylistFileLoad.Failed(PlaylistFileProblem.NOTHING_TO_READ)
            } else {
                PlaylistFileLoad.Ok(preview(file, name))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "can't read the playlist file: ${e.javaClass.simpleName}")
            PlaylistFileLoad.Failed(PlaylistFileProblem.CANT_OPEN)
        } catch (e: StackOverflowError) {
            // A pathological file the listener picked: nothing read, never a crash.
            Log.w(TAG, "the playlist file is too deep to read")
            PlaylistFileLoad.Failed(PlaylistFileProblem.NOTHING_TO_READ)
        }
    }

    /** The document's name as its app gives it ("road_trip.csv"), else the last part of its address. */
    private fun displayName(uri: Uri): String? {
        val given = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return given?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** The file's bytes, at most [MAX_BYTES] of them (and whether there was more): a huge file is never read whole. */
    private class Start(val bytes: ByteArray, val truncated: Boolean)

    private fun readStart(input: InputStream): Start {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK)
        while (out.size() <= MAX_BYTES) {
            val n = input.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        val bytes = out.toByteArray()
        return if (bytes.size > MAX_BYTES) Start(bytes.copyOf(MAX_BYTES), truncated = true) else Start(bytes, truncated = false)
    }

    companion object {
        private const val TAG = "PlaylistFileImport"

        /** 8 MiB: 2,000 songs of the widest export (Apple Music's, in UTF-16) take under 2 MiB. */
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val CHUNK = 64 * 1024

        /** A file's playlist is a link import's (`custom_link_…`): Library and the playlist's page show it as one. */
        const val SOURCE_PREFIX = "${LinkImporter.PLAYLIST_PREFIX}file_"

        /** The name of a playlist whose file names none and has no name of its own. */
        const val UNNAMED = "Imported playlist"

        /** The preview of [file], which the phone calls [fileName]. */
        internal fun preview(file: PlaylistFileContent, fileName: String?): LinkPreview {
            val name = file.title ?: nameOf(fileName) ?: UNNAMED
            return LinkPreview(
                kind = LinkKind.PLAYLIST,
                name = name,
                byline = null,
                coverUrl = null,
                service = "",
                songs = file.songs,
                sourceId = sourceIdOf(name),
                partial = file.cut,
                fromFile = true,
            )
        }

        /**
         * A playlist's name from its file's: no extension or a browser's copy mark, underscores as spaces, and a name all in
         * lower case starts with a capital ("road_trip (1).csv", Exportify's file for "Road Trip", is "Road trip").
         */
        internal fun nameOf(fileName: String?): String? {
            val base = fileName?.trim()?.let { FILE_EXTENSION.replace(it, "") } ?: return null
            val words = COPY_MARK.replace(base, "").replace('_', ' ').replace(SPACES, " ").trim()
            if (words.isEmpty()) return null
            return if (words == words.lowercase()) words.replaceFirstChar { it.titlecase() } else words
        }

        /**
         * The playlist a file named [name] becomes, the same for every file of that name however it is written ("Road Trip",
         * "road_trip"): the prefix and 24 hex characters of the SHA-256 of its folded name, as a Spotify export's playlists are
         * named ([com.stash.core.data.record.SpotifyLibraryImport.sourceIdOf]).
         */
        internal fun sourceIdOf(name: String): String {
            val key = SongKey.fold(name).ifEmpty { name.trim() }
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return SOURCE_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(24)
        }

        private val FILE_EXTENSION = Regex("\\.[A-Za-z0-9]{1,5}$")

        /** A second download of one file: "road_trip (1).csv". */
        private val COPY_MARK = Regex("\\s*\\(\\d{1,3}\\)$")
        private val SPACES = Regex("\\s+")
    }
}
