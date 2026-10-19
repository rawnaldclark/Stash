package com.stash.feature.listening.recap

import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.db.dao.FirstListen
import com.stash.core.data.db.dao.ListeningTotals
import com.stash.core.data.db.dao.ObsessionRow
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.genre.GenreShare
import com.stash.core.data.genre.TopGenres
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningStatsCalculator.Streak
import com.stash.feature.listening.stats.ListeningInsights
import com.stash.feature.listening.stats.TopArtistUi
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Everything one Recap story is built from, read once when the story opens (RecapViewModel). Pure
 * data: [RecapDeck.build] turns it into cards without any I/O.
 */
data class RecapData(
    val period: RecapPeriod,
    /** The days read: the period, cut at today while in progress. */
    val days: DayRange,
    val today: Long,
    val totals: ListeningTotals,
    /** Top songs, one row per song (library duplicates merged), most played first (up to 10). */
    val topSongs: List<TopTrackRow>,
    /** Top artists by main act, most played first (up to 5), with photos. */
    val topArtists: List<TopArtistUi>,
    /** Main acts heard in the period. */
    val artistCount: Int,
    val genres: TopGenres = TopGenres.EMPTY,
    /**
     * Each top artist's most played song in the period other than the #1 song, by
     * [TopArtistUi.key]: the artist cards come before the quiz, so they must not play its answer.
     */
    val artistTopSongs: Map<String, TopTrackRow> = emptyMap(),
    /** The top genre's leading artist's most played song other than the #1 (the genres card's music). */
    val genreSong: TopTrackRow? = null,
    /** Days in [days] the top artist was played. */
    val topArtistDays: Set<Long> = emptySet(),
    /** Listens per hour 0..23. */
    val hourly: List<Int> = List(24) { 0 },
    val longestStreak: Streak? = null,
    val busiestDay: DayCount? = null,
    /** The most played songs on [busiestDay] (up to 3), with their plays that day. */
    val busiestDaySongs: List<TopTrackRow> = emptyList(),
    val obsession: ObsessionRow? = null,
    /** Songs first heard in the period (0 when history starts inside it: everything is new). */
    val newSongs: Int = 0,
    /** The most played songs first heard in the period (a few, so a hero of an earlier card can be skipped). */
    val newFinds: List<TopTrackRow> = emptyList(),
    /** The first listen ever of the #1 song. */
    val topSongFirstListen: FirstListen? = null,
) {
    /** Some listening time is estimated from song length: numbers say "about". */
    val estimated: Boolean get() = totals.estimatedPlays > 0
}

/** One card of the story. Every card knows the cover its background colour comes from. */
sealed interface RecapCard {
    /** The cover (file path or URL) whose colours tint the card; null = Stash purple. */
    val tint: ArtModel?

    /** The images the card draws, for preloading a share image. */
    val images: List<ArtModel> get() = listOfNotNull(tint)

    /** The one image drawn big (decoded large for a share image; the rest are small). */
    val hero: ArtModel? get() = null

    /** A card that reveals something (ranks, the #1): it stays up longer. */
    val isReveal: Boolean get() = false

    data class Intro(val plays: Int, val covers: List<ArtModel>, override val tint: ArtModel?) : RecapCard {
        override val images: List<ArtModel> get() = covers
    }

    data class Minutes(
        val listenedMs: Long,
        val estimated: Boolean,
        val big: BigTime,
        val translation: String,
        override val tint: ArtModel?,
    ) : RecapCard

    data class Genres(val genres: List<GenreShare>, override val tint: ArtModel?) : RecapCard

    data class TopArtist(
        val artist: TopArtistUi,
        /** Time is partly estimated from song length: "about". */
        val estimated: Boolean,
        val daysPlayed: Int,
        /** Days of the period so far (the "of 31 days"). */
        val daysInPeriod: Int,
        val playedDays: Set<Long>,
        /** The calendar grid: every day of the period, the days to come drawn faint. */
        val grid: DayRange,
        val today: Long,
        override val tint: ArtModel?,
    ) : RecapCard {
        override val hero: ArtModel? get() = artist.art()
    }

