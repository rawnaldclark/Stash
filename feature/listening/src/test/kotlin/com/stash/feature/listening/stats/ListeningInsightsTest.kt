package com.stash.feature.listening.stats

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.db.dao.HourCount
import com.stash.core.data.db.dao.TopAlbumRow
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningRange
import org.junit.Test

class ListeningInsightsTest {

    private val today = 20_744L // 2026-10-18

    @Test fun `the chips offer five ranges and anything else opens on 30 days`() {
        assertThat(ListeningInsights.RANGES.map { it.key }).containsExactly("7d", "30d", "3m", "12m", "all").inOrder()
        assertThat(ListeningInsights.rangeOf(null)).isEqualTo(ListeningRange.Days30)
        assertThat(ListeningInsights.rangeOf("3m")).isEqualTo(ListeningRange.Months3)
        assertThat(ListeningInsights.rangeOf("6m")).isEqualTo(ListeningRange.Days30) // not on the chips
        assertThat(ListeningInsights.rangeOf("garbage")).isEqualTo(ListeningRange.Days30)
    }

    @Test fun `the delta compares complete days ending yesterday, only inside the history`() {
        // 30 days: today-30..today-1 vs today-60..today-31.
        val w = ListeningInsights.deltaWindows(ListeningRange.Days30, today, today - 60)!!
        assertThat(w.current).isEqualTo(DayRange(today - 30, today - 1))
        assertThat(w.previous).isEqualTo(DayRange(today - 60, today - 31))
        assertThat(ListeningInsights.deltaWindows(ListeningRange.Days30, today, today - 100)).isEqualTo(w)
        // History starts one day into the earlier window: not comparable.
        assertThat(ListeningInsights.deltaWindows(ListeningRange.Days30, today, today - 59)).isNull()
        assertThat(ListeningInsights.deltaWindows(ListeningRange.Days30, today, null)).isNull()
        assertThat(ListeningInsights.deltaWindows(ListeningRange.AllTime, today, today - 1000)).isNull()
    }

    @Test fun `today's listening never moves the delta`() {
        val w = ListeningInsights.deltaWindows(ListeningRange.Days7, today, today - 14)!!
        val days = listOf(
            DayCount(today, 50, 9_000_000), // today, still going
            DayCount(today - 1, 2, 300),
            DayCount(today - 8, 2, 200),
        )
        assertThat(ListeningInsights.deltaFromDays(days, w)).isEqualTo(50)
        assertThat(ListeningInsights.deltaFromDays(days.filter { it.day != today - 8 }, w)).isNull()
    }

    @Test fun `delta percent rounds and needs something before`() {
        assertThat(ListeningInsights.deltaPercent(150, 100)).isEqualTo(50)
        assertThat(ListeningInsights.deltaPercent(50, 100)).isEqualTo(-50)
        assertThat(ListeningInsights.deltaPercent(100, 300)).isEqualTo(-67)
        assertThat(ListeningInsights.deltaPercent(100, 100)).isEqualTo(0)
        assertThat(ListeningInsights.deltaPercent(100, 0)).isNull()
    }

    @Test fun `previous period phrases`() {
        assertThat(ListeningInsights.previousPhrase(ListeningRange.Days7)).isEqualTo("vs the week before")
        assertThat(ListeningInsights.previousPhrase(ListeningRange.Days30)).isEqualTo("vs the 30 days before")
        assertThat(ListeningInsights.previousPhrase(ListeningRange.Months3)).isEqualTo("vs the 3 months before")
        assertThat(ListeningInsights.previousPhrase(ListeningRange.Months12)).isEqualTo("vs the year before")
    }

    @Test fun `the clock peak is the busiest hour, the earliest on a tie`() {
        val hourly = ListeningInsights.hourly(listOf(HourCount(21, 5), HourCount(9, 5), HourCount(14, 2)))
        assertThat(hourly.size).isEqualTo(24)
        assertThat(hourly[21]).isEqualTo(5)
        assertThat(ListeningInsights.peakHour(hourly)).isEqualTo(9)
        assertThat(ListeningInsights.peakHour(IntArray(24))).isNull()
    }

    @Test fun `the day split covers every hour and adds up to 100`() {
        assertThat((0..23).map { ListeningInsights.partOf(it) })
            .isEqualTo(listOf(3, 3, 3, 3, 3, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3))
        // One listen in each of three parts: 33/33/34 style rounding still sums to 100.
        val split = ListeningInsights.daySplit(ListeningInsights.hourly(listOf(HourCount(8, 1), HourCount(13, 1), HourCount(23, 1))))!!
        assertThat(split.morning + split.afternoon + split.evening + split.night).isEqualTo(100)
        assertThat(split.evening).isEqualTo(0)
        assertThat(listOf(split.morning, split.afternoon, split.night).sorted()).isEqualTo(listOf(33, 33, 34))
        val night = ListeningInsights.daySplit(ListeningInsights.hourly(listOf(HourCount(2, 3), HourCount(22, 1))))!!
        assertThat(night).isEqualTo(DaySplit(0, 0, 0, 100))
        assertThat(ListeningInsights.daySplit(IntArray(24))).isNull()
    }

