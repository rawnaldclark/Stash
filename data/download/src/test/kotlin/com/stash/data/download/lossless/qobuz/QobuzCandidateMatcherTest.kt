package com.stash.data.download.lossless.qobuz

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.TrackQuery
import org.junit.Test

/**
 * Characterization tests for [QobuzCandidateMatcher] — the scorer extracted
 * (behavior-preserving) out of [QobuzSource]. These pin the EXACT scoring the
 * old `QobuzSource.confidence` produced so the qbdlx source can reuse it.
 *
 * The helper primitives (`normalize`/`jaccard`/`artistSimilarity`) keep their
 * own coverage in [QobuzSourceTest] via the delegating shims, so they aren't
 * re-tested here.
 */
class QobuzCandidateMatcherTest {

    @Test fun `ISRC match short-circuits to 0_95`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(
                artist = "John Frusciante",
                title = "Murderers",
                isrc = "USWB10003085",
                durationMs = 160_000,
            ),
            candTitle = "Murderers",
            candArtist = "John Frusciante",
            candIsrc = "USWB10003085",
            candDurationSec = 160,
            candStreamable = true,
        )
        assertThat(score).isEqualTo(0.95f)
    }

    @Test fun `ISRC match is case-insensitive`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(artist = "x", title = "y", isrc = "uswb10003085"),
            candTitle = "totally different",
            candArtist = "someone else",
            candIsrc = "USWB10003085",
            candDurationSec = 0,
            candStreamable = true,
        )
        assertThat(score).isEqualTo(0.95f)
    }

    @Test fun `perfect title+artist+duration agreement scores 1_0`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(
                artist = "John Frusciante",
                title = "Murderers",
                durationMs = 160_000,
            ),
            candTitle = "Murderers",
            candArtist = "John Frusciante",
            candIsrc = null,
            candDurationSec = 160,
            candStreamable = true,
        )
        // titleSim 1.0 * artistSim 1.0 * durationFactor 1.0
        assertThat(score).isEqualTo(1.0f)
    }

    @Test fun `dramatic duration mismatch downweights to 0_3`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(
                artist = "John Frusciante",
                title = "Murderers",
                durationMs = 160_000,
            ),
            candTitle = "Murderers",
            candArtist = "John Frusciante",
            candIsrc = null,
            candDurationSec = 200, // 25% drift → 0.3 factor
            candStreamable = true,
        )
        assertThat(score).isWithin(0.0001f).of(0.3f)
    }

    @Test fun `non-streamable candidate scores 0`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(
                artist = "John Frusciante",
                title = "Murderers",
                isrc = "USWB10003085",
                durationMs = 160_000,
            ),
            candTitle = "Murderers",
            candArtist = "John Frusciante",
            candIsrc = "USWB10003085",
            candDurationSec = 160,
            candStreamable = false,
        )
        assertThat(score).isEqualTo(0f)
    }

    @Test fun `unknown query duration skips the duration penalty`() {
        val score = QobuzCandidateMatcher.confidence(
            query = TrackQuery(artist = "John Frusciante", title = "Murderers", durationMs = null),
            candTitle = "Murderers",
            candArtist = "John Frusciante",
            candIsrc = null,
            candDurationSec = 999,
            candStreamable = true,
        )
        // durationFactor forced to 1.0 → 1.0 * 1.0 * 1.0
        assertThat(score).isEqualTo(1.0f)
    }

    @Test fun `MIN_CONFIDENCE threshold value is preserved`() {
        assertThat(QobuzCandidateMatcher.MIN_CONFIDENCE).isEqualTo(0.5f)
    }

    // ── #502: a named version the query lacks ──────────────────────────────

    private fun score(
        queryTitle: String,
        candTitle: String,
        candVersion: String? = null,
        queryDurationMs: Long? = null,
        candDurationSec: Int = 0,
        album: String? = null,
    ) = QobuzCandidateMatcher.confidence(
        query = TrackQuery(artist = "Sea of Thieves", title = queryTitle, album = album, durationMs = queryDurationMs),
        candTitle = candTitle,
        candArtist = "Sea of Thieves",
        candIsrc = null,
        candDurationSec = candDurationSec,
        candStreamable = true,
        candVersion = candVersion,
    )

    @Test fun `Bosun Bill Retro Mix in the version field no longer passes for the original`() {
        // Was 1.0 * 1.0 * 0.6 = 0.6, which downloaded the remix.
        val s = score("Bosun Bill", "Bosun Bill", candVersion = "Retro Mix", queryDurationMs = 144_000, candDurationSec = 117)
        assertThat(s).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    @Test fun `Bosun Bill Retro Mix in the title no longer passes for the original`() {
        val s = score("Bosun Bill", "Bosun Bill (Retro Mix)", queryDurationMs = 144_000, candDurationSec = 117)
        assertThat(s).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    @Test fun `a version named only in the version field is penalized`() {
        assertThat(score("bloody valentine", "bloody valentine", candVersion = "Acoustic"))
            .isWithin(0.0001f).of(0.4f)
    }

    @Test fun `a remaster still matches the original`() {
        assertThat(score("Song", "Song (Remastered 2011)")).isEqualTo(1.0f)
        assertThat(score("Song", "Song", candVersion = "2011 Remaster")).isEqualTo(1.0f)
    }

    @Test fun `an original mix still matches the original`() {
        assertThat(score("Song", "Song (Original Mix)")).isEqualTo(1.0f)
    }

    @Test fun `a version the query also names is not penalized`() {
        assertThat(score("Song - Live", "Song (Live)")).isAtLeast(QobuzCandidateMatcher.MIN_CONFIDENCE)
        assertThat(score("Song (Live)", "Song - Live")).isAtLeast(QobuzCandidateMatcher.MIN_CONFIDENCE)
        assertThat(score("Bosun Bill (Retro Mix)", "Bosun Bill", candVersion = "Retro Mix")).isEqualTo(1.0f)
    }

    @Test fun `a live album lets a live candidate through`() {
        assertThat(score("Young Man Blues", "Young Man Blues", candVersion = "Live", album = "Live at Leeds"))
            .isEqualTo(1.0f)
    }

    @Test fun `version words match whole words only`() {
        assertThat(QobuzCandidateMatcher.versionWords("Alive (Stay Alive) - Olive Remixed")).isEmpty()
        assertThat(score("Stay Alive", "Stay Alive (Alive)", candVersion = "Alive")).isEqualTo(1.0f)
    }
}
