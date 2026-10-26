package com.stash.feature.record.art

import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.JsText
import java.text.Normalizer

/** A song (or album) by its words, or an artist alone ([isArtist]: their picture; [title] unused). */
data class ArtSong(val title: String, val artist: String, val album: String? = null, val isArtist: Boolean = false)

/**
 * The words rules of the web's cover lookup, ported regex for regex: the Worker's (stash-player `worker/art.ts`: how a song is
 * searched for and a hit is judged) and the client's (`src/lib/spotify/art.ts` `artKey`, `src/lib/spotify/song-key.ts`
 * `bareTitle`: what a cover is kept under). JavaScript's `\s` is spelled out ([JsText.SPACE]) and its `$` (no `m` flag: the
 * end of the text only) is `\z`, since Java's `$` also matches before a final line break.
 */
internal object ArtText {
    private const val S = JsText.SPACE
    private val MARKS = Regex("\\p{M}")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACE_RUN = Regex("$S+")

    /** The first credited artist's cut: "A, B feat. C" -> "A"; "A & B" stays one name. */
    private val CREDIT_SPLIT = Regex("$S*(?:[,;]|$S(?:feat\\.?|ft\\.?|featuring|with)$S)$S*", RegexOption.IGNORE_CASE)

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

    /** song-key.ts `REMASTER`: a trailing remaster mark only, the song's own words kept. */
    private val REMASTER = Regex(
        "$S*(?:[-\\u2013]$S*(?:\\d{4}$S+)?(?:digital(?:ly)?$S+)?remaster(?:ed)?(?:$S+\\d{4})?(?:$S+version)?|" +
            "[(\\[]$S*(?:\\d{4}$S+)?(?:digital(?:ly)?$S+)?remaster(?:ed)?(?:$S+\\d{4})?(?:$S+version)?$S*[)\\]])$S*\\z",
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

    /** worker art.ts `primary` (radio station.ts `primaryArtist` is the same cut): the first credited artist. */
    fun primary(a: String): String = JsText.trim(a.split(CREDIT_SPLIT, limit = 2)[0]).ifEmpty { a }

    /** worker art.ts / client art.ts `bareAlbum`. */
    fun bareAlbum(a: String): String = JsText.trim(ALBUM_EXTRA.replace(a, "")).ifEmpty { a }

    /** worker art.ts `bareTitle`: what a song is searched for by. */
    fun searchTitle(t: String): String = JsText.trim(SEARCH_TITLE_EXTRA.replace(t, "")).ifEmpty { t }

    /** song-key.ts `bareTitle`: what a song's cover is kept under. */
    fun keyTitle(t: String): String = JsText.trim(REMASTER.replaceFirst(t, "")).ifEmpty { t }

    /**
     * client art.ts `artKey`: one cover per album (the primary artist and the album, folded by the identity fold); a song with
     * no album, its own words; an artist, their name.
     */
    fun artKey(s: ArtSong): String {
        val a = SongKey.fold(primary(s.artist))
        return when {
            s.isArtist -> "ar:$a"
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
