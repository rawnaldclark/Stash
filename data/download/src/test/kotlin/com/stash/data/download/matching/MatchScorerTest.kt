package com.stash.data.download.matching

import com.stash.core.data.sync.TrackMatcher
import com.stash.data.download.ytdlp.YtDlpSearchResult
import com.stash.data.ytmusic.model.MusicVideoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [MatchScorer].
 *
 * Locks in the Phase 3 scoring rules:
 *  - duration hard gate (±15s) — prevents the Smooth Criminal 9:25-vs-4:18 class
 *  - musicVideoType structural signal replaces title-keyword MV penalty
 *  - UGC and PODCAST_EPISODE are effectively rejected
 *  - variant-title penalty expanded (sped up, nightcore, slowed, edit, extended)
 */
class MatchScorerTest {

    private val scorer = MatchScorer(TrackMatcher())

    private fun candidate(
        id: String,
        title: String,
        artist: String = "Michael Jackson",
        channel: String = artist,
        durationSec: Double = 258.0,
        viewCount: Long = 10_000_000,
        musicVideoType: MusicVideoType? = null,
        album: String? = null,
        explicit: Boolean? = null,
    ) = YtDlpSearchResult(
        id = id,
        title = title,
        uploader = artist,
        uploaderId = "",
        channel = channel,
        duration = durationSec,
        viewCount = viewCount,
        webpageUrl = "https://www.youtube.com/watch?v=$id",
        url = "",
        likeCount = null,
        description = "",
        thumbnail = null,
        album = album,
        musicVideoType = musicVideoType,
        explicit = explicit,
    )

    // ── Phase 3a: duration hard gate ─────────────────────────────────────

    @Test
    fun `durationPassesHardGate accepts within 15s tolerance`() {
        assertTrue(scorer.durationPassesHardGate(targetMs = 258_000, candidateDurationSec = 258))
        assertTrue(scorer.durationPassesHardGate(targetMs = 258_000, candidateDurationSec = 270))
        assertTrue(scorer.durationPassesHardGate(targetMs = 258_000, candidateDurationSec = 243))
    }

    @Test
    fun `durationPassesHardGate rejects the 9_25 MV matching a 4_18 target`() {
        // This is the Smooth Criminal case: Spotify track is 4:18 (258s),
        // YouTube MV is 9:25 (565s). The pre-Phase-3 scorer accepted this
        // because duration was only a soft weight. Now it's a hard reject.
        assertFalse(scorer.durationPassesHardGate(targetMs = 258_000, candidateDurationSec = 565))
    }

    @Test
    fun `durationPassesHardGate accepts when either duration is unknown`() {
        // InnerTube occasionally omits durations; don't punish missing data.
        assertTrue(scorer.durationPassesHardGate(targetMs = 0, candidateDurationSec = 565))
        assertTrue(scorer.durationPassesHardGate(targetMs = 258_000, candidateDurationSec = 0))
    }

    // ── Phase 3b: musicVideoType signal ──────────────────────────────────

    @Test
    fun `scorer prefers ATV over OMV when other signals are equal`() {
        // Identical title, artist, duration, popularity — only videoType differs.
        // Phase 3 must let the structured enum break the tie in favour of ATV.
        val results = scorer.scoreResults(
            targetTitle = "Smooth Criminal",
            targetArtist = "Michael Jackson",
            targetDurationMs = 258_000,
            results = listOf(
                candidate(id = "omv", title = "Smooth Criminal", musicVideoType = MusicVideoType.OMV),
                candidate(id = "atv", title = "Smooth Criminal", musicVideoType = MusicVideoType.ATV),
            ),
        )
        assertEquals(
            "ATV must outscore OMV when every other signal matches",
            "atv",
            results.first().videoId,
        )
    }

    @Test
    fun `UGC candidate scores below auto-accept threshold`() {
        val results = scorer.scoreResults(
            targetTitle = "Smooth Criminal",
            targetArtist = "Michael Jackson",
            targetDurationMs = 258_000,
            results = listOf(
                candidate(
                    id = "ugc",
                    title = "Smooth Criminal (fan lyrics video)",
                    artist = "SomeFanChannel",
                    channel = "SomeFanChannel",
                    musicVideoType = MusicVideoType.UGC,
                ),
            ),
        )
        assertTrue(
            "UGC-only result list must not auto-accept — bestMatch should return null",
            scorer.bestMatch(results) == null,
        )
    }

