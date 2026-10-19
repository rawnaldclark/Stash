package com.stash.core.data.genre

import com.stash.core.data.lastfm.LastFmTag
import com.stash.core.data.mix.GenreCatalog
import java.util.Locale

/**
 * Turns Last.fm's free-form tags into real genres.
 *
 * Last.fm tags are whatever listeners typed: next to "hip-hop" sit "seen live", "female vocalists",
 * "british", "00s", "favorites" and a dozen spellings of the same genre. Top genres need only the
 * genres, each spelled one way. So a tag counts only when, after cleaning and synonyms, it is on
 * the whitelist [genres] — anything else (moods, decades, places, opinions) falls out without
 * needing a list of its own.
 *
 * The whitelist is the Mix Builder's [GenreCatalog] (the genres the app already offers) plus the
 * other genres and scenes common in Last.fm's top tags. It is deliberately a list of genres people
 * recognise, not every micro-genre: a top-genres card reading "Hip-Hop, R&B, Indie Pop" is the goal.
 *
 * Pure and stateless: safe from any thread, unit-tested in GenreNormalizerTest.
 */
object GenreNormalizer {

    /** Genres kept per artist. */
    const val MAX_GENRES_PER_ARTIST = 3

    /**
     * A genre tag weaker than this (Last.fm count / the artist's top tag count) is noise: a few
     * listeners' opinion, not the artist's genre.
     */
    const val MIN_TAG_WEIGHT = 0.05f

    /** A genre weaker than this share of the artist's strongest genre is dropped as a stray. */
    const val MIN_RELATIVE_WEIGHT = 0.25f

    /** Catalog entries that are instruments or descriptors, not genres to rank. */
    private val NOT_GENRES_IN_CATALOG = setOf("piano")

    /**
     * Catalog entries folded into another genre (see [SYNONYMS]) because, as separate bars, they
     * read as the same thing twice: "Hip-Hop 41% · Rap 27%".
     */
    private val FOLDED_GENRES = setOf("rap")

    /** Common Last.fm genre tags the Mix Builder's catalog doesn't carry. Canonical spellings. */
    private val EXTRA_GENRES = setOf(
        // Electronic
        "electronic", "edm", "dance", "electropop", "electro", "electro house", "dubstep",
        "deep house", "tech house", "progressive house", "future bass", "uk garage", "breakbeat",
        "jungle", "hardstyle", "trip-hop", "downtempo", "chillout", "chillwave", "vaporwave",
        "eurodance", "disco", "nu disco", "big beat", "glitch", "hyperpop", "phonk", "industrial",
        // Hip-hop & R&B
        "drill", "uk drill", "grime", "cloud rap", "emo rap", "gangsta rap", "conscious hip-hop",
        "underground hip-hop", "alternative hip-hop", "jazz rap", "pop rap", "southern hip-hop",
        "crunk", "alternative r&b", "soul", "gospel", "motown",
        // Rock
        "alternative", "alternative rock", "indie", "hard rock", "progressive rock",
        "psychedelic rock", "psychedelic", "grunge", "post-punk", "new wave", "britpop", "emo",
        "pop punk", "punk rock", "hardcore", "post-hardcore", "math rock", "stoner rock",
        "southern rock", "blues rock", "folk rock", "art rock", "soft rock", "glam rock",
        "rock and roll", "rockabilly", "krautrock", "noise rock", "pop rock", "j-rock", "slowcore",
        "midwest emo", "screamo",
        // Metal
        "metalcore", "deathcore", "nu metal", "thrash metal", "power metal", "progressive metal",
        "symphonic metal", "alternative metal", "industrial metal", "post-metal", "djent",
        // Pop
        "dance pop", "teen pop", "bedroom pop", "chamber pop", "baroque pop", "power pop", "j-pop",
        "c-pop", "latin pop", "city pop",
        // Jazz, soul, blues
        "smooth jazz", "jazz fusion", "bebop", "swing", "big band", "vocal jazz", "acid jazz",
        "nu jazz",
        // Folk & country
        "indie folk", "alt-country", "country pop", "country rock",
        // Classical & screen
        "opera", "baroque", "neoclassical", "soundtrack", "video game music",
        // Reggae, Latin, world
        "roots reggae", "ska punk", "bachata", "samba", "mpb", "tango", "latin trap", "afrobeats",
        "amapiano", "highlife", "bollywood", "fado", "new age", "christian",
    )

    /** Every genre a tag can become (canonical, lower case). */
    val genres: Set<String> =
        (GenreCatalog.allGenres() - NOT_GENRES_IN_CATALOG - FOLDED_GENRES) + EXTRA_GENRES

