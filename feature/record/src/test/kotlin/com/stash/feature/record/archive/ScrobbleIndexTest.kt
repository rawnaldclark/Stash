package com.stash.feature.record.archive

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.weblink.SongKey
import org.junit.Test

/** The Home card's change check for the scrobbles this phone's plays cover ([ScrobbleIndex.covering]). */
class ScrobbleIndexTest {
    private val start = 1_800_000_000_000L

    private fun scrobble(t: String, s: Long) = ArchivePlay("${SongKey.textKey(t, "Band")}|$s", t, "Band", s = s, ms = 210_000, f = RecordFlags.LASTFM or RecordFlags.ESTIMATED)

    private fun play(t: String, s: Long) = ArchivePlay("${SongKey.textKey(t, "Band")}|$s", t, "Band", s = s, ms = 200_000, f = RecordFlags.STASH)

    private val scrobbles = (0 until 96).map { scrobble("Song $it", start + it * 3_600_000L) }
    private val index = ScrobbleIndex.Builder().apply { scrobbles.forEach(::add) }.build()

    /** What a single play of scrobble [i]'s song, at its time, covers. */
    private fun coveringOf(i: Int) = index.covering(StashCover.Builder().apply { add(play("Song $i", scrobbles[i].s)) }.build())

    @Test fun `two plays covering different scrobbles are told apart, even where a 32-bit hash of the set is the same`() {
        val at = (0 until 96).associateBy { coveringOf(it).positions.nextSetBit(0) }
        // The scrobbles at positions 0 and 32 (one 64-bit word): a BitSet's hashCode folds the two sets to one number
        val a = at.getValue(0)
        val b = at.getValue(32)
        val ca = coveringOf(a)
        val cb = coveringOf(b)
        assertThat(ca.positions.hashCode()).isEqualTo(cb.positions.hashCode())
        assertThat(ca.positions.cardinality()).isEqualTo(cb.positions.cardinality())
        assertThat(ca).isNotEqualTo(cb)
    }

    @Test fun `the same plays cover the same, and a skip claiming a scrobble's id counts`() {
        assertThat(coveringOf(3)).isEqualTo(coveringOf(3))
        val skip = scrobbles[5].copy(f = RecordFlags.STASH or RecordFlags.SKIPPED)
        val withSkip = index.covering(StashCover.Builder().apply { add(skip) }.build())
        assertThat(withSkip.positions.isEmpty).isTrue()
        assertThat(withSkip.claimed).hasSize(1)
        assertThat(withSkip).isNotEqualTo(index.covering(StashCover.NONE))
    }
}
