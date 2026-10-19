package com.stash.feature.listening.stats

import com.stash.core.common.foldCase
import com.stash.core.common.matchesArtistCredits
import com.stash.core.common.primaryArtist
import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.db.dao.HourCount
import com.stash.core.data.db.dao.TopAlbumRow
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningRange
import kotlin.math.roundToInt

/**
 * The pure pieces of "Your listening" and Top: which ranges the chips offer, the honest "vs
 * before" comparison, the listening clock's peak and day split, and the top lists grouped the way
 * the Library groups (artists and albums by main act, one row per song). No Android, no clock:
 * every input is passed in, so each rule is unit-tested.
 */
internal object ListeningInsights {

    /** The range chips, in order (design: 7 days · 30 days · 3 months · 12 months · All time). */
    val RANGES: List<ListeningRange> by lazy {
        listOf(ListeningRange.Days7, ListeningRange.Days30, ListeningRange.Months3, ListeningRange.Months12, ListeningRange.AllTime)
    }

    /** The range a screen opens on when none (or an unknown one) is passed. */
    val DEFAULT_RANGE: ListeningRange get() = ListeningRange.Days30

    /** The chip range saved as [key]; anything not on the chips opens on [DEFAULT_RANGE]. */
    fun rangeOf(key: String?): ListeningRange =
        ListeningRange.fromKey(key)?.takeIf { it in RANGES } ?: DEFAULT_RANGE

    /** Fewer listens than this in a range: just the total and one line, no half-empty charts. */
    const val COLD_START_PLAYS = 10

    /** Ranges this long or longer show the day heatmap (13 weeks fit; shorter would be mostly blank). */
    const val HEATMAP_MIN_DAYS = 84L

    /** Rows read before grouping, so grouping (which merges rows) still leaves a full top 100. */
    const val SCAN_LIMIT = 300

    /** Artist credits read before grouping: enough to count every main act in the range exactly. */
    const val ARTIST_SCAN_LIMIT = 10_000

    /** Top lists stop here, after grouping. */
    const val TOP_LIMIT = 100

    // ── The "vs before" comparison ──────────────────────────────────────

    /** The two windows a delta compares: [current] and the same number of days just before it. */
    data class DeltaWindows(val current: DayRange, val previous: DayRange)

    /**
     * The windows for the "vs before" pill, or null when a comparison would mislead.
     *
     * Complete days only: the range's length ending **yesterday** against the same length before
     * it. Today is still going, so counting it would read as a drop every morning. Null for All
     * time (nothing before it) and when the earlier window starts before the first listen: a
     * window history doesn't fully cover would read as a big rise that is only history starting.
     */
    fun deltaWindows(range: ListeningRange, today: Long, firstListenDay: Long?): DeltaWindows? {
        if (firstListenDay == null || range == ListeningRange.AllTime) return null
        val length = range.toDayRange(today, firstListenDay).length
        val current = DayRange(today - length, today - 1)
        val previous = DayRange(current.fromDay - length, current.fromDay - 1)
        return DeltaWindows(current, previous).takeIf { firstListenDay <= previous.fromDay }
    }

    /** Listening time in [windows]' current window vs its previous one, from per-day counts. */
    fun deltaFromDays(days: Collection<DayCount>, windows: DeltaWindows): Int? {
        var now = 0L
        var before = 0L
        for (d in days) {
            if (d.day in windows.current) now += d.listenedMs
            if (d.day in windows.previous) before += d.listenedMs
        }
        return deltaPercent(now, before)
    }

    /**
     * The change from [previous] to [current] in whole percent, or null when there's nothing to
     * compare with (no listening at all before).
     */
    fun deltaPercent(current: Long, previous: Long): Int? {
        if (previous <= 0) return null
        return (((current - previous) * 100.0) / previous).roundToInt()
    }

    /** "vs the week before", "vs the 30 days before", … for the delta pill's caption. */
    fun previousPhrase(range: ListeningRange): String = when (range) {
        ListeningRange.Days7 -> "vs the week before"
        ListeningRange.Months12 -> "vs the year before"
        else -> "vs the ${range.label.lowercase()} before"
    }

    /** The "Playing from" line of a queue started from a top songs list: "Top songs · 30 days". */
    fun topSongsLabel(range: ListeningRange): String = "Top songs · ${range.label}"

    // ── The clock ───────────────────────────────────────────────────────

    /** Listens per hour 0..23 (hours without a listen 0). */
    fun hourly(hours: List<HourCount>): IntArray {
        val out = IntArray(24)
        for (h in hours) if (h.hour in 0..23) out[h.hour] += h.plays
        return out
    }

    /** The hour with the most listens (the earliest on a tie), or null when nothing was heard. */
    fun peakHour(hourly: IntArray): Int? {
        var best = -1
        for (h in hourly.indices) if (hourly[h] > 0 && (best < 0 || hourly[h] > hourly[best])) best = h
        return best.takeIf { it >= 0 }
    }

    /**
     * Share of listens per part of the day, in whole percent that add up to 100: morning 5-12,
     * afternoon 12-17, evening 17-22, night 22-5. Null when nothing was heard.
     */
    fun daySplit(hourly: IntArray): DaySplit? {
        val total = hourly.sum()
        if (total == 0) return null
        val parts = intArrayOf(0, 0, 0, 0)
        for (h in 0..23) parts[partOf(h)] += hourly[h]
        val pct = largestRemainder(parts, total)
        return DaySplit(morning = pct[0], afternoon = pct[1], evening = pct[2], night = pct[3])
    }