    /**
     * Spellings that the generic rules in [canonical] can't reach (different words, not just
     * different spacing or hyphens). Keys are already cleaned (see [clean]).
     */
    internal val SYNONYMS: Map<String, String> = mapOf(
        "rap" to "hip-hop",
        "rap/hip-hop" to "hip-hop",
        "hip-hop/rap" to "hip-hop",
        "hip-hop and rap" to "hip-hop",
        "rnb" to "r&b",
        "r'n'b" to "r&b",
        "r n b" to "r&b",
        "r and b" to "r&b",
        "randb" to "r&b",
        "rhythm and blues" to "r&b",
        "contemporary r&b" to "r&b",
        "electronica" to "electronic",
        "electronic dance music" to "edm",
        "lo-fi hip-hop" to "lo-fi",
        "lofi hip-hop" to "lo-fi",
        "chillhop" to "lo-fi",
        "lo-fi beats" to "lo-fi",
        "drum n bass" to "drum and bass",
        "drum'n'bass" to "drum and bass",
        "drum&bass" to "drum and bass",
        "dnb" to "drum and bass",
        "d&b" to "drum and bass",
        "korean pop" to "k-pop",
        "mandopop" to "c-pop",
        "alt rock" to "alternative rock",
        "alt-rock" to "alternative rock",
        "alternative country" to "alt-country",
        "rock n roll" to "rock and roll",
        "rock 'n' roll" to "rock and roll",
        "rock'n'roll" to "rock and roll",
        "reggaetón" to "reggaeton",
        "soundtracks" to "soundtrack",
        "ost" to "soundtrack",
        "original soundtrack" to "soundtrack",
        "film score" to "soundtrack",
        "film soundtrack" to "soundtrack",
        "score" to "soundtrack",
        "neo-classical" to "neoclassical",
        "trap music" to "trap",
    )

    /**
     * Every genre with its spaces and hyphens squeezed out ("synthpop" -> "synth pop",
     * "hiphop" -> "hip-hop", "kpop" -> "k-pop"). Built once; a collision between two genres would
     * make one unreachable, which GenreNormalizerTest guards against.
     */
    private val SQUASHED: Map<String, String> = genres.associateBy { squash(it) }

    private val WHITESPACE = Regex("\\s+")

    /** "hip hop" / "hip-hop" / "hiphop" anywhere in a tag ("underground hip hop"). */
    private val HIP_HOP = Regex("\\bhip[\\s-]?hop\\b")

    /**
     * Extra words that make a genre a variety of an umbrella, beyond the umbrella's own words
     * (see [isCoveredBy]): with "rap" folded into hip-hop, "Cloud Rap" and "Emo Rap" are kinds of
     * Hip-Hop.
     */
    private val UMBRELLA_ALIASES: Map<String, List<List<String>>> = mapOf(
        "hip-hop" to listOf(listOf("rap")),
    )

    /** Words kept upper case in display names. */
    private val ACRONYMS = mapOf("edm" to "EDM", "idm" to "IDM", "uk" to "UK", "mpb" to "MPB", "r&b" to "R&B")

    /** Joining words kept lower case in display names (never as the first word). */
    private val SMALL_WORDS = setOf("and", "n", "of")

    /**
     * The canonical genre a Last.fm [rawTag] stands for, or null when the tag isn't a genre
     * ("seen live", "female vocalists", "british", "80s").
     *
     * Tried in order: a known synonym; the tag itself; the tag without a trailing " music"
     * ("classical music"); the tag with hyphens as spaces ("indie-rock") or spaces as hyphens
     * ("neo soul", "post rock", "lo fi"); and the tag with both squeezed out ("synthpop").
     */
    fun canonical(rawTag: String): String? {
        val cleaned = clean(rawTag)
        if (cleaned.isEmpty()) return null
        (SYNONYMS[cleaned] ?: lookup(cleaned))?.let { return it }
        val withoutMusic = cleaned.removeSuffix(" music")
        if (withoutMusic != cleaned && withoutMusic.isNotEmpty()) {
            return SYNONYMS[withoutMusic] ?: lookup(withoutMusic)
        }
        return null
    }

