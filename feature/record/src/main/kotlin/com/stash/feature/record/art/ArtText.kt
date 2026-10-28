package com.stash.feature.record.art

import com.stash.core.model.weblink.Credits
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.JsText
import java.text.Normalizer

/** A song (or album) by its words, or an artist alone ([isArtist]: their picture; [title] unused). */
data class ArtSong(val title: String, val artist: String, val album: String? = null, val isArtist: Boolean = false)

/**
 * The words rules of the web's cover lookup, ported regex for regex: the Worker's (stash-player `worker/art.ts`: how a song is
 * searched for and a hit is judged) and the client's (`src/lib/spotify/art.ts` `artKey`, `src/lib/spotify/song-key.ts`
 * `bareTitle`: what a cover is kept under). JavaScript's `\s` is spelled out ([JsText.SPACE]) and its `$` (no `m` flag: the
 * end of the text only) is `\z`, since Java's `$` also matches before a final line break. The song key's and the credit's cuts are
 * core:model's ([SongKey.bareTitle], [Credits]), shared with the taste and the mixes.
 */
internal object ArtText {
    private const val S = JsText.SPACE
    private val MARKS = Regex("\\p{M}")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACE_RUN = Regex("$S+")

    /** An album's marks that aren't its name: "- Single", "- EP", "(Deluxe Edition)", "[2011 Remaster]". */
    private val ALBUM_EXTRA = Regex(
        "$S*(?:-$S*(?:single|ep)$S*\\z|[(\\[][^)\\]]*(?:deluxe|edition|remaster|expanded|anniversary|bonus|version)[^)\\]]*[)\\]])",
        RegexOption.IGNORE_CASE,
    )

    /** worker art.ts `bareTitle`'s marks: " - 2011 Remaster", "(Remastered)", "(feat. B)". `.` never crosses a line break. */
    private val SEARCH_TITLE_EXTRA = Regex(
        "$S*(?:[-\\u2013]$S*[^\\n\\r\\u2028\\u2029]*remaster[^\\n\\r\\u2028\\u2029]*\\z|[(\\[][^)\\]]*(?:remaster|feat|ft\\.)[^)\\]]*[)\\]])",
        RegexOption.IGNORE_CASE,
    )

    /** worker art.ts `BAD_CHARS`: a control character or a bidi override/isolate. */
    private val BAD_CHARS = Regex("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]")

    /** worker art.ts `MAX_TEXT`. */
    const val MAX_TEXT = 300

    /** worker art.ts `fold`: accents, case and punctuation don't count (the identity fold without its featuring rule). */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(MARKS, "")
            .lowercase()
            .replace(NON_WORD, " ")
            .trim(' ')

    /**
     * worker art.ts `primary`: the first credited artist ([Credits.firstCredit], the cut station.ts `primaryArtist` makes too),
     * or the credit as it was when the cut leaves nothing (`primaryArtist` gives it trimmed: [Credits.primary]).
     */
    fun primary(a: String): String = Credits.firstCredit(a).ifEmpty { a }

    /** worker art.ts / client art.ts `bareAlbum`. */
    fun bareAlbum(a: String): String = JsText.trim(ALBUM_EXTRA.replace(a, "")).ifEmpty { a }

    /** worker art.ts `bareTitle`: what a song is searched for by. */
    fun searchTitle(t: String): String = JsText.trim(SEARCH_TITLE_EXTRA.replace(t, "")).ifEmpty { t }

    /** song-key.ts `bareTitle` ([SongKey.bareTitle]): what a song's cover is kept under. */
    fun keyTitle(t: String): String = SongKey.bareTitle(t)

    /**
     * client art.ts `artKey`: one cover per album (the primary artist and the album, folded by the identity fold); a song with
     * no album, its own words; an artist, their name ("ar2:": the pictures kept from Deezer's old artist search, often another
     * artist's, are asked again).
     */
    fun artKey(s: ArtSong): String {
        val a = SongKey.fold(primary(s.artist))
        return when {
            s.isArtist -> "ar2:$a"
            !s.album.isNullOrEmpty() -> "al:$a|${SongKey.fold(bareAlbum(s.album))}"
            else -> "tr:$a|${SongKey.fold(keyTitle(s.title))}"
        }
    }

    /** worker art.ts `clean`: whitespace runs one space, trimmed; null when empty, over [MAX_TEXT], or with a bad character. */
    fun clean(v: String?): String? {
        if (v == null) return null
        val s = JsText.trim(SPACE_RUN.replace(v, " "))
        return s.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT && !BAD_CHARS.containsMatchIn(it) }
    }

    /** `encodeURIComponent`: UTF-8, every byte but A–Z a–z 0–9 `-_.!~*'()` escaped. */
    fun encodeUriComponent(s: String): String {
        val out = StringBuilder(s.length * 3)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