    data class TopArtists(val artists: List<TopArtistUi>, override val tint: ArtModel?) : RecapCard {
        override val images: List<ArtModel> get() = artists.mapNotNull { it.art() }
        override val isReveal: Boolean get() = true
    }

    data class Quiz(val options: List<TopTrackRow>, val correctIndex: Int, override val tint: ArtModel?) : RecapCard {
        override val images: List<ArtModel> get() = options.mapNotNull { it.art() }
    }

    data class TopSong(
        val song: TopTrackRow,
        val firstListen: FirstListen?,
        val estimated: Boolean,
        override val tint: ArtModel?,
    ) : RecapCard {
        override val hero: ArtModel? get() = song.art()
        override val isReveal: Boolean get() = true
    }

    data class TopSongs(val songs: List<TopTrackRow>, override val tint: ArtModel?) : RecapCard {
        override val images: List<ArtModel> get() = songs.mapNotNull { it.art() }
        override val isReveal: Boolean get() = true
    }

    data class OnRepeat(val obsession: ObsessionRow, override val tint: ArtModel?) : RecapCard

    data class Clock(
        val hourly: List<Int>,
        val peakHour: Int,
        val label: ClockLabel,
        override val tint: ArtModel?,
    ) : RecapCard

    data class Streak(
        val longest: com.stash.core.data.listening.ListeningStatsCalculator.Streak?,
        val busiest: DayCount?,
        /** The day's most played song, shown as a row; null when [dayTop] is shown instead. */
        val busiestSong: TopTrackRow?,
        /**
         * The day's top songs as covers, instead of one song row, when that song was already the
         * hero of an earlier card (the same day as On repeat, or the #1).
         */
        val dayTop: List<TopTrackRow>,
        val estimated: Boolean,
        val today: Long,
        override val tint: ArtModel?,
    ) : RecapCard {
        override val images: List<ArtModel> get() = listOfNotNull(busiestSong?.art()) + dayTop.mapNotNull { it.art() }
    }

    data class Discoveries(val newSongs: Int, val best: TopTrackRow?, override val tint: ArtModel?) : RecapCard

    data class Summary(
        val title: String,
        val listenedMs: Long,
        val estimated: Boolean,
        val artists: List<TopArtistUi>,
        val songs: List<TopTrackRow>,
        val topGenre: String?,
        val clockLabel: ClockLabel?,
        override val tint: ArtModel?,
    ) : RecapCard {
        override val images: List<ArtModel> get() = listOfNotNull(tint) + songs.take(1).mapNotNull { it.art() }
        override val isReveal: Boolean get() = true
    }
}

/** A cover or photo to draw: a file on the phone or a URL. [key] is what Coil and PaletteCache load. */
data class ArtModel(val key: String)

/** The song's cover: the file on the phone first, else its URL. */
fun TopTrackRow.art(): ArtModel? = (track.albumArtPath ?: track.albumArtUrl)?.takeIf { it.isNotBlank() }?.let(::ArtModel)

/** The artist's photo, else the cover of their latest song. */
fun TopArtistUi.art(): ArtModel? = (photoUrl ?: artPath ?: artUrl)?.takeIf { it.isNotBlank() }?.let(::ArtModel)

/** The minutes card's big number: "38" + "hours", or "94" + "minutes" under two hours. */
data class BigTime(val value: Int, val unit: String)

/**
 * The listening-clock label, only ever shown with the number that earns it (DESIGN.md: labels
 * traceable to the user's own behaviour). [percent] of listens fall in the label's hours.
 */
data class ClockLabel(val title: String, val percent: Int, val line: String)

/** The music a card plays (story audio, PHASE3-STORY-SPEC "Music"). */
sealed interface SongCue {
    data class Play(val song: TopTrackRow) : SongCue
    data object Silence : SongCue
}

/**
 * Builds a Recap story from [RecapData]: the cards in their fixed order, each skipped when its
 * data is too thin to say something worth a card (thresholds below). Pure and deterministic,
 * including the quiz (seeded per period), so the same history always tells the same story.
 */
object RecapDeck {

    /** Fewer listens than this: no story at all (RecapAvailability.MIN_PLAYS). */
    const val MIN_PLAYS = RecapAvailability.MIN_PLAYS

