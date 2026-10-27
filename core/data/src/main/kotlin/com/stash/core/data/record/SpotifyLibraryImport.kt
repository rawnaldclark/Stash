package com.stash.core.data.record

import com.stash.core.data.db.StashDatabase
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.weblibrary.ImportSelection
import com.stash.core.data.weblibrary.ImportedLike
import com.stash.core.data.weblibrary.ImportedPlaylist
import com.stash.core.data.weblibrary.WebLibraryContent
import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblibrary.WebLibraryImportResult
import com.stash.core.data.weblibrary.WebLibraryImporter
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.merge.matchOccurrences
import com.stash.core.model.weblink.SongIdentity
import com.stash.core.model.weblink.SongKey
import com.stash.core.model.weblink.SongRef
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A song of a Spotify export's playlist or library (stash-player `src/lib/spotify/export.ts` `ExportSong`). */
data class ExportSong(
    val title: String,
    val artist: String,
    val album: String? = null,
    /** Its 22-character Spotify track id, when the export names one. */
    val sid: String? = null,
    /** When it was added to its playlist (epoch ms), when the export says. */
    val addedAt: Long? = null,
)

/** A playlist of a Spotify export (export.ts `ExportPlaylist`): its songs, the episodes and audiobooks left out. */
data class ExportPlaylist(val name: String, val description: String? = null, val items: List<ExportSong>)

/** A playlist an import brings into the library, with [key], its place in the whole export ("Chill#1": [SpotifyLibraryImport.playlistKeys]). */
data class ChosenPlaylist(val key: String, val playlist: ExportPlaylist)

/** A playlist as [SpotifyLibraryImport.alreadyIn] reads it: its name and its songs' words (importer.ts `HavePlaylist`). */
class HavePlaylist(val name: String, val items: List<SongIdentity>)

/**
 * The library half of Bring your Spotify (stash-player `src/lib/spotify/service.ts` `confirm` and `libraryData`, spec §5 step 3):
 * a Spotify export's playlists and liked songs into this library, through the web library's own importer
 * ([WebLibraryImporter]: songs matched by identity, else a stream-only row; blocked songs left out; adds only, never removes).
 *
 * - A playlist is known by its name and which of that name it is in the whole export (`name#n`), and becomes the CUSTOM playlist
 *   `custom_spotify_` + the first 24 hex characters of the SHA-256 of that key: no map to keep, so a playlist renamed here still
 *   receives its songs, and Remove import then the same export again makes nothing twice. `source` is [com.stash.core.model.MusicSource.BOTH],
 *   never SPOTIFY (a Spotify sync deactivates only its own), and it is never kept offline.
 * - Liked songs become Stash likes in Spotify's order (newest first), each a millisecond before the one above it, never sent on
 *   to Spotify or YouTube Music.
 *
 * Nothing is downloaded: the playlists are stream-only until the listener turns Download on, and the songs of Liked Songs only
 * when Liked Songs is kept offline ([likesDownload]: the preview says so first).
 */
