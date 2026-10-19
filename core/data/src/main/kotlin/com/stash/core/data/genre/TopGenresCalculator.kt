package com.stash.core.data.genre

/**
 * One artist's share of a listening range, as the caller measures it (minutes, milliseconds or
 * plays: any unit, as long as one call uses one unit).
 *
 * @property name the artist credit as listened ("Drake", "Drake, 21 Savage", "Drake feat. Rihanna");
 *   matched to cached genres through [ArtistGenreKey].
 * @property weight how much of the range it took. Zero or negative counts for nothing.
 */
data class ArtistWeight(val name: String, val weight: Long)

/**
 * One genre's place in the range.
 *
 * @property key the canonical genre ("hip-hop"), stable for lookups and tests.
 * @property name the display name ("Hip-Hop").
 * @property share 0..1 of the listening that HAS a genre (so the shown bars add up to at most 100%).
 * @property weight the genre's absolute weight in the caller's unit (fractional: an artist's weight
 *   is split across its genres).
 */
data class GenreShare(val key: String, val name: String, val share: Double, val weight: Double)

/**
 * Top genres of a range.
 *
 * @property items strongest first, at most the requested limit.
 * @property coverage 0..1 of the range's total weight whose artist has a known genre. The UI hides
 *   genres when this is below [MIN_COVERAGE_TO_SHOW], since a "top genre" drawn from a sliver of
 *   the listening would be a guess presented as a fact.
 */
data class TopGenres(val items: List<GenreShare>, val coverage: Double) {
    /** True when enough of the listening has a genre for the result to be shown. */
    val isShowable: Boolean get() = items.isNotEmpty() && coverage >= MIN_COVERAGE_TO_SHOW

    companion object {
        /** Below this coverage the genres card stays hidden (DESIGN.md: < 50% -> hide). */
        const val MIN_COVERAGE_TO_SHOW = 0.5

        val EMPTY = TopGenres(items = emptyList(), coverage = 0.0)
    }
}

/**
 * Cached genres keyed for lookup by any artist credit: keys are [ArtistGenreKey]s in lower case,
 * matching the `artist_genres` primary key's COLLATE NOCASE. Built once per cache change (see
 * [ArtistGenreRepository.genreIndex]) rather than on every top-genres computation.
 */
class ArtistGenreIndex private constructor(private val byKey: Map<String, List<WeightedGenre>>) {

    /** Artists with genres. */
    val size: Int get() = byKey.size

    /** The genres of the act [credit] is keyed under ("Drake feat. Rihanna" -> Drake's), or null. */
    fun genresFor(credit: String): List<WeightedGenre>? = byKey[ArtistGenreKey.of(credit).lowercase()]

    override fun equals(other: Any?): Boolean = other is ArtistGenreIndex && other.byKey == byKey
    override fun hashCode(): Int = byKey.hashCode()
    override fun toString(): String = "ArtistGenreIndex(${byKey.size} artists)"

    companion object {
        val EMPTY = ArtistGenreIndex(emptyMap())

        /** From genres under any artist names: each name is re-keyed through [ArtistGenreKey]. */
        fun of(genres: Map<String, List<WeightedGenre>>): ArtistGenreIndex =
            ArtistGenreIndex(genres.entries.associate { (name, list) -> ArtistGenreKey.of(name).lowercase() to list })

        /**
         * From `artist_genres` keys, which already are [ArtistGenreKey]s: lower-cased only, no
         * credit parsing.
         */
        internal fun fromStoredKeys(genres: Map<String, List<WeightedGenre>>): ArtistGenreIndex =
            ArtistGenreIndex(genres.entries.associate { (key, list) -> key.lowercase() to list })
    }
}

/**
 * Top genres from top artists: pure arithmetic, no I/O.
 *
 * Each artist's weight is split across its genres in proportion to their genre weights (an artist
 * that is "hip-hop 1.0, r&b 0.5" gives two thirds of its minutes to Hip-Hop and one third to R&B),
 * then summed per genre. Shares are relative to the weight that has a genre; [TopGenres.coverage]
 * says how much that is of the whole.
 *
 * O(A·G + K log K) for A artists, G ≤ 3 genres each and K distinct genres.
 */
object TopGenresCalculator {

    /** Genres shown by default (DESIGN.md: horizontal bars, top 5). */
    const val DEFAULT_LIMIT = 5

    /**
     * @param artists the range's artists with their weights; repeated or differently-credited
     *   entries for one act simply add up.
     * @param genres cached genres by artist name; re-keyed through [ArtistGenreKey] and matched
     *   ignoring case, as the `artist_genres` primary key is COLLATE NOCASE. Screens use the
     *   [ArtistGenreIndex] overload with the repository's shared index instead.
     * @param limit how many genres to return; 0 or less returns none (coverage is still computed).
     */
    fun compute(
        artists: List<ArtistWeight>,
        genres: Map<String, List<WeightedGenre>>,
        limit: Int = DEFAULT_LIMIT,
    ): TopGenres = compute(artists, ArtistGenreIndex.of(genres), limit)

    /** [compute] over an index built once ([ArtistGenreRepository.genreIndex]). */
    fun compute(
        artists: List<ArtistWeight>,
        index: ArtistGenreIndex,
        limit: Int = DEFAULT_LIMIT,
    ): TopGenres {
        var total = 0.0
        var covered = 0.0
        val perGenre = HashMap<String, Double>()
        for (artist in artists) {
            if (artist.weight <= 0L) continue
            val weight = artist.weight.toDouble()
            total += weight
            val artistGenres = index.genresFor(artist.name)
                ?.filter { it.weight.isFinite() && it.weight > 0f && it.genre.isNotBlank() }
                .orEmpty()
            val genreWeightSum = artistGenres.sumOf { it.weight.toDouble() }
            if (genreWeightSum <= 0.0) continue
            covered += weight
            for (genre in artistGenres) {
                perGenre.merge(genre.genre, weight * genre.weight / genreWeightSum, Double::plus)
            }
        }
        if (total <= 0.0) return TopGenres.EMPTY

        val items = if (limit <= 0 || covered <= 0.0) {
            emptyList()
        } else {
            perGenre.entries
                // Strongest first; equal weights by name, so a tie never reorders between emissions.
                .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .take(limit)
                .map { (key, weight) ->
                    GenreShare(
                        key = key,
                        name = GenreNormalizer.displayName(key),
                        share = (weight / covered).coerceIn(0.0, 1.0),
                        weight = weight,
                    )
                }
        }
        return TopGenres(items = items, coverage = (covered / total).coerceIn(0.0, 1.0))
    }
}