    /** The top artist card needs them played at least this often. */
    const val TOP_ARTIST_MIN_PLAYS = 3

    /** The top artists card needs at least this many artists (it shows up to [RANKED]). */
    const val TOP_ARTISTS_MIN = 3

    /** The top songs card needs at least this many songs. */
    const val TOP_SONGS_MIN = 3

    /** Ranked cards show this many. */
    const val RANKED = 5

    /** The #1 reveal needs the song played at least this often. */
    const val TOP_SONG_MIN_PLAYS = 2

    /** The quiz needs this many distinct songs (#1 + 3 others). */
    const val QUIZ_OPTIONS = 4

    /** Quiz wrong answers come from ranks 2..this (never songs tied with #1). */
    const val QUIZ_POOL_RANK = 8

    /** "On repeat" needs one song played this often in one day. */
    const val ON_REPEAT_MIN_PLAYS = 4

    /** The streak card needs a streak this long, or a busiest day with [BUSIEST_MIN_PLAYS]. */
    const val STREAK_MIN_DAYS = 2
    const val BUSIEST_MIN_PLAYS = 5

    /** Discoveries needs this many new songs. */
    const val DISCOVERIES_MIN = 3

    /** Genres shown on the genres card. */
    const val GENRES_SHOWN = 3

    // Clock labels (PHASE3-STORY-SPEC card 10).
    /** Night owl: at least this share of listens between 10 PM and 4 AM. */
    const val NIGHT_OWL_SHARE = 0.35

    /** Early bird: at least this share of listens between 5 and 9 AM. */
    const val EARLY_BIRD_SHARE = 0.30

    private const val HOUR_MS = 3_600_000L
    private const val TWO_HOURS_MS = 2 * HOUR_MS

    fun build(data: RecapData, title: String): List<RecapCard> {
        if (data.totals.plays < MIN_PLAYS) return emptyList()
        val songs = data.topSongs
        val artists = data.topArtists
        val no1 = songs.firstOrNull()?.art()
        val clock = clockLabel(data.hourly)
        // Songs already the hero of an earlier card; later cards avoid them where they can.
        val heroes = HashSet<String>()
        return buildList {
            add(RecapCard.Intro(data.totals.plays, songs.take(RANKED).mapNotNull { it.art() }.distinct(), no1))
            if (data.totals.listenedMs > 0) {
                add(
                    RecapCard.Minutes(
                        data.totals.listenedMs, data.estimated, bigTime(data.totals.listenedMs),
                        translation(data.totals.listenedMs), songs.getOrNull(RANKED - 1)?.art() ?: no1,
                    ),
                )
            }
            if (data.genres.isShowable) {
                add(RecapCard.Genres(data.genres.items.take(GENRES_SHOWN), data.genreSong?.art() ?: no1))
            }
            val top = artists.firstOrNull()
            if (top != null && top.plays >= TOP_ARTIST_MIN_PLAYS) {
                val played = data.topArtistDays.filter { it in data.days }.toSet()
                add(
                    RecapCard.TopArtist(
                        artist = top,
                        estimated = data.estimated,
                        daysPlayed = played.size,
                        daysInPeriod = data.days.length.toInt(),
                        playedDays = played,
                        grid = data.period.fullRange(),
                        today = data.today,
                        tint = top.art(),
                    ),
                )
            }
            if (artists.size >= TOP_ARTISTS_MIN) {
                add(RecapCard.TopArtists(artists.take(RANKED), artists.first().art()))
            }
            quiz(songs, data.period)?.let(::add)
            val first = songs.firstOrNull()
            if (first != null && first.plays >= TOP_SONG_MIN_PLAYS) {
                add(RecapCard.TopSong(first, data.topSongFirstListen, data.estimated, no1))
                heroes += songKeyOf(first)
            }
            if (songs.size >= TOP_SONGS_MIN) add(RecapCard.TopSongs(songs.take(RANKED), songs.getOrNull(1)?.art() ?: no1))
            val onRepeat = data.obsession?.takeIf { it.plays >= ON_REPEAT_MIN_PLAYS }
            onRepeat?.let { o ->
                add(RecapCard.OnRepeat(o, o.track.let { t -> (t.albumArtPath ?: t.albumArtUrl)?.let(::ArtModel) }))
                heroes += ListeningInsights.songKey(o.track.title, o.track.artist)
            }
            val peak = ListeningInsights.peakHour(data.hourly.toIntArray())
            if (peak != null && clock != null) add(RecapCard.Clock(data.hourly, peak, clock, no1))
            val streak = data.longestStreak?.takeIf { it.days >= STREAK_MIN_DAYS }
            val busiest = data.busiestDay?.takeIf { it.plays >= BUSIEST_MIN_PLAYS }
            if (streak != null || busiest != null) {
                val daySong = data.busiestDaySongs.firstOrNull().takeIf { busiest != null }
                // The day's song was already an earlier card's hero (the same day as On repeat,
                // or the #1): show the day's top covers instead of the same song again.
                val repeats = daySong != null && (
                    (onRepeat != null && onRepeat.day == busiest?.day) || songKeyOf(daySong) in heroes
                    )
                val dayTop = if (repeats && data.busiestDaySongs.size > 1) data.busiestDaySongs.take(DAY_TOP) else emptyList()
                val single = daySong.takeIf { dayTop.isEmpty() }
                add(RecapCard.Streak(streak, busiest, single, dayTop, data.estimated, data.today, daySong?.art() ?: no1))
                single?.let { heroes += songKeyOf(it) }
            }
            if (data.newSongs >= DISCOVERIES_MIN) {
                // Best find: the most played new song that wasn't already an earlier card's hero,
                // when there is one (never invented: always one of the period's real new songs).
                val best = data.newFinds.firstOrNull { songKeyOf(it) !in heroes } ?: data.newFinds.firstOrNull()
                add(RecapCard.Discoveries(data.newSongs, best, best?.art() ?: no1))
            }
            add(
                RecapCard.Summary(
                    title = title,
                    listenedMs = data.totals.listenedMs,
                    estimated = data.estimated,
                    artists = artists.take(RANKED),
                    songs = songs.take(RANKED),
                    topGenre = data.genres.takeIf { it.isShowable }?.items?.firstOrNull()?.name,
                    clockLabel = clock,
                    tint = no1,
                ),
            )
        }
    }

