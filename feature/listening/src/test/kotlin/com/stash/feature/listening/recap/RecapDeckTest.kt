package com.stash.feature.listening.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.db.dao.ListeningTotals
import com.stash.core.data.db.dao.ObsessionRow
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.genre.GenreShare
import com.stash.core.data.genre.TopGenres
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningStatsCalculator
import com.stash.feature.listening.stats.TopArtistUi
import org.junit.Test

/** The Recap deck: which cards a history earns, their order, the quiz, the labels and the music. */
class RecapDeckTest {

    private val october = RecapPeriod.MonthOf(2026, 10)
    private val today = 20_744L // 2026-10-18
    private val days = october.dayRange(today)

    private fun track(id: Long, artist: String = "Artist $id") = TrackEntity(
        id = id, title = "Song $id", artist = artist, album = "", durationMs = 200_000,
        canonicalTitle = "song $id", canonicalArtist = artist.lowercase(), albumArtUrl = "https://art/$id.jpg",
    )

    /** [n] songs, #1 with the most plays, each next one fewer. */
    private fun songs(n: Int, top: Int = 20) =
        (1..n).map { i -> TopTrackRow(track(i.toLong()), (top - i + 1).coerceAtLeast(1), 0L, 60_000L * i) }

    private fun artist(name: String, plays: Int) = TopArtistUi(name, plays, 3, 3_600_000, null, null, "https://art/$name.jpg")

    private fun totals(plays: Int, ms: Long = 38 * 3_600_000L, estimated: Int = 0) = ListeningTotals(plays, 40, 12, 15, ms, estimated)

    private fun full() = RecapData(
        period = october,
        days = days,
        today = today,
        totals = totals(142, estimated = 5),
        topSongs = songs(10),
        topArtists = listOf(artist("Taro", 41), artist("Mira", 30), artist("Ode", 20), artist("Lux", 10), artist("Kai", 5)),
        artistCount = 12,
        genres = TopGenres(listOf(GenreShare("jazz", "Jazz", 0.5, 1.0), GenreShare("soul", "Soul", 0.3, 1.0)), coverage = 0.9),
        topArtistDays = setOf(days.fromDay, days.fromDay + 3, days.fromDay + 40), // the last is outside
        hourly = List(24) { if (it == 21) 10 else 1 },
        longestStreak = ListeningStatsCalculator.Streak(6, days.fromDay + 2, days.fromDay + 7),
        busiestDay = DayCount(days.fromDay + 10, 28, 4_000_000),
        busiestDaySongs = listOf(TopTrackRow(track(1), 9, 0L), TopTrackRow(track(2), 6, 0L), TopTrackRow(track(3), 4, 0L)),
        obsession = ObsessionRow(track(1), days.fromDay + 10, 9),
        newSongs = 37,
        newFinds = listOf(TopTrackRow(track(4), 12, 0L), TopTrackRow(track(6), 3, 0L)),
    )

    @Test fun `a full month tells every card, in the story's order`() {
        val cards = RecapDeck.build(full(), "Your October so far")
        assertThat(cards.map { it::class.simpleName }).containsExactly(
            "Intro", "Minutes", "Genres", "TopArtist", "TopArtists", "Quiz", "TopSong", "TopSongs",
            "OnRepeat", "Clock", "Streak", "Discoveries", "Summary",
        ).inOrder()
        val top = cards.filterIsInstance<RecapCard.TopArtist>().single()
        assertThat(top.daysPlayed).isEqualTo(2)
        assertThat(top.daysInPeriod).isEqualTo(18) // Oct 1..18 so far
        assertThat(top.grid.length).isEqualTo(31)
        val summary = cards.last() as RecapCard.Summary
        assertThat(summary.topGenre).isEqualTo("Jazz")
        assertThat(summary.songs).hasSize(5)
        assertThat(summary.estimated).isTrue()
    }

    @Test fun `under twenty listens there is no story`() {
        assertThat(RecapDeck.build(full().copy(totals = totals(19)), "t")).isEmpty()
        assertThat(RecapDeck.build(full().copy(totals = totals(20)), "t")).isNotEmpty()
    }

    @Test fun `thin cards are skipped`() {
        val thin = full().copy(
            genres = TopGenres(listOf(GenreShare("jazz", "Jazz", 1.0, 1.0)), coverage = 0.4), // under half known
            topSongs = songs(3),
            topArtists = listOf(artist("Taro", 2), artist("Mira", 1)),
            obsession = ObsessionRow(track(1), days.fromDay, 3),
            longestStreak = ListeningStatsCalculator.Streak(1, days.fromDay, days.fromDay),
            busiestDay = DayCount(days.fromDay, 4, 1),
            newSongs = 2,
        )
        val kinds = RecapDeck.build(thin, "t").map { it::class.simpleName }
        assertThat(kinds).containsExactly("Intro", "Minutes", "TopSong", "TopSongs", "Clock", "Summary").inOrder()
    }