    @Test
    fun `PODCAST_EPISODE candidate is hard-rejected regardless of other signals`() {
        val results = scorer.scoreResults(
            targetTitle = "Smooth Criminal",
            targetArtist = "Michael Jackson",
            targetDurationMs = 258_000,
            results = listOf(
                candidate(
                    id = "pod",
                    title = "Smooth Criminal",
                    channel = "Michael Jackson - Topic",
                    musicVideoType = MusicVideoType.PODCAST_EPISODE,
                ),
            ),
        )
        assertTrue(
            "a PODCAST_EPISODE must not be returned by bestMatch even with a perfect title+artist+topic match",
            scorer.bestMatch(results) == null,
        )
    }

    // ── Phase 3b: expanded variant-title vocabulary ──────────────────────

    @Test
    fun `sped up title variant is penalized against a non-sped-up target`() {
        val results = scorer.scoreResults(
            targetTitle = "Smooth Criminal",
            targetArtist = "Michael Jackson",
            targetDurationMs = 258_000,
            results = listOf(
                candidate(
                    id = "spedup",
                    title = "Smooth Criminal (Sped Up)",
                    channel = "Michael Jackson - Topic",
                    musicVideoType = MusicVideoType.ATV,
                ),
                candidate(
                    id = "original",
                    title = "Smooth Criminal",
                    channel = "Michael Jackson - Topic",
                    musicVideoType = MusicVideoType.ATV,
                ),
            ),
        )
        assertEquals(
            "'Sped Up' variant must not outrank the original title",
            "original",
            results.first().videoId,
        )
    }

    @Test
    fun `nightcore title variant is penalized`() {
        val results = scorer.scoreResults(
            targetTitle = "Smooth Criminal",
            targetArtist = "Michael Jackson",
            targetDurationMs = 258_000,
            results = listOf(
                candidate(
                    id = "nightcore",
                    title = "Smooth Criminal (Nightcore)",
                    channel = "Michael Jackson - Topic",
                    musicVideoType = MusicVideoType.ATV,
                ),
                candidate(
                    id = "original",
                    title = "Smooth Criminal",
                    channel = "Michael Jackson - Topic",
                    musicVideoType = MusicVideoType.ATV,
                ),
            ),
        )
        assertEquals("original", results.first().videoId)
    }

    /** [title]'s score against [target], every other signal equal. */
    private fun scoreOf(target: String, title: String): Float = scorer.scoreResults(
        targetTitle = target,
        targetArtist = "Michael Jackson",
        targetDurationMs = 258_000,
        results = listOf(candidate(id = "only", title = title)),
    ).single().matchScore

    @Test
    fun `edit and live penalties match whole words only`() {
        // "edit" inside "Edition" / "Credits" used to take the 0.2 tempo penalty;
        // the parenthetical is canonicalised away, so the right score is the bare title's.
        val bare = scoreOf("Bad", "Bad")
        assertEquals(bare, scoreOf("Bad", "Bad (Deluxe Edition)"), 0.0001f)
        assertEquals(bare, scoreOf("Bad", "Bad (Credits)"), 0.0001f)
        // The real variants still are penalised.
        assertTrue(scoreOf("Bad", "Bad (Radio Edit)") < bare)
        assertTrue(scoreOf("Bad", "Bad (Live at Wembley)") < bare)
    }

    @Test
    fun `a target containing live inside a word does not exempt live recordings`() {
        // "Deliverance" and "Alive" contain "live", which exempted every live
        // recording from the penalty for these targets.
        assertTrue(scoreOf("Deliverance", "Deliverance (Live)") < scoreOf("Deliverance", "Deliverance"))
        assertTrue(scoreOf("Alive", "Alive (Live)") < scoreOf("Alive", "Alive"))
        // A target that asks for the live cut keeps its exemption.
        assertEquals(
            scoreOf("Alive (Live)", "Alive"),
            scoreOf("Alive (Live)", "Alive (Live)"),
            0.0001f,
        )
    }

    // ── Phase 3c: explicit-vs-title demotion ─────────────────────────────

