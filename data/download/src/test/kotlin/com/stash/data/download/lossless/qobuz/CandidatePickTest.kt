package com.stash.data.download.lossless.qobuz

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.forLibrarySource
import org.junit.Test

/**
 * How a YouTube library row's length is trusted ([forLibrarySource]) and how every source picks
 * among scored candidates ([QobuzCandidateMatcher.pickBest]).
 */
class CandidatePickTest {

    /** [explicit]: the catalog's parental advisory, null when it doesn't say (every test before it). */
    private data class Cand(val name: String, val sec: Int, val explicit: Boolean? = null)

    private fun pick(query: TrackQuery, vararg scored: Pair<Cand, Float>) =
        QobuzCandidateMatcher.pickBest(query, scored.toList(), explicit = { it.explicit }) { it.sec }?.first?.name

    private val q210 = TrackQuery(artist = "A", title = "Song", durationMs = 210_000)

    @Test fun `the higher score wins whatever the length`() {
        assertThat(pick(q210, Cand("far", 280) to 0.9f, Cand("near", 210) to 0.8f)).isEqualTo("far")
    }

    @Test fun `equal scores go to the closest length, not the first listed`() {
        // The review's case: an unlabelled 4:40 album cut listed before the right 3:30 single.
        assertThat(pick(q210, Cand("album cut", 280) to 1.0f, Cand("single", 210) to 1.0f)).isEqualTo("single")
        assertThat(pick(q210, Cand("single", 210) to 1.0f, Cand("album cut", 280) to 1.0f)).isEqualTo("single")
    }

    @Test fun `equal scores and equal gaps keep the first listed`() {
        assertThat(pick(q210, Cand("first", 200) to 1.0f, Cand("second", 220) to 1.0f)).isEqualTo("first")
    }

    @Test fun `an unknown candidate length loses a tie, and no query length keeps the order`() {
        assertThat(pick(q210, Cand("unknown", 0) to 1.0f, Cand("known", 300) to 1.0f)).isEqualTo("known")
        val noLength = q210.copy(durationMs = null)
        assertThat(pick(noLength, Cand("first", 280) to 1.0f, Cand("second", 210) to 1.0f)).isEqualTo("first")
    }

    @Test fun `nothing under the threshold is picked`() {
        assertThat(pick(q210, Cand("weak", 210) to QobuzCandidateMatcher.MIN_CONFIDENCE - 0.01f)).isNull()
        assertThat(pick(q210)).isNull()
    }

    // A song's clean and explicit twins: same title, album and length, so the same score.
    private val clean = Cand("clean", 210, explicit = false)
    private val dirty = Cand("explicit", 210, explicit = true)
    private val unmarked = Cand("unmarked", 210, explicit = null)

    @Test fun `twins go to the explicit one for an explicit or unknown row, whichever is listed first`() {
        for (row in listOf(q210.copy(explicit = true), q210.copy(explicit = null))) {
            assertThat(pick(row, clean to 1.0f, dirty to 1.0f)).isEqualTo("explicit")
            assertThat(pick(row, dirty to 1.0f, clean to 1.0f)).isEqualTo("explicit")
        }
    }

    @Test fun `twins go to the clean one for a row known to be clean`() {
        val row = q210.copy(explicit = false)
        assertThat(pick(row, dirty to 1.0f, clean to 1.0f)).isEqualTo("clean")
        assertThat(pick(row, clean to 1.0f, dirty to 1.0f)).isEqualTo("clean")
    }

    @Test fun `a candidate the catalog doesn't mark is never taken for explicit`() {
        // A clean row takes it over the marked explicit twin; an explicit row takes the marked one.
        assertThat(pick(q210.copy(explicit = false), dirty to 1.0f, unmarked to 1.0f)).isEqualTo("unmarked")
        assertThat(pick(q210.copy(explicit = true), unmarked to 1.0f, dirty to 1.0f)).isEqualTo("explicit")
        // Neither marked explicit: the length and then the order decide, as before.
        assertThat(pick(q210.copy(explicit = true), unmarked to 1.0f, clean to 1.0f)).isEqualTo("unmarked")
        assertThat(pick(q210.copy(explicit = true), clean to 1.0f, unmarked to 1.0f)).isEqualTo("clean")
    }

    @Test fun `a single candidate is picked whatever its explicitness`() {
        for (wanted in listOf(true, false, null)) {
            for (cand in listOf(clean, dirty, unmarked)) {
                assertThat(pick(q210.copy(explicit = wanted), cand to 1.0f)).isEqualTo(cand.name)
            }
        }
    }

    @Test fun `explicitness never beats a higher score`() {
        assertThat(pick(q210.copy(explicit = true), clean to 1.0f, dirty to 0.9f)).isEqualTo("clean")
        assertThat(pick(q210.copy(explicit = true), dirty to 0.9f, clean to 1.0f)).isEqualTo("clean")
        assertThat(pick(q210.copy(explicit = false), dirty to 1.0f, clean to 0.9f)).isEqualTo("explicit")
        // Nor lifts a candidate over the threshold.
        val under = QobuzCandidateMatcher.MIN_CONFIDENCE - 0.01f
        assertThat(pick(q210.copy(explicit = true), dirty to under)).isNull()
        assertThat(pick(q210.copy(explicit = true), dirty to under, clean to 0.6f)).isEqualTo("clean")
    }