    private fun artist(name: String, plays: Int, last: Long, ms: Long = plays * 1000L, songs: Int = 1) =
        TopArtistRow(name, plays, songs, last, "art-$name", null, ms)

    @Test fun `artists group by main act, adding up plays, songs and time`() {
        val merged = ListeningInsights.byMainArtist(
            listOf(
                artist("Other", 4, 50),
                artist("Drake", 3, 10),
                artist("Drake, Future", 2, 40),
                artist("drake, 21 Savage", 1, 20),
            ),
        )
        assertThat(merged.map { it.artist }).containsExactly("Drake", "Other").inOrder()
        val drake = merged[0]
        assertThat(drake.plays).isEqualTo(6)
        assertThat(drake.songs).isEqualTo(3)
        assertThat(drake.listenedMs).isEqualTo(6_000)
        assertThat(drake.lastPlayedAt).isEqualTo(40)
        assertThat(drake.albumArtUrl).isEqualTo("art-Drake") // the busiest credit's cover
    }

    @Test fun `a main act its own credit doesn't name is left out, as in the Library`() {
        val merged = ListeningInsights.byMainArtist(
            listOf(artist("Earth, Wind & Fire, Chicago", 5, 10), artist("Earth, Wind & Fire", 2, 5), artist("", 3, 1)),
        )
        // "Earth" (from the first credit) has no page; "Earth, Wind & Fire" (allow-listed) does.
        assertThat(merged.map { it.artist }).containsExactly("Earth, Wind & Fire")
    }

    private fun album(name: String, artist: String, plays: Int, last: Long = plays.toLong()) =
        TopAlbumRow(name, artist, plays, 1, last, "art-$artist", null, plays * 1000L)

    @Test fun `albums group by title and main act, folding case past ASCII`() {
        val merged = ListeningInsights.byMainAlbum(
            listOf(
                album("RENAISSANCE", "BEYONCÉ", 3),
                album("Renaissance", "Beyoncé, JAY-Z", 2),
                album("Renaissance", "Someone Else", 4),
            ),
        )
        assertThat(merged.map { it.album to it.artist }).containsExactly(
            "RENAISSANCE" to "BEYONCÉ", "Renaissance" to "Someone Else",
        ).inOrder()
        assertThat(merged[0].plays).isEqualTo(5)
        assertThat(merged[0].listenedMs).isEqualTo(5_000)
        // One key per grouped album, so list keys never collide.
        assertThat(merged.map { ListeningInsights.albumKey(it.album, it.artist) }.distinct()).hasSize(2)
        // SQLite's LOWER() leaves É alone; the key folds it.
        assertThat(ListeningInsights.albumKey("A", "BEYONCÉ")).isEqualTo(ListeningInsights.albumKey("a", "beyoncé"))
    }

    private fun song(id: Long, title: String, artist: String, plays: Int, isrc: String? = null) = TopTrackRow(
        TrackEntity(
            id = id, title = title, artist = artist, album = "", durationMs = 1, isrc = isrc,
            canonicalTitle = title.lowercase(), canonicalArtist = artist.lowercase(),
        ),
        plays, lastPlayedAt = id, listenedMs = plays * 1000L,
    )

    @Test fun `one row per song, by the same title and main act or the same ISRC`() {
        val merged = ListeningInsights.mergeSameSongs(
            listOf(
                song(1, "Garden", "Death Plus", 4),
                song(2, "GARDEN", "Death Plus, Guest", 3),
                song(3, "Garden - 2024 Remaster", "Death Plus", 2, isrc = "US1"),
                song(4, "Garden", "Death Plus", 1, isrc = "us1"), // links 3 to 1's song through the ISRC
                song(5, "Garden", "Other Band", 6), // same title, another act
            ),
        )
        assertThat(merged.map { it.track.id }).containsExactly(1L, 5L).inOrder()
        assertThat(merged[0].plays).isEqualTo(10)
        assertThat(merged[0].listenedMs).isEqualTo(10_000)
        assertThat(merged[0].lastPlayedAt).isEqualTo(4)
        assertThat(ListeningInsights.mergeSameSongs(emptyList())).isEmpty()
    }

    @Test fun `the top songs label names the range`() {
        assertThat(ListeningInsights.topSongsLabel(ListeningRange.Days30)).isEqualTo("Top songs · 30 days")
        assertThat(ListeningInsights.topSongsLabel(ListeningRange.AllTime)).isEqualTo("Top songs · All time")
    }
}
