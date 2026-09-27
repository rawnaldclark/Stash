package com.stash.data.download.lossless.qobuz

import com.stash.data.download.lossless.TrackQuery
import kotlin.math.abs

/**
 * Scores how well a Qobuz catalog candidate matches a [TrackQuery]. Extracted
 * verbatim from [QobuzSource] (behavior-preserving) so multiple Qobuz-backed
 * sources — the squid.wtf proxy source and the direct-API qbdlx source — share
 * one scorer instead of duplicating the matching primitives.
 *
 * Operates on NEUTRAL candidate fields (title/artist/isrc/duration/streamable)
 * rather than any one source's wire type, so each source maps its own response
 * model into these parameters.
 */
object QobuzCandidateMatcher {

    /** Threshold below which a candidate is rejected outright. */
    const val MIN_CONFIDENCE = 0.5f

    /**
     * Confidence score on [0.0, 1.0]. ISRC equality short-circuits to
     * 0.95 — same recording, same master, by definition. Otherwise
     * combines token-overlap on title and artist with a duration
     * penalty that downweights mismatched cuts (live, extended,
     * edits) without hard-rejecting them.
     *
     * #502: a candidate that names a version the query doesn't (a remix,
     * live take, cover...) is scaled by [VERSION_PENALTY], or only by
     * [VERSION_TIEBREAK] when its length confirms the match. Qobuz keeps the
     * version out of the title and [normalize] drops brackets, so the title
     * score alone can't tell them apart.
     *
     * @param candDurationSec candidate duration in SECONDS (Qobuz's unit).
     * @param candVersion Qobuz's separate `version` field, when known.
     */
    fun confidence(
        query: TrackQuery,
        candTitle: String,
        candArtist: String,
        candIsrc: String?,
        candDurationSec: Int,
        candStreamable: Boolean,
        candVersion: String? = null,
    ): Float {
        if (!candStreamable) return 0f

        // ISRC match → highest possible non-1.0 score.
        val queryIsrc = query.isrc?.takeIf { it.isNotBlank() }
        val candidateIsrc = candIsrc?.takeIf { it.isNotBlank() }
        if (queryIsrc != null && candidateIsrc != null &&
            queryIsrc.equals(candidateIsrc, ignoreCase = true)
        ) {
            return 0.95f
        }

        val titleSim = jaccard(normalize(query.title), normalize(candTitle))
        // Spotify often expands artist names with collaborators/eras
        // ("Diana Ross and the Supremes", "Joey Bada$$ feat. Jay
        // Electronica") while Qobuz indexes the canonical short form
        // ("The Supremes", "Joey Bada$$"). Plain jaccard penalises
        // these subset matches harshly — so we take the higher of
        // jaccard and subset-coverage. Subset coverage is gated on a
        // minimum smaller-set size to avoid spurious 1-word matches
        // (e.g. an artist named "Love" matching every track with
        // "Love" in the artist string).
        val artistSim = artistSimilarity(
            normalize(query.artist),
            normalize(candArtist),
        )

        // Duration similarity. Skip the penalty when query duration is
        // unknown (Stash sometimes lacks duration for stub tracks).
        val durationFactor: Float = run {
            val queryMs = query.durationMs ?: return@run 1.0f
            if (queryMs <= 0 || candDurationSec <= 0) return@run 1.0f
            val candidateMs = candDurationSec * 1000L
            val drift = abs(queryMs - candidateMs).toDouble() / queryMs.toDouble()
            when {
                drift < 0.05 -> 1.0f      // <5% off — same recording almost certainly
                drift < 0.10 -> 0.85f     // 5-10% — typical compression-vs-original variance
                drift < 0.20 -> 0.6f      // 10-20% — possibly different cut
                else -> 0.3f              // dramatic mismatch (live vs studio etc.)
            }
        }

        // Only the candidate's bracketed/dashed parts and version field count, so
        // "Piano Man" itself isn't a piano version. The query's album counts too,
        // so a track off a live album can still take a live candidate.
        val candVersionWords = versionWords(
            BRACKETED.findAll(candTitle).joinToString(" ") { it.value } + " " +
                DASHED.find(candTitle)?.value.orEmpty() + " " + candVersion.orEmpty(),
        )
        val extraVersion = candVersionWords - versionWords(query.title + " " + query.album.orEmpty())
        // A length within 5% says it's the recording the user has (e.g. a live album
        // whose titles don't say "live"), so the version only breaks ties.
        val durationConfirms = (query.durationMs ?: 0L) > 0 && candDurationSec > 0 && durationFactor == 1.0f
        val versionFactor = when {
            extraVersion.isEmpty() -> 1.0f
            durationConfirms -> VERSION_TIEBREAK
            else -> VERSION_PENALTY
        }

        val base = titleSim * artistSim * durationFactor
        if (base >= MIN_CONFIDENCE && base * versionFactor < MIN_CONFIDENCE) {
            android.util.Log.d(
                "QobuzMatcher",
                "version_penalty dropped '$candTitle' (version='${candVersion.orEmpty()}', $extraVersion) " +
                    "for '${query.artist} - ${query.title}'",
            )
        }
        return base * versionFactor
    }

    /** Scales a candidate that names a version the query doesn't. 1.0 x 0.4 falls under [MIN_CONFIDENCE]. */
    private const val VERSION_PENALTY = 0.4f

