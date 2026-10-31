package com.stash.feature.record.archive

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.weblink.SongKey
import org.junit.Test

/**
 * The web's own vectors, so the phone and the web make the same rows with the same ids: stash-player
 * `src/lib/identity.test.ts` (the words a play's id is made of) and `src/lib/spotify/phone.test.ts` (the phone's history read
 * from the library file, and planPhone).
 */
class PhoneRowsTest {
    private val now = 1_792_756_800_000L // 2026-10-23T12:00:00Z
    private val t = 1_790_856_000_000L // Date.UTC(2026, 9, 1, 12)

    private fun key(title: String, artist: String = "Artist") = SongKey.textKey(title, artist)

    // ── identity.test.ts ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `parenthetical titles stay distinct (the reviewer cases)`() {
        assertThat(key("Dance (Without You)")).isNotEqualTo(key("Dance"))
        assertThat(key("Stay (Alive)")).isNotEqualTo(key("Stay"))
        assertThat(key("Song (Olive Tree)")).isNotEqualTo(key("Song (Deliver Me)"))
        assertThat(key("Song (Olive Tree)")).isNotEqualTo(key("Song"))
        assertThat(key("Song (Withdrawn)")).isNotEqualTo(key("Song"))
        assertThat(key("Song (Liver Version)")).isNotEqualTo(key("Song"))
    }

    @Test fun `live, remaster and version recordings are not the studio one`() {
        for (v in listOf("(Live)", "(Remastered 2011)", "(Acoustic Version)", "[Live at Wembley]", "(Remaster)")) {
            assertThat(key("Song $v")).isNotEqualTo(key("Song"))
        }
        assertThat(key("Song (Live)")).isNotEqualTo(key("Song (Remaster)"))
    }

    @Test fun `only a bracketed featuring credit folds away, as a whole word`() {
        assertThat(key("Song (feat. Someone)")).isEqualTo(key("Song"))
        assertThat(key("Song [ft. Someone]")).isEqualTo(key("Song"))
        assertThat(key("Song (Featuring Someone)")).isEqualTo(key("Song"))
        assertThat(key("Song (Featherweight)")).isNotEqualTo(key("Song"))
        assertThat(key("Song (Ftw)")).isNotEqualTo(key("Song"))
        assertThat(key("Song", "Artist (feat. B)")).isEqualTo(key("Song", "Artist"))
    }

    @Test fun `case, accents and punctuation don't count, and nothing merges inside words`() {
        assertThat(key("Café   Del-Mar!")).isEqualTo(key("cafe del mar"))
        assertThat(key("Under")).isNotEqualTo(key("Un der"))
        assertThat(key("Song", "Mithoon")).isNotEqualTo(key("Song", "Mithoon Kumar"))
    }

    @Test fun `a play's id is the folded words and the start`() {
        assertThat(playId("Pink + White", "Frank Ocean", 1_759_838_400_000)).isEqualTo("pink white|frank ocean|1759838400000")
    }

    // ── phone.test.ts: the library file's listens, read as untrusted input ─────────────────────────────────────────────

    private fun play(t: String, a: String, s: Long, al: String? = null, ms: Long? = null, d: Long? = null) =
        PhoneRows.read(t, a, al, s, ms, d, now, skip = false)

    @Test fun `reads a library file's listens as untrusted input`() {
        assertThat(play("Song", "Band", t, al = "LP", ms = 120_000, d = 200_000)).isEqualTo(PhonePlay("Song", "Band", "LP", t, 120_000, 200_000))
        assertThat(play("Guess", "Band", t + 1)).isEqualTo(PhonePlay("Guess", "Band", s = t + 1))
        assertThat(play("", "Band", t)).isNull()
        assertThat(play("Future", "Band", now + 9 * 86_400_000L)).isNull()
        // up to a day: a long mix is real
        assertThat(play("Long", "Band", t, ms = 99 * 3_600_000L)).isEqualTo(PhonePlay("Long", "Band", s = t, ms = 24 * 3_600_000L))
        // a skip needs to say how far it got
        assertThat(PhoneRows.read("S", "B", null, t, null, null, now, skip = true)).isNull()
    }

    @Test fun `times before 2006 and past two days from now are broken rows`() {
        assertThat(play("Old", "B", PhoneRows.MIN_T - 1)).isNull()
        assertThat(play("Old", "B", PhoneRows.MIN_T)).isNotNull()
        assertThat(play("Soon", "B", now + 2 * 86_400_000L)).isNotNull()
        assertThat(play("Soon", "B", now + 2 * 86_400_000L + 1)).isNull()
    }

    @Test fun `words lose their control characters, are trimmed as JavaScript trims, and cut to 300 UTF-16 units`() {
        assertThat(PhoneRows.str("  A\u0000B\tC  ")).isEqualTo("A B C")
        assertThat(PhoneRows.str("﻿  ")).isNull()
        assertThat(PhoneRows.str(null)).isNull()
        // 299 letters, then a pair cut in half at 300: kept as the web keeps it (slice cuts units, not characters)
        val cut = PhoneRows.str("a".repeat(299) + "🎵" + "b")!!
        assertThat(cut.length).isEqualTo(300)
        assertThat(cut.last()).isEqualTo('\uD83C')
        // the cut comes after the trim: a space left at the end stays
        assertThat(PhoneRows.str("a".repeat(299) + " b")).endsWith(" ")
        // a length that isn't positive and within a day is no length
        assertThat(play("D", "B", t, d = 0)?.d).isNull()
        assertThat(play("D", "B", t, d = 25 * 3_600_000L)?.d).isNull()
        assertThat(play("M", "B", t, ms = -1)?.ms).isNull()
        assertThat(play("A", "B", t, al = " \u0007 ")?.al).isNull()
    }

    @Test fun `keeps plays and skips once, with their time, the song's length or a guess`() {
        val p = PhoneRows.planPhone(
            listOf(PhonePlay("Timed", "B", s = t, ms = 90_000, d = 200_000), PhonePlay("Length", "B", s = t + 1, d = 200_000), PhonePlay("Guess", "B", s = t + 2)),
            listOf(PhonePlay("Skip", "B", s = t + 3, ms = 5000)),
        )
        assertThat(p.map { Triple(it.t, it.ms, it.f) }).containsExactly(
            Triple("Timed", 90_000L, RecordFlags.STASH),
            Triple("Length", 200_000L, RecordFlags.STASH or RecordFlags.ESTIMATED),
            Triple("Guess", GUESS_MS, RecordFlags.STASH or RecordFlags.ESTIMATED),
            Triple("Skip", 5000L, RecordFlags.STASH or RecordFlags.SKIPPED),
        ).inOrder()
        // never DONE: the phone doesn't say how a play ended
        assertThat(p.none { it.f and RecordFlags.DONE != 0 }).isTrue()
    }

    @Test fun `one id once, a play over a skip, the first by start then title`() {
        val p = PhoneRows.planPhone(
            plays = listOf(PhonePlay("song", "B", s = t, ms = 1), PhonePlay("Song", "B", s = t, ms = 2), PhonePlay("Other", "B", s = t - 5, ms = 3)),
            skips = listOf(PhonePlay("SONG", "b", s = t, ms = 4), PhonePlay("Skipped", "B", s = t + 9, ms = 5)),
        )
        // "Song" < "song" in UTF-16 order, so the capitalised one is kept; the skip with the same words and start goes
        assertThat(p.map { it.t to it.ms }).containsExactly("Other" to 3L, "Song" to 2L, "Skipped" to 5L).inOrder()
        assertThat(p.map { it.id }).containsNoDuplicates()
    }

    @Test fun `an album that cleans to nothing is no album`() {
        assertThat(PhoneRows.phoneRow(PhonePlay("T", "A", al = "", s = t), skip = false).al).isNull()
        assertThat(PhoneRows.phoneRow(PhonePlay("T", "A", al = "LP", s = t), skip = false).al).isEqualTo("LP")
    }
}
