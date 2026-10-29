package com.stash.feature.record.art

import com.stash.core.data.db.dao.LibraryArtDao

/**
 * The library's covers by the record's keys ([ArtText.artKey]), owner decision D1: a cover of the person's own files (a local
 * import's, a scanned or cloud file's embedded art, a download's) is the song's cover, before any lookup; a remote address the
 * library has (i.scdn.co, ytimg, ...) only where the lookups found nothing.
 *
 * Each track is kept under `al:` its artist and album, `al:` its album artist and album (a guest's song on someone else's
 * album), and `tr:` its artist and title (a play with no album); words that fold to nothing make no key. The first row with a key wins: [local] and [remote] are
 * apart, so a file always comes before an address whatever the order of the rows. Artists' pictures (`ar3:`) are never here:
 * they stay with Deezer.
 *
 * Paths are kept as the database has them; whether one may be read is [LibraryArtFiles]'s to say, when it is asked.
 */
internal class LibraryArtIndex private constructor(
    private val local: Map<String, String>,
    private val remote: Map<String, String>,
) {
    /** [song]'s own-file cover (an absolute path), or null. */
    fun localFor(song: ArtSong): String? = if (song.isArtist) null else local[ArtText.artKey(song)]

    /** [song]'s remote cover from the library, or null. */
    fun remoteFor(song: ArtSong): String? = if (song.isArtist) null else remote[ArtText.artKey(song)]

    val localKeys: Int get() = local.size
    val remoteKeys: Int get() = remote.size

    companion object {
        val EMPTY = LibraryArtIndex(emptyMap(), emptyMap())

        /** Built from the database's rows: tracks first, then scanned songs (their file names resolved by [scannedPath]). */
        fun build(
            tracks: List<LibraryArtDao.TrackArt>,
            scanned: List<LibraryArtDao.ScannedArt>,
            scannedPath: (String) -> String?,
        ): LibraryArtIndex {
            val local = HashMap<String, String>()
            val remote = HashMap<String, String>()
            for (t in tracks) {
                val path = t.path?.takeIf(::isFilePath)
                val url = t.url?.takeIf(::isRemote)
                if (path == null && url == null) continue
                for (key in keys(t.title, t.artist, t.albumArtist, t.album)) {
                    if (path != null) local.putIfAbsent(key, path)
                    if (url != null) remote.putIfAbsent(key, url)
                }
            }
            for (s in scanned) {
                val path = scannedPath(s.name)?.takeIf(::isFilePath) ?: continue
                for (key in keys(s.title, s.artist, "", s.album)) local.putIfAbsent(key, path)
            }
            return LibraryArtIndex(local, remote)
        }

        /** The keys a song's cover is kept under (blank words make no key). */
        internal fun keys(title: String, artist: String, albumArtist: String, album: String): List<String> {
            val out = ArrayList<String>(3)
            if (album.isNotBlank()) {
                if (artist.isNotBlank()) out += ArtText.artKey(ArtSong(title, artist, album))
                if (albumArtist.isNotBlank()) out += ArtText.artKey(ArtSong(title, albumArtist, album))
            }
            if (title.isNotBlank() && artist.isNotBlank()) out += ArtText.artKey(ArtSong(title, artist))
            // Words that fold to nothing ("!!!") would key every such song alike: no key
            return out.filter { k -> k.substringAfter(':').split('|').none(String::isEmpty) }.distinct()
        }

        /** A plain absolute path: never a `content://` or `file://` address. */
        private fun isFilePath(p: String) = p.startsWith("/") && !p.contains('\u0000')

        private fun isRemote(u: String) = u.startsWith("https://") || u.startsWith("http://")
    }
}
