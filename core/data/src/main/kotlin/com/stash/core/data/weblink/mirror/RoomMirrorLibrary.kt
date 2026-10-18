package com.stash.core.data.weblink.mirror

import android.util.Log
import androidx.room.withTransaction
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.MirrorDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.listening.ListeningDay
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.data.weblink.handoff.MirrorIdentities
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.merge.Follow
import com.stash.core.data.weblink.merge.LikedSong
import com.stash.core.data.weblink.merge.PlayRec
import com.stash.core.data.weblink.merge.PlaysMerge
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.weblink.SongIndex
import com.stash.core.model.weblink.SongKey
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
class RoomMirrorLibrary internal constructor(
    private val database: StashDatabase,
    private val matcher: HandoffSongMatcher,
    private val liked: StashLikedPlaylistRepository,
    private val aliases: AliasStore,
) : MirrorLibrary {
    @Inject constructor(
        database: StashDatabase,
        matcher: HandoffSongMatcher,
        liked: StashLikedPlaylistRepository,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ) : this(database, matcher, liked, PrefsAliasStore(context.getSharedPreferences(FileMirrorStore.MAP_PREFS, android.content.Context.MODE_PRIVATE)))

    /** Tests: the aliases in memory. */
    internal constructor(database: StashDatabase, matcher: HandoffSongMatcher, liked: StashLikedPlaylistRepository) :
        this(database, matcher, liked, MemoryAliasStore())

    private val dao: MirrorDao get() = database.mirrorDao()

    /**
     * A row matched to a song whose words differ a little (an id match, review S4) stands for that song in the mirror: it is
     * read back under the song's identity, so the next diff sees the same song, unlikes find it both ways, and the phone's own
     * words never replace the other side's (review R-S3). The matcher allows that only for a row with no mirror history, so no
     * identity the mirror already sent is ever rewritten (final check F-B1).
     */
    private suspend fun remember(songs: List<WireSong>, rows: List<TrackEntity?>) {
        val add = HashMap<Long, WireSong>()
        songs.forEachIndexed { i, s ->
            val row = rows.getOrNull(i) ?: return@forEachIndexed
            if (!SongKey.sameSong(SongRef(row.title, row.artist, row.isrc), s)) add[row.id] = s
        }
        if (add.isNotEmpty()) aliases.put(add)
        // Rows deleted since are let go (final check F-N9).
        val keys = aliases.keys()
        if (keys.isNotEmpty()) {
            val alive = keys.chunked(BATCH).flatMapTo(HashSet()) { dao.existingTracks(it) }
            aliases.remove(keys.filterNot { it in alive })
        }
    }

    /** A row's alias, unless its words were changed to another recording since (final check F-N9). */
    private fun aliasOf(id: Long, title: String, artist: String, isrc: String?): WireSong? {
        val a = aliases.get(id) ?: return null
        val row = TrackEntity(id = id, title = title, artist = artist, isrc = isrc)
        if (HandoffSongMatcher.sameRecording(row, a)) return a
        aliases.remove(listOf(id))
        return null
    }

    private val identities = object : MirrorIdentities {
        override fun aliasOf(row: TrackEntity): WireSong? = aliasOf(row.id, row.title, row.artist, row.isrc)
        override suspend fun hasHistory(trackId: Long): Boolean = dao.hasHistory(trackId)
    }

    private fun wire(row: MirrorDao.MirrorSong): WireSong? = aliasOf(row.id, row.title, row.artist, row.isrc) ?: songOf(row)

    override suspend fun forget() = io { aliases.clear() }

    override suspend fun likes(): List<LikedSong<WireSong>> = io {
        val seen = HashSet<Long>()
        dao.likes().mapNotNull { r ->
            if (!seen.add(r.song.id)) return@mapNotNull null
            wire(r.song)?.let { LikedSong(it, external = !r.stash) }
        }
    }

    override suspend fun setLikes(on: List<WireSong>, off: List<WireSong>, at: Long) = io {
        if (on.isNotEmpty()) {
            val rows = matcher.matchAll(on, mirror = true, identities = identities)
            remember(on, rows)
            val ids = rows.mapNotNull { it?.id }
            for (chunk in ids.chunked(BATCH)) database.withTransaction { liked.addAllFrom(chunk.map { it to at }, recount = false) }
            liked.recount()
        }
        if (off.isNotEmpty()) {
            // Only a Stash like can be cleared from a mirror: a song liked through Spotify / YouTube Music stays liked there.
            val stash = dao.likes().filter { it.stash }
            val index = SongIndex.of(stash.mapNotNull { r -> wire(r.song)?.let { it to r.song.id } })
            val ids = off.mapNotNull { index.find(it) }.distinct()
            database.withTransaction { ids.forEach { liked.remove(it) } }
        }
    }

    override suspend fun ownPlays(since: Long, limit: Int): List<PlayRec<WireSong>> = io {
        dao.ownPlays(since, limit).mapNotNull { r -> wire(r.song)?.let { PlayRec(it, r.playedAt) } }
    }

    override suspend fun ownPlayAt(n: Int): Long = io { if (n < 1) 0 else dao.ownPlayAt(n - 1) ?: 0 }

    override suspend fun addPlays(plays: List<PlayRec<WireSong>>) = io {
        if (plays.isEmpty()) return@io
        val here = dao.playKeysBetween(plays.minOf { it.playedAt }, plays.maxOf { it.playedAt })
            .mapTo(HashSet()) { PlaysMerge.playId(SongRef(it.title, it.artist, it.isrc), it.startedAt) }
        val fresh = plays.filter { here.add(PlaysMerge.playId(it.s, it.playedAt)) }
        if (fresh.isEmpty()) return@io
        val rows = matcher.matchAll(fresh.map { it.s }, mirror = true, identities = identities)
        remember(fresh.map { it.s }, rows)
        val events = database.listeningEventDao()
        database.withTransaction {
            fresh.forEachIndexed { i, p ->
                val t = rows[i] ?: return@forEachIndexed
                // Already "scrobbled" everywhere and marked with its device: never submitted from here, never fed to mixes.
                // Its day is read in this phone's zone: the mirror doesn't carry the other device's zone.
                val stamp = ListeningDay.localStamp(p.playedAt)
                events.insert(
                    ListeningEventEntity(
                        trackId = t.id, startedAt = p.playedAt, scrobbled = true, ytScrobbled = true, originDevice = p.origin,
                        localDay = stamp.day, localMinute = stamp.minute,
                    ),
                )
            }
        }
    }

    override suspend fun dropPlaysFrom(origin: String, before: Long) = io { dao.dropPlaysFrom(origin, before); Unit }

    override suspend fun dropOthersBefore(before: Long) = io { dao.dropOthersBefore(before); Unit }

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
        LocalVersion(nameOf(p.name), dao.playlistItems(localId, MirrorWire.MAX_PL_ITEMS).mapNotNull { wire(it.song) }, follow, isSynced(p))
    }

    override suspend fun putPlaylist(localId: Long?, mirrorId: String, name: String, items: List<WireSong>): PutPlaylist = io {
        val playlists = database.playlistDao()
        // Throws (MirrorMatchException) when a batch couldn't be matched: nothing is written then (review B2).
        val rows = matcher.matchAll(items, mirror = true, identities = identities)
        remember(items, rows)
        val seen = HashSet<Long>()
        // A repeat of one row is kept once here (one cross-ref per song); the engine carries the others (review B2).
        val held = rows.map { row -> row != null && seen.add(row.id) }
        val ids = rows.filterIndexed { i, _ -> held[i] }.map { it!!.id }
        if (ids.size < items.size) Log.i(TAG, "${items.size - ids.size} song(s) of a mirrored playlist aren't held here")
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
            PutPlaylist(id, held)
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

/** Track id → the song a loosely matched row stands for in the mirror (review R-S3). */
interface AliasStore {
    fun get(trackId: Long): WireSong?
    fun put(entries: Map<Long, WireSong>)
    fun keys(): List<Long>
    fun remove(ids: Collection<Long>)
    fun clear()
}

internal class MemoryAliasStore : AliasStore {
    private val map = HashMap<Long, WireSong>()
    override fun get(trackId: Long) = map[trackId]
    override fun put(entries: Map<Long, WireSong>) = map.putAll(entries)
    override fun keys() = map.keys.toList()
    override fun remove(ids: Collection<Long>) { ids.forEach { map.remove(it) } }
    override fun clear() = map.clear()
}

/** Kept with the mirror's other small state (excluded from backups and transfers), as one JSON object of wire songs. */
internal class PrefsAliasStore(private val prefs: android.content.SharedPreferences) : AliasStore {
    private val lock = Any()
    private var cache: HashMap<Long, WireSong>? = null

    private fun all(): HashMap<Long, WireSong> = cache ?: HashMap<Long, WireSong>().also { m ->
        val text = prefs.getString(KEY, null)
        if (text != null) {
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject }.getOrNull()?.forEach { (k, v) ->
                val id = k.toLongOrNull() ?: return@forEach
                com.stash.core.data.weblink.handoff.HandoffWire.readSong(v)?.let { m[id] = it }
            }
        }
        cache = m
    }

    override fun get(trackId: Long): WireSong? = synchronized(lock) { all()[trackId] }

    override fun put(entries: Map<Long, WireSong>) = synchronized(lock) {
        all().putAll(entries)
        write()
    }

    override fun keys(): List<Long> = synchronized(lock) { all().keys.toList() }

    override fun remove(ids: Collection<Long>) = synchronized(lock) {
        val m = all()
        if (ids.count { m.remove(it) != null } > 0) write()
    }

    private fun write() {
        val m = all()
        val json = kotlinx.serialization.json.JsonObject(m.entries.associate { (k, v) -> k.toString() to com.stash.core.data.weblink.handoff.HandoffWire.songJson(v) })
        // Written through before the run saves its record: a process death in between must not leave a record that reads an
        // aliased row under its own words (last look L-N3). Every caller runs on IO.
        prefs.edit().putString(KEY, json.toString()).commit()
    }

    override fun clear() = synchronized(lock) {
        cache = HashMap()
        prefs.edit().remove(KEY).commit()
        Unit
    }

    private companion object {
        const val KEY = "aliases"
    }
}