    /** The same, when the length confirms the match: an unversioned twin still wins, and so does an ISRC match (0.95). */
    private const val VERSION_TIEBREAK = 0.9f

    private val BRACKETED = Regex("\\([^)]*\\)|\\[[^]]*\\]")
    private val DASHED = Regex("\\s[-\u2013\u2014]\\s.*")
    // A year, stereo, mono, single... mix is the same song, often a remaster reissue.
    private val SAME_SONG_MIX =
        Regex("\\b(\\d{4}|stereo|mono|single|radio|album|original|remaster|remastered)\\s+mix\\b")

    // Whole words only, so "Alive" is not "live". "Edit" is left out: radio edits are fine.
    private val VERSION_WORDS = Regex(
        "\\b(remix|mix|live|cover|karaoke|instrumental|lofi|lo-fi|lo fi|piano|acoustic|" +
            "orchestral|sped up|sped|slowed|nightcore|demo)\\b",
    )

    /** The version words [text] names, folded so "remix"/"mix", "lo-fi"/"lofi" and "sped up"/"sped" compare equal. */
    internal fun versionWords(text: String): Set<String> =
        VERSION_WORDS.findAll(SAME_SONG_MIX.replace(text.lowercase(), " "))
            .map {
                when (it.value) {
                    "remix" -> "mix"
                    "lo-fi", "lo fi" -> "lofi"
                    "sped up" -> "sped"
                    else -> it.value
                }
            }
            .toSet()

    // ── Pure-function helpers ────────────────────────────────────────────

    /**
     * Lowercase + strip parenthetical content, "feat./featuring"
     * suffixes, and non-alphanumeric characters; collapse whitespace.
     * Keeps Unicode letters/digits so non-Latin titles still tokenize
     * sensibly.
     */
    fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*\\]"), " ")
            .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ")
            // Elide within-word marks (straight + curly apostrophes)
            // BEFORE the punctuation-to-space pass, otherwise
            // contractions like "don't" tokenize as "don t" instead
            // of "dont" and pollute the Jaccard set.
            .replace(Regex("[''`]"), "")
            // v0.9.x: include \p{S} (Symbols, incl. Currency) so stylized artist
            // names like "¥$" (Kanye+Ty Dolla $ign), "$NOT", "+44", "!!!" survive
            // normalization. Without this they stripped to empty string and
            // artistSimilarity returned 0 for every catalog match, even when the
            // title was an exact hit. Punctuation (\p{P}) still gets stripped, so
            // commas/dashes/parens still tokenize correctly.
            .replace(Regex("[^\\p{L}\\p{N}\\p{S}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Jaccard similarity on whitespace-tokenized strings. */
    fun jaccard(a: String, b: String): Float {
        val setA = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val setB = b.split(" ").filter { it.isNotEmpty() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val intersection = setA.intersect(setB).size.toFloat()
        val union = setA.union(setB).size.toFloat()
        return intersection / union
    }

    /**
     * Artist-aware similarity: max of plain jaccard and
     * subset-coverage. Returns 1.0 when the smaller artist string
     * is fully contained in the larger AND at least one shared
     * token is "distinctive" (length > 3). This is the common
     * Spotify-expansion vs Qobuz-canonical pattern:
     *
     *  - Spotify: "Diana Ross and the Supremes" → Qobuz: "The Supremes"
     *  - Spotify: "Joey Bada$$ feat. Jay Electronica" → Qobuz: "Joey Bada$$"
     *  - Spotify: "Ghostemane, Shakewell, Pouya" → Qobuz: "Ghostemane"
     *  - Spotify: "¥$, Kanye West, Ty Dolla $ign" → Qobuz: "¥$"
     *
     * Includes the single-canonical-artist case (Qobuz indexes
     * one lead artist where Spotify expands to a featuring list).
     * "Distinctive" gating guards against generic 1-3 char tokens
     * ("Air", "U2") spuriously matching unrelated acts that
     * happen to share that token.
     * Stylized short names with symbols (¥$, $NOT, +44) bypass the
     * length-only check via the non-alphanumeric clause — they're rare
     * enough to be safe matches.
     *
     * Final fallback is plain jaccard, so unrelated artists with
     * partial token overlap still score reasonably.
     */
    fun artistSimilarity(a: String, b: String): Float {
        val setA = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val setB = b.split(" ").filter { it.isNotEmpty() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f

        val intersection = setA.intersect(setB)
        val union = setA.union(setB)
        val jaccardScore = intersection.size.toFloat() / union.size.toFloat()

        val smallerSize = minOf(setA.size, setB.size)
        val smallerFullyCovered = intersection.size == smallerSize
        // Distinctive = length > 3 OR contains a non-alphanumeric character.
        // The second clause catches stylized short artist names like "¥$",
        // "$NOT", "+44" — they're unique enough not to spuriously match.
        // Common short generic tokens ("the", "u2") remain non-distinctive
        // since they're pure alphanumeric and short.
        val hasDistinctiveOverlap = intersection.any { token ->
            token.length > 3 || token.any { ch -> !ch.isLetterOrDigit() }
        }

        val coverageScore = if (smallerFullyCovered && hasDistinctiveOverlap) {
            1.0f
        } else {
            0f
        }

        return maxOf(jaccardScore, coverageScore)
    }
}
