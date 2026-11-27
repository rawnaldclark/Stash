package com.stash.core.data.weblibrary

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.model.MusicSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What an export wrote. [listens] and [skips] are the `listens` section's (0 when it wasn't asked for). */
data class WebLibraryExportResult(val likes: Int, val playlists: Int, val plays: Int, val listens: Int = 0, val skips: Int = 0)

/**
 * What an export or a one-off send carries (link-sync spec §2.3): likes yes/no, plays yes/no, and which of your playlists.
 * [playlistIds] null means every playlist (also ones made after the choice was saved). An unticked part is written as an
 * empty array, which `stash-web-library` v1 allows (`docs/library-file-v1.md` "Writers"), so the file stays v1.
 */
data class ExportSelection(
    val likes: Boolean = true,
    val plays: Boolean = true,
    val playlistIds: Set<Long>? = null,
) {
    fun includes(playlistId: Long): Boolean = playlistIds == null || playlistId in playlistIds

    /** Nothing ticked: there is nothing to save or send. */
    val isEmpty: Boolean get() = !likes && !plays && playlistIds?.isEmpty() == true

    companion object {
        /** Everything: today's export, and the picker's first-time default. */
        val ALL = ExportSelection()
    }
}

/** One of your playlists as the picker lists it: name, songs that would go, and whether it is a followed shared mix. */
data class ExportPlaylistChoice(val id: Long, val name: String, val songs: Int, val sharedMix: Boolean)

/**
 * What the library holds that an export could carry, for the picker's counts (spec §2.3 "Likes · 1,204"). [listens] is how
 * many of this phone's own plays "Your whole listening history" would write, and [listensSince] when the first of them
 * started (null when there is none).
 */