    @Test fun `explicitness breaks a tie ahead of length, and float rounding is still a tie`() {
        val row = q210.copy(explicit = true)
        assertThat(pick(row, clean to 1.0f, Cand("explicit", 213, explicit = true) to 1.0f)).isEqualTo("explicit")
        assertThat(pick(row, clean to 1.0f, dirty to 0.99995f)).isEqualTo("explicit")
        // Twins tied on explicitness too: the closest length still wins.
        assertThat(pick(row, Cand("far", 230, explicit = true) to 1.0f, Cand("near", 211, explicit = true) to 1.0f)).isEqualTo("near")
    }

    private fun yt(type: String? = null, album: String? = null, artist: String = "Some Channel", title: String = "Artist - Song (Official Video)") =
        TrackQuery(artist = artist, title = title, album = album, durationMs = 210_000).forLibrarySource(MusicSource.YOUTUBE, type)

    @Test fun `an official audio upload keeps its length, a video does not`() {
        assertThat(yt(type = "ATV").durationReliable).isTrue()
        assertThat(yt(type = "OMV", album = "Album").durationReliable).isFalse() // the stored class wins over the album
        assertThat(yt(type = "UGC").durationReliable).isFalse()
        // Never classified: YouTube Music gives songs an album and videos none.
        assertThat(yt(album = "Album").durationReliable).isTrue()
        assertThat(yt().durationReliable).isFalse()
        assertThat(yt(artist = "Artist - Topic", title = "Song").durationReliable).isTrue()
        // A clean title alone is not enough: the artist's own music video often reads "Song" by "Artist".
        assertThat(yt(artist = "Artist", title = "Song").durationReliable).isFalse()
    }

    @Test fun `a mix song with no video takes its Last fm length as a hint, so the album cut wins`() {
        // The owner's "Shook Ones, Pt. II" (2026-10-26): a mix row with Last.fm's 241 s radio edit and
        // its compilation album, no video id. The album used to make the length count as the song's.
        val stub = TrackQuery(
            artist = "Mobb Deep",
            title = "Shook Ones, Pt. II",
            album = "Reminisce Hip Hop Classics",
            durationMs = 241_000,
        ).forLibrarySource(MusicSource.YOUTUBE, null, hasVideoId = false)
        assertThat(stub.durationReliable).isFalse()
        fun score(q: TrackQuery, sec: Int) = QobuzCandidateMatcher.confidence(q, "Shook Ones, Pt. II", "Mobb Deep", null, sec, true)
        // The 326 s album cut, 35% longer than Last.fm says, still passes; before, it didn't.
        assertThat(score(stub, 326)).isAtLeast(QobuzCandidateMatcher.MIN_CONFIDENCE)
        val asBefore = stub.copy(durationReliable = true)
        assertThat(score(asBefore, 326)).isLessThan(QobuzCandidateMatcher.MIN_CONFIDENCE)
        // Against a same-titled clean edit of Last.fm's length, the explicit album cut wins.
        val edit = Cand("radio edit", 241, explicit = false)
        val album = Cand("album cut", 326, explicit = true)
        assertThat(pick(stub, edit to score(stub, edit.sec), album to score(stub, album.sec))).isEqualTo("album cut")
        // The same row once matched has a video, and the album reads as YouTube Music's again.
        assertThat(stub.copy(durationReliable = true).forLibrarySource(MusicSource.YOUTUBE, null, hasVideoId = true).durationReliable)
            .isTrue()
    }

    @Test fun `other library rows are left as they are`() {
        val spotify = TrackQuery(artist = "Artist", title = "Song (Official Video)", durationMs = 210_000)
        assertThat(spotify.forLibrarySource(MusicSource.SPOTIFY, "OMV")).isEqualTo(spotify)
    }

    @Test fun `an official audio row keeps the strict length rule against a longer album cut`() {
        // A 3:30 ATV single: the 4:40 album cut is listed first and names no version.
        fun score(q: TrackQuery, sec: Int) = QobuzCandidateMatcher.confidence(q, "Song", "Artist", null, sec, true)
        val atv = TrackQuery(artist = "Artist", title = "Song", album = "Song - Single", durationMs = 210_000)
            .forLibrarySource(MusicSource.YOUTUBE, "ATV")
        val albumCut = Cand("album cut", 280)
        val single = Cand("single", 210)
        assertThat(score(atv, albumCut.sec)).isLessThan(score(atv, single.sec))
        assertThat(QobuzCandidateMatcher.pickBest(atv, listOf(albumCut to score(atv, albumCut.sec), single to score(atv, single.sec))) { it.sec }?.first)
            .isEqualTo(single)
        // A music video of the same song scores both alike, and its length (the video's) breaks no
        // tie: the catalog's order stands, as it does with no length at all.
        val video = TrackQuery(artist = "Artist", title = "Song", durationMs = 210_000).forLibrarySource(MusicSource.YOUTUBE, "OMV")
        assertThat(score(video, albumCut.sec)).isEqualTo(score(video, single.sec))
        assertThat(QobuzCandidateMatcher.pickBest(video, listOf(albumCut to score(video, albumCut.sec), single to score(video, single.sec))) { it.sec }?.first)
            .isEqualTo(albumCut)
    }
}
