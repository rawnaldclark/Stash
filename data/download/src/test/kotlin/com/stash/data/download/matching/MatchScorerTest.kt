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
    fun `unknown target explicit does not penalize either variant`() {
        // For legacy pre-v12 rows and YouTube-imported tracks where the
        // explicit flag is null, neither (Clean) nor (Explicit) titles
        // should be penalised — we don't know what the user wanted.
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
        // Both candidates should auto-accept — gate passes for either when
        // the target has no explicit signal.
        assertTrue(
            "both candidates must pass auto-accept when target explicit is null",
            scorer.bestMatch(results) != null,
        )
    }
}