    @Test
    fun `explicit target demotes clean version when both candidates present`() {
        // This is the GitHub issue #12 failure mode: user's Spotify track
        // is marked explicit, but YouTube has both an explicit and a clean
        // upload. Without target-side explicit bias, title/artist/duration
        // are identical and the tie breaks by popularity, arbitrarily
        // picking the clean cut.
        val results = scorer.scoreResults(
            targetTitle = "Some Song",
            targetArtist = "Some Artist",
            targetDurationMs = 180_000,
            targetExplicit = true,
            results = listOf(
                candidate(
                    id = "clean",
                    title = "Some Song (Clean)",
                    artist = "Some Artist",
                    channel = "Some Artist - Topic",
                    musicVideoType = MusicVideoType.ATV,
                ),
                candidate(
                    id = "explicit",
                    title = "Some Song",
                    artist = "Some Artist",
                    channel = "Some Artist - Topic",
                    musicVideoType = MusicVideoType.ATV,
                ),
            ),
        )
        assertEquals(
            "when target is explicit, a (Clean) candidate must not win",
            "explicit",
            results.first().videoId,
        )
    }

    @Test
    fun `clean target demotes explicit version when both candidates present`() {
        val results = scorer.scoreResults(
            targetTitle = "Some Song",
            targetArtist = "Some Artist",
            targetDurationMs = 180_000,
            targetExplicit = false,
            results = listOf(
                candidate(
                    id = "explicit",
                    title = "Some Song (Explicit)",
                    artist = "Some Artist",
                    channel = "Some Artist - Topic",
                    musicVideoType = MusicVideoType.ATV,
                ),
                candidate(
                    id = "clean",
                    title = "Some Song",
                    artist = "Some Artist",
                    channel = "Some Artist - Topic",
                    musicVideoType = MusicVideoType.ATV,
                ),
            ),
        )
        assertEquals("clean", results.first().videoId)
    }

    @Test
    fun `unknown target prefers the explicit variant and still accepts either`() {
        // Legacy rows and YouTube imports often carry no flag. The original is
        // the explicit cut, so a song that doesn't say plays that one, but the
        // preference only orders acceptable candidates: the (Clean) one must
        // still clear auto-accept on its own.
        val results = scorer.scoreResults(
            targetTitle = "Some Song",
            targetArtist = "Some Artist",
            targetDurationMs = 180_000,
            targetExplicit = null,
            results = listOf(
                candidate(
                    id = "explicit",
                    title = "Some Song (Explicit)",
                    artist = "Some Artist",
                    channel = "Some Artist - Topic",
                    musicVideoType = MusicVideoType.ATV,
                    viewCount = 100,
                ),
                candidate(
                    id = "clean",
                    title = "Some Song (Clean)",
                    artist = "Some Artist",
                    channel = "Some Artist - Topic",
                    musicVideoType = MusicVideoType.ATV,
                    viewCount = 100,
                ),
            ),
        )
        assertEquals("explicit", results.first().videoId)
        assertTrue(
            "both candidates must pass auto-accept when target explicit is null",
            results.all { it.matchScore >= MatchScorer.AUTO_ACCEPT_THRESHOLD },
        )
    }

    // ── YouTube Music's explicit badge ───────────────────────────────────
    //
    // A song's explicit and clean twins share title, artist, album and length;
    // only the badge tells them apart. Each test puts the expected loser first,
    // so a tie (decided by input order) fails it.

    private fun twin(id: String, badged: Boolean) = candidate(
        id = id,
        title = "Some Song",
        artist = "Some Artist",
        channel = "Some Artist - Topic",
        durationSec = 180.0,
        musicVideoType = MusicVideoType.ATV,
        album = "Some Album",
        explicit = if (badged) true else null,
    )

    private fun rankTwins(targetExplicit: Boolean?, vararg twins: YtDlpSearchResult) = scorer.scoreResults(
        targetTitle = "Some Song",
        targetArtist = "Some Artist",
        targetDurationMs = 180_000,
        results = twins.toList(),
        targetAlbum = "Some Album",
        targetExplicit = targetExplicit,
    )

    @Test
    fun `a badged twin beats its unbadged twin for an explicit or unknown target`() {
        for (target in listOf(true, null)) {
            val results = rankTwins(target, twin("clean", badged = false), twin("explicit", badged = true))
            assertEquals("target=$target", "explicit", results.first().videoId)
        }
    }

    @Test
    fun `a badged twin loses to its unbadged twin for a clean target`() {
        val results = rankTwins(false, twin("explicit", badged = true), twin("clean", badged = false))
        assertEquals("clean", results.first().videoId)
        assertTrue(
            "the explicit twin stays acceptable: it's the only copy some songs have",
            results.all { it.matchScore >= MatchScorer.AUTO_ACCEPT_THRESHOLD },
        )
    }

    @Test
    fun `the badge never outweighs a much better candidate`() {
        // The badged row names no album; the unbadged one is on the track's album
        // (+0.15), which outweighs the badge (+0.03).
        val results = rankTwins(
            true,
            twin("badged-elsewhere", badged = true).copy(album = null),
            twin("on-the-album", badged = false),
        )
        assertEquals("on-the-album", results.first().videoId)
    }

