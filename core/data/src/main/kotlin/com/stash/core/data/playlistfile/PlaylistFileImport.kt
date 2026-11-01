package com.stash.core.data.playlistfile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.link.LinkImporter
import com.stash.core.data.link.LinkKind
import com.stash.core.data.link.LinkPreview
import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblink.merge.matchOccurrences
import com.stash.core.model.weblink.SongIdentity
import com.stash.core.model.weblink.SongKey
import com.stash.core.model.weblink.SongRef
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /** It was read, and nothing in it names a song (a title and its artist); or it is a song, a video or a picture. */
    NOTHING_TO_READ,

    /** It names no songs but holds several Spotify / YouTube links: those are added one at a time, as links. */
    LINKS,
}

sealed interface PlaylistFileLoad {
    data class Ok(val preview: LinkPreview) : PlaylistFileLoad

    /** The file holds one music link and nothing else: opened as that link, as if it were pasted. */
    data class Link(val url: String) : PlaylistFileLoad

    data class Failed(val problem: PlaylistFileProblem) : PlaylistFileLoad
}

/**
 * "+" › Import a playlist file (issue #61): the document the listener picked, read ([PlaylistFileParser]) into the preview a
 * pasted link opens, so it is added as a link's playlist is ([LinkImporter.add]: songs matched by identity or words, blocked
 * songs left out, nothing removed). Importing the same export again (a newer one, or "road_trip (1).csv") finds the playlist
 * the first made, by its name and its songs, and adds only what it lacks.
 */
