package com.stash.core.data.weblink.mirror

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.MirrorDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.merge.Follow
import com.stash.core.data.weblink.merge.LikedSong
import com.stash.core.data.weblink.merge.PlayRec
import com.stash.core.data.weblink.merge.PlaysMerge
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.weblink.SongIndex
import com.stash.core.model.weblink.SongRef
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MirrorLibrary] over the app's Room library. Incoming songs are matched as a handoff matches them ([HandoffSongMatcher]: ISRC,
 * YouTube and Spotify ids, the same words, else a stream-only row); a `phoneOnly` song is found by its words only.
 */
@Singleton
class RoomMirrorLibrary @Inject constructor(
    private val database: StashDatabase,
    private val matcher: HandoffSongMatcher,
    private val liked: StashLikedPlaylistRepository,
) : MirrorLibrary {
    private val dao: MirrorDao get() = database.mirrorDao()

    override suspend fun likes(): List<LikedSong<WireSong>> = io {
        val seen = HashSet<Long>()
        dao.likes().mapNotNull { r ->
            if (!seen.add(r.song.id)) return@mapNotNull null
            songOf(r.song)?.let { LikedSong(it, external = !r.stash) }
        }
    }

    override suspend fun setLikes(on: List<WireSong>, off: List<WireSong>, at: Long) = io {
        if (on.isNotEmpty()) {
            val ids = matcher.matchAll(on, mirror = true).mapNotNull { it?.id }
            for (chunk in ids.chunked(BATCH)) database.withTransaction { liked.addAllFrom(chunk.map { it to at }, recount = false) }
            liked.recount()
        }
        if (off.isNotEmpty()) {
            // Only a Stash like can be cleared from a mirror: a song liked through Spotify / YouTube Music stays liked there.
            val stash = dao.likes().filter { it.stash }
            val index = SongIndex.of(stash.map { SongRef(it.song.title, it.song.artist, it.song.isrc) to it.song.id })
            val ids = off.mapNotNull { index.find(it) }.distinct()
            database.withTransaction { ids.forEach { liked.remove(it) } }
        }
    }

    override suspend fun ownPlays(since: Long, limit: Int): List<PlayRec<WireSong>> = io {
        dao.ownPlays(since, limit).mapNotNull { r -> songOf(r.song)?.let { PlayRec(it, r.playedAt) } }
    }

    override suspend fun ownPlayAt(n: Int): Long = io { if (n < 1) 0 else dao.ownPlayAt(n - 1) ?: 0 }

    override suspend fun addPlays(plays: List<PlayRec<WireSong>>) = io {
        if (plays.isEmpty()) return@io
        val here = dao.playKeysBetween(plays.minOf { it.playedAt }, plays.maxOf { it.playedAt })
            .mapTo(HashSet()) { PlaysMerge.playId(SongRef(it.title, it.artist, it.isrc), it.startedAt) }
        val fresh = plays.filter { here.add(PlaysMerge.playId(it.s, it.playedAt)) }
        if (fresh.isEmpty()) return@io
        val rows = matcher.matchAll(fresh.map { it.s }, mirror = true)
        val events = database.listeningEventDao()
        database.withTransaction {
            fresh.forEachIndexed { i, p ->
                val t = rows[i] ?: return@forEachIndexed
                // Already "scrobbled" everywhere and marked with its device: never submitted from here, never fed to mixes.
                events.insert(ListeningEventEntity(trackId = t.id, startedAt = p.playedAt, scrobbled = true, ytScrobbled = true, originDevice = p.origin))
            }
        }
    }

    override suspend fun dropPlaysFrom(origin: String, before: Long) = io { dao.dropPlaysFrom(origin, before); Unit }

    override suspend fun dropPlaysBefore(before: Long, floor: Long) = io { dao.dropPlaysBefore(before, floor); Unit }

    override suspend fun playlists(): List<LocalPlaylist> = io {
        val follows = database.webLibraryExportDao().followedMixes().associateBy { it.playlistId }
        dao.playlists().map { p ->
            LocalPlaylist(
                id = p.id,
                name = nameOf(p.name),
                createdAt = p.dateAdded.toEpochMilli(),
                ro = isSynced(p),
                follow = follows[p.id]?.let { Follow(it.shareId, it.version) },
                songs = dao.playlistCount(p.id),
            )
        }
    }

    override suspend fun playlist(localId: Long): LocalVersion? = io {
        val p = database.playlistDao().getById(localId)?.takeIf { it.isActive } ?: return@io null
        val follow = database.webLibraryExportDao().followedMixes().firstOrNull { it.playlistId == localId }?.let { Follow(it.shareId, it.version) }
        LocalVersion(nameOf(p.name), dao.playlistItems(localId, MirrorWire.MAX_PL_ITEMS).mapNotNull { songOf(it.song) }, follow, isSynced(p))
    }

    override suspend fun putPlaylist(localId: Long?, mirrorId: String, name: String, items: List<WireSong>): Long = io {
        val playlists = database.playlistDao()
        val ids = matcher.matchAll(items, mirror = true).mapNotNull { it?.id }.distinct()
        if (ids.size < items.size) Log.i(TAG, "${items.size - ids.size} song(s) of a mirrored playlist have no row here")
        database.withTransaction {
            val existing = localId?.let { playlists.getById(it) }?.takeIf { it.isActive }
                ?: playlists.findBySourceId(SOURCE_PREFIX + mirrorId)
            val id = existing?.id ?: playlists.insert(
                // Not set to sync: a mirrored playlist never starts downloads by itself (#532).
                PlaylistEntity(name = name, source = MusicSource.BOTH, sourceId = SOURCE_PREFIX + mirrorId, type = PlaylistType.CUSTOM, isActive = true, syncEnabled = false),
            )
            if (existing != null) {
                if (!existing.isActive) playlists.setActiveById(id, true)
                if (existing.name != name) playlists.updateName(id, name)
            }
            if (playlists.getOrderedTrackIdsForPlaylist(id) != ids) {
                dao.clearPlaylist(id)
                val now = Instant.now()
                ids.forEachIndexed { i, t -> playlists.insertCrossRef(PlaylistTrackCrossRef(playlistId = id, trackId = t, position = i, addedAt = now, locallyAdded = true)) }
                playlists.updateTrackCount(id, ids.size)
            }
            id
        }
    }

    override suspend fun deletePlaylist(localId: Long) = io {
        // Only the playlist goes (deleted everywhere from another device); its songs and their files stay in the library.
        database.withTransaction {
            dao.clearPlaylist(localId)
            database.playlistDao().deleteById(localId)
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val TAG = "WebLinkMirror"
        private const val BATCH = 500

        /** A playlist made from the mirror (spec §4.3); Library shows every `custom_%` playlist. */
        const val SOURCE_PREFIX = "custom_sync_"

        /** Synced from Spotify or YouTube Music: it mirrors phone → web only and is read-only on the web (spec §2.4). */
        fun isSynced(p: PlaylistEntity): Boolean =
            (p.source == MusicSource.SPOTIFY || p.source == MusicSource.YOUTUBE) &&
                !p.sourceId.startsWith("custom_") && !p.sourceId.startsWith("share:")

        fun nameOf(name: String) = name.trim().ifEmpty { "Playlist" }.let { com.stash.core.data.weblink.merge.FirstMerge.cutUnits(it, MirrorWire.NAME_MAX) }

        /**
         * A library row as a wire song: the library file's Song, marked `phoneOnly` when a file here is its only source (no ISRC,
         * Spotify or YouTube id), as the handoff marks it. Null without a title or an artist.
         */
        fun songOf(row: MirrorDao.MirrorSong): WireSong? {
            val t = row.toTrack()
            val song = WebLibraryFile.song(t, addedAt = null) ?: return null
            val noIds = song.isrc == null && song.spotifyId == null && song.refs.isNullOrEmpty()
            return WireSong.of(song, phoneOnly = noIds && (t.source == MusicSource.LOCAL || t.isDownloaded))
        }
    }
}
