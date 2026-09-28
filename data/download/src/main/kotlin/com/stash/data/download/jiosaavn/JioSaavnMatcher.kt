package com.stash.data.download.jiosaavn

import com.stash.data.download.lossless.TrackQuery
import java.text.Normalizer
import kotlin.math.abs

data class JioSaavnMatch(
    val song: JioSaavnSong,
    val media: JioSaavnMediaLink,
    val confidence: Float,
)

/** Conservative matcher: an uncertain result must fall through to YouTube. */
object JioSaavnMatcher {
    fun best(query: TrackQuery, songs: List<JioSaavnSong>): JioSaavnMatch? {
        val ranked = songs.mapNotNull { score(query, it) }.sortedByDescending { it.rankScore }
        val top = ranked.firstOrNull() ?: return null
        // JioSaavn lists one recording on many compilations and the copies
        // score alike, so "Ae Mere Humsafar" on seven albums was rejected as
        // ambiguous and fell to YouTube (#484). A copy of the top pick is not
        // a rival, unless ANY result (gated out or not) has the top's title in
        // another language: a dubbed film song keeps its title, singer and
        // length in every language ("Fear Song" is Tamil and Telugu).
        val topSong = top.match.song
        val topTitle = recordingTitle(topSong.name)
        val oneLanguage = topSong.language != null && songs.none {
            it.language != topSong.language && recordingTitle(it.name) == topTitle
        }
        val runnerUp = ranked.drop(1).firstOrNull { !(oneLanguage && sameRecording(topSong, it.match.song)) }
        if (
            runnerUp != null &&
            top.rankScore - runnerUp.rankScore < MIN_MARGIN &&
            !albumDisambiguates(query, top.match.song, runnerUp.match.song)
        ) return null
        return top.match
    }

    private fun score(query: TrackQuery, song: JioSaavnSong): RankedMatch? {
        val media = song.downloadUrl.firstOrNull {
            it.quality.equals("320kbps", ignoreCase = true) && it.url.startsWith("https://")
        } ?: return null
        val candidateArtist = song.artists.primary.joinToString(" ") { it.name }
        if (candidateArtist.isBlank()) return null
        if (versionSignature(query.title) != versionSignature(song.name)) return null
        if ((artistImpersonationMarkers(candidateArtist) - artistImpersonationMarkers(query.artist)).isNotEmpty()) {
            return null
        }
        if (query.explicit != null && query.explicit != song.explicitContent) return null
        // "Devara Part 1 (Tamil)" must never get the Telugu dub of the song.
        val languages = requestedLanguages(query)
        if (languages.isNotEmpty() && song.language != null && song.language !in languages) return null

        // Compare CORE titles: parenthetical decorations — '(From "Bhediya")',
        // "(Official Video)" — tank token similarity for what is the same
        // recording (device-verified 2026-08-13: Apna Bana Le scored 0.6 and
        // fell to YouTube). Safe to strip here because version identity
        // ("live", "remix", …) is enforced by the versionSignature gate above,
        // which sees the FULL title.
        val title = similarity(coreTitle(query.title), coreTitle(song.name))
        val artist = artistSimilarity(normalize(query.artist), normalize(candidateArtist))
        if (title < MIN_TITLE || artist < MIN_ARTIST) return null
        val duration = durationSimilarity(query.durationMs, song.duration) ?: return null
        val albumBonus = (albumSimilarity(query, song) ?: 0f) * ALBUM_WEIGHT
        // Keep the uncapped score for ranking so a strong album match can
        // disambiguate otherwise-identical recordings. Cap only the public
        // confidence value exposed to downstream callers.
        val rankScore = title * TITLE_WEIGHT + artist * ARTIST_WEIGHT +
            duration * DURATION_WEIGHT + albumBonus
        val threshold = if (query.durationMs != null && song.duration != null) {
            MIN_WITH_DURATION
        } else {
            MIN_WITHOUT_DURATION
        }
        if (rankScore < threshold) return null
        return RankedMatch(
            match = JioSaavnMatch(song, media, rankScore.coerceAtMost(0.99f)),
            rankScore = rankScore,
        )
    }

    private fun durationSimilarity(targetMs: Long?, candidateSec: Int?): Float? {
        if (targetMs == null || targetMs <= 0 || candidateSec == null || candidateSec <= 0) return 1f
        val targetSec = targetMs / 1000.0
        val difference = abs(targetSec - candidateSec)
        val tolerance = maxOf(8.0, targetSec * 0.03)
        if (difference > tolerance) return null
        return (1.0 - difference / tolerance).toFloat().coerceIn(0f, 1f)
    }

    private fun albumDisambiguates(
        query: TrackQuery,
        top: JioSaavnSong,
        runnerUp: JioSaavnSong,
    ): Boolean {
        val topAlbum = albumSimilarity(query, top) ?: return false
        val runnerUpAlbum = albumSimilarity(query, runnerUp) ?: 0f
        return topAlbum >= MIN_EXACT_ALBUM && topAlbum - runnerUpAlbum >= MIN_ALBUM_ADVANTAGE
    }

