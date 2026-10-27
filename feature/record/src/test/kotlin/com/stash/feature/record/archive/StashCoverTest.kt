package com.stash.feature.record.archive

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.weblink.SongKey
import org.junit.Test

/** Which scrobbles this phone's own plays cover (spec §7 "Stash over Last.fm"): the 180 s rule, by folded title, never by a skip. */
class StashCoverTest {
    private fun row(t: String, a: String, s: Long, ms: Long, f: Int) = ArchivePlay("${SongKey.textKey(t, a)}|$s", t, a, s = s, ms = ms, f = f)
    private fun scrobble(t: String, s: Long, a: String = "Band") = row(t, a, s, 210_000, RecordFlags.LASTFM or RecordFlags.ESTIMATED)

    private val start = 1_800_000_000_000L
    private val play = row("Here Song", "Stash Artist", start, 180_000, RecordFlags.STASH)
    private val skip = row("Skipped", "Stash Artist", start + 3_600_000, 20_000, RecordFlags.STASH or RecordFlags.SKIPPED)
    private val cover = StashCover.Builder().apply {
        add(play)
        add(skip)
        // not this phone's: never covers
        add(row("Spotify Song", "X", start, 200_000, RecordFlags.REASONS or RecordFlags.DONE))
    }.build()

    @Test fun `a scrobble 180 s before the start or after the end is the play, a millisecond more is not`() {
        assertThat(cover.covers(scrobble("Here Song", start - 180_000))).isTrue()
        assertThat(cover.covers(scrobble("Here Song", start - 180_001))).isFalse()
        assertThat(cover.covers(scrobble("Here Song", start + 180_000 + 180_000))).isTrue()
        assertThat(cover.covers(scrobble("Here Song", start + 180_000 + 180_001))).isFalse()
        assertThat(cover.covers(scrobble("Here Song", start + 60_000))).isTrue()
    }

    @Test fun `titles folded alike, whoever the artist`() {
        assertThat(cover.covers(scrobble("HERE song!", start + 1_000, a = "Someone Else"))).isTrue()
        assertThat(cover.covers(scrobble("Here Songs", start + 1_000))).isFalse()
    }

    @Test fun `a skip covers nothing, but a scrobble with its very id is left out (the record keeps one row an id)`() {
        assertThat(cover.covers(scrobble("Skipped", skip.s + 5_000, a = "Stash Artist"))).isFalse()
        assertThat(cover.covers(scrobble("Skipped", skip.s, a = "Stash Artist"))).isTrue()
        assertThat(cover.covers(scrobble("Skipped", skip.s, a = "Another Artist"))).isFalse()
    }

    @Test fun `only this phone's plays cover`() {
        assertThat(cover.covers(scrobble("Spotify Song", start + 1_000))).isFalse()
        assertThat(StashCover.NONE.covers(scrobble("Here Song", start))).isFalse()
    }
}