    /**
     * The quiz: the #1 and three songs from ranks 2..[QUIZ_POOL_RANK] that have fewer plays than
     * it (a song tied with #1 would make the "right" answer a coin toss), in an order seeded by the
     * period. Null when there aren't enough such songs.
     */
    fun quiz(songs: List<TopTrackRow>, period: RecapPeriod): RecapCard.Quiz? {
        val no1 = songs.firstOrNull() ?: return null
        val pool = songs.drop(1).take(QUIZ_POOL_RANK - 1).filter { it.plays < no1.plays }
        if (pool.size < QUIZ_OPTIONS - 1) return null
        val random = Random(seedOf(period))
        val options = (pool.shuffled(random).take(QUIZ_OPTIONS - 1) + no1).shuffled(random)
        // No cover colour: the #1's would give the answer away.
        return RecapCard.Quiz(options, options.indexOf(no1), tint = null)
    }

    /** One seed per period, so reopening a recap asks the same quiz in the same order. */
    fun seedOf(period: RecapPeriod): Int = period.year * 100 + period.monthArg

    /** The minutes card's big number (hours, rounded; minutes under two hours). */
    fun bigTime(ms: Long): BigTime =
        if (ms < TWO_HOURS_MS) {
            val m = ((ms + 30_000) / 60_000).toInt().coerceAtLeast(1)
            BigTime(m, if (m == 1) "minute" else "minutes")
        } else {
            BigTime((ms.toDouble() / HOUR_MS).roundToInt(), "hours")
        }