    /**
     * One recording re-issued on another album: equal titles once a soundtrack
     * credit ('(From "Qayamat Se Qayamat Tak")') is dropped, known durations
     * within [SAME_RECORDING_MAX_DRIFT_SEC], the same explicit flag (a
     * search-tab request cannot tell clean from explicit) and the same singers.
     * Every other parenthetical, "(Sad)", "(Female Version)", "(feat. X)", stays
     * significant, and an unknown duration never counts as a copy. The singers
     * matter because the artist check lets a solo by one of a duet's singers
     * through: without them a same-titled solo counted as a copy of the duet.
     */
    private fun sameRecording(a: JioSaavnSong, b: JioSaavnSong): Boolean {
        val aSec = a.duration?.takeIf { it > 0 } ?: return false
        val bSec = b.duration?.takeIf { it > 0 } ?: return false
        return abs(aSec - bSec) <= SAME_RECORDING_MAX_DRIFT_SEC &&
            a.explicitContent == b.explicitContent &&
            primaryArtists(a) == primaryArtists(b) &&
            recordingTitle(a.name) == recordingTitle(b.name)
    }

    private fun primaryArtists(song: JioSaavnSong): Set<String> =
        song.artists.primary.mapTo(mutableSetOf()) { normalize(it.name) }

    /** Languages the request names as whole words, e.g. the album "KGF Chapter 2 - Telugu". */
    private fun requestedLanguages(query: TrackQuery): Set<String> =
        listOfNotNull(query.album, query.title)
            .flatMap { normalize(it).split(' ') }
            .filterTo(mutableSetOf()) { it in LANGUAGES }

    private fun recordingTitle(value: String): String =
        normalize(value.replace(SOUNDTRACK_CREDIT, " "), keepFeaturing = true)

    private fun albumSimilarity(query: TrackQuery, song: JioSaavnSong): Float? {
        val requestedAlbum = query.album?.takeIf { it.isNotBlank() } ?: return null
        val candidateAlbum = song.album?.name?.takeIf { it.isNotBlank() } ?: return null
        return similarity(normalize(requestedAlbum), normalize(candidateAlbum))
    }

    private fun versionSignature(value: String): Set<String> {
        val normalized = normalize(value)
        return VERSION_MARKERS.filterTo(linkedSetOf()) { marker ->
            Regex("(?:^|\\s)${Regex.escape(marker)}(?:$|\\s)").containsMatchIn(normalized)
        }
    }

    /** [normalize] with parenthesized/bracketed decorations removed first. */
    private fun coreTitle(value: String): String =
        normalize(value.replace(Regex("[(\\[][^)\\]]*[)\\]]"), " "))

    private fun normalize(value: String, keepFeaturing: Boolean = false): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .let { if (keepFeaturing) it else it.replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ") }
            .replace(Regex("[^\\p{L}\\p{N}\\p{S}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun similarity(a: String, b: String): Float {
        val left = a.split(' ').filter { it.isNotBlank() }.toSet()
        val right = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0f
        return left.intersect(right).size.toFloat() / left.union(right).size.toFloat()
    }

    private fun artistSimilarity(a: String, b: String): Float {
        val left = a.split(' ').filter { it.isNotBlank() }.toSet()
        val right = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0f
        val overlap = left.intersect(right)
        val jaccard = overlap.size.toFloat() / left.union(right).size.toFloat()
        // A source may omit featured artists from its primary credit, but a
        // candidate must not add arbitrary identity words (for example,
        // "Journey Tribute Band" for Journey). Only allow candidateâŠ†target.
        val subset = overlap.size == right.size && overlap.any {
            it.length > 3 || it.any { ch -> !ch.isLetterOrDigit() }
        }
        return if (subset) 1f else jaccard
    }

    private fun artistImpersonationMarkers(value: String): Set<String> {
        val normalized = normalize(value)
        return ARTIST_IMPERSONATION_MARKERS.filterTo(linkedSetOf()) { marker ->
            Regex("(?:^|\\s)${Regex.escape(marker)}(?:$|\\s)").containsMatchIn(normalized)
        }
    }

    private const val MIN_TITLE = 0.82f
    private const val MIN_ARTIST = 0.78f
    private const val TITLE_WEIGHT = 0.50f
    private const val ARTIST_WEIGHT = 0.35f
    private const val DURATION_WEIGHT = 0.15f
    private const val ALBUM_WEIGHT = 0.03f
    private const val MIN_WITH_DURATION = 0.88f
    private const val MIN_WITHOUT_DURATION = 0.93f
    private const val MIN_MARGIN = 0.06f
    private const val MIN_EXACT_ALBUM = 0.90f
    private const val MIN_ALBUM_ADVANTAGE = 0.35f
    // ponytail: JioSaavn's duration drifts a few seconds between copies of one
    // master. Live 2026-09-28: Ae Mere Humsafar at 352-355 s, its Baazigar
    // namesake at 450-454 s; distinct edits sat 10 s+ apart. 6 s apart must
    // stay ambiguous (JioSaavnMatcherTest); widen only on log evidence.
    private const val SAME_RECORDING_MAX_DRIFT_SEC = 4
    // f(?:ro|or)m: JioSaavn also lists 'Jaadu Teri Nazar (Form "Darr")'.
    private val SOUNDTRACK_CREDIT =
        Regex("""(?i)\(\s*f(?:ro|or)m\s+"[^"]*"\s*\)|\[\s*f(?:ro|or)m\s+"[^"]*"\s*\]""")
    private val LANGUAGES = setOf(
        "tamil", "telugu", "hindi", "kannada", "malayalam", "marathi", "bengali", "punjabi", "gujarati",
    )
    private data class RankedMatch(val match: JioSaavnMatch, val rankScore: Float)
    private val VERSION_MARKERS = listOf(
        "karaoke", "instrumental", "cover", "tribute", "live", "concert",
        "remix", "acoustic", "sped up", "slowed", "nightcore", "edit", "extended",
    )
    private val ARTIST_IMPERSONATION_MARKERS = listOf("karaoke", "cover", "tribute", "impersonator")
}
