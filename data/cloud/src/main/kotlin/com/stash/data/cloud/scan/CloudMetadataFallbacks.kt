package com.stash.data.cloud.scan

import com.stash.data.cloud.tags.AudioTags

/** The names a cloud song is filed under once tags and the folder path have both been asked. */
data class ResolvedNames(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
)

/**
 * Fills what a file's tags leave out from where the file sits:
 * title from the file name, album from the parent folder, artist from the folder above it.
 *
 * Only folders *below* the folder the user picked count: for `Music/song.mp3` with `Music`
 * picked, "Music" is not an album. Disc folders (`CD1`, `Disc 2`) are skipped so
 * `Artist/Album/CD1/01 Song.flac` still files under Album by Artist.
 */
object CloudMetadataFallbacks {

    /** What the library shows for a song nobody named the artist of. */
    const val UNKNOWN_ARTIST = "Unknown Artist"

    private val DISC_FOLDER = Regex("""^(cd|disc|disk)\s*[-_.]?\s*\d{1,2}$""", RegexOption.IGNORE_CASE)

    /** "01 ", "01. ", "01 - ", "1-02 ", "A1 " style track-number prefixes. */
    private val TRACK_PREFIX = Regex("""^(\d{1,2}[-.])?\d{1,3}\s*([-._)]\s*)?(?=\S)""")

    /**
     * @param tags What the file's own tags said (possibly [AudioTags.EMPTY]).
     * @param fileName The file's name, extension included.
     * @param folders Folder names from just below the picked folder down to the file's parent.
     *   Empty when the file sits directly in the picked folder.
     */
    fun resolve(tags: AudioTags, fileName: String, folders: List<String>): ResolvedNames {
        val meaningful = folders.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (meaningful.isNotEmpty() && DISC_FOLDER.matches(meaningful.last())) meaningful.removeAt(meaningful.lastIndex)
        val folderAlbum = meaningful.lastOrNull()
        val folderArtist = meaningful.getOrNull(meaningful.size - 2)

        val base = fileName.substringBeforeLast('.', fileName).trim()
        val unnumbered = stripTrackNumber(base)
        // "Artist - Title" names: only trusted when nothing better names the artist.
        val dash = unnumbered.indexOf(" - ")
        val nameArtist = if (dash > 0) unnumbered.substring(0, dash).trim().takeIf { it.isNotEmpty() } else null
        val nameTitle = if (dash > 0) unnumbered.substring(dash + 3).trim().takeIf { it.isNotEmpty() } else null

        val artist = tags.artist ?: tags.albumArtist ?: folderArtist ?: nameArtist ?: UNKNOWN_ARTIST
        // "Radiohead - Creep.mp3" splits only when its left half is the artist we settled on.
        val title = tags.title
            ?: nameTitle?.takeIf { nameArtist != null && nameArtist.equals(artist, ignoreCase = true) }
            ?: unnumbered.ifEmpty { base.ifEmpty { fileName } }
        return ResolvedNames(
            title = title,
            artist = artist,
            album = tags.album ?: folderAlbum.orEmpty(),
            albumArtist = tags.albumArtist ?: (if (tags.album == null) folderArtist else null) ?: "",
        )
    }

    /**
     * "03 - Song" / "03. Song" / "1-02 Song" / "12 Song" → "Song". A lone digit followed only by a
     * space ("1 Thing") is kept, and so is a number glued to a word ("2Pac"). Known miss: an
     * untagged "99 Problems.mp3" loses its "99", the price of reading "12 Song" right.
     */
    internal fun stripTrackNumber(name: String): String {
        val m = TRACK_PREFIX.find(name) ?: return name
        val rest = name.substring(m.range.last + 1).trim()
        if (rest.isEmpty()) return name
        val punctuated = m.groupValues[2].isNotEmpty() || m.groupValues[1].isNotEmpty()
        val spaced = m.value.last().isWhitespace()
        val digits = m.value.trim().trimEnd('-', '.', '_', ')').trim()
        return when {
            punctuated -> rest
            spaced && digits.length >= 2 -> rest
            else -> name
        }
    }
}