    /**
     * An artist's genres from its Last.fm top tags: non-genres dropped, spellings merged (the
     * strongest spelling wins, since "hip-hop" and "hip hop" are the same listeners twice), weak
     * strays dropped ([MIN_TAG_WEIGHT], [MIN_RELATIVE_WEIGHT]), umbrellas dropped where a more
     * specific genre of theirs is also kept ("rock" next to "indie rock", "indie" next to "indie
     * pop", "hip-hop" next to "cloud rap"), strongest first, at most [MAX_GENRES_PER_ARTIST],
     * re-weighted so the strongest genre is 1.
     *
     * Dropping the umbrella keeps each artist's bars distinct: "Indie 30% · Indie Rock 28% ·
     * Rock 15%" says the same thing three times. The specific genre is the more informative one,
     * and the artist's whole weight still lands in the family through it.
     *
     * Ties are broken by name so the same tags always give the same genres. Empty when Last.fm
     * has no genre for the artist.
     */
    fun topGenres(tags: List<LastFmTag>): List<WeightedGenre> {
        val strongestBySpelling = LinkedHashMap<String, Float>()
        for (tag in tags) {
            if (!tag.weight.isFinite() || tag.weight <= 0f) continue
            val genre = canonical(tag.name) ?: continue
            val current = strongestBySpelling[genre]
            if (current == null || tag.weight > current) strongestBySpelling[genre] = tag.weight
        }
        val strongest = strongestBySpelling.values.maxOrNull() ?: return emptyList()
        if (strongest < MIN_TAG_WEIGHT) return emptyList()
        val kept = strongestBySpelling.entries
            .filter { it.value >= MIN_TAG_WEIGHT && it.value >= strongest * MIN_RELATIVE_WEIGHT }
        val specific = kept.filter { candidate ->
            kept.none { other -> other.key != candidate.key && isCoveredBy(candidate.key, other.key) }
        }
        val top = specific
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
            .take(MAX_GENRES_PER_ARTIST)
        val best = top.firstOrNull()?.value ?: return emptyList()
        return top.map { WeightedGenre(genre = it.key, weight = (it.value / best).coerceIn(0f, 1f)) }
    }

    /**
     * True when [umbrella] is a broader form of [specific]: the umbrella's words appear, in order
     * and whole, inside the specific genre's words ("rock" in "indie rock", "soul" in "neo-soul",
     * "r&b" in "alternative r&b"), or one of its [UMBRELLA_ALIASES] does ("rap" in "cloud rap").
     * Words split on spaces and hyphens. Pure word matching: "pop" is not inside "k-popular".
     */
    internal fun isCoveredBy(umbrella: String, specific: String): Boolean {
        if (umbrella == specific) return false
        val specificWords = words(specific)
        val forms = listOf(words(umbrella)) + UMBRELLA_ALIASES[umbrella].orEmpty()
        return forms.any { form -> form.size < specificWords.size && specificWords.containsRun(form) }
    }

    private fun words(genre: String): List<String> = genre.split(' ', '-').filter { it.isNotEmpty() }

    private fun List<String>.containsRun(run: List<String>): Boolean =
        (0..size - run.size).any { start -> run.indices.all { this[start + it] == run[it] } }

    /**
     * How a canonical genre is shown: "hip-hop" -> "Hip-Hop", "r&b" -> "R&B", "indie pop" ->
     * "Indie Pop", "drum and bass" -> "Drum and Bass", "edm" -> "EDM", "k-pop" -> "K-Pop".
     */
    fun displayName(genre: String): String =
        genre.trim().split(WHITESPACE).filter { it.isNotEmpty() }.mapIndexed { index, word ->
            if (index > 0 && word in SMALL_WORDS) {
                word
            } else {
                word.split('-').joinToString("-") { part ->
                    ACRONYMS[part] ?: part.replaceFirstChar { it.titlecase(Locale.ROOT) }
                }
            }
        }.joinToString(" ")

    /** Lower case, `_` as a space, `&` spaced out to "and" ("drum & bass"), whitespace collapsed. */
    private fun clean(raw: String): String = raw.trim()
        .lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replace(" & ", " and ")
        .replace(WHITESPACE, " ")
        .replace(HIP_HOP, "hip-hop")
        .trim()

    private fun lookup(cleaned: String): String? {
        if (cleaned in genres) return cleaned
        val spaced = cleaned.replace('-', ' ')
        if (spaced in genres) return spaced
        val hyphenated = cleaned.replace(' ', '-')
        if (hyphenated in genres) return hyphenated
        return SQUASHED[squash(cleaned)]
    }

    private fun squash(value: String): String = value.replace(" ", "").replace("-", "")

    /** Internal for the test that checks no two genres squash to the same key. */
    internal fun squashCollisions(): List<Set<String>> =
        genres.groupBy { squash(it) }.values.filter { it.size > 1 }.map { it.toSet() }
}