@Singleton
class PlaylistFileImport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlists: PlaylistDao,
    private val exports: WebLibraryExportDao,
) {

    /** Reads the document at [uri] (the system file picker's); never throws but a cancellation. */
    suspend fun load(uri: String): PlaylistFileLoad = withContext(Dispatchers.IO) {
        try {
            val document = Uri.parse(uri)
            val name = displayName(document)
            val start = context.contentResolver.openInputStream(document)?.use { readStart(it) }
                ?: return@withContext PlaylistFileLoad.Failed(PlaylistFileProblem.CANT_OPEN)
            if (start.media) {
                Log.i(TAG, "a media file, not a playlist")
                return@withContext PlaylistFileLoad.Failed(PlaylistFileProblem.NOTHING_TO_READ)
            }
            val file = PlaylistFileParser.parse(start.bytes, name, start.truncated)
            Log.i(TAG, "read ${file.format}: ${file.songs.size} songs${if (file.cut) " (cut)" else ""}, ${file.links.size} links")
            when {
                file.songs.isNotEmpty() -> {
                    val preview = preview(file, name)
                    PlaylistFileLoad.Ok(preview.copy(sourceId = placeOf(preview.sourceId, preview.songs) { held(it) }))
                }
                file.links.size == 1 -> PlaylistFileLoad.Link(file.links.single())
                file.links.isNotEmpty() -> PlaylistFileLoad.Failed(PlaylistFileProblem.LINKS)
                else -> PlaylistFileLoad.Failed(PlaylistFileProblem.NOTHING_TO_READ)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "can't read the playlist file: ${e.javaClass.simpleName}")
            PlaylistFileLoad.Failed(PlaylistFileProblem.CANT_OPEN)
        } catch (e: VirtualMachineError) {
            // A pathological file the listener picked (too deep, or too much for the memory left): nothing read, never a crash.
            Log.w(TAG, "the playlist file is too much to read: ${e.javaClass.simpleName}")
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

    /** What holds [sourceId] here: null when no playlist does. */
    private suspend fun held(sourceId: String): Held? {
        val playlist = playlists.findBySourceId(sourceId) ?: return null
        if (!playlist.isActive) return Held(null)
        val songs = exports.playlistItems(playlist.id, WebLibraryFile.MAX_PLAYLIST_ITEMS)
            .map { SongRef(it.track.title, it.track.artist, it.track.isrc) }
        return Held(songs)
    }

    /** The file's first bytes. [media]: they are an audio, video, image, archive or PDF file's, and weren't read on. */
    private class Start(val bytes: ByteArray, val truncated: Boolean, val media: Boolean = false)

    /**
     * At most [MAX_BYTES] of the file (and whether there was more), into one buffer: a huge file is never read whole, and a song
     * or a video picked by mistake is told from its first bytes and read no further.
     */
    private fun readStart(input: InputStream): Start {
        val buffer = ByteArray(MAX_BYTES + 1)
        var n = 0
        var sniffed = false
        while (n < buffer.size) {
            val read = input.read(buffer, n, minOf(CHUNK, buffer.size - n))
            if (read < 0) break
            n += read
            if (!sniffed && n >= PlaylistFileText.SNIFF_BYTES) {
                sniffed = true
                if (PlaylistFileText.isMedia(buffer, n)) return Start(ByteArray(0), truncated = false, media = true)
            }
        }
        if (!sniffed && PlaylistFileText.isMedia(buffer, n)) return Start(ByteArray(0), truncated = false, media = true)
        return if (n > MAX_BYTES) Start(buffer.copyOf(MAX_BYTES), truncated = true) else Start(buffer.copyOf(n), truncated = false)
    }

    /** A playlist id taken here: by a playlist the listener has, with its [songs], or (null) by one no longer shown. */
    internal class Held(val songs: List<SongIdentity>?)

    companion object {
        private const val TAG = "PlaylistFileImport"

        /** 4 MiB: 2,000 songs of the widest export (Apple Music's, in UTF-16, every column) take about 2 MiB. */
        const val MAX_BYTES = 4 * 1024 * 1024
        private const val CHUNK = 64 * 1024

        /** A file's playlist is a link import's (`custom_link_…`): Library and the playlist's page show it as one. */
        const val SOURCE_PREFIX = "${LinkImporter.PLAYLIST_PREFIX}file_"

        /** The name of a playlist whose file names none and has no name of its own. */
        const val UNNAMED = "Imported playlist"

        /** How many playlists of one name, each with other songs, are told apart (`…_2`, `…_3`) before the time does it. */
        private const val MAX_SAME_NAME = 100

        /** The preview of [file], which the phone calls [fileName], for the playlist of its name ([placeOf] picks among them). */
        internal fun preview(file: PlaylistFileContent, fileName: String?): LinkPreview {
            val name = nameFor(file, fileName)
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
         * What [file]'s playlist is called: the name it gives itself, unless that is one a player gives every playlist (VLC's
         * "Playlist"), then its file's name, then whatever it had.
         */
        internal fun nameFor(file: PlaylistFileContent, fileName: String?): String =
            file.title?.takeUnless(::isGeneric) ?: nameOf(fileName) ?: file.title ?: UNNAMED

        /** [name] is one any playlist could have: it says nothing about which ("Playlist", "Untitled", "New playlist 2"). */
        internal fun isGeneric(name: String): Boolean = SongKey.fold(name).replace(TRAILING_NUMBER, "") in GENERIC_NAMES

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
         * The first playlist a file named [name] would go into, the same for every file of that name however it is written
         * ("Road Trip", "road_trip"): the prefix and 24 hex characters of the SHA-256 of its folded name, as a Spotify export's
         * playlists are named ([com.stash.core.data.record.SpotifyLibraryImport.sourceIdOf]).
         */
        internal fun sourceIdOf(name: String): String {
            val key = SongKey.fold(name).ifEmpty { name.trim() }
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return SOURCE_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(24)
        }

        /**
         * The playlist [songs] go into, from [base] (their name's): the first of `base`, `base_2`, `base_3`… that is free, or that
         * holds this list already ([sameList]: an earlier import of this file, or of a newer export of it). Two lists that share
         * only a name ("Playlist", as VLC titles every file) never become one. [held]: what holds an id here.
         */
        internal suspend fun placeOf(base: String, songs: List<SongIdentity>, held: suspend (String) -> Held?): String {
            for (n in 1..MAX_SAME_NAME) {
                val id = if (n == 1) base else "${base}_$n"
                val here = held(id) ?: return id
                // A playlist no longer shown is never added to: the songs would go somewhere the listener can't see.
                if (here.songs != null && sameList(here.songs, songs)) return id
            }
            return "${base}_${System.currentTimeMillis()}"
        }

        /**
         * [here] and [incoming] are one playlist: at least half of the shorter one's songs are in both (by identity, as the web
         * library's import pairs them). The shorter one, not the incoming one: a newer export of a playlist that grew still finds
         * it, and an empty one of the same name takes the songs.
         */
        internal fun sameList(here: List<SongIdentity>, incoming: List<SongIdentity>): Boolean {
            val shorter = minOf(here.size, incoming.size)
            if (shorter == 0) return true
            val shared = matchOccurrences(here, incoming).count { it >= 0 }
            return shared * 2 >= shorter
        }

        private val FILE_EXTENSION = Regex("\\.[A-Za-z0-9]{1,5}$")

        /** A second download of one file: "road_trip (1).csv". */
        private val COPY_MARK = Regex("\\s*\\(\\d{1,3}\\)$")
        private val SPACES = Regex("\\s+")
        private val TRAILING_NUMBER = Regex("\\s*\\d+$")

        /**
         * Names that name no playlist, folded: what an unnamed list is called, and VLC's "Playlist", which it writes as the title
         * of every XSPF it saves, in the listener's language.
         */
        private val GENERIC_NAMES: Set<String> = listOf(
            "Playlist", "Playlists", "Untitled", "Untitled playlist", "Unnamed", "Unnamed playlist", "New playlist", "My playlist",
            UNNAMED, "Liste de lecture", "Wiedergabeliste", "Lista de reproducción", "Lista de reprodução", "Lista di riproduzione",
            "Afspeellijst", "Spellista", "Spilleliste", "Afspilningsliste", "Soittolista", "Lista odtwarzania", "Seznam skladeb",
            "Çalma listesi", "Плейлист", "Список воспроизведения", "Список відтворення", "プレイリスト", "播放列表", "재생 목록",
        ).map(SongKey::fold).toSet()
    }
}
