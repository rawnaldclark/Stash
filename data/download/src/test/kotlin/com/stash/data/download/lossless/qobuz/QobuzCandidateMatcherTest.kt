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

    @Test fun `a year mix remaster still matches the remastered original`() {
        val s = score("Come Together - Remastered 2009", "Come Together (2019 Mix)")
        assertThat(s).isAtLeast(QobuzCandidateMatcher.MIN_CONFIDENCE)
        assertThat(score("Come Together - Remastered 2009", "Come Together", candVersion = "2019 Mix"))
            .isAtLeast(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    @Test fun `a stereo mono or single mix still matches the original`() {
        assertThat(score("Song", "Song (Stereo Mix)")).isEqualTo(1.0f)
        assertThat(score("Song", "Song (Mono Mix)")).isEqualTo(1.0f)
        assertThat(score("Song", "Song", candVersion = "Single Mix")).isEqualTo(1.0f)
        // The dashed form names no version either (its title score is lower for other reasons).
        assertThat(QobuzCandidateMatcher.versionWords("Song - Single Mix")).isEmpty()
    }

    @Test fun `a club mix or year remix is still rejected`() {
        assertThat(score("Song", "Song (Club Mix)")).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
        assertThat(score("Song", "Song", candVersion = "2019 Remix")).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    @Test fun `the version words the library shares are rejected too`() {
        // One list with the library's same-song rule (VersionWords): a re-recording, an unplugged
        // set or a session take is not the song the query asked for.
        assertThat(score("Song", "Song (Taylor's Version)")).isWithin(0.0001f).of(0.4f)
        assertThat(score("Song", "Song", candVersion = "MTV Unplugged")).isWithin(0.0001f).of(0.4f)
        assertThat(score("Song", "Song", candVersion = "BBC Session")).isWithin(0.0001f).of(0.4f)
        assertThat(score("Song (Taylor's Version)", "Song (Taylor's Version)")).isEqualTo(1.0f)
    }

    @Test fun `a live album whose titles don't say live keeps the live cut when the length confirms it`() {
        // KISS "Deuce" off "Alive!" (217 s). Neither the title nor the album says "live".
        val query = TrackQuery(artist = "KISS", title = "Deuce", album = "Alive!", durationMs = 217_000)
        fun cand(version: String?, sec: Int, album: String?) = QobuzCandidateMatcher.confidence(
            query, "Deuce", "KISS", candIsrc = null, candDurationSec = sec, candStreamable = true, candVersion = version,
            candAlbum = album,
        )
        val live = cand("Live", 218, "Alive!")
        val studio = cand(null, 185, "KISS")
        assertThat(live).isWithin(0.0001f).of(0.9f)
        assertThat(live).isGreaterThan(studio)
    }

    @Test fun `a length-matched live take from another album is still rejected`() {
        // Björk "Pagan Poetry" (Vespertine) vs a live take that runs the same length: the
        // matching length alone used to let it through (0.9) when no studio cut was listed.
        val query = TrackQuery(artist = "Björk", title = "Pagan Poetry", album = "Vespertine", durationMs = 314_000)
        val live = QobuzCandidateMatcher.confidence(
            query, "Pagan Poetry", "Björk", candIsrc = null, candDurationSec = 315, candStreamable = true,
            candVersion = "Live", candAlbum = "Live at Royal Opera House",
        )
        assertThat(live).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
        // Nor does a live edition of the same album count as the user's release.
        val liveEdition = QobuzCandidateMatcher.confidence(
            query, "Pagan Poetry", "Björk", candIsrc = null, candDurationSec = 330, candStreamable = true,
            candVersion = "Live", candAlbum = "Vespertine (Live)",
        )
        assertThat(liveEdition).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    @Test fun `a length more than 5 percent off still rejects the version`() {
        // 8% off is not confirmation (Bosun Bill itself, 19% off, is covered above).
        val s = score("Bosun Bill", "Bosun Bill", candVersion = "Retro Mix", queryDurationMs = 144_000, candDurationSec = 133)
        assertThat(s).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    @Test fun `with equal lengths the unversioned candidate still wins`() {
        val plain = score("Song", "Song", queryDurationMs = 200_000, candDurationSec = 200)
        val live = score("Song", "Song", candVersion = "Live", queryDurationMs = 200_000, candDurationSec = 200)
        assertThat(plain).isGreaterThan(live)
        // No album to confirm it is the user's release, so the version rule rejects it.
        assertThat(live).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    @Test fun `a Spotify dash suffix matches the catalog title with the version kept apart`() {
        assertThat(score("Disorder - 2007 Remaster", "Disorder", candVersion = "2007 Remaster")).isEqualTo(1.0f)
        assertThat(score("Dreams - 2004 Remaster", "Dreams")).isEqualTo(1.0f) // reissue words alone
        assertThat(score("Breathe Me - Ulrich Schnauss Remix", "Breathe Me", candVersion = "Ulrich Schnauss Remix")).isEqualTo(1.0f)
        assertThat(score("Silver - Robot Baby Mix", "Silver (Robot Baby Mix)")).isEqualTo(1.0f)
        assertThat(score("Silver - Robot Baby Mix", "Silver", candVersion = "(Robot Baby Mix)")).isEqualTo(1.0f) // bracketed version field
    }

    @Test fun `a dash suffix the candidate does not explain is not set aside`() {
        // "Part 1" vs "Part 2", and a remix the candidate doesn't name, keep the plain title score.
        assertThat(score("Song - Part 1", "Song - Part 2")).isAtMost(0.5f) // plain title score, not raised to 1.0
        assertThat(score("Song - Club Mix", "Song")).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    @Test fun `a version the query names but the candidate lacks is penalized`() {
        // "Song (Live)" took the studio "Song" at 1.0 and "Song - Live" at 0.5, even at equal lengths.
        assertThat(score("Song - Live", "Song", queryDurationMs = 200_000, candDurationSec = 200)).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
        assertThat(score("Song (Live)", "Song", queryDurationMs = 200_000, candDurationSec = 200)).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    @Test fun `a YouTube row's video length does not reject the album cut`() {
        // "She Talks to Angels": the library row is the video (~23% longer than the album cut).
        fun yt(querySec: Long, candSec: Int, version: String? = null, reliable: Boolean = false) = QobuzCandidateMatcher.confidence(
            query = TrackQuery(artist = "The Black Crowes", title = "She Talks to Angels", durationMs = querySec * 1000, durationReliable = reliable),
            candTitle = "She Talks To Angels", candArtist = "The Black Crowes", candIsrc = null,
            candDurationSec = candSec, candStreamable = true, candVersion = version,
        )
        assertThat(yt(390, 300)).isEqualTo(1.0f)
        assertThat(yt(390, 300, reliable = true)).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE) // Spotify rows keep the strict rule
        // A gross gap still counts: a 12-minute jam is not the 4-minute song.
        assertThat(yt(240, 720)).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
        // And a matching YouTube length never confirms a version the query doesn't name.
        assertThat(yt(325, 326, version = "Live")).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    private fun byArtist(queryArtist: String, candArtist: String, candTitle: String = "Song", candVersion: String? = null, querySec: Long = 200, candSec: Int = 200) =
        QobuzCandidateMatcher.confidence(
            query = TrackQuery(artist = queryArtist, title = "Song", durationMs = querySec * 1000),
            candTitle = candTitle, candArtist = candArtist, candIsrc = null, candDurationSec = candSec,
            candStreamable = true, candVersion = candVersion,
        )

    @Test fun `an all-bracket artist name still matches itself`() {
        assertThat(byArtist("[bsd.u]", "[bsd.u]")).isEqualTo(1.0f)
    }

    @Test fun `a short lead artist credited alone on the catalog matches the full credit`() {
        assertThat(byArtist("Sia, Spencer Brown", "Sia")).isEqualTo(1.0f)
        assertThat(byArtist("Air, Beth Hirsch", "Air")).isEqualTo(1.0f)
        // Not a lead: a short name elsewhere in the credit still earns nothing extra.
        assertThat(byArtist("Beth Hirsch, Air", "Air")).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
    }

    @Test fun `dotted and dashed names compare joined up`() {
        assertThat(byArtist("Run–D.M.C., Jason Nevins", "Run-DMC & Jason Nevins")).isEqualTo(1.0f)
    }

    @Test fun `a named single or radio edit only passes when the length confirms it`() {
        // Gladys Knight: the library's album cut is 4:39, the catalog's Single Version 3:55.
        assertThat(byArtist("A", "A", candVersion = "Single Version", querySec = 279, candSec = 235)).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
        assertThat(byArtist("A", "A", candTitle = "Song (Radio Edit)", querySec = 279, candSec = 230)).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
        assertThat(byArtist("A", "A", candVersion = "Single Version", querySec = 235, candSec = 235)).isEqualTo(1.0f)
        assertThat(byArtist("A", "A", candVersion = "Album Version", querySec = 279, candSec = 280)).isEqualTo(1.0f)
    }

    @Test fun `accents fold on both sides, including decomposed catalog text`() {
        val decomposed = "Molécules" // e + combining acute, as some catalogs store it
        assertThat(score("Molécules", decomposed)).isEqualTo(1.0f)
        assertThat(QobuzCandidateMatcher.normalize("Gautier Capuçon")).isEqualTo(QobuzCandidateMatcher.normalize("Gautier Capucon"))
        assertThat(QobuzCandidateMatcher.normalize("Rodríguez")).isEqualTo("rodriguez")
    }

    @Test fun `an ISRC match ignores the version`() {
        val s = QobuzCandidateMatcher.confidence(
            query = TrackQuery(artist = "KISS", title = "Deuce", isrc = "USPR37500001"),
            candTitle = "Deuce",
            candArtist = "KISS",
            candIsrc = "USPR37500001",
            candDurationSec = 0,
            candStreamable = true,
            candVersion = "Live",
        )
        assertThat(s).isEqualTo(0.95f)
    }

    @Test fun `an ISRC match beats a length-confirmed version`() {
        val query = TrackQuery(artist = "KISS", title = "Deuce", isrc = "USPR37500001", durationMs = 217_000)
        fun cand(isrc: String, version: String?) = QobuzCandidateMatcher.confidence(
            query, "Deuce", "KISS", candIsrc = isrc, candDurationSec = 217, candStreamable = true, candVersion = version,
        )
        assertThat(cand("USPR37500001", null)).isGreaterThan(cand("USPR37699999", "Live"))
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