    /**
     * One relatable line for the time listened, picked by size: days of music for a lot, movies
     * for some, albums for a little.
     */
    fun translation(ms: Long): String {
        val hours = ms.toDouble() / HOUR_MS
        return when {
            hours >= 36 -> {
                val days = hours / 24
                val text = if ((days * 10).roundToInt() % 10 == 0) "${days.roundToInt()}" else "%.1f".format(java.util.Locale.US, days)
                "That's $text days of music."
            }
            hours >= 4 -> "That's as long as ${(hours / 2).roundToInt()} movies."
            else -> {
                val albums = (ms / (45 * 60_000.0)).roundToInt().coerceAtLeast(1)
                if (albums == 1) "That's about one album, start to finish." else "That's about $albums albums, start to finish."
            }
        }
    }

    /**
     * The clock label with its number: Night owl (≥ 35% of listens 10 PM–4 AM), Early bird (≥ 30%
     * 5–9 AM), else the part of the day with the most listens. Null when nothing was heard.
     */
    fun clockLabel(hourly: List<Int>): ClockLabel? {
        val total = hourly.sum()
        if (total <= 0) return null
        fun share(hours: Iterable<Int>) = hours.sumOf { hourly.getOrElse(it) { 0 } }.toDouble() / total
        fun pct(s: Double) = (s * 100).roundToInt()
        val night = share(listOf(22, 23, 0, 1, 2, 3))
        if (night >= NIGHT_OWL_SHARE) return ClockLabel("Night owl", pct(night), "${pct(night)}% of your listening after 10 PM")
        val early = share(5..8)
        if (early >= EARLY_BIRD_SHARE) return ClockLabel("Early bird", pct(early), "${pct(early)}% of your listening before 9 AM")
        val parts = listOf(
            Triple("Morning listener", share(5..11), "between 5 AM and noon"),
            Triple("Afternoon listener", share(12..16), "between noon and 5 PM"),
            Triple("Evening listener", share(17..21), "between 5 and 10 PM"),
            Triple("Night listener", night, "after 10 PM"),
        )
        val best = parts.maxBy { it.second }
        return ClockLabel(best.first, pct(best.second), "${pct(best.second)}% of your listening ${best.third}")
    }

    /**
     * The music for each card: intro and minutes play the #5 song, genres the top genre's leading
     * artist's song, the artist cards the top artist's song, the quiz is silent, the #1 reveal and
     * the summary the #1, top songs the #2, and every other card keeps the song before it (so
     * stepping back and forth always hears the same song under the same card).
     */
    fun cues(cards: List<RecapCard>, data: RecapData): List<SongCue> {
        val songs = data.topSongs
        val no1 = songs.firstOrNull()
        val no2 = songs.getOrNull(1) ?: no1
        val no5 = songs.getOrNull(RANKED - 1) ?: songs.lastOrNull()
        // Never the #1 before its reveal (artistTopSongs excludes it): none = keep the song before.
        val topArtistSong = data.topArtists.firstOrNull()?.let { data.artistTopSongs[it.key] }
        var previous: SongCue = no5?.let(SongCue::Play) ?: SongCue.Silence
        return cards.map { card ->
            val explicit: TopTrackRow? = when (card) {
                is RecapCard.Intro, is RecapCard.Minutes -> no5
                is RecapCard.Genres -> data.genreSong ?: no5
                is RecapCard.TopArtist, is RecapCard.TopArtists -> topArtistSong
                is RecapCard.TopSong, is RecapCard.Summary -> no1
                is RecapCard.TopSongs -> no2
                else -> null
            }
            val cue = when {
                card is RecapCard.Quiz -> SongCue.Silence
                explicit != null -> SongCue.Play(explicit)
                previous is SongCue.Play -> previous
                else -> no1?.let(SongCue::Play) ?: SongCue.Silence
            }
            if (cue is SongCue.Play) previous = cue
            cue
        }
    }

    /** How long a card stays up before moving on by itself (ms). */
    fun durationMs(card: RecapCard): Long = when {
        card is RecapCard.Summary -> SUMMARY_MS
        card.isReveal -> REVEAL_MS
        else -> CARD_MS
    }

    /** Covers on the busiest-day card when its song repeats an earlier hero. */
    const val DAY_TOP = 3

    private fun songKeyOf(row: TopTrackRow): String = ListeningInsights.songKey(row.track.title, row.track.artist)

    const val CARD_MS = 6_000L
    const val REVEAL_MS = 9_000L
    const val SUMMARY_MS = 12_000L
}
