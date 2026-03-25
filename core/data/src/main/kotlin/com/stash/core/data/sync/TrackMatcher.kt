package com.stash.core.data.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Utility for matching tracks across local and remote data sources.
 *
 * Provides both exact (canonical string comparison) and fuzzy
 * (Jaro-Winkler similarity + duration tolerance) matching strategies.
 */
@Singleton
class TrackMatcher @Inject constructor() {

    companion object {
        /** Jaro-Winkler threshold for title similarity. */
        private const val TITLE_SIMILARITY_THRESHOLD = 0.92

        /** Jaro-Winkler threshold for artist similarity. */
        private const val ARTIST_SIMILARITY_THRESHOLD = 0.90

        /** Maximum duration difference (in milliseconds) for a fuzzy match. */
        private const val DURATION_TOLERANCE_MS = 10_000L

        /** Regex to strip parenthetical content: (remix), (feat. X), [live], etc. */
        private val PARENTHETICAL_REGEX = Regex("""\s*[\(\[][^)\]]*[\)\]]""")

        /** Regex to strip "feat", "ft", "featuring" and everything after. */
        private val FEAT_REGEX = Regex("""\s*(feat\.?|ft\.?|featuring)\s+.*""", RegexOption.IGNORE_CASE)

        /** Common artist separators. */
        private val ARTIST_SEPARATOR_REGEX = Regex("""\s*[,;&/]\s*|\s+(?:and|&)\s+""", RegexOption.IGNORE_CASE)
    }

    /**
     * Produce a canonical form of a track title for comparison.
     *
     * Strips parenthetical content (remix tags, featured artists in parens),
     * removes "feat/ft" suffixes, lowercases, and trims whitespace.
     *
     * @param title Raw track title.
     * @return Normalised title string.
     */
    fun canonicalTitle(title: String): String {
        return title
            .replace(PARENTHETICAL_REGEX, "")
            .replace(FEAT_REGEX, "")
            .lowercase()
            .trim()
    }

    /**
     * Produce a canonical form of an artist name for comparison.
     *
     * Splits on common separators (, ; & / "and"), lowercases each part,
     * sorts alphabetically, and rejoins with ", ".
     *
     * @param artist Raw artist string (may contain multiple artists).
     * @return Normalised, sorted artist string.
     */
    fun canonicalArtist(artist: String): String {
        return artist
            .split(ARTIST_SEPARATOR_REGEX)
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(", ")
    }

    /**
     * Check whether two tracks are an exact match based on canonical
     * title and artist comparison.
     *
     * @param title1  First track title.
     * @param artist1 First track artist.
     * @param title2  Second track title.
     * @param artist2 Second track artist.
     * @return True if both canonical title and artist are identical.
     */
    fun isExactMatch(
        title1: String,
        artist1: String,
        title2: String,
        artist2: String,
    ): Boolean {
        return canonicalTitle(title1) == canonicalTitle(title2) &&
            canonicalArtist(artist1) == canonicalArtist(artist2)
    }

    /**
     * Compute Jaro-Winkler similarity between two strings.
     *
     * The Jaro-Winkler metric gives a value between 0.0 (no similarity)
     * and 1.0 (exact match), with a prefix bonus that boosts scores for
     * strings sharing a common prefix.
     *
     * @param s1 First string.
     * @param s2 Second string.
     * @return Similarity score in [0.0, 1.0].
     */
    fun jaroWinklerSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val jaroScore = jaroSimilarity(s1, s2)

        // Winkler prefix bonus: up to 4 characters, scaling factor 0.1
        val prefixLength = s1.zip(s2)
            .takeWhile { (a, b) -> a == b }
            .count()
            .coerceAtMost(4)

        return jaroScore + prefixLength * 0.1 * (1.0 - jaroScore)
    }

    /**
     * Check whether two tracks are a fuzzy match based on Jaro-Winkler
     * similarity of canonical titles/artists and duration tolerance.
     *
     * @param title1    First track title.
     * @param artist1   First track artist.
     * @param durationMs1 First track duration in milliseconds.
     * @param title2    Second track title.
     * @param artist2   Second track artist.
     * @param durationMs2 Second track duration in milliseconds.
     * @return True if the tracks are a fuzzy match.
     */
    fun isFuzzyMatch(
        title1: String,
        artist1: String,
        durationMs1: Long,
        title2: String,
        artist2: String,
        durationMs2: Long,
    ): Boolean {
        val ct1 = canonicalTitle(title1)
        val ct2 = canonicalTitle(title2)
        val ca1 = canonicalArtist(artist1)
        val ca2 = canonicalArtist(artist2)

        val titleSim = jaroWinklerSimilarity(ct1, ct2)
        val artistSim = jaroWinklerSimilarity(ca1, ca2)
        val durationClose = abs(durationMs1 - durationMs2) <= DURATION_TOLERANCE_MS

        return titleSim >= TITLE_SIMILARITY_THRESHOLD &&
            artistSim >= ARTIST_SIMILARITY_THRESHOLD &&
            durationClose
    }

    // ── Private helpers ─────────────────────────────────────────────────

    /**
     * Compute the base Jaro similarity between two strings.
     *
     * @return Jaro similarity in [0.0, 1.0].
     */
    private fun jaroSimilarity(s1: String, s2: String): Double {
        val maxDist = max(s1.length, s2.length) / 2 - 1
        if (maxDist < 0) return if (s1 == s2) 1.0 else 0.0

        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        var matches = 0
        var transpositions = 0

        // Find matching characters within the allowed window.
        for (i in s1.indices) {
            val start = max(0, i - maxDist)
            val end = min(i + maxDist + 1, s2.length)
            for (j in start until end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        // Count transpositions (matched chars in different order).
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        val jaro = (
            matches.toDouble() / s1.length +
                matches.toDouble() / s2.length +
                (matches - transpositions / 2.0) / matches
            ) / 3.0

        return jaro
    }
}
