package com.stash.data.download.matching

import android.util.Log
import com.stash.core.data.sync.TrackMatcher
import com.stash.data.download.model.MatchResult
import com.stash.data.download.ytdlp.YtDlpSearchResult
import com.stash.data.ytmusic.model.MusicVideoType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10

/**
 * Scores YouTube search results against a target track using a weighted
 * composite of title similarity, artist similarity, duration closeness,
 * and popularity.
 *
 * Delegates canonical normalisation and Jaro-Winkler computation to
 * [TrackMatcher] (from :core:data) to avoid logic duplication.
 *
 * Scoring formula:
 *   score = titleSim * 0.35 + artistSim * 0.25 + durationSim * 0.25
 *           + popularitySim * 0.15 + topicBonus - penalty
 *
 * Results are returned sorted by descending score.
 */
@Singleton
class MatchScorer @Inject constructor(
    private val trackMatcher: TrackMatcher,
) {
    companion object {
        /** Weight given to title similarity in the composite score. */
        const val TITLE_WEIGHT = 0.30f

        /** Weight given to artist similarity in the composite score. */
        const val ARTIST_WEIGHT = 0.30f

        /** Weight given to duration closeness in the composite score. */
        const val DURATION_WEIGHT = 0.20f

        /** Weight given to relative popularity (view count) in the composite score. */
        const val POPULARITY_WEIGHT = 0.10f

        /** Minimum score to auto-accept a match. Set at 0.60 to avoid accepting
         *  wrong versions. The multi-strategy search (4 query variations) handles
         *  hard-to-find tracks by producing cleaner queries, not by lowering standards. */
        const val AUTO_ACCEPT_THRESHOLD = 0.60f

        /** Scores below this value are discarded as unlikely matches. */
        const val REJECT_THRESHOLD = 0.50f

        /**
         * Hard duration tolerance in seconds. Candidates further than this
         * from the target duration are rejected by [durationPassesHardGate]
         * regardless of how well every other signal scores. Pre-Phase-3
         * duration was only a soft weight, which let a 9:25 MV beat a 4:18
         * target when title + artist + topic piled up enough score; the
         * gate neutralises that at the pipeline level.
         */
        const val DURATION_HARD_GATE_SEC = 15

        /** Adjustments keyed off InnerTube's authoritative musicVideoType enum. */
        const val VIDEO_TYPE_ATV_BONUS = 0.20f
        const val VIDEO_TYPE_OFFICIAL_SOURCE_BONUS = 0.10f
        const val VIDEO_TYPE_OMV_PENALTY = 0.15f
        const val VIDEO_TYPE_UGC_PENALTY = 0.40f
        /** Large enough to clamp to zero for any realistic candidate score. */
        const val VIDEO_TYPE_PODCAST_PENALTY = 1.0f

        /**
         * Demotion applied when a candidate is the wrong side of the target's
         * parental advisory: a `(Clean)` title for an explicit or unknown target,
         * or an explicit candidate (YouTube Music's badge, or an `(Explicit)`
         * title) for a target known to be clean.
         */
        const val EXPLICIT_MISMATCH_PENALTY = 0.15f

        /**
         * Lift for an explicit candidate (badged, or titled `(Explicit)`) when the
         * target is explicit or doesn't say. YouTube Music's explicit and clean
         * twins share title, artist, album and length, so they tie on every other
         * signal and the tie went to whichever row came first. This only has to
         * break that tie, so it sits under the smallest step any other signal
         * takes (a duration tier, 0.04; an uploader or album-in-title nudge, 0.05):
         * it only reorders candidates already within 0.03 of each other.
         */
        const val EXPLICIT_MATCH_BONUS = 0.03f
    }

    /**
     * Hard duration gate. Returns `false` iff both target and candidate
     * durations are known and differ by more than [DURATION_HARD_GATE_SEC]
     * seconds. Unknown durations pass — InnerTube omits duration on some
     * results and we don't want to blanket-reject them.
     */
    fun durationPassesHardGate(targetMs: Long, candidateDurationSec: Long): Boolean {
        if (targetMs <= 0L || candidateDurationSec <= 0L) return true
        return abs((targetMs / 1000) - candidateDurationSec) <= DURATION_HARD_GATE_SEC
    }

    /**
     * Score every [YtDlpSearchResult] against the target track metadata.
     *
     * @param targetTitle      Track title from the library/playlist.
     * @param targetArtist     Track artist from the library/playlist.
     * @param targetDurationMs Track duration in milliseconds.
     * @param results          Raw search results from YouTube.
     * @return Scored [MatchResult] list, sorted best-first.
     */
    fun scoreResults(
        targetTitle: String,
        targetArtist: String,
        targetDurationMs: Long,
        results: List<YtDlpSearchResult>,
        targetAlbum: String = "",
        targetExplicit: Boolean? = null,
    ): List<MatchResult> {
        if (results.isEmpty()) return emptyList()

        val maxViewCount = results.maxOf { it.viewCount }

        return results.map { result ->
            val titleScore = computeTitleScore(targetTitle, result.title)
            // Credit the artist when the uploader name doesn't match but the
            // artist is named in the title — official J/K-pop uploads
            // ("美波「カワキヲアメク」MV" on romanised channel "Minami") otherwise
            // score artist=0 and miss the 0.60 composite gate.
            val artistScore = maxOf(
                computeArtistScore(targetArtist, result.uploader),
                if (artistAppearsInTitle(targetArtist, result.title)) artistInTitleScore else 0f,
            )
            val durationScore = computeDurationScore(targetDurationMs, result.duration.toLong())
            val popularityScore = computePopularityScore(result.viewCount, maxViewCount)
            val penalty = computePenalty(targetTitle, result.title)
            val topicBonus = computeTopicBonus(result.uploader, result.channel)
            val uploaderPenalty = computeUploaderMismatchPenalty(targetArtist, result.uploader, result.channel)
            val albumBonus = computeAlbumBonus(targetAlbum, result.album, result.title)
            val videoTypeAdjustment = computeVideoTypeAdjustment(result.musicVideoType)

            // coerceAtLeast(0f) floors negative contributions so a catastrophic
            // candidate doesn't accidentally sort ABOVE a better one (a -1.0
            // podcast penalty otherwise produces −0.9 which compares <0.0 but
            // we want it strictly non-negative for the auto-accept check).
            // We DON'T cap the ceiling: bonuses legitimately push a great
            // match past 1.0, and clamping collapses tie-breaks — e.g. a
            // (Sped Up) variant and its original would both hit the ceiling
            // and sort by input order rather than by quality.
            val baseScore = (
                titleScore * TITLE_WEIGHT +
                    artistScore * ARTIST_WEIGHT +
                    durationScore * DURATION_WEIGHT +
                    popularityScore * POPULARITY_WEIGHT +
                    topicBonus + albumBonus + videoTypeAdjustment -
                    penalty - uploaderPenalty
                ).coerceAtLeast(0f)
            val finalScore = applyExplicitAdjustment(
                baseScore,
                computeExplicitAdjustment(targetExplicit, result.title, result.explicit),
            )

            Log.d("MatchScorer", "  ${result.title} by ${result.uploader} (album=${result.album ?: "?"}, mvt=${result.musicVideoType}, badge=${result.explicit}) | " +
                "title=%.2f art=%.2f dur=%.2f pop=%.2f topic=%.2f alb=%.2f vt=%+.2f pen=%.2f uplPen=%.2f xpl=%+.2f → %.2f".format(
                    titleScore, artistScore, durationScore, popularityScore,
                    topicBonus, albumBonus, videoTypeAdjustment, penalty, uploaderPenalty,
                    finalScore - baseScore, finalScore))

            MatchResult(
                youtubeUrl = result.webpageUrl.ifEmpty {
                    "https://www.youtube.com/watch?v=${result.id}"
                },
                videoId = result.id,
                title = result.title,
                uploader = result.uploader,
                durationSeconds = result.duration.toLong(),
                viewCount = result.viewCount,
                matchScore = finalScore,
                album = result.album,
                thumbnailUrl = result.thumbnail,
            )
        }.sortedByDescending { it.matchScore }
    }

    /**
     * Return the best match from a scored list if it exceeds [AUTO_ACCEPT_THRESHOLD].
     *
     * @param results Scored and sorted match results.
     * @return The top result if its score is high enough, null otherwise.
     */
    fun bestMatch(results: List<MatchResult>): MatchResult? {
        val best = results.firstOrNull()
        if (best != null && best.matchScore < AUTO_ACCEPT_THRESHOLD) {
            // Only the numbers go through format(): a title can hold a "%".
            Log.w(
                "MatchScorer",
                "Best match rejected: ${best.title} score=${"%.2f".format(best.matchScore)} " +
                    "(threshold=${"%.2f".format(AUTO_ACCEPT_THRESHOLD)})",
            )
        }
        return results.firstOrNull { it.matchScore >= AUTO_ACCEPT_THRESHOLD }
    }

    /**
     * Computes the raw artist similarity for a result (used by the caller
     * to enforce a minimum artist match independently of the weighted score).
     */
    fun artistSimilarity(targetArtist: String, resultUploader: String): Float {
        return computeArtistScore(targetArtist, resultUploader)
    }

    fun titleSimilarity(targetTitle: String, resultTitle: String): Float {
        return computeTitleScore(targetTitle, resultTitle)
    }

    /**
     * Containment escape for the hard title gate. Returns true when the
     * (canonicalised) target title appears as a contiguous run of word tokens
     * inside the candidate's video title.
     *
     * The Jaro-Winkler title gate compares the clean target against the
     * candidate's *full decorated* title — artist prefix ("SunKissed Lola -
     * …"), "(Official Lyric Video)" suffix, 【…】 annotations, "OST - 172"
     * numbering, or a CJK + romanised dual title — and so rejects correct
     * matches whose extra tokens drown out the overlap (field-observed at
     * 0.43–0.58 for confirmed-correct official uploads). Substring containment
     * recovers them while the duration gate, artist gate, and short-title word
     * gate still guard against wrong-song acceptance.
     *
     * Asymmetric normalisation by design:
     *  - **Target** runs through [TrackMatcher.canonicalTitle] first so a
     *    "(feat. …)" / "(remix)" decoration on the target doesn't prevent a
     *    bare candidate from satisfying it.
     *  - **Candidate** keeps bracket/paren *contents* (only punctuation is
     *    neutralised to token breaks) — canonicalTitle would delete the
     *    "(Readymade)" that a "Readymade" target needs to match.
     *
     * CJK scripts carry no whitespace, so a kana/hanzi title collapses to a
     * single token and matches by whole-token equality.
     */
    fun titleContainsTarget(targetTitle: String, candidateTitle: String): Boolean {
        val target = looseTokens(trackMatcher.canonicalTitle(targetTitle))
        return ArtistMatching.containsRun(haystack = looseTokens(candidateTitle), needle = target)
    }

    /**
     * Per-part escape for the artist gate. Returns true when any significant
     * part of the target artist matches any part of the candidate uploader
     * after splitting on multi-artist separators and stripping spaces and
     * punctuation.
     *
     * Rescues bilingual / slash-joined official channels such as
     * "かいりきベア／Kairiki bear" whose romanised half equals a target part
     * ("Kairikibear") but whose whole-string Jaro-Winkler is dragged below the
     * 0.65 gate by the CJK half plus the user's comma-joined multi-artist
     * credit. The leading " - Topic" suffix is stripped first (Topic channels
     * are the authoritative audio source).
     */
    fun artistPartMatches(targetArtist: String, candidateUploader: String): Boolean {
        val targetParts = ArtistMatching.artistParts(targetArtist)
        val candidateParts = ArtistMatching.artistParts(candidateUploader.replace(" - Topic", ""))
        if (targetParts.isEmpty() || candidateParts.isEmpty()) return false
        return targetParts.any { t ->
            candidateParts.any { c ->
                t == c || (t.length >= 4 && c.length >= 4 && (c.contains(t) || t.contains(c)))
            }
        }
    }

    /**
     * True when the target artist appears inside the candidate's video title.
     *
     * Official J-pop/K-pop uploads format titles as "Artist「Song」MV" or
     * "【Artist】Song", so the artist is present in the *title* even when the
     * uploader is a romanised channel name ("Minami" for 美波) that scores 0
     * against the CJK artist on [artistSimilarity]. This is the dominant cause
     * of CJK-artist tracks failing the 0.60 composite auto-accept gate. Used
     * both as a scoring credit (lifts the composite) and as an escape on the
     * verify-stage artist gate.
     *
     * A part matches when it equals a candidate title token or is contained in
     * one (CJK titles glue artist + song with no separator). Parts shorter than
     * 2 chars are ignored to avoid trivial collisions.
     */
    fun artistAppearsInTitle(targetArtist: String, candidateTitle: String): Boolean {
        val candidate = looseTokens(candidateTitle)
        if (candidate.isEmpty()) return false
        // Each distinct artist in a multi-artist credit, matched as its own
        // contiguous word run ("Will Stetson" → ["will","stetson"]).
        return targetArtist.split(ArtistMatching.ARTIST_PART_SEPARATOR)
            .map { looseTokens(it) }
            .filter { tokens -> tokens.sumOf { it.length } >= 2 }
            .any { artistTokens -> ArtistMatching.containsRun(haystack = candidate, needle = artistTokens) }
    }

    /** Artist score credited when [artistAppearsInTitle] holds but the uploader
     *  name itself doesn't match (e.g. CJK artist, romanised channel). */
    private val artistInTitleScore = 0.85f

    /**
     * Split a string into lowercased word tokens, treating every non
     * letter-or-digit character (punctuation, brackets, whitespace, CJK
     * full-width punctuation) as a token break. CJK ideographs, kana and
     * hangul are letters, so a whitespace-free native title stays one token.
     */
    private fun looseTokens(s: String): List<String> =
        s.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }

    // -- Private scoring helpers --------------------------------------------------

    /**
     * Jaro-Winkler similarity between canonical titles.
     */
    private fun computeTitleScore(target: String, candidate: String): Float {
        return trackMatcher.jaroWinklerSimilarity(
            trackMatcher.canonicalTitle(target),
            trackMatcher.canonicalTitle(candidate),
        ).toFloat()
    }

    /**
     * Jaro-Winkler similarity between canonical artist names.
     * Strips YouTube's " - Topic" suffix from auto-generated channels.
     */
    private fun computeArtistScore(target: String, candidateUploader: String): Float {
        val cleanUploader = candidateUploader.replace(" - Topic", "")
        return trackMatcher.jaroWinklerSimilarity(
            trackMatcher.canonicalArtist(target),
            trackMatcher.canonicalArtist(cleanUploader),
        ).toFloat()
    }

    /**
     * Duration closeness score using tiered thresholds.
     *
     * - Within 3 seconds: perfect (1.0)
     * - Within 10 seconds: good (0.8)
     * - Within 30 seconds: fair (0.4)
     * - Beyond 30 seconds: no match (0.0)
     */
    private fun computeDurationScore(targetDurationMs: Long, candidateDurationSec: Long): Float {
        // When duration is unknown (0), return neutral score instead of penalizing.
        // InnerTube music search often doesn't include duration data.
        if (candidateDurationSec <= 0 || targetDurationMs <= 0) return 0.5f

        val durationDiffSec = abs((targetDurationMs / 1000) - candidateDurationSec)
        return when {
            durationDiffSec <= 3 -> 1.0f
            durationDiffSec <= 10 -> 0.8f
            durationDiffSec <= 30 -> 0.4f
            else -> 0.0f
        }
    }

    /**
     * Log-scaled popularity relative to the most-viewed result in the set.
     */
    private fun computePopularityScore(viewCount: Long, maxViewCount: Long): Float {
        // When all results have viewCount=0 (common with InnerTube/YouTube Music),
        // return a neutral score instead of 0. This prevents InnerTube results from
        // being penalized for lacking view data.
        if (maxViewCount <= 0) return 0.5f
        if (viewCount <= 0) return 0.1f
        return (log10(viewCount.toDouble()) / log10(maxViewCount.toDouble())).toFloat()
    }

    /**
     * Penalty for content variants (covers, remixes, reworks, live, karaoke,
     * instrumental, sped-up / nightcore / slowed / edit / extended) when the
     * target title does not contain the same keyword.
     *
     * "music video" / "official video" used to live here too. They were
     * removed in Phase 3: [computeVideoTypeAdjustment] now handles those
     * cases via InnerTube's structured `musicVideoType` enum, which is
     * more reliable than string-matching titles.
     */
    private fun computePenalty(targetTitle: String, candidateTitle: String): Float {
        val targetLower = targetTitle.lowercase()
        val candidateLower = candidateTitle.lowercase()

        // Live/concert indicators (many live versions don't say "live" explicitly)
        val liveIndicators = listOf("live", "concert", "woodstock", "festival", "session",
            "unplugged", "acoustic version", "radio session", "bbc session", "peel session",
            "mtv", "letterman", "snl", "tonight show")
        val isLikelyLive = liveIndicators.any { containsWord(candidateLower, it) } &&
            liveIndicators.none { containsWord(targetLower, it) }

        // Tempo/pitch edits commonly uploaded to YT that masquerade as the
        // real track — penalise whenever the target title doesn't explicitly
        // request the variant. "slowed" covers both "Slowed" and "Slowed Down";
        // "edit" and "extended" catch alternate cuts that differ from the
        // album master.
        val tempoVariants = listOf("sped up", "nightcore", "slowed", "extended", "edit")
        val isTempoVariant = tempoVariants.any { containsWord(candidateLower, it) } &&
            tempoVariants.none { containsWord(targetLower, it) }

        return when {
            "karaoke" in candidateLower -> 0.4f
            "cover" in candidateLower && "cover" !in targetLower -> 0.25f
            "remix" in candidateLower && "remix" !in targetLower -> 0.25f
            // "Rework" is the fan-edit cousin of "remix" — common in
            // electronic/ambient music (Conundrum playlists, etc.). Without
            // this, a candidate like "Stay The Same (Rework)" scored ~0.85
            // for a target of "Stay The Same (Radio Edit)" and sailed past
            // the 0.60 auto-accept threshold, silently downloading the
            // wrong audio under the original track's filename.
            "rework" in candidateLower && "rework" !in targetLower -> 0.25f
            "instrumental" in candidateLower && "instrumental" !in targetLower -> 0.2f
            isTempoVariant -> 0.2f
            isLikelyLive -> 0.15f
            else -> 0f
        }
    }

    /**
     * True when [phrase] appears in [text] as whole words. The live and tempo
     * keyword lists used raw substring tests, so "edit" fired on "(Deluxe
     * Edition)" and "Credits" (a 0.2 penalty that could sink the right
     * candidate under the bar), and "live" / "session" fired on the *target*
     * titles "Deliverance", "Alive" or "Obsession", exempting real live
     * recordings from the penalty.
     */
    private fun containsWord(text: String, phrase: String): Boolean =
        wordPatterns.getOrPut(phrase) { Regex("\\b" + Regex.escape(phrase) + "\\b") }
            .containsMatchIn(text)

    /** [containsWord]'s compiled patterns, one per keyword. */
    private val wordPatterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /**
     * Adjustment keyed off YouTube Music's authoritative video classification.
     *
     * Replaces the "music video" / "official video" string match in
     * [computePenalty]. ATV (Topic-channel audio) gets a positive bump on
     * top of the Topic-channel name bonus; OMV (official music video) is
     * demoted because its audio track often differs from the album master
     * (intros, outros, alternate mixes); UGC (user-uploaded) is demoted
     * hard enough that bestMatch can't accept it unless no other candidate
     * exists; PODCAST_EPISODE gets an effectively-infinite penalty so it
     * clamps to zero and falls below [AUTO_ACCEPT_THRESHOLD].
     *
     * Null means "enum not available" (yt-dlp fallback path or InnerTube
     * renderer shape we couldn't parse) — no adjustment so the scorer
     * falls back to everything else it has.
     */
    private fun computeVideoTypeAdjustment(musicVideoType: MusicVideoType?): Float =
        when (musicVideoType) {
            MusicVideoType.ATV -> VIDEO_TYPE_ATV_BONUS
            MusicVideoType.OFFICIAL_SOURCE_MUSIC -> VIDEO_TYPE_OFFICIAL_SOURCE_BONUS
            MusicVideoType.OMV -> -VIDEO_TYPE_OMV_PENALTY
            MusicVideoType.UGC -> -VIDEO_TYPE_UGC_PENALTY
            MusicVideoType.PODCAST_EPISODE -> -VIDEO_TYPE_PODCAST_PENALTY
            null -> 0f
        }

    /**
     * How the candidate sits against the target's parental advisory
     * ([targetExplicit]: the source track's flag, null when it doesn't say).
     *
     * A candidate is explicit when YouTube Music badges it ([candidateExplicit])
     * or its title says "(Explicit)"; it is clean only when its title says
     * "(Clean)" / "[Clean Version]". An unbadged, unlabelled candidate is
     * unknown, and gets nothing either way: most songs are never explicit.
     *
     * - Target explicit or unknown (play the original when the source doesn't
     *   say): an explicit candidate gets [EXPLICIT_MATCH_BONUS], a clean one
     *   [EXPLICIT_MISMATCH_PENALTY].
     * - Target clean: an explicit candidate gets [EXPLICIT_MISMATCH_PENALTY].
     *
     * The result is applied by [applyExplicitAdjustment], which keeps it from
     * deciding whether a candidate is accepted at all.
     */
    private fun computeExplicitAdjustment(
        targetExplicit: Boolean?,
        candidateTitle: String,
        candidateExplicit: Boolean?,
    ): Float {
        val title = candidateTitle.lowercase()
        val titleSaysClean = cleanTitleMarker.containsMatchIn(title)
        val candidateIsExplicit = candidateExplicit == true || explicitTitleMarker.containsMatchIn(title)

        return if (targetExplicit == false) {
            if (candidateIsExplicit) -EXPLICIT_MISMATCH_PENALTY else 0f
        } else {
            when {
                titleSaysClean -> -EXPLICIT_MISMATCH_PENALTY
                candidateIsExplicit -> EXPLICIT_MATCH_BONUS
                else -> 0f
            }
        }
    }

    /**
     * [score] with the explicitness [adjustment], applied only to order
     * candidates that clear [AUTO_ACCEPT_THRESHOLD] on their own. One under it
     * gets nothing, so explicitness never makes a match acceptable; a docked one
     * floors at it, so explicitness never makes one unacceptable either. Between
     * a song's explicit and clean twins, which score alike, it decides the order.
     */
    private fun applyExplicitAdjustment(score: Float, adjustment: Float): Float =
        if (adjustment == 0f || score < AUTO_ACCEPT_THRESHOLD) {
            score
        } else {
            (score + adjustment).coerceAtLeast(AUTO_ACCEPT_THRESHOLD)
        }

    /** A title labelled as the clean cut: "(Clean)", "[Clean Version]". Matched lowercased. */
    private val cleanTitleMarker = Regex("""[\(\[]\s*clean(\s+version)?\s*[\)\]]""")

    /** A title labelled as the explicit cut: "(Explicit)". Matched lowercased. */
    private val explicitTitleMarker = Regex("""[\(\[]\s*explicit\s*[\)\]]""")

    /**
     * Bonus when the YouTube result's album matches the Spotify track's album.
     * This is the strongest signal for correct matching — if album matches,
     * it's almost certainly the right recording.
     */
    private fun computeAlbumBonus(targetAlbum: String, resultAlbum: String?, resultTitle: String): Float {
        if (targetAlbum.isBlank()) return 0f
        val target = trackMatcher.canonicalTitle(targetAlbum)
        if (target.isBlank()) return 0f

        // Check result's album field (from InnerTube)
        if (resultAlbum != null) {
            val candidate = trackMatcher.canonicalTitle(resultAlbum)
            val similarity = trackMatcher.jaroWinklerSimilarity(target, candidate).toFloat()
            if (similarity >= 0.8f) return 0.15f  // Strong album match
            if (similarity >= 0.6f) return 0.08f  // Partial album match
        }

        // Check if album name appears in the video title
        val titleLower = resultTitle.lowercase()
        val albumLower = targetAlbum.lowercase()
        if (albumLower.length > 3 && albumLower in titleLower) return 0.05f

        return 0f
    }

    /**
     * Strong bonus for YouTube's auto-generated "Artist - Topic" channels.
     * These carry the official audio from the label and are the most reliable
     * source for correct tracks. A Topic channel match is the single strongest
     * signal that this is the right version of the song.
     */
    private fun computeTopicBonus(uploader: String, channel: String): Float {
        return if (uploader.endsWith(" - Topic") || channel.endsWith(" - Topic")) 0.25f else 0f
    }

    /**
     * Penalty when the uploader clearly doesn't match the target artist.
     * Catches covers and re-uploads by unrelated channels. A low artist score
     * (<0.4 Jaro-Winkler) combined with NOT being a Topic channel means this
     * is likely someone else's upload of the song (cover, tribute, etc).
     */
    private fun computeUploaderMismatchPenalty(
        targetArtist: String,
        uploader: String,
        channel: String,
    ): Float {
        val isTopic = uploader.endsWith(" - Topic") || channel.endsWith(" - Topic")
        if (isTopic) return 0f // Topic channels are always trusted

        val cleanUploader = uploader.replace(" - Topic", "")
        val similarity = trackMatcher.jaroWinklerSimilarity(
            trackMatcher.canonicalArtist(targetArtist),
            trackMatcher.canonicalArtist(cleanUploader),
        ).toFloat()

        // If the uploader name is very different from the artist, penalize
        return when {
            similarity >= 0.7f -> 0f        // close enough match
            similarity >= 0.4f -> 0.05f     // somewhat different
            else -> 0.15f                    // clearly different uploader
        }
    }
}
