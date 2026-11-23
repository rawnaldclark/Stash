package com.stash.data.download.files

import com.stash.core.model.Track
import java.text.Normalizer

/**
 * What a music catalogue says of a recording beyond what Stash knows of
 * the track: the tags a saved file would otherwise lack, so it sorts
 * properly in any music library (by album, year, track and genre).
 */
data class ExtraTags(
    val album: String = "",
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genre: String = "",
) {
    val isEmpty: Boolean
        get() = album.isBlank() && year == null && trackNumber == null && discNumber == null && genre.isBlank()

    /**
     * Only what the file does not already say. The album when neither
     * the file nor the track names one; the genre when the file has
     * none; the year, track and disc only when the album is the
     * catalogue's too, as those say where the song sits on that album
     * and nothing about another.
     */
    fun onlyBlanks(existing: ExistingTags, track: Track): ExtraTags {
        val albumNow = existing.album.ifBlank { track.album }
        val noAlbum = albumNow.isBlank()
        val sameAlbum = album.isNotBlank() &&
            (noAlbum || CatalogueMatch.fold(albumNow) == CatalogueMatch.fold(album))
        val takeTrack = sameAlbum && !existing.hasTrackNumber
        return ExtraTags(
            album = if (noAlbum) album else "",
            year = year.takeIf { sameAlbum && !existing.hasYear },
            trackNumber = trackNumber.takeIf { takeTrack },
            // A disc number is only worth writing with its track number, and only past disc one.
            discNumber = discNumber.takeIf { takeTrack && trackNumber != null && it != null && it > 1 },
            genre = if (existing.hasGenre) "" else genre,
        )
    }
}

/** What a file's own tags already say, as far as [ExtraTags] is concerned. */
data class ExistingTags(
    val album: String = "",
    val hasGenre: Boolean = false,
    val hasYear: Boolean = false,
    val hasTrackNumber: Boolean = false,
) {
    /** Nothing a catalogue could add. */
    fun isComplete(track: Track): Boolean =
        hasGenre && hasYear && hasTrackNumber && (album.isNotBlank() || track.album.isNotBlank())
}

/** Where [ExtraTags] come from. Never throws: no answer is `null`. */
interface ExtraTagSource {
    suspend fun lookUp(track: Track): ExtraTags?

    /** No catalogue: files are tagged with what Stash knows. */
    object None : ExtraTagSource {
        override suspend fun lookUp(track: Track): ExtraTags? = null
    }
}

/** One recording in a catalogue's answer to a search. */
data class CatalogueHit(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    /** ISO date, "2007-10-10T07:00:00Z"; sorts as text. */
    val releaseDate: String,
    val trackNumber: Int,
    val discNumber: Int,
    val genre: String,
    val durationMs: Long,
)

/** Picks the catalogue recording that is a track. Pure, so it is tested without a network. */
object CatalogueMatch {
    /** How far a catalogue recording's length may be from the track's and still be the same recording. */
    private const val LENGTH_SLACK_MS = 4_000L

    private val FEAT_BRACKET = Regex("""[(\[]\s*(feat|ft|featuring)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("""\s+(feat|ft|featuring)\.?\s.*$""", RegexOption.IGNORE_CASE)
    private val MARKS = Regex("""\p{Mn}+""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** A release label, not another version: "2011 Remaster", "Mono", "Deluxe Edition", "Album Version". */
    private val LABEL = Regex(
        """remaster|deluxe|\bmono\b|\bstereo\b|bonus track|album version|single version|explicit|\bclean\b|anniversary|expanded""",
        RegexOption.IGNORE_CASE,
    )
    private val LAST_BRACKET = Regex("""\s*[(\[]([^()\[\]]*)[)\]]\s*$""")
    private val LAST_DASH = Regex("""\s+-\s+([^-]*)$""")
    private val CREDIT_SPLIT = Regex("""\s*(?:,|&|;|/|\bx\b|\band\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)

    /** Text as it is compared: accents and case gone, a "feat." credit gone, anything not a letter or digit a space. */
    fun fold(text: String): String {
        val plain = Normalizer.normalize(text.replace(FEAT_BRACKET, " "), Normalizer.Form.NFD).replace(MARKS, "")
        return plain.lowercase().replace(NOT_WORD, " ").trim()
    }

    /**
     * A title as it is compared: folded, without a trailing release
     * label ("Song - 2011 Remaster", "Song (Mono)"). A bracket or dash
     * part that says anything else stays: "Song (Live)" is another
     * recording.
     */
    fun titleKey(title: String): String {
        var rest = title.trim().replace(FEAT_TAIL, "")
        while (true) {
            val match = LAST_BRACKET.find(rest) ?: LAST_DASH.find(rest) ?: break
            if (!LABEL.containsMatchIn(match.groupValues[1])) break
            rest = rest.removeRange(match.range).trim()
        }
        return fold(rest)
    }

    /** The artists a credit names, folded: "A, B & C" is a, b, c. */
    fun artists(credit: String): List<String> =
        credit.replace(FEAT_BRACKET, " ").split(CREDIT_SPLIT).map(::fold).filter { it.isNotEmpty() }

    private fun sameArtist(wanted: String, credit: String): Boolean {
        val (w, c) = fold(wanted) to fold(credit)
        if (w.isEmpty() || c.isEmpty()) return false
        if (w == c) return true
        val named = artists(credit).toSet()
        return artists(wanted).any { it in named }
    }

    /**
     * The hit that is this track: the same title and artist, and the
     * same length when the track's is known. Of several, the closest in
     * length (to the second), then the artist's own record before a
     * various-artists collection, then the earliest released (the album
     * a song first came out on, not a later collection of hits).
     */
    fun pick(track: Track, hits: List<CatalogueHit>): ExtraTags? {
        val wantedTitle = titleKey(track.title)
        if (wantedTitle.isEmpty()) return null
        val best = hits
            .filter { titleKey(it.title) == wantedTitle && sameArtist(track.artist, it.artist) }
            .mapNotNull { hit ->
                val off = if (track.durationMs <= 0 || hit.durationMs <= 0) LENGTH_SLACK_MS
                else kotlin.math.abs(track.durationMs - hit.durationMs)
                if (off > LENGTH_SLACK_MS) null else Triple(off / 1_000, fold(hit.albumArtist) == "various artists", hit)
            }
            .minWithOrNull(compareBy<Triple<Long, Boolean, CatalogueHit>>({ it.first }, { it.second }, { it.third.releaseDate }))
            ?.third ?: return null
        return ExtraTags(
            album = albumName(best.album),
            year = best.releaseDate.take(4).toIntOrNull()?.takeIf { it in 1000..9999 },
            trackNumber = best.trackNumber.takeIf { it in 1..9_999 },
            discNumber = best.discNumber.takeIf { it in 1..999 },
            genre = best.genre.trim(),
        ).takeUnless { it.isEmpty }
    }

    /** An album's name without the catalogue's own label for a single or an EP. */
    fun albumName(name: String): String {
        val trimmed = name.trim()
        for (label in listOf(" - Single", " - EP")) {
            if (trimmed.endsWith(label)) return trimmed.removeSuffix(label).trim()
        }
        return trimmed
    }

    /** What is searched for: the first artist and the title's own words. */
    fun query(track: Track): String =
        listOf(artists(track.artist).firstOrNull() ?: fold(track.artist), titleKey(track.title))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