    /** 0 morning, 1 afternoon, 2 evening, 3 night. */
    fun partOf(hour: Int): Int = when (hour) {
        in 5..11 -> 0
        in 12..16 -> 1
        in 17..21 -> 2
        else -> 3
    }

    /** Whole percents of [parts] (summing to [total]) that add up to exactly 100. */
    private fun largestRemainder(parts: IntArray, total: Int): IntArray {
        val exact = parts.map { it * 100.0 / total }
        val floors = exact.map { it.toInt() }.toIntArray()
        var left = 100 - floors.sum()
        exact.indices.sortedByDescending { exact[it] - floors[it] }.forEach { i ->
            if (left > 0) {
                floors[i]++
                left--
            }
        }
        return floors
    }

    // ── Grouping the top lists ──────────────────────────────────────────

    /** The list key of an artist: one per main act, folded like `equals(ignoreCase = true)`. */
    fun artistKey(name: String): String = foldCase(name.primaryArtist().trim())

    /** The list key of an album: its title and main act, folded (SQLite's LOWER folds ASCII only). */
    fun albumKey(album: String, artist: String): String = foldCase(album.trim()) + "|" + artistKey(artist)

    /**
     * Top artists by main act: "Drake, Future" counts for Drake, as the Library's Artists tab and
     * artist pages group. Plays, songs and time add up; the cover and spelling are the busiest
     * credit's. A main act its own credit doesn't name ("Earth" from "Earth, Wind & Fire,
     * Chicago") or a blank one is left out, as the Library does: its artist page would be empty.
     * Most played first, then the one heard last.
     */
    fun byMainArtist(rows: List<TopArtistRow>): List<TopArtistRow> =
        rows.groupBy { artistKey(it.artist) }
            .mapNotNull { (_, group) ->
                val lead = group.maxWith(compareBy<TopArtistRow> { it.plays }.thenBy { it.lastPlayedAt })
                val name = lead.artist.primaryArtist().trim()
                if (name.isEmpty() || group.none { matchesArtistCredits(it.artist, "", name) }) return@mapNotNull null
                lead.copy(
                    artist = name,
                    plays = group.sumOf { it.plays },
                    songs = group.sumOf { it.songs },
                    lastPlayedAt = group.maxOf { it.lastPlayedAt },
                    listenedMs = group.sumOf { it.listenedMs },
                )
            }
            .sortedWith(compareByDescending<TopArtistRow> { it.plays }.thenByDescending { it.lastPlayedAt })

    /**
     * Top albums by title and main act, as the Library's Albums tab merges them (a collab credit
     * and its lead act's are one release): the artist shown, and passed to the album page, is the
     * main act, so the page finds the album's songs. Plays, songs and time add up; the cover is
     * the busiest row's.
     */
    fun byMainAlbum(rows: List<TopAlbumRow>): List<TopAlbumRow> =
        rows.groupBy { albumKey(it.album, it.artist) }
            .map { (_, group) ->
                val lead = group.maxWith(compareBy<TopAlbumRow> { it.plays }.thenBy { it.lastPlayedAt })
                lead.copy(
                    artist = lead.artist.primaryArtist(),
                    plays = group.sumOf { it.plays },
                    songs = group.sumOf { it.songs },
                    lastPlayedAt = group.maxOf { it.lastPlayedAt },
                    listenedMs = group.sumOf { it.listenedMs },
                )
            }
            .sortedWith(compareByDescending<TopAlbumRow> { it.plays }.thenByDescending { it.lastPlayedAt })

    /**
     * One row per song: library rows that are the same song (the same title and main act, ignoring
     * case, or the same ISRC) count together. Plays and time add up; the most played row (then the
     * one heard last) stands for the song: its cover, what Play plays, the now-playing mark. Only
     * what the screens show: the library itself is untouched.
     */
    /** The "same song" key [mergeSameSongs] uses first: title and main act, folded. */
    fun songKey(title: String, artist: String): String = foldCase(title.trim()) + "|" + artistKey(artist)

    fun mergeSameSongs(rows: List<TopTrackRow>): List<TopTrackRow> {
        if (rows.size < 2) return rows
        // Union-find over rows: two rows sharing either key are one song.
        val parent = IntArray(rows.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var c = i
            while (parent[c] != r) c = parent[c].also { parent[c] = r }
            return r
        }
        val firstWithKey = HashMap<String, Int>()
        rows.forEachIndexed { i, row ->
            val keys = buildList {
                add("t:" + songKey(row.track.title, row.track.artist))
                row.track.isrc?.trim()?.takeIf { it.isNotEmpty() }?.let { add("i:" + foldCase(it)) }
            }
            for (k in keys) {
                val j = firstWithKey.putIfAbsent(k, i) ?: continue
                parent[find(i)] = find(j)
            }
        }
        return rows.indices.groupBy(::find).values
            .map { idx ->
                val group = idx.map { rows[it] }
                val lead = group.maxWith(compareBy<TopTrackRow> { it.plays }.thenBy { it.lastPlayedAt }.thenBy { -it.track.id })
                lead.copy(
                    plays = group.sumOf { it.plays },
                    lastPlayedAt = group.maxOf { it.lastPlayedAt },
                    listenedMs = group.sumOf { it.listenedMs },
                )
            }
            .sortedWith(
                compareByDescending<TopTrackRow> { it.plays }.thenByDescending { it.lastPlayedAt }.thenBy { it.track.id },
            )
    }
}

/** Percent of listens in each part of the day; adds up to 100. */
data class DaySplit(val morning: Int, val afternoon: Int, val evening: Int, val night: Int)
