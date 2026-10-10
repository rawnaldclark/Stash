package com.stash.core.data.weblibrary

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.ShareResult
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.weblink.SongKey
import com.stash.core.model.weblink.SongRef
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One playlist of a file or a send as the import picker lists it. */
data class ImportPlaylistChoice(val id: String, val name: String, val songs: Int, val sharedMix: Boolean)

/** What the import picker shows ("This file has 1,204 likes, 5,000 plays and 14 playlists"). */
data class ImportSummary(val likes: Int, val plays: Int, val playlists: List<ImportPlaylistChoice>) {
    companion object {
        fun of(c: WebLibraryContent) = ImportSummary(
            likes = c.likes.size,
            plays = c.history.size,
            playlists = c.playlists.map { ImportPlaylistChoice(it.id, it.name, it.items.size, it.follow != null) },
        )
    }
}

/** What an import added, and what was already here ("skipped"). */
data class WebLibraryImportResult(
    val likesAdded: Int = 0,
    val likesSkipped: Int = 0,
    val playlistsAdded: Int = 0,
    /** Playlists already here that gained songs. */
    val playlistsUpdated: Int = 0,
    /** Playlists already here (or followed mixes) left as they were. */
    val playlistsSkipped: Int = 0,
    val songsAdded: Int = 0,
    val playsAdded: Int = 0,
    val playsSkipped: Int = 0,
)

/**
 * "Import from Stash on the web" and a received send (link-sync spec §2.3, §9): merges a `stash-web-library` v1 file into this
 * library, adding and never removing or reordering ([WebLibraryMergePlanner] decides, this writes):
 * - songs are matched by identity (ISRC, then the YouTube and Spotify ids, then the same words with `sameSong`, else a new
 *   stream-only row: [HandoffSongMatcher], as a handoff does); songs this phone blocked are left out;
 * - likes become Stash likes at their own time, never sent on to Spotify or YouTube Music;
 * - a playlist is merged by its id: an `app-<n>` id (this phone's own export coming back) into playlist n when it is still
 *   called the same, else into the `custom_web_<id>` playlist an earlier import made; a new one becomes `custom_web_<id>`;
 *   a followed shared mix is followed here too (copied when the share service can't be reached);
 * - plays go into History marked as from another device ([ListeningEventEntity.originDevice]), already "scrobbled", so they
 *   never feed Stash Mixes, play counts, auto-save or a scrobble destination (spec §7.2).
 * Settings in the file are never read.
 */