    @Test
    fun `explicitness never decides whether a candidate is accepted`() {
        // Under the bar on its own (wrong title, wrong uploader): the badge doesn't lift it.
        val weak = candidate(id = "weak", title = "Completely Different Track", artist = "Other Person")
        fun scoreAlone(row: YtDlpSearchResult, target: Boolean?) = scorer.scoreResults(
            "Some Song", "Some Artist", 180_000, listOf(row), targetExplicit = target,
        ).single().matchScore
        val weakScore = scoreAlone(weak, null)
        assertTrue("precondition: $weakScore is under the bar", weakScore < MatchScorer.AUTO_ACCEPT_THRESHOLD)
        assertEquals(weakScore, scoreAlone(weak.copy(explicit = true), null), 0.0001f)

        // Just over the bar on its own (length 78 s off scores 0 there, the hard
        // gate is the pipeline's): a clean target's dock can't push it under.
        val borderline = candidate(id = "borderline", title = "Some Song", artist = "Some Artist")
        val borderlineScore = scoreAlone(borderline, false)
        assertTrue(
            "precondition: $borderlineScore is over the bar by less than the dock",
            borderlineScore >= MatchScorer.AUTO_ACCEPT_THRESHOLD &&
                borderlineScore - MatchScorer.EXPLICIT_MISMATCH_PENALTY < MatchScorer.AUTO_ACCEPT_THRESHOLD,
        )
        val docked = scoreAlone(borderline.copy(explicit = true), false)
        assertTrue("docked $docked < $borderlineScore", docked < borderlineScore)
        assertTrue("docked $docked still accepted", docked >= MatchScorer.AUTO_ACCEPT_THRESHOLD)
    }

    // ── Decorated-title containment escape (titleContainsTarget) ─────────
    //
    // Real field failures from a K-pop/J-pop library download (v0.9.38).
    // The hard title gate (titleSimilarity >= 0.6) rejected these CORRECT
    // matches because it Jaro-Winklers the clean target against the
    // candidate's full *decorated* video title — artist prefix, "(Official
    // Lyric Video)" suffix, 【…】 annotations, OST numbering, or CJK +
    // romanised dual titles. Containment of the (canonicalised) target as a
    // contiguous token run rescues them. NOTE: not a CJK-only bug — the
    // Pasilyo case is pure ASCII Filipino OPM.

    @Test
    fun `titleContainsTarget rescues Pasilyo behind artist prefix and lyric-video suffix`() {
        // titleSimilarity scored 0.58 in the field — official channel, art=1.00.
        assertTrue(
            scorer.titleContainsTarget("Pasilyo", "SunKissed Lola - Pasilyo (Official Lyric Video)"),
        )
    }

    @Test
    fun `titleContainsTarget rescues Ado Readymade behind CJK title and brackets`() {
        // titleSimilarity scored 0.47 — the (Readymade) the gate needs is one
        // canonicalTitle would strip, so containment must keep candidate parens.
        assertTrue(
            scorer.titleContainsTarget("Readymade", "【Ado】レディメイド (Readymade)"),
        )
    }

    @Test
    fun `titleContainsTarget rescues OST numbered title`() {
        // titleSimilarity scored 0.43 for "Duet" vs "OMORI OST - 172 DUET".
        assertTrue(
            scorer.titleContainsTarget("Duet", "OMORI OST - 172 DUET"),
        )
    }

    @Test
    fun `titleContainsTarget rescues feat-decorated target against bare candidate`() {
        // Target carries "(feat. …)"; the official upload leads with the bare
        // native title. Canonicalising the target (drops the feat) then
        // matching its token run inside the candidate rescues it.
        assertTrue(
            scorer.titleContainsTarget("メズマライザー (feat. 初音ミク&重音テト)", "メズマライザー／32ki"),
        )
    }

    @Test
    fun `titleContainsTarget rejects an unrelated piano-arrangement cover`() {
        // Correct rejection: this candidate is a piano-sheet cover, not the
        // track. Containment must NOT rescue it.
        assertFalse(
            scorer.titleContainsTarget("『んっあっあっ。』 (feat. 初音ミク)", "\"Hmm,Ah,Ah.\"　SLAVE.V-V-R"),
        )
    }

    @Test
    fun `titleContainsTarget rejects a different song that merely shares no run`() {
        assertFalse(scorer.titleContainsTarget("Hello", "Goodbye Song"))
    }

