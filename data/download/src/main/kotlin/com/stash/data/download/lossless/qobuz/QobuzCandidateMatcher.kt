package com.stash.data.download.lossless.qobuz

import com.stash.core.common.VersionWords
import com.stash.data.download.lossless.TrackQuery
import java.text.Normalizer
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
     * @param candAlbum the candidate's album title, when known. A version the query doesn't name
     *   only gets the length tiebreak on the query's own album (a live album like KISS "Alive!").
     */
    fun confidence(
        query: TrackQuery,
        candTitle: String,
        candArtist: String,
        candIsrc: String?,
        candDurationSec: Int,
        candStreamable: Boolean,
        candVersion: String? = null,
        candAlbum: String? = null,
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

        val titleSim = maxOf(jaccard(normalize(query.title), normalize(candTitle)), suffixFreeTitleSim(query.title, candTitle, candVersion))
        // Spotify often expands artist names with collaborators/eras
        // ("Diana Ross and the Supremes", "Joey Bada$$ feat. Jay
        // Electronica") while Qobuz indexes the canonical short form
        // ("The Supremes", "Joey Bada$$"). Plain jaccard penalises
        // these subset matches harshly — so we take the higher of
        // jaccard and subset-coverage. Subset coverage is gated on a
        // minimum smaller-set size to avoid spurious 1-word matches
        // (e.g. an artist named "Love" matching every track with
        // "Love" in the artist string).
        val artistSim = queryArtistSimilarity(query.artist, candArtist)

        // Duration similarity. Skip the penalty when query duration is
        // unknown (Stash sometimes lacks duration for stub tracks).
        val durationFactor: Float = run {
            val queryMs = query.durationMs ?: return@run 1.0f
            if (queryMs <= 0 || candDurationSec <= 0) return@run 1.0f
            val candidateMs = candDurationSec * 1000L
            val drift = abs(queryMs - candidateMs).toDouble() / queryMs.toDouble()
            when {
                drift < 0.05 -> 1.0f      // <5% off — same recording almost certainly
                // A YouTube row's length is often the video's: only a gross gap (a 12-minute
                // jam for a 4-minute song) still counts against it. Versions are left to the
                // version words below.
                !query.durationReliable -> if (drift < VIDEO_DRIFT_OK) 1.0f else 0.3f
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
                VersionWords.DASH_SUFFIX.find(candTitle)?.value.orEmpty() + " " + candVersion.orEmpty(),
        )
        val extraVersion = candVersionWords - versionWords(query.title + " " + query.album.orEmpty())
        // The reverse: the query's title names a version ("Song - Live", "Song (Remix)") the
        // candidate names nowhere, its album included ("Live at Leeds" carries live takes).
        val queryVersionWords = versionWords(
            BRACKETED.findAll(query.title).joinToString(" ") { it.value } + " " +
                VersionWords.DASH_SUFFIX.find(query.title)?.value.orEmpty(),
        )
        val missingVersion = queryVersionWords - candVersionWords - versionWords(candAlbum.orEmpty())
        // A length within 5% on the query's own album says it's the recording the user has
        // (a live album whose titles don't say "live"), so the version only breaks ties. On
        // another album the same length proves nothing: a live take often runs like the studio cut.
        val durationConfirms = query.durationReliable && (query.durationMs ?: 0L) > 0 && candDurationSec > 0 &&
            durationFactor == 1.0f
        // A named edit ("Single Version", "Radio Edit", "Short Version") the query doesn't name is a
        // different cut of the same recording: the owner's rule is the cut the user knows or nothing,
        // so it only passes when the length confirms it (an edit of the user's own length).
        val candEditText = BRACKETED.findAll(candTitle).joinToString(" ") { it.value } + " " +
            VersionWords.DASH_SUFFIX.find(candTitle)?.value.orEmpty() + " " + candVersion.orEmpty()
        val namedEdit = EDIT_CUT.containsMatchIn(candEditText) && !EDIT_CUT.containsMatchIn(query.title)
        val versionFactor = when {
            extraVersion.isEmpty() && missingVersion.isEmpty() && !namedEdit -> 1.0f
            extraVersion.isEmpty() && missingVersion.isEmpty() -> if (durationConfirms) 1.0f else VERSION_PENALTY
            durationConfirms && sameAlbum(query.album, candAlbum) -> VERSION_TIEBREAK
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

    /** Largest length gap a [TrackQuery.durationReliable]=false query tolerates before the 0.3 factor. */
    private const val VIDEO_DRIFT_OK = 0.40

    /** Scales a candidate that names a version the query doesn't. 1.0 x 0.4 falls under [MIN_CONFIDENCE]. */
    private const val VERSION_PENALTY = 0.4f

    /** The same, when the length confirms the match: an unversioned twin still wins, and so does an ISRC match (0.95). */
    private const val VERSION_TIEBREAK = 0.9f

    private val BRACKETED = Regex("\\([^)]*\\)|\\[[^]]*\\]")

    /**
     * Words a reissue suffix may carry without naming a different recording: Spotify writes
     * "Disorder - 2007 Remaster" where the catalog has "Disorder" with the version kept apart.
     */
    private val REISSUE_WORDS = setOf(
        "remaster", "remastered", "remasters", "digital", "digitally", "mono", "stereo",
        "single", "album", "version", "edition", "deluxe",
    )

    /**
     * The title score with the query's dash suffix set aside ("Disorder - 2007 Remaster" vs the
     * catalog's "Disorder" + version "2007 Remaster"), or 0 when that's not safe: the candidate
     * title has a dash suffix of its own ("Song - Part 1" vs "Song - Part 2"), or the suffix says
     * something neither the candidate's brackets/version repeat nor a reissue word explains.
     * Versions the suffix names are still checked by the version rule after this.
     */
    private fun suffixFreeTitleSim(queryTitle: String, candTitle: String, candVersion: String?): Float {
        val suffix = VersionWords.DASH_SUFFIX.find(queryTitle)?.value ?: return 0f
        if (VersionWords.DASH_SUFFIX.containsMatchIn(candTitle)) return 0f
        val suffixWords = tokens(normalize(suffix))
        if (suffixWords.isEmpty()) return 0f
        // Bracket contents unwrapped first: normalize() drops anything in brackets, and some
        // catalogs write the version field itself as "(Robot Baby Mix)".
        val candExtra = tokens(normalize((BRACKETED.findAll(candTitle).map { it.value } + candVersion.orEmpty())
            .joinToString(" ") { it.trim().removeSurrounding("(", ")").removeSurrounding("[", "]") }))
        val explained = suffixWords.all { it in candExtra || it in REISSUE_WORDS || it.all(Char::isDigit) }
        if (!explained) return 0f
        return jaccard(normalize(queryTitle.removeRange(queryTitle.length - suffix.length, queryTitle.length)), normalize(candTitle))
    }

    /**
     * Same release: the albums' normalised words mostly agree ("Alive!" vs "Alive! (Remastered)")
     * and neither names a version the other doesn't. normalize() drops brackets, so without that
     * check "Vespertine (Live)" passed for "Vespertine".
     */
    private fun sameAlbum(queryAlbum: String?, candAlbum: String?): Boolean {
        val a = normalize(queryAlbum ?: return false)
        val b = normalize(candAlbum ?: return false)
        if (a.isEmpty() || b.isEmpty() || jaccard(a, b) < SAME_ALBUM_MIN) return false
        return versionWords(queryAlbum) == versionWords(candAlbum)
    }

    private const val SAME_ALBUM_MIN = 0.6f

    /** A cut of a recording: single/radio/short/edit versions. Not "Album Version" (the cut most libraries hold). */
    private val EDIT_CUT = Regex(
        "\\b(single (version|edit)|radio (version|edit|mix)|short (version|edit)|edit(ed)? version|\\bedit\\b|7\" version|45 version)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * [artistSimilarity] on raw credits, with three gaps closed (each found by the labelled library set):
     *  - a name that is all brackets ("[bsd.u]") normalised to nothing and matched nobody: its
     *    bracket contents are used instead;
     *  - the catalog credits only the lead artist ("Sia" for "Sia, Spencer Brown"), and a lead of
     *    three letters or fewer never counted as a distinctive overlap: the lead artist named in
     *    full on one side is now full credit;
     *  - "Run–D.M.C." vs "Run-DMC", "U-Ziq" vs "µ-Ziq": dots and dashes inside a name are also
     *    compared joined up ("rundmc"), and the better score wins.
     */
    private fun queryArtistSimilarity(queryArtist: String, candArtist: String): Float {
        val q = artistWords(queryArtist)
        val c = artistWords(candArtist)
        if (q.isEmpty() || c.isEmpty()) return 0f
        val plain = artistSimilarity(q, c)
        if (plain >= 1f) return plain
        val lead = if (leadArtist(queryArtist) == c || leadArtist(candArtist) == q) 1f else 0f
        val joined = artistSimilarity(artistWords(INNER_JOIN.replace(queryArtist, "")), artistWords(INNER_JOIN.replace(candArtist, "")))
        return maxOf(plain, lead, joined)
    }

    private fun artistWords(s: String): String =
        normalize(s).ifEmpty { normalize(s.replace(Regex("[\\[\\]()]"), " ")) }

    /** The first credited artist: "Sia, Spencer Brown" and "Sia & Chaka Khan" lead with "sia". */
    private fun leadArtist(s: String): String =
        artistWords(s.split(LEAD_SPLIT).first())

    private val LEAD_SPLIT = Regex(",|&|\\s+(?:x|and|feat\\.?|ft\\.?|featuring|with)\\s+", RegexOption.IGNORE_CASE)

    /** A dot or dash between two letters/digits: "D.M.C.", "Run-DMC", "U-Ziq". */
    private val INNER_JOIN = Regex("(?<=[\\p{L}\\p{N}])[.\\-‐–](?=[\\p{L}\\p{N}])")

    private fun tokens(s: String): Set<String> = s.split(" ").filter { it.isNotEmpty() }.toSet()

    /**
     * The version words [text] names. The list (whole words; a year, stereo, mono, single... mix
     * is the same song; "Edit" left out, radio edits are fine) is the library's too, so it lives in
     * core:common: [VersionWords].
     */
    internal fun versionWords(text: String): Set<String> = VersionWords.of(text)

    // ── Pure-function helpers ────────────────────────────────────────────

    /**
     * Lowercase + strip parenthetical content, "feat./featuring"
     * suffixes, and non-alphanumeric characters; collapse whitespace.
     * Keeps Unicode letters/digits so non-Latin titles still tokenize
     * sensibly.
     */
    fun normalize(s: String): String =
        // Fold accents first: catalogs often store "é" as "e" + a combining mark (NFD), which the
        // punctuation pass below would delete, so "Molécules" never matched "Molécules". Folding
        // both sides also lets "Capuçon"/"Capucon" and "Rodríguez"/"Rodriguez" agree.
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
            .lowercase()
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

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

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