    @Test fun `the quiz holds the number one and three lower songs, the same way every time`() {
        val list = songs(10)
        val q1 = RecapDeck.quiz(list, october)!!
        val q2 = RecapDeck.quiz(list, october)!!
        assertThat(q1).isEqualTo(q2)
        assertThat(q1.options).hasSize(4)
        assertThat(q1.options[q1.correctIndex]).isEqualTo(list[0])
        assertThat(q1.options.toSet()).hasSize(4)
        // Wrong answers come from ranks 2..8.
        assertThat(list.subList(1, 8)).containsAtLeastElementsIn(q1.options - list[0])
        val other = RecapDeck.quiz(list, RecapPeriod.MonthOf(2026, 9))!!
        assertThat(other.options[other.correctIndex]).isEqualTo(list[0])
    }

    @Test fun `songs tied with the number one are never wrong answers`() {
        val tied = listOf(20, 20, 20, 5, 4).mapIndexed { i, p -> TopTrackRow(track(i + 1L), p, 0L) }
        assertThat(RecapDeck.quiz(tied, october)).isNull() // only 2 songs below the #1
        val ok = listOf(20, 20, 6, 5, 4).mapIndexed { i, p -> TopTrackRow(track(i + 1L), p, 0L) }
        val q = RecapDeck.quiz(ok, october)!!
        assertThat(q.options.map { it.track.id }).doesNotContain(2L)
        assertThat(RecapDeck.quiz(songs(3), october)).isNull()
    }

    @Test fun `big time is hours, or minutes under two hours`() {
        assertThat(RecapDeck.bigTime(38 * 3_600_000L + 20 * 60_000L)).isEqualTo(BigTime(38, "hours"))
        assertThat(RecapDeck.bigTime(94 * 60_000L)).isEqualTo(BigTime(94, "minutes"))
        assertThat(RecapDeck.bigTime(20_000L)).isEqualTo(BigTime(1, "minute"))
        assertThat(RecapDeck.bigTime(2 * 3_600_000L)).isEqualTo(BigTime(2, "hours"))
    }

    @Test fun `the translation picks days, movies or albums by size`() {
        assertThat(RecapDeck.translation(38 * 3_600_000L)).isEqualTo("That's 1.6 days of music.")
        assertThat(RecapDeck.translation(48 * 3_600_000L)).isEqualTo("That's 2 days of music.")
        assertThat(RecapDeck.translation(11 * 3_600_000L)).isEqualTo("That's as long as 6 movies.")
        assertThat(RecapDeck.translation(90 * 60_000L)).isEqualTo("That's about 2 albums, start to finish.")
        assertThat(RecapDeck.translation(10 * 60_000L)).isEqualTo("That's about one album, start to finish.")
    }

    private fun hours(vararg pairs: Pair<Int, Int>): List<Int> = MutableList(24) { 0 }.also { l -> pairs.forEach { (h, n) -> l[h] = n } }

    @Test fun `night owl needs 35 percent between 10 PM and 4 AM, with the number shown`() {
        val owl = RecapDeck.clockLabel(hours(23 to 20, 1 to 15, 14 to 65))!!
        assertThat(owl.title).isEqualTo("Night owl")
        assertThat(owl.percent).isEqualTo(35)
        assertThat(owl.line).isEqualTo("35% of your listening after 10 PM")
        // 4 AM is not night: 34%.
        assertThat(RecapDeck.clockLabel(hours(23 to 34, 4 to 1, 14 to 65))!!.title).isNotEqualTo("Night owl")
    }

    @Test fun `early bird needs 30 percent between 5 and 9 AM`() {
        val bird = RecapDeck.clockLabel(hours(6 to 30, 15 to 70))!!
        assertThat(bird.title).isEqualTo("Early bird")
        assertThat(bird.line).isEqualTo("30% of your listening before 9 AM")
        // 9 AM is not early: falls to the biggest part of the day.
        val morning = RecapDeck.clockLabel(hours(9 to 60, 15 to 40))!!
        assertThat(morning.title).isEqualTo("Morning listener")
        assertThat(morning.percent).isEqualTo(60)
    }

    @Test fun `otherwise the biggest part of the day names the listener`() {
        assertThat(RecapDeck.clockLabel(hours(18 to 50, 13 to 30, 9 to 20))!!.title).isEqualTo("Evening listener")
        val afternoon = RecapDeck.clockLabel(hours(13 to 60, 18 to 40))!!
        assertThat(afternoon.title).isEqualTo("Afternoon listener")
        assertThat(afternoon.line).isEqualTo("60% of your listening between noon and 5 PM")
        assertThat(RecapDeck.clockLabel(List(24) { 0 })).isNull()
    }

