package com.stash.core.data.album

import com.stash.core.common.TrackCanonical
import com.stash.core.common.primaryArtist
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import java.text.Normalizer
import java.util.Locale

/**
 * One album saved in the library, with its songs: what a Library › Albums card stands for.
 *
 * A saved album is a playlist row ([playlistId]) that [SavedAlbums.isSavedAlbum] recognises. Its
 * songs are the playlist's memberships in their saved positions, so its tracklist is the album's
 * own order and no song can appear twice (one membership per song).
 *
 * @property artist        The act most of its songs credit first ([SavedAlbums.leadArtist]).
 * @property trackCount    Its songs the album page lists in Stream only (blocked songs left out).
 * @property downloadedCount Of those, the ones on this phone.
 * @property downloadingCount Of the rest, the ones waiting or running in the download queue.
 * @property songKeys      Its songs as [SavedAlbums.songKey]s, to tell a second copy of this album
 *                         from another album that shares its name ([SavedAlbums.sameAlbum]).
 */
data class SavedAlbum(
    val playlistId: Long,
    val title: String,
    val artist: String,
    val artUrl: String?,
    val source: MusicSource,
    val sourceId: String,
    val dateAdded: Long,
    val trackCount: Int,
    val downloadedCount: Int,
    val downloadingCount: Int,
    val songKeys: Set<String> = emptySet(),
) {
    /** Every song is on this phone: Download mode lists it. */
    val onPhone: Boolean get() = trackCount > 0 && downloadedCount >= trackCount

    /** Not all on the phone yet, and the rest is downloading now: Download mode lists it with its progress. */
    val downloading: Boolean get() = !onPhone && downloadingCount > 0
}

/**
 * What Stash calls an album (owner, 2026-11-02, items 6 + 7): a release saved whole, never songs
 * grouped by their album text. Stash files a saved album as an ordinary `CUSTOM` playlist and knows
 * it by its source id, so sync, the Download switch, undo, backup and the web keep working as they
 * do for playlists. The rule lives here and in [com.stash.core.data.db.dao.SAVED_ALBUM] (its SQL
 * twin): change the two together.
 *
 * Today's ways in, all `CUSTOM`:
 *  - Save on an album page: `album:youtube:<MPREb_…>` or `album:qobuz:<id>` ([sourceId]);
 *  - a pasted album link: `album:spotify:<id>` or `album:youtube:<MPREb_…>`;
 *  - YouTube Music's saved albums, synced: source YOUTUBE, id `MPREb_…`.
 *
 * Spotify's saved albums (item 5) plug in by snapshotting each one as source SPOTIFY under
 * `sourceId("spotify", id)`: Albums, the pickers, Home and the album page follow by themselves.
 */
object SavedAlbums {

    private const val PREFIX = "album:"

    /** A YouTube Music album's browse id, which is how a synced saved album is keyed. */
    private const val YOUTUBE_ALBUM_ID = "MPREb"

    /** The key an album from [service] ("youtube", "qobuz", "spotify") is saved under. */
    fun sourceId(service: String, albumId: String): String = "$PREFIX$service:$albumId"

    /**
     * Whether a playlist row is a saved album. A saved Qobuz playlist also gets an `album:` key
     * (`album:qobuz_playlist:<id>`), so a kind that names a playlist is left out.
     */
    fun isSavedAlbum(type: PlaylistType, source: MusicSource, sourceId: String): Boolean =
        type == PlaylistType.CUSTOM && (
            (sourceId.startsWith(PREFIX, ignoreCase = true) &&
                !sourceId.substring(PREFIX.length).contains("playlist:", ignoreCase = true)) ||
                (source == MusicSource.YOUTUBE && sourceId.startsWith(YOUTUBE_ALBUM_ID, ignoreCase = true))
            )

    fun isSavedAlbum(playlist: Playlist): Boolean = isSavedAlbum(playlist.type, playlist.source, playlist.sourceId)

    /**
     * The album as "service:id", whichever way it was saved: `album:youtube:MPREb_x` and a synced
     * `MPREb_x` are both "youtube:MPREb_x". The id keeps its case (catalog ids are case-sensitive).
     * Null for a row that isn't a saved album's.
     */
    fun serviceKey(source: MusicSource, sourceId: String): String? = when {
        sourceId.startsWith(PREFIX, ignoreCase = true) -> {
            val rest = sourceId.substring(PREFIX.length)
            val service = rest.substringBefore(':', missingDelimiterValue = "")
            val id = rest.substringAfter(':', missingDelimiterValue = "")
            if (service.isEmpty() || id.isEmpty()) null else "${service.lowercase(Locale.ROOT)}:$id"
        }
        source == MusicSource.YOUTUBE && sourceId.startsWith(YOUTUBE_ALBUM_ID, ignoreCase = true) -> "youtube:$sourceId"
        else -> null
    }

    /**
     * Title and lead act, folded so the two services' spellings meet: case, accents, curly quotes,
     * "&" / "and" and punctuation are set aside ("Echo & the Bunnymen" is "Echo and The
     * Bunnymen"). Words in brackets stay, so "Abbey Road (Super Deluxe)" is another release.
     */
    fun nameKey(title: String, artist: String): String = "${fold(artist.primaryArtist())}|${fold(title)}"

    /**
     * A song as two copies of one album both name it: [TrackCanonical.title] (brackets and a
     * featured credit dropped), then a " - Remastered 2011" style suffix dropped, then folded.
     */
    fun songKey(title: String): String = fold(TrackCanonical.title(title).substringBefore(" - "))