    // ── Bilingual / multi-artist uploader escape (artistPartMatches) ─────

    @Test
    fun `artistPartMatches rescues bilingual slash-joined uploader`() {
        // fuzzy artist sim scored 0.62 (< 0.65) for "Kairikibear, flower" vs
        // "かいりきベア／Kairiki bear" — but the romanised half equals a target part
        // once spaces are stripped.
        assertTrue(
            scorer.artistPartMatches("Kairikibear, flower", "かいりきベア／Kairiki bear"),
        )
    }

    @Test
    fun `artistPartMatches strips the Topic suffix before comparing`() {
        assertTrue(scorer.artistPartMatches("Ado", "Ado - Topic"))
    }

    @Test
    fun `artistPartMatches rejects an unrelated cover uploader`() {
        // Correct rejection: piano-arrangement channel, not the artist.
        assertFalse(
            scorer.artistPartMatches("SLAVE.V-V-R", "Niisan【ピアノ楽譜 アレンジ】"),
        )
    }

    // ── Artist-in-title signal (artistAppearsInTitle) ────────────────────
    //
    // Official J-pop/K-pop uploads format the title as "Artist「Song」MV" or
    // "【Artist】Song", so the artist is present in the *title* even when the
    // uploader is a romanised channel name that scores 0 against the CJK
    // artist — the dominant remaining cause of CJK-artist download failures.

    @Test
    fun `artistAppearsInTitle credits CJK artist present in Artist-bracket-Song title`() {
        // 美波「カワキヲアメク」MV uploaded by romanised channel "Minami":
        // artistSimilarity(美波, Minami) = 0.00 tanks the composite below 0.6.
        assertTrue(scorer.artistAppearsInTitle("美波", "美波「カワキヲアメク」MV"))
    }

    @Test
    fun `artistAppearsInTitle credits OMORI present in OST title uploaded by studio`() {
        // "OMORI OST - 172 DUET" by uploader "OMOCAT" — artist "Omori" is in
        // the title even though it disagrees with the uploader.
        assertTrue(scorer.artistAppearsInTitle("Omori", "OMORI OST - 172 DUET"))
    }

    @Test
    fun `artistAppearsInTitle credits cover artist in bracketed title`() {
        assertTrue(
            scorer.artistAppearsInTitle(
                "Will Stetson",
                "Lost Umbrella (English Cover)【Will Stetson feat. Kariyu】「ロストアンブレラ」",
            ),
        )
    }

    @Test
    fun `artistAppearsInTitle rejects a title that does not name the artist`() {
        assertFalse(scorer.artistAppearsInTitle("Adele", "Taylor Swift - Love Story"))
    }

    @Test
    fun `scoreResults lifts a CJK-artist match above auto-accept via artist-in-title`() {
        // Regression guard for the 美波 class: title in the candidate, romanised
        // uploader → without the artist-in-title credit the composite is 0.45
        // and bestMatch returns null. With it, the official MV auto-accepts.
        val results = scorer.scoreResults(
            targetTitle = "カワキヲアメク",
            targetArtist = "美波",
            targetDurationMs = 240_000,
            results = listOf(
                candidate(
                    id = "minami_mv",
                    title = "美波「カワキヲアメク」MV",
                    artist = "Minami",
                    channel = "Minami",
                    durationSec = 240.0,
                    viewCount = 5_000_000,
                ),
            ),
        )
        assertTrue(
            "official MV must auto-accept once the artist-in-title credit applies",
            scorer.bestMatch(results) != null,
        )
    }

    // ── bestMatch's reject line ──────────────────────────────────────────

    @Test
    fun `bestMatch rejects an under-bar row whose title holds a percent sign`() {
        // The reject line's format() pattern held the title, so "% P" threw instead
        // of returning null, and the caller's search stopped there.
        val results = scorer.scoreResults(
            targetTitle = "100% Pure Love",
            targetArtist = "Crystal Waters",
            targetDurationMs = 0,
            results = listOf(
                candidate(id = "karaoke", title = "100% Pure Love (Karaoke)", artist = "Crystal Waters"),
            ),
        )
        assertTrue(
            "premise: the karaoke cut scores under the bar",
            results.single().matchScore < MatchScorer.AUTO_ACCEPT_THRESHOLD,
        )

        assertTrue(
            "an under-bar row must be rejected, not throw, when its title holds a %",
            scorer.bestMatch(results) == null,
        )
    }
}