    @Test fun `each card's music follows the spec, quiz silent, others keep the song before`() {
        val base = full()
        val data = base.copy(artistTopSongs = mapOf(base.topArtists[0].key to base.topSongs[2]), genreSong = base.topSongs[6])
        val cards = RecapDeck.build(data, "t")
        val cues = RecapDeck.cues(cards, data)
        val byKind = cards.map { it::class.simpleName }.zip(cues).toMap()
        fun id(kind: String) = (byKind[kind] as? SongCue.Play)?.song?.track?.id
        assertThat(id("Intro")).isEqualTo(5L)
        assertThat(id("Minutes")).isEqualTo(5L)
        assertThat(id("Genres")).isEqualTo(7L)
        assertThat(id("TopArtist")).isEqualTo(3L)
        assertThat(id("TopArtists")).isEqualTo(3L)
        assertThat(byKind["Quiz"]).isEqualTo(SongCue.Silence)
        assertThat(id("TopSong")).isEqualTo(1L)
        assertThat(id("TopSongs")).isEqualTo(2L)
        assertThat(id("OnRepeat")).isEqualTo(2L) // keeps the top songs card's song
        assertThat(id("Discoveries")).isEqualTo(2L)
        assertThat(id("Summary")).isEqualTo(1L)
    }

    @Test fun `the quiz wears Stash purple, not the answer's cover`() {
        val quiz = RecapDeck.build(full(), "t").filterIsInstance<RecapCard.Quiz>().single()
        assertThat(quiz.tint).isNull()
    }

    @Test fun `an artist with no song but the number one keeps the song before, never plays the answer`() {
        val data = full().copy(artistTopSongs = emptyMap())
        val cards = RecapDeck.build(data, "t")
        val cues = RecapDeck.cues(cards, data)
        val kinds = cards.map { it::class.simpleName }
        val beforeQuiz = cues.subList(0, kinds.indexOf("Quiz"))
        assertThat(beforeQuiz.filterIsInstance<SongCue.Play>().map { it.song.track.id }).doesNotContain(1L)
    }

    @Test fun `the busiest day on the same day as On repeat shows the day's top covers, not the song again`() {
        // On repeat and the busiest day are both days.fromDay + 10, both song 1.
        val streak = RecapDeck.build(full(), "t").filterIsInstance<RecapCard.Streak>().single()
        assertThat(streak.busiestSong).isNull()
        assertThat(streak.dayTop.map { it.track.id }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test fun `a different busiest day shows its song as a row`() {
        val data = full().copy(busiestDay = DayCount(days.fromDay + 3, 28, 4_000_000), busiestDaySongs = listOf(TopTrackRow(track(7), 9, 0L)))
        val streak = RecapDeck.build(data, "t").filterIsInstance<RecapCard.Streak>().single()
        assertThat(streak.busiestSong?.track?.id).isEqualTo(7L)
        assertThat(streak.dayTop).isEmpty()
    }

    @Test fun `best find skips a song an earlier card already showed off, when there's another`() {
        // Song 4 is the busiest day's song (a row), so the best find moves to song 6.
        val data = full().copy(
            busiestDay = DayCount(days.fromDay + 3, 28, 4_000_000),
            busiestDaySongs = listOf(TopTrackRow(track(4), 9, 0L)),
        )
        val best = RecapDeck.build(data, "t").filterIsInstance<RecapCard.Discoveries>().single().best
        assertThat(best?.track?.id).isEqualTo(6L)
        // With no other new song, the truth stands: the same song.
        val only = data.copy(newFinds = listOf(TopTrackRow(track(4), 12, 0L)))
        assertThat(RecapDeck.build(only, "t").filterIsInstance<RecapCard.Discoveries>().single().best?.track?.id).isEqualTo(4L)
    }

    @Test fun `times are about when estimated, on every card that shows one`() {
        val cards = RecapDeck.build(full(), "t")
        assertThat(cards.filterIsInstance<RecapCard.TopSong>().single().estimated).isTrue()
        assertThat(cards.filterIsInstance<RecapCard.TopArtist>().single().estimated).isTrue()
        assertThat(cards.filterIsInstance<RecapCard.Streak>().single().estimated).isTrue()
        assertThat(playsAndTime(7, 600_000, estimated = true)).isEqualTo("7 plays · about 10 min")
        assertThat(playsAndTime(7, 600_000, estimated = false)).isEqualTo("7 plays · 10 min")
    }

    @Test fun `reveal cards stay up longer`() {
        val cards = RecapDeck.build(full(), "t")
        assertThat(RecapDeck.durationMs(cards.first { it is RecapCard.Intro })).isEqualTo(RecapDeck.CARD_MS)
        assertThat(RecapDeck.durationMs(cards.first { it is RecapCard.TopSongs })).isEqualTo(RecapDeck.REVEAL_MS)
        assertThat(RecapDeck.durationMs(cards.first { it is RecapCard.TopSong })).isEqualTo(RecapDeck.REVEAL_MS)
    }

    @Test fun `days of the period cut at today while it's in progress`() {
        assertThat(days).isEqualTo(DayRange(20_727L, today))
        assertThat(RecapPeriod.MonthOf(2026, 9).dayRange(today).length).isEqualTo(30)
    }
}