data class ExportCatalog(
    val likes: Int,
    val plays: Int,
    val playlists: List<ExportPlaylistChoice>,
    val listens: Int = 0,
    val listensSince: Long? = null,
)

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
 *
 * When asked, a saved file also carries `listens` ([WebLibraryListens]): every play and skip made on this phone (never a
 * mirrored or imported one), streamed from the database, for Your record on the web.
 *
 * Send to computer carries the same file, with the synced mixes and more plays besides ([forComputer]).
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
    suspend fun collect(nowMs: Long, generator: String?, selection: ExportSelection = ExportSelection.ALL): WebLibraryFile =
        withContext(Dispatchers.IO) {
            database.withTransaction { file(rows(selection, computer = false), nowMs, generator, sentAtMs = null) }
        }

    /**
     * What the picker shows: how many likes and plays would go, and every playlist an export would write with its song count
     * (the same rules as [collect]: blocked songs and songs without a title or artist never count).
     */
    suspend fun catalog(): ExportCatalog = withContext(Dispatchers.IO) {
        val dao = database.webLibraryExportDao()
        val library = database.withTransaction {
            val follows = dao.followedMixes().mapTo(HashSet()) { it.playlistId }
            ExportCatalog(
                likes = dao.likeCount(),
                plays = dao.playCount(WebLibraryFile.MAX_PLAYS),
                playlists = dao.playlists().map { p ->
                    ExportPlaylistChoice(
                        id = p.id,
                        name = playlistName(p.name),
                        songs = minOf(dao.playlistItemCount(p.id), WebLibraryFile.MAX_PLAYLIST_ITEMS),
                        sharedMix = p.id in follows,
                    )
                },
            )
        }
        // The history's counts read every play, so they stay out of that transaction (which holds the write lock): a play
        // recorded in between only makes the count one newer, and the picker's counts are a guide, not the file.
        library.copy(listens = minOf(dao.ownPlayCount(), WebLibraryListens.MAX_PLAYS), listensSince = dao.ownFirstPlayAt())
    }

    /** The file as text (a one-off send seals it whole): the same bytes [export] writes to a file. */
    fun text(file: WebLibraryFile): String = WebLibraryFile.text(file)

    /** "Stash for Android 0.9.112", the writer named inside the file. */
    fun generator(): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return listOfNotNull("Stash for Android", version).joinToString(" ")
    }

    /**
     * The library as Send to computer carries it beside the songs (`stash-library.json`, which Stash for desktop brings in):
     * everything the phone has synced. The web's file ([collect], everything ticked), and besides
     * - each like saying where it is liked ([WebLibraryFile.Like.likedOn]): in Stash, on Spotify, YouTube Music, SoundCloud,
     * - every saved album, Stash Mix, synced mix and radio (playlists, SoundCloud's too, are the web's), in the order
     *   [computerLists] gives,
     * - each list saying what it is ([WebLibraryFile.Playlist.kind]) and where it came from ([WebLibraryFile.Playlist.from]),
     * - each list's id the same on every install of Stash ([computerIdOf]),
     * - up to [WebLibraryFile.MAX_COMPUTER_PLAYS] plays, not the web's [WebLibraryFile.MAX_PLAYS],
     * - [sentAtMs], the send's own number, as its list has it.
     *
     * Liked Songs lists, Stash's own Liked Songs and the Downloads list are never lists here: the likes and the files are
     * them.
     *
     * The rows are read in one transaction, as [collect]'s are, but made into the file after it: with up to 100,000 plays and
     * every mix's songs, the database's write lock is held for the reads alone. Then cut to fit ([fitForComputer]). Null
     * when even cut it is more than the computer reads.
     */
    suspend fun forComputer(nowMs: Long, sentAtMs: Long): WebLibraryFile? = withContext(Dispatchers.IO) {
        val generator = generator()
        val rows = database.withTransaction { rows(ExportSelection.ALL, computer = true) }
        fitForComputer(file(rows, nowMs, generator, sentAtMs))
    }

    /**
     * [file], kept to about [target] bytes: over it, the oldest plays go first (as many as it takes), then the oldest likes
     * beyond [WebLibraryFile.COMPUTER_LIKES_KEPT]. Null when it is still more than [most], the most the computer reads: the
     * send then goes without it.
     */
    internal fun fitForComputer(
        file: WebLibraryFile,
        target: Long = WebLibraryFile.COMPUTER_BYTES,
        most: Long = WebLibraryReader.MAX_BYTES.toLong(),
    ): WebLibraryFile? {
        var size = WebLibraryFile.bytes(WebLibraryFile.serializer(), file)
        if (size <= target) return file
        // Each one taken out takes its own bytes and the comma before it (the first in a list has none).
        var plays = file.history.size
        while (size > target && plays > 0) {
            plays--
            size -= WebLibraryFile.bytes(WebLibraryFile.Play.serializer(), file.history[plays]) + if (plays > 0) 1 else 0
        }
        var likes = file.likes.size
        while (size > target && likes > WebLibraryFile.COMPUTER_LIKES_KEPT) {
            likes--
            size -= WebLibraryFile.bytes(WebLibraryFile.Like.serializer(), file.likes[likes]) + 1
        }
        val cut = file.copy(likes = file.likes.take(likes), history = file.history.take(plays))
        // Counted again whole, so the last word is the file's real size, not the sum.
        val bytes = WebLibraryFile.bytes(WebLibraryFile.serializer(), cut)
        Log.i(
            TAG,
            "Send to computer's library cut to $bytes bytes: ${file.history.size - plays} of ${file.history.size} plays and " +
                "${file.likes.size - likes} of ${file.likes.size} likes left out",
        )
        return cut.takeIf { bytes <= most }
    }

    /** What a file is made from, as the database has it: read in a transaction, made into the file by [file]. */
    private class Rows(
        val likes: List<WebLibraryExportDao.LikedRow>,
        /** Send to computer's only: where each like is liked. */
        val likePlaces: List<WebLibraryExportDao.LikePlace>,
        val follows: Map<Long, SharedMixEntity>,
        val playlists: List<Pair<ExportList, List<WebLibraryExportDao.PlaylistItemRow>>>,
        val plays: List<WebLibraryExportDao.PlayRow>,
    )

    /**
     * The rows of the file: likes, follows, playlists with their songs, plays. [computer] for Send to computer's file
     * ([forComputer]), which has where each like is liked, every list the phone has synced and more plays; the web's has
     * none of it.
     */
    private suspend fun rows(selection: ExportSelection, computer: Boolean): Rows {
        val dao = database.webLibraryExportDao()
        val likes = if (!selection.likes) emptyList() else dao.likes()
        val likePlaces = if (computer && selection.likes) dao.likePlaces() else emptyList()
        val follows = dao.followedMixes().associateBy { it.playlistId }
        val lists = if (computer) {
            computerLists(dao.playlists(), dao.savedAlbums(), dao.stashMixes(), dao.syncedMixes())
        } else {
            dao.playlists().map { ExportList(it, kind = null, from = null) }
        }
        val playlists = lists.filter { selection.includes(it.playlist.id) }
            .map { it to dao.playlistItems(it.playlist.id, WebLibraryFile.MAX_PLAYLIST_ITEMS) }
        val maxPlays = if (computer) WebLibraryFile.MAX_COMPUTER_PLAYS else WebLibraryFile.MAX_PLAYS
        val plays = if (!selection.plays) emptyList() else dao.recentPlays(maxPlays)
        return Rows(likes, likePlaces, follows, playlists, plays)
    }

    /**
     * [rows] made into the file. [sentAtMs] is set for Send to computer's file ([forComputer]), which says where each
     * playlist came from and gives it an id the same on every install; null for the web's, which stays exactly as it
     * always was.
     */
    private fun file(rows: Rows, nowMs: Long, generator: String?, sentAtMs: Long?): WebLibraryFile {
        val computer = sentAtMs != null

        val seenLikes = HashSet<Long>()
        val places = rows.likePlaces.groupBy({ it.trackId }, { it.place })
        val likes = rows.likes.mapNotNull { row ->
            if (!seenLikes.add(row.track.id)) return@mapNotNull null
            val added = row.track.dateAdded
            val song = WebLibraryFile.song(row.track.toTrack(), added) ?: return@mapNotNull null
            val likedOn = if (computer) places[row.track.id].orEmpty().let { here -> LIKED_ORDER.filter { it in here } } else null
            WebLibraryFile.Like(song, row.likedAt?.takeIf { it > 0 } ?: added.takeIf { it > 0 } ?: nowMs, likedOn)
        }

        // The computer's ids given so far: each is given once.
        val ids = HashSet<String>()
        val playlists = rows.playlists.map { (list, items) ->
            val p = list.playlist
            val created = p.dateAdded.toEpochMilli().takeIf { it > 0 } ?: nowMs
            val updated = items.mapNotNull { it.addedAt }.maxOrNull()?.coerceAtLeast(created) ?: created
            val webId = fileIdOf(p.id, p.sourceId)
            WebLibraryFile.Playlist(
                id = if (computer) computerIdOf(p, list.from, list.kind, webId, ids) else webId,
                name = playlistName(p.name),
                items = items.mapNotNull { WebLibraryFile.song(it.track.toTrack(), it.track.dateAdded) },
                createdAt = created,
                updatedAt = updated,
                follow = rows.follows[p.id]?.let { WebLibraryFile.follow(it.shareId, it.version, it.sharedBy) },
                from = list.from,
                kind = list.kind,
            )
        }

        val history = rows.plays.mapNotNull { row ->
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
            sentAtMs = sentAtMs,
        )
    }

    /**
     * Writes the file to [targetUri] (a new document from the system file picker). The picker has already created
     * that document, so if the export fails or is cancelled (the user leaves the screen), the document is deleted
     * again: an empty `stash-library-….json` would only tell the web "That file isn't a Stash backup."
     *
     * With [listens], the file also carries this phone's whole listening history ([WebLibraryListens], "Your whole listening
     * history" in the picker). Only a saved file carries it: a one-off send ([text]) is sealed whole and stays as it was.
     */
    suspend fun export(
        targetUri: Uri,
        selection: ExportSelection = ExportSelection.ALL,
        listens: Boolean = false,
    ): Result<WebLibraryExportResult> = withContext(Dispatchers.IO) {
        try {
            val file = collect(System.currentTimeMillis(), generator(), selection)
            ensureActive()
            // Streamed straight into the document: the text is never held whole in memory.
            val stream = context.contentResolver.openOutputStream(targetUri)
                ?: throw IllegalStateException("Could not open output stream for URI: $targetUri")
            var history = ListensWritten(0, 0)
            val bytes = stream.use { raw ->
                val out = CountingOutputStream(raw.buffered(WRITE_BUFFER))
                if (listens) history = writeWithListens(file, out) else WebLibraryFile.write(file, out)
                out.flush()
                out.count
            }
            val result = WebLibraryExportResult(
                likes = file.likes.size,
                playlists = file.playlists.size,
                plays = file.history.size,
                listens = history.plays,
                skips = history.skips,
            )
            Log.i(
                TAG,
                "Wrote $bytes bytes: ${result.likes} likes, ${result.playlists} playlists, ${result.plays} plays" +
                    if (listens) ", listens ${result.listens} plays and ${result.skips} skips" else "",
            )
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

    /**
     * Writes [file] with the `listens` section before its closing brace, to [out] (counted, so the whole file stays within
     * the reader's 64 MiB, [WebLibraryReader.MAX_BYTES]). The rest of the file is written exactly as without it.
     *
     * The history is read after [collect]'s transaction, a page at a time, so the database isn't held while the file is
     * written. A play recorded meanwhile is newer than the first page and simply isn't in this file.
     */
    internal suspend fun writeWithListens(
        file: WebLibraryFile,
        out: CountingOutputStream,
        maxBytes: Long = WebLibraryReader.MAX_BYTES.toLong(),
    ): ListensWritten {
        val head = WebLibraryListens.HoldLastByte(out)
        WebLibraryFile.write(file, head)
        head.flush()
        check(head.release() == '}'.code) { "The library file didn't end with its closing brace" }
        // What is left for listens: the cap, less what is written and the closing brace still to come.
        val budget = maxBytes - out.count - 1
        val written = WebLibraryListens.write(database.webLibraryExportDao(), out, budget)
        out.write('}'.code)
        return written
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

    /** Counts what passes through, for the log line and the size cap. */
    internal class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
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

        fun playlistName(name: String) = name.trim().ifEmpty { "Playlist" }

        private val FILE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        /**
         * A playlist's id in the file: `app-<its id>`, except one that came from Stash on the web (an import or a send made it
         * `custom_web_<id>`), which goes back under the web's own id, so the web merges it into the playlist it came from
         * instead of adding a copy (library-file v1: ids are stable, and a reader merges by id).
         */
        fun fileIdOf(playlistId: Long, sourceId: String): String =
            sourceId.removePrefix(WebLibraryImporter.WEB_SOURCE_PREFIX).takeIf { sourceId.startsWith(WebLibraryImporter.WEB_SOURCE_PREFIX) && FILE_ID.matches(it) }
                ?: "app-$playlistId"

        const val WRITE_BUFFER = 64 * 1024

        /** The order of a like's places ([WebLibraryFile.Like.likedOn]). */
        val LIKED_ORDER = listOf(
            WebLibraryFile.LIKED_STASH,
            WebLibraryFile.FROM_SPOTIFY,
            WebLibraryFile.FROM_YOUTUBE,
            WebLibraryFile.FROM_SOUNDCLOUD,
        )
    }
}

/**
 * Where a playlist or mix synced from a service came from, for Send to computer's file ([WebLibraryFile.Playlist.from]):
 * Spotify, YouTube Music or SoundCloud, by its source. Null for your own (made in Stash: a `custom_…` source id, the web's
 * and the mirror's too), a shared mix (`share:…`), and anything else (one saved in Stash). The same rule the web mirror
 * tells a synced playlist by. An album goes by [albumFromOf].
 */
internal fun fromOf(p: PlaylistEntity): String? = when {
    p.sourceId.startsWith("custom_") || p.sourceId.startsWith("share:") -> null
    p.source == MusicSource.SPOTIFY -> WebLibraryFile.FROM_SPOTIFY
    p.source == MusicSource.YOUTUBE -> WebLibraryFile.FROM_YOUTUBE
    p.source == MusicSource.SOUNDCLOUD -> WebLibraryFile.FROM_SOUNDCLOUD
    else -> null
}

/**
 * A saved album's service, by its key ([com.stash.core.data.album.SavedAlbums]): Spotify's (`album:spotify:…`) or YouTube
 * Music's (`album:youtube:MPREb…`, or `MPREb…` synced), saved in Stash or synced. Null for another's (`album:qobuz:…`).
 */
internal fun albumFromOf(p: PlaylistEntity): String? = when {
    p.sourceId.startsWith(SPOTIFY_ALBUM_KEY, ignoreCase = true) -> WebLibraryFile.FROM_SPOTIFY
    p.sourceId.startsWith(YOUTUBE_ALBUM_KEY, ignoreCase = true) || p.sourceId.startsWith(YOUTUBE_ALBUM) ->
        WebLibraryFile.FROM_YOUTUBE
    else -> null
}

/** How a Spotify album saved in Stash or synced is keyed ([com.stash.core.data.album.SavedAlbums.sourceId]). */
private const val SPOTIFY_ALBUM_KEY = "album:spotify:"

/** How a YouTube Music album saved in Stash is keyed ([com.stash.core.data.album.SavedAlbums.sourceId]). */
private const val YOUTUBE_ALBUM_KEY = "album:youtube:"

/** A YouTube Music album's browse id starts so: a synced one is keyed by it alone. */
private const val YOUTUBE_ALBUM = "MPREb"

/**
 * A service's mix that is a radio: its name, trimmed, ends with "Radio", as Home's rails tell one (feature/home MixRail.kt),
 * where a SoundCloud mix is never one.
 */
internal fun isRadio(p: PlaylistEntity): Boolean =
    !(p.source == MusicSource.SOUNDCLOUD && p.sourceId.startsWith("soundcloud_mix_")) &&
        p.name.trim().endsWith("Radio", ignoreCase = true)

/**
 * A list in the file: its row, and for Send to computer's file what it is ([WebLibraryFile.Playlist.kind]) and where it came
 * from ([WebLibraryFile.Playlist.from]). Both null in the web's.
 */
internal class ExportList(val playlist: PlaylistEntity, val kind: String?, val from: String?)

/**
 * Send to computer's lists, in the file's order: your own playlists (and shared mixes), then the Stash Mixes; then for each
 * service in turn (Spotify, YouTube Music, SoundCloud) its playlists, its albums, its mixes, its radios; then the albums of
 * no service of these (saved in Stash from another). [playlists] are the playlists, [albums] the saved albums, [stashMixes]
 * the Stash Mixes, [mixes] the services' mixes and radios, each in its own order.
 */
internal fun computerLists(
    playlists: List<PlaylistEntity>,
    albums: List<PlaylistEntity>,
    stashMixes: List<PlaylistEntity>,
    mixes: List<PlaylistEntity>,
): List<ExportList> {
    val lists = playlists.map { ExportList(it, WebLibraryFile.KIND_PLAYLIST, fromOf(it)) }
    val albumLists = albums.map { ExportList(it, WebLibraryFile.KIND_ALBUM, albumFromOf(it)) }
    val mixLists = mixes.map { ExportList(it, if (isRadio(it)) WebLibraryFile.KIND_RADIO else WebLibraryFile.KIND_MIX, fromOf(it)) }
    val out = ArrayList<ExportList>()
    out += lists.filter { it.from == null }
    out += stashMixes.map { ExportList(it, WebLibraryFile.KIND_STASH_MIX, from = null) }
    for (service in listOf(WebLibraryFile.FROM_SPOTIFY, WebLibraryFile.FROM_YOUTUBE, WebLibraryFile.FROM_SOUNDCLOUD)) {
        out += lists.filter { it.from == service }
        out += albumLists.filter { it.from == service }
        out += mixLists.filter { it.from == service && it.kind == WebLibraryFile.KIND_MIX }
        out += mixLists.filter { it.from == service && it.kind == WebLibraryFile.KIND_RADIO }
    }
    out += albumLists.filter { it.from == null }
    return out
}

/** A synced list's own id at its service that can follow `spotify-` or `youtube-` within the desktop's 64 characters. */
private val SYNCED_ID = Regex("^[A-Za-z0-9_-]{1,55}$")

/** An id the desktop takes: 1 to 64 letters, digits, `_` and `-`. */
private val DESKTOP_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

/** What a SoundCloud id can't have in the desktop's ids: anything but letters, digits, `_` and `-`. */
private val NOT_ID = Regex("[^A-Za-z0-9_-]")

/**
 * A synced list's own id at its service, as one install and another may store it. The sync stores Spotify's bare id, but
 * a Spotify URI (`spotify:playlist:<id>`, `spotify:user:<user>:playlist:<id>`) is taken to its id. YouTube Music's comes
 * as a playlist id (`RDTMAK5uy_…`, `PL…`) or as the browse id of one (`VLRDTMAK5uy_…`, `VLPL…`), which is taken to the
 * playlist id: no playlist id starts with `VL`. SoundCloud's (`soundcloud_playlist_<user>_<id>`,
 * `soundcloud_mix_<user>_<id>`) loses its `soundcloud_` and has anything an id can't have made a `-`.
 */
private fun serviceIdOf(from: String, sourceId: String): String = when (from) {
    WebLibraryFile.FROM_SPOTIFY ->
        if (sourceId.startsWith("spotify:")) sourceId.substringAfterLast(":playlist:", missingDelimiterValue = sourceId) else sourceId
    WebLibraryFile.FROM_YOUTUBE -> sourceId.removePrefix("VL")
    WebLibraryFile.FROM_SOUNDCLOUD -> sourceId.removePrefix("soundcloud_").replace(NOT_ID, "-")
    else -> sourceId
}

/**
 * A saved album's id: Spotify's `album:spotify:<id>` as `spotify-album-<id>`, YouTube Music's `MPREb…` (synced, or saved as
 * `album:youtube:MPREb…`) as `youtube-MPREb…`, so one album has one id however it was saved. Null for another service's,
 * or one the desktop couldn't take as an id.
 */
private fun albumIdOf(sourceId: String): String? {
    val id = when {
        sourceId.startsWith(SPOTIFY_ALBUM_KEY, ignoreCase = true) -> "spotify-album-" + sourceId.substring(SPOTIFY_ALBUM_KEY.length)
        sourceId.startsWith(YOUTUBE_ALBUM) -> "youtube-$sourceId"
        sourceId.startsWith(YOUTUBE_ALBUM_KEY + YOUTUBE_ALBUM, ignoreCase = true) -> "youtube-" + sourceId.substring(YOUTUBE_ALBUM_KEY.length)
        else -> return null
    }
    return id.takeIf { DESKTOP_ID.matches(it) }
}

/**
 * A list's id in Send to computer's file, the same on every install of Stash: the desktop keeps lists by id, and a phone can
 * have two installs (the release and debug apps), whose row numbers differ. By its [kind]:
 * - a saved album: `spotify-album-<id>` or `youtube-MPREb…` ([albumIdOf]);
 * - a Stash Mix: `stash-mix-<its source id>`;
 * - a synced list ([from] set): its service and its own id there, `spotify-<id>`, `youtube-<id>` (the id 1 to 55 long), or
 *   `soundcloud-<id>` ([serviceIdOf]);
 * - your own list (a `custom_…` source id): that source id.
 *
 * Anything else, or an id the desktop couldn't take (1 to 64 of `[A-Za-z0-9_-]`), is [fallback], as the web's file has it
 * ([fileIdOf]). [used] holds the ids given so far in the file; one given already goes to the later list's `app-<row id>`.
 */
internal fun computerIdOf(p: PlaylistEntity, from: String?, kind: String?, fallback: String, used: MutableSet<String>): String {
    val serviceId = from?.let { serviceIdOf(it, p.sourceId) }
    val stable = when {
        kind == WebLibraryFile.KIND_ALBUM -> albumIdOf(p.sourceId)
        kind == WebLibraryFile.KIND_STASH_MIX -> "stash-mix-${p.sourceId}".takeIf { DESKTOP_ID.matches(it) }
        from == WebLibraryFile.FROM_SOUNDCLOUD -> "soundcloud-$serviceId".takeIf { !serviceId.isNullOrEmpty() && DESKTOP_ID.matches(it) }
        serviceId != null -> "$from-$serviceId".takeIf { SYNCED_ID.matches(serviceId) }
        p.sourceId.startsWith("custom_") && DESKTOP_ID.matches(p.sourceId) -> p.sourceId
        else -> null
    } ?: fallback
    if (used.add(stable)) return stable
    return "app-${p.id}".also { used.add(it) }
}