@Singleton
class WebLibraryImporter internal constructor(
    private val context: Context,
    private val database: StashDatabase,
    private val matcher: HandoffSongMatcher,
    private val liked: StashLikedPlaylistRepository,
    private val musicRepository: MusicRepository,
    /** Follows the shared mix with that id here; false when it can't be fetched. */
    private val followMix: suspend (String) -> Boolean,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        database: StashDatabase,
        matcher: HandoffSongMatcher,
        liked: StashLikedPlaylistRepository,
        musicRepository: MusicRepository,
        sharedMixes: SharedMixRepository,
    ) : this(context, database, matcher, liked, musicRepository, { id ->
        when (val r = sharedMixes.fetch(id)) {
            is ShareResult.Ok -> {
                sharedMixes.follow(r.value)
                true
            }
            else -> false
        }
    })

    /** One import at a time: two at once would both see a playlist as new and make it twice. */
    private val lock = Mutex()

    /** Reads the file at [uri] (the system file picker's document). */
    suspend fun read(uri: Uri): Result<WebLibraryContent> = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: throw WebLibraryReadException(WebLibraryReader.NOT_A_BACKUP)
            Result.success(stream.use { WebLibraryReader.read(it, System.currentTimeMillis()) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebLibraryReadException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "can't read the file: ${e.javaClass.simpleName}")
            Result.failure(WebLibraryReadException(WebLibraryReader.NOT_A_BACKUP))
        }
    }

    /**
     * Merges the [selection] of [content] into the library. [origin] marks the plays: the sending device's id for a send, or
     * [ORIGIN_FILE] for a file.
     */
    suspend fun import(content: WebLibraryContent, selection: ImportSelection, origin: String): WebLibraryImportResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val plan = WebLibraryMergePlanner.plan(content, selection, local(content, selection))
                var result = WebLibraryImportResult(likesSkipped = plan.likesSkipped, playsSkipped = plan.playsSkipped)
                result = importLikes(plan, result)
                for (action in plan.playlists) result = importPlaylist(action, result)
                result = importPlays(plan, origin, result)
                Log.i(TAG, "imported: $result")
                result
            }
        }

    // -------------------------------------------------------------------------------------------- what is here

    private suspend fun local(content: WebLibraryContent, selection: ImportSelection): LocalLibrary {
        val dao = database.webLibraryExportDao()
        val likes = if (selection.likes && content.likes.isNotEmpty()) {
            dao.likes().map { SongRef(it.track.title, it.track.artist, it.track.isrc) }
        } else {
            emptyList()
        }
        val playKeys = if (selection.plays && content.history.isNotEmpty()) {
            dao.playKeysBetween(content.history.minOf { it.playedAt }, content.history.maxOf { it.playedAt })
                .mapTo(HashSet()) { WebLibraryMergePlanner.playKey(SongRef(it.title, it.artist, it.isrc), it.startedAt) }
        } else {
            emptySet()
        }
        val targets = HashMap<String, LocalPlaylist>()
        val followed = HashSet<String>()
        for (p in content.playlists) {
            if (!selection.includes(p.id)) continue
            val follow = p.follow
            if (follow != null) {
                if (database.sharedMixDao().byShareId(follow.id) != null) followed += follow.id
                continue
            }
            val id = targetOf(p) ?: continue
            targets[p.id] = LocalPlaylist(id, dao.playlistItems(id, WebLibraryFile.MAX_PLAYLIST_ITEMS).map { SongRef(it.track.title, it.track.artist, it.track.isrc) })
        }
        return LocalLibrary(likes, playKeys, targets, followed)
    }

    /** This phone's playlist an incoming one merges into, or null for a new one. */
    private suspend fun targetOf(p: ImportedPlaylist): Long? {
        val playlists = database.playlistDao()
        // This phone's own export coming back: the same playlist when it still exists here under the same name. Another
        // phone's `app-42` is a different playlist that happens to share the number, so the name must agree too.
        APP_ID.matchEntire(p.id)?.groupValues?.get(1)?.toLongOrNull()?.let { n ->
            val own = playlists.getById(n)
            if (own != null && own.type == PlaylistType.CUSTOM && own.isActive && SongKey.fold(own.name) == SongKey.fold(p.name)) return own.id
        }
        return playlists.findBySourceId(sourceIdOf(p))?.takeIf { it.isActive }?.id
    }

    // -------------------------------------------------------------------------------------------- writing

    /** The matched rows of [songs], null for one that can't be held here or that this phone blocked. */
    private suspend fun rows(songs: List<WireSong>): List<TrackEntity?> {
        val blocklist = database.trackBlocklistDao()
        return matcher.matchAll(songs).map { t ->
            t?.takeUnless { blocklist.isBlocked("${it.canonicalArtist}|${it.canonicalTitle}", it.spotifyUri, it.youtubeId) }
        }
    }

    private suspend fun importLikes(plan: MergePlan, r: WebLibraryImportResult): WebLibraryImportResult {
        if (plan.likes.isEmpty()) return r
        val rows = rows(plan.likes.map { it.song })
        val pairs = plan.likes.indices.mapNotNull { i -> rows[i]?.let { it.id to plan.likes[i].likedAt } }
        val added = liked.addAllFrom(pairs)
        return r.copy(likesAdded = added, likesSkipped = r.likesSkipped + plan.likes.size - added)
    }

    private suspend fun importPlaylist(action: PlaylistAction, r: WebLibraryImportResult): WebLibraryImportResult = try {
        when (action) {
            is PlaylistAction.Skip -> r.copy(playlistsSkipped = r.playlistsSkipped + 1)
            is PlaylistAction.Append -> {
                val n = append(action.localId, action.songs)
                if (n == 0) r.copy(playlistsSkipped = r.playlistsSkipped + 1) else r.copy(playlistsUpdated = r.playlistsUpdated + 1, songsAdded = r.songsAdded + n)
            }
            is PlaylistAction.Create -> create(action.playlist, r)
            is PlaylistAction.Follow -> {
                val follow = action.playlist.follow!!
                if (followMix(follow.id)) r.copy(playlistsAdded = r.playlistsAdded + 1) else create(action.playlist, r)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // One playlist that can't be written doesn't stop the others.
        Log.w(TAG, "playlist not imported: ${e.javaClass.simpleName}")
        r
    }

    /** A new CUSTOM playlist `custom_web_<id>` with [p]'s songs (or, when one was made meanwhile, the songs it lacks). */
    private suspend fun create(p: ImportedPlaylist, r: WebLibraryImportResult): WebLibraryImportResult {
        val sourceId = sourceIdOf(p)
        val existing = database.playlistDao().findBySourceId(sourceId)
        if (existing != null) {
            val n = append(existing.id, p.items)
            return r.copy(playlistsUpdated = r.playlistsUpdated + (if (n > 0) 1 else 0), playlistsSkipped = r.playlistsSkipped + (if (n > 0) 0 else 1), songsAdded = r.songsAdded + n)
        }
        val ids = rows(p.items).mapNotNull { it?.id }.distinct()
        val playlists = database.playlistDao()
        val playlistId = database.withTransaction {
            val id = playlists.ensurePlaylist(
                PlaylistEntity(
                    name = p.name,
                    source = MusicSource.BOTH,
                    sourceId = sourceId,
                    type = PlaylistType.CUSTOM,
                    isActive = true,
                    syncEnabled = true,
                ),
            )
            val now = Instant.now()
            ids.forEachIndexed { i, trackId ->
                playlists.insertCrossRef(PlaylistTrackCrossRef(playlistId = id, trackId = trackId, position = i, addedAt = now, locallyAdded = true))
            }
            id
        }
        playlists.updateTrackCount(playlistId, database.trackDao().getByPlaylist(playlistId, includeStreamable = true).first().size)
        return r.copy(playlistsAdded = r.playlistsAdded + 1, songsAdded = r.songsAdded + ids.size)
    }

    /** Adds the songs of [songs] that [playlistId] doesn't already hold (by row), at the end. Returns how many. */
    private suspend fun append(playlistId: Long, songs: List<WireSong>): Int {
        if (songs.isEmpty()) return 0
        val have = database.playlistDao().getCrossRefsForPlaylist(playlistId).filter { it.removedAt == null }.mapTo(HashSet()) { it.trackId }
        val ids = rows(songs).mapNotNull { it?.id }.filter { have.add(it) }
        if (ids.isNotEmpty()) musicRepository.addTracksToPlaylist(ids, playlistId)
        return ids.size
    }

    private suspend fun importPlays(plan: MergePlan, origin: String, r: WebLibraryImportResult): WebLibraryImportResult {
        if (plan.plays.isEmpty()) return r
        val rows = rows(plan.plays.map { it.song })
        val events = database.listeningEventDao()
        var added = 0
        database.withTransaction {
            plan.plays.forEachIndexed { i, play ->
                val t = rows[i] ?: return@forEachIndexed
                // Already counted ("scrobbled") everywhere: a play from another device is never submitted from here.
                events.insert(
                    ListeningEventEntity(trackId = t.id, startedAt = play.playedAt, scrobbled = true, ytScrobbled = true, originDevice = origin),
                )
                added++
            }
        }
        return r.copy(playsAdded = added, playsSkipped = r.playsSkipped + plan.plays.size - added)
    }

    companion object {
        private const val TAG = "WebLibraryImport"

        /** The `origin_device` of plays imported from a file. */
        const val ORIGIN_FILE = "file"

        private val APP_ID = Regex("^app-(\\d{1,18})$")

        /** The playlist an imported one becomes: CUSTOM, shown by Library like every `custom_%` playlist. */
        fun sourceIdOf(p: ImportedPlaylist) = "custom_web_${p.id}"
    }
}
