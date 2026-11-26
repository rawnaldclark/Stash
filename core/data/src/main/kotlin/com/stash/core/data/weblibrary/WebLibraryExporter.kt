package com.stash.core.data.weblibrary

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
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
            database.withTransaction { read(nowMs, generator, selection) }
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
     * the web's file ([collect], everything ticked), and besides
     * - every synced mix ([WebLibraryExportDao.syncedMixes]),
     * - each playlist synced from a service saying which ([WebLibraryFile.Playlist.from], [fromOf]),
     * - up to [WebLibraryFile.MAX_COMPUTER_PLAYS] plays, not the web's [WebLibraryFile.MAX_PLAYS],
     * - [sentAtMs], the send's own number, as its list has it.
     *
     * Read in one transaction, as [collect] is, then cut to fit ([fitForComputer]). Null when even cut it is more than the
     * computer reads.
     */
    suspend fun forComputer(nowMs: Long, sentAtMs: Long): WebLibraryFile? = withContext(Dispatchers.IO) {
        val generator = generator()
        fitForComputer(database.withTransaction { read(nowMs, generator, ExportSelection.ALL, sentAtMs) })
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

    /**
     * Reads the file. [sentAtMs] is set for Send to computer's file ([forComputer]), which has the synced mixes, where each
     * playlist came from and more plays; null for the web's, which stays exactly as it always was.
     */
    private suspend fun read(nowMs: Long, generator: String?, selection: ExportSelection, sentAtMs: Long? = null): WebLibraryFile {
        val dao = database.webLibraryExportDao()
        val computer = sentAtMs != null

        val seenLikes = HashSet<Long>()
        val likes = if (!selection.likes) emptyList() else dao.likes().mapNotNull { row ->
            if (!seenLikes.add(row.track.id)) return@mapNotNull null
            val added = row.track.dateAdded
            val song = WebLibraryFile.song(row.track.toTrack(), added) ?: return@mapNotNull null
            WebLibraryFile.Like(song, row.likedAt?.takeIf { it > 0 } ?: added.takeIf { it > 0 } ?: nowMs)
        }

        val follows = dao.followedMixes().associateBy { it.playlistId }
        val lists = if (computer) dao.playlists() + dao.syncedMixes() else dao.playlists()
        val playlists = lists.filter { selection.includes(it.id) }.map { p ->
            val rows = dao.playlistItems(p.id, WebLibraryFile.MAX_PLAYLIST_ITEMS)
            val created = p.dateAdded.toEpochMilli().takeIf { it > 0 } ?: nowMs
            val updated = rows.mapNotNull { it.addedAt }.maxOrNull()?.coerceAtLeast(created) ?: created
            WebLibraryFile.Playlist(
                id = fileIdOf(p.id, p.sourceId),
                name = playlistName(p.name),
                items = rows.mapNotNull { WebLibraryFile.song(it.track.toTrack(), it.track.dateAdded) },
                createdAt = created,
                updatedAt = updated,
                follow = follows[p.id]?.let { WebLibraryFile.follow(it.shareId, it.version, it.sharedBy) },
                from = if (computer) fromOf(p) else null,
            )
        }

        val maxPlays = if (computer) WebLibraryFile.MAX_COMPUTER_PLAYS else WebLibraryFile.MAX_PLAYS
        val history = if (!selection.plays) emptyList() else dao.recentPlays(maxPlays).mapNotNull { row ->
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
    }
}

/**
 * Where a playlist synced from a service came from, for Send to computer's file ([WebLibraryFile.Playlist.from]): Spotify or
 * YouTube Music, by the playlist's source. Null for your own (made in Stash: a `custom_…` source id, the web's and the mirror's
 * too), a shared mix (`share:…`), and anything else: one saved in Stash, a SoundCloud one (the file can't name SoundCloud). The
 * same rule the web mirror tells a synced playlist by.
 */
internal fun fromOf(p: PlaylistEntity): String? = when {
    p.sourceId.startsWith("custom_") || p.sourceId.startsWith("share:") -> null
    p.source == MusicSource.SPOTIFY -> WebLibraryFile.FROM_SPOTIFY
    p.source == MusicSource.YOUTUBE -> WebLibraryFile.FROM_YOUTUBE
    else -> null
}
