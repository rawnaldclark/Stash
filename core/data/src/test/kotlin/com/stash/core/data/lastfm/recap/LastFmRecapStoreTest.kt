package com.stash.core.data.lastfm.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.genre.WeightedGenre
import com.stash.core.data.lastfm.LastFmScrobble
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.YearMonth

class LastFmRecapStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = LastFmRecapStore(tmp.root)

    @Test fun `months, counts and catalog caches round-trip`() {
        val s = store()
        val month = MonthFile("Europe/Paris", 42L, listOf(LastFmScrobble(1, "A", "T", "Al", "https://x/i.jpg"), LastFmScrobble(2, "B", "U")))
        s.writeMonth("RawnClark", YearMonth.of(2026, 10), month)
        s.writeCounts("rawnclark", CountsFile("Europe/Paris", 1776526202L, spans = mapOf("2026-10" to SpanCount(939, 7L))))
        s.writeDurations(DurationsFile(mapOf("t|a" to CachedValue(201_000L, 3L))))
        s.writeGenres(GenresFile(mapOf("a" to CachedValue(listOf(WeightedGenre("rock", 1f)), 4L))))

        // A fresh instance reads what the last one wrote; the username's case doesn't matter.
        val again = store()
        assertThat(again.readMonth("rawnclark", YearMonth.of(2026, 10))).isEqualTo(month)
        assertThat(again.readMonth("rawnclark", YearMonth.of(2026, 9))).isNull()
        assertThat(again.readCounts("RAWNCLARK")!!.spans["2026-10"]!!.total).isEqualTo(939)
        assertThat(again.readDurations().songs["t|a"]!!.value).isEqualTo(201_000L)
        assertThat(again.readGenres().artists["a"]!!.value.single().genre).isEqualTo("rock")
    }

    @Test fun `an unreadable file is a cache miss, not a crash`() {
        val s = store()
        s.writeCounts("u", CountsFile("UTC"))
        tmp.root.resolve("u/u/counts.json").writeText("{ not json")
        assertThat(s.readCounts("u")).isNull()
        tmp.root.resolve("durations.json").writeText("garbage")
        assertThat(s.readDurations().songs).isEmpty()
    }

    @Test fun `disconnect and clear everything forget every user's scrobbles, not the catalog`() {
        val s = store()
        s.writeMonth("rawnclark", YearMonth.of(2026, 9), MonthFile("UTC", 1L, listOf(LastFmScrobble(1, "A", "T")), complete = true))
        s.writeCounts("rawnclark", CountsFile("UTC"))
        s.writeDurations(DurationsFile(mapOf("t|a" to CachedValue(201_000L, 3L))))
        s.clearUsers()
        assertThat(s.readMonth("rawnclark", YearMonth.of(2026, 9))).isNull()
        assertThat(s.readCounts("rawnclark")).isNull()
        assertThat(s.readDurations().songs).isNotEmpty()
        // The static form LastFmSessionPreference.clear uses (on filesDir).
        val filesDir = tmp.newFolder("files")
        val inFiles = LastFmRecapStore(filesDir.resolve(LastFmRecapStore.DIR))
        inFiles.writeCounts("u", CountsFile("UTC"))
        LastFmRecapStore.clearUsers(filesDir)
        assertThat(inFiles.readCounts("u")).isNull()
    }

    @Test fun `a month file from before the completeness flag reads as incomplete`() {
        val s = store()
        tmp.root.resolve("u/u").mkdirs()
        tmp.root.resolve("u/u/m-2026-09.json").writeText("""{"zone":"UTC","fetchedAt":1,"scrobbles":[]}""")
        assertThat(s.readMonth("u", YearMonth.of(2026, 9))!!.complete).isFalse()
    }

    @Test fun `user folders are safe names`() {
        assertThat(LastFmRecapStore.safeName("Rawn_Clark-1")).isEqualTo("rawn_clark-1")
        assertThat(LastFmRecapStore.safeName("../etc")).isEqualTo("___etc")
        assertThat(LastFmRecapStore.safeName(" ")).isEqualTo("_")
    }
}