@Singleton
class SpotifyLibraryImport @Inject constructor(
    private val database: StashDatabase,
    private val importer: WebLibraryImporter,
    private val liked: StashLikedPlaylistRepository,
) {
    /**
     * The indexes of [playlists] the library holds already (owner decision D3; importer.ts `alreadyIn`): one of the listener's
     * own playlists, as Library shows them and not made by a Spotify import, has the same folded name and at least half its
     * songs. They start unticked in the preview. An export playlist whose own playlist (`custom_spotify_…` of its `name#n`) an
     * earlier import made, still here, is that playlist, brought in once and updated now: never marked, or it would start
     * unticked on every import after the first (importer.ts `made`).
     */
    suspend fun alreadyHere(playlists: List<ExportPlaylist>): Set<Int> = withContext(Dispatchers.IO) {
        if (playlists.isEmpty()) return@withContext emptySet()
        val keys = playlistKeys(playlists)
        val dao = database.webLibraryExportDao()
        val made = playlists.indices.filterTo(HashSet()) { i -> database.playlistDao().findBySourceId(sourceIdOf(keys[i]))?.isActive == true }
        val names = playlists.mapTo(HashSet()) { SongKey.fold(it.name) }
        val byName = dao.playlists()
            .filter { !it.sourceId.startsWith(SOURCE_PREFIX) && SongKey.fold(it.name) in names }
            .map { p -> HavePlaylist(p.name, dao.playlistItems(p.id, WebLibraryFile.MAX_PLAYLIST_ITEMS).map { SongRef(it.track.title, it.track.artist, it.track.isrc) }) }
            .groupBy { SongKey.fold(it.name) }
        playlists.indices.filterTo(LinkedHashSet()) { i ->
            val p = playlists[i]
            i !in made && alreadyIn(have(p), byName[SongKey.fold(p.name)].orEmpty())
        }
    }

    /** Stash's Liked Songs is kept on this phone (its Download switch): liked songs brought in would download too. */
    suspend fun likesDownload(): Boolean = liked.keptOffline()

    /**
     * Brings [playlists] and [likes] into the library ([content]); [progress] is told the songs matched so far of all, and may
     * throw to stop where it is. Safe to run again: a playlist already made gains only what it lacks, a song already liked is
     * left as it is.
     */
    suspend fun write(playlists: List<ChosenPlaylist>, likes: List<ExportSong>, now: Long, progress: (done: Int, total: Int) -> Unit): WebLibraryImportResult =
        importer.import(
            content(playlists, likes, now),
            ImportSelection(likes = likes.isNotEmpty(), plays = false),
            ORIGIN,
            { sourceIdOf(it.id) },
            progress,
        )

    companion object {
        /** The `source_id` prefix of a playlist a Spotify export made. */
        const val SOURCE_PREFIX = "custom_spotify_"

        /** The import's origin (it brings no plays, so none is ever marked with it). */
        private const val ORIGIN = "spotify-export"

        /** The web cuts a playlist's name to this many UTF-16 units (service.ts `libraryData`). */
        private const val MAX_NAME = 100

        /**
         * importer.ts `playlistKeys`: each playlist's name and which of that name it is, "Chill#0", "Chill#1", counted over the
         * whole export, chosen or not (spec §5, step 3).
         */
        fun playlistKeys(playlists: List<ExportPlaylist>): List<String> {
            val seen = HashMap<String, Int>()
            return playlists.map { p ->
                val n = seen[p.name] ?: 0
                seen[p.name] = n + 1
                "${p.name}#$n"
            }
        }

        /** The playlist the export playlist [key] (`name#n`) becomes: `custom_spotify_` and 24 hex characters of its SHA-256. */
        fun sourceIdOf(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return SOURCE_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(24)
        }

        /**
         * importer.ts `alreadyIn`: one of [have] has [p]'s folded name and at least half its songs, paired by occurrence
         * ([matchOccurrences]: a song twice in the export needs two copies here). An empty playlist of the same name is held already;
         * a name that folds to nothing ("🔥") names no playlist and matches none.
         */
        fun alreadyIn(p: HavePlaylist, have: List<HavePlaylist>): Boolean {
            val name = SongKey.fold(p.name)
            if (name.isEmpty()) return false
            return have.any { h -> SongKey.fold(h.name) == name && 2 * matchOccurrences(h.items, p.items).count { it >= 0 } >= p.items.size }
        }

        /** An export playlist as [alreadyIn] reads it: its songs' words alone (an export song has no ISRC). */
        fun have(p: ExportPlaylist): HavePlaylist = HavePlaylist(p.name, p.items.map { SongRef(it.title, it.artist) })

        /**
         * service.ts `libraryData` for this library: each playlist under its key, its name cut to 100 UTF-16 units, its songs with
         * the time they were added (else [now]); the liked songs in Spotify's order, `likedAt = now − index`. The Spotify id goes
         * with each song (the web drops it; here it finds a song already synced from Spotify first).
         */
        fun content(playlists: List<ChosenPlaylist>, likes: List<ExportSong>, now: Long): WebLibraryContent = WebLibraryContent(
            likes = likes.mapIndexed { i, s -> ImportedLike(wire(s, now), likedAt = now - i) },
            playlists = playlists.map { c ->
                ImportedPlaylist(id = c.key, name = c.playlist.name.take(MAX_NAME), items = c.playlist.items.map { wire(it, now) }, createdAt = now, updatedAt = now)
            },
            history = emptyList(),
        )

        private fun wire(s: ExportSong, now: Long) = WireSong(title = s.title, artist = s.artist, album = s.album, spotifyId = s.sid, addedAt = s.addedAt ?: now)
    }
}