    /**
     * The act an album is by: the one most of its songs credit first (a tie goes to the first act
     * A to Z). Stash stores no album artist for a saved album, so its songs say. The songs' order
     * plays no part, so the card (songs from [com.stash.core.data.db.dao.PlaylistDao.observeSavedAlbums],
     * in no set order) and the album page (songs in album order) name the same act.
     */
    fun leadArtist(songArtists: List<String>): String {
        val counts = HashMap<String, Int>()
        songArtists.map { it.primaryArtist().trim() }.filter { it.isNotEmpty() }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return counts.entries
            .minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key }.thenBy { it.key })
            ?.key.orEmpty()
    }

    /**
     * Whether [a] and [b] are copies of one album (one card): the same album on the same service
     * ([serviceKey]), or the same title and lead act ([nameKey]) with at least half of the shorter
     * tracklist's songs in the other. The songs keep apart albums that share a name, like Weezer's
     * self-titled ones, while a copy missing a song or two still meets the full one.
     */
    fun sameAlbum(a: SavedAlbum, b: SavedAlbum): Boolean {
        val keyA = serviceKey(a.source, a.sourceId)
        if (keyA != null && keyA == serviceKey(b.source, b.sourceId)) return true
        return nameKey(a.title, a.artist) == nameKey(b.title, b.artist) && sameSongs(a.songKeys, b.songKeys)
    }

    /** At least half of the shorter list is in the longer one. Two empty lists say nothing. */
    fun sameSongs(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val shared = a.count { it in b }
        return shared * 2 >= minOf(a.size, b.size)
    }

    /**
     * The copy a merged card stands for: the one on the phone, then one downloading, then the
     * longest tracklist, then the most songs downloaded, then the newest.
     */
    private val MOST_COMPLETE: Comparator<SavedAlbum> =
        compareByDescending<SavedAlbum> { it.onPhone }
            .thenByDescending { it.downloading }
            .thenByDescending { it.trackCount }
            .thenByDescending { it.downloadedCount }
            .thenByDescending { it.dateAdded }
            .thenByDescending { it.playlistId }

    /**
     * One card per album: copies ([sameAlbum], followed through, so A~B and B~C are one) collapse
     * to their most complete copy ([MOST_COMPLETE]). Keeps the order of each album's first copy.
     */
    fun oneCardPerAlbum(albums: List<SavedAlbum>): List<SavedAlbum> {
        if (albums.size < 2) return albums
        val parent = IntArray(albums.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val next = parent[j]; parent[j] = r; j = next }
            return r
        }
        fun join(i: Int, j: Int) { val a = root(i); val b = root(j); if (a != b) parent[maxOf(a, b)] = minOf(a, b) }

        // Only rows that share a service key or a name key can be copies: compare within those.
        albums.indices.groupBy { serviceKey(albums[it].source, albums[it].sourceId) }
            .forEach { (key, ids) -> if (key != null) ids.drop(1).forEach { join(ids.first(), it) } }
        albums.indices.groupBy { nameKey(albums[it].title, albums[it].artist) }.values
            .filter { it.size > 1 }
            .forEach { ids ->
                for (x in ids.indices) for (y in x + 1 until ids.size) {
                    if (sameSongs(albums[ids[x]].songKeys, albums[ids[y]].songKeys)) join(ids[x], ids[y])
                }
            }
        return albums.indices.groupBy { root(it) }.values.map { group -> group.map { albums[it] }.sortedWith(MOST_COMPLETE).first() }
    }

    /**
     * Library › Albums: every saved album with songs, one card per album. Download mode (fully
     * offline, 2026-10-29) lists an album once all its songs are on the phone, or while the rest
     * is downloading (its card says how far it is); Stream only lists them all.
     */
    fun forLibrary(albums: List<SavedAlbum>, streamOnly: Boolean): List<SavedAlbum> =
        oneCardPerAlbum(albums.filter { it.trackCount > 0 })
            .filter { streamOnly || it.onPhone || it.downloading }

    /**
     * The saved copy of the album an album page shows, or null: saved under one of [sourceIds]
     * (the page's own keys, any service's), or the same title, act and songs. [songTitles] are the
     * page's tracklist; empty while it loads, when only the keys can tell.
     */
    fun findSaved(
        saved: List<SavedAlbum>,
        sourceIds: Collection<String>,
        title: String,
        artist: String,
        songTitles: List<String>,
    ): SavedAlbum? {
        val keys = sourceIds.mapNotNull { serviceKey(MusicSource.BOTH, it) }.toSet()
        saved.firstOrNull { it.sourceId in sourceIds || serviceKey(it.source, it.sourceId) in keys }?.let { return it }
        if (title.isBlank() || songTitles.isEmpty()) return null
        val name = nameKey(title, artist)
        val songs = songTitles.mapTo(HashSet()) { songKey(it) }
        return saved.firstOrNull { nameKey(it.title, it.artist) == name && sameSongs(songs, it.songKeys) }
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(NOT_WORD, " ")
            .trim()
}

/** [com.stash.core.data.db.dao.SavedAlbumRow] as the app reads it: the act from its songs, its songs as keys. */
fun com.stash.core.data.db.dao.SavedAlbumRow.toSavedAlbum(): SavedAlbum {
    val separator = com.stash.core.data.db.dao.SAVED_ALBUM_LIST_SEPARATOR
    val songArtists = artists?.split(separator).orEmpty()
    val songTitles = titles?.split(separator).orEmpty()
    return SavedAlbum(
        playlistId = id,
        title = name,
        artist = SavedAlbums.leadArtist(songArtists),
        artUrl = artUrl,
        source = source,
        sourceId = sourceId,
        dateAdded = dateAdded,
        trackCount = trackCount,
        downloadedCount = downloadedCount,
        downloadingCount = downloadingCount,
        songKeys = songTitles.mapTo(LinkedHashSet()) { SavedAlbums.songKey(it) }.apply { remove("") },
    )
}
