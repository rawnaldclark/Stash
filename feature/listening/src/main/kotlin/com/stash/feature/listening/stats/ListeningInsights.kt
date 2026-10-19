package com.stash.feature.listening.stats

import com.stash.core.common.primaryArtist
import com.stash.core.data.db.dao.HourCount
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.listening.DayRange
import com.stash.core.data.listening.ListeningRange
import kotlin.math.roundToInt

/**
 * The pure pieces of "Your listening" and Top: which ranges the chips offer, when a "vs before"
 * delta is honest, the listening clock's peak and day split, and artists grouped by their main
 * act. No Android, no clock: every input is passed in, so each rule is unit-tested.
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

    /**
     * The period to compare with, or null when the comparison would mislead: All time has nothing
     * before it, and a previous period that starts before the first listen isn't fully covered
     * by history (it would read as a huge rise that's only the history starting).
     */
    fun comparablePrevious(range: ListeningRange, today: Long, firstListenDay: Long?): DayRange? {
        if (firstListenDay == null) return null
        val previous = range.previous(today, firstListenDay) ?: return null
        return previous.takeIf { firstListenDay <= it.fromDay }
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

    /**
     * Top artists by main act: "Drake, Future" counts for Drake, as the Library's Artists tab and
     * artist pages group (primaryArtist). Plays, songs and time add up; the cover and spelling are
     * the busiest credit's. Most played first, then the one heard last.
     */
    fun byMainArtist(rows: List<TopArtistRow>): List<TopArtistRow> =
        rows.groupBy { it.artist.primaryArtist().lowercase() }
            .map { (_, group) ->
                val lead = group.maxWith(compareBy<TopArtistRow> { it.plays }.thenBy { it.lastPlayedAt })
                lead.copy(
                    artist = lead.artist.primaryArtist(),
                    plays = group.sumOf { it.plays },
                    songs = group.sumOf { it.songs },
                    lastPlayedAt = group.maxOf { it.lastPlayedAt },
                    listenedMs = group.sumOf { it.listenedMs },
                )
            }
            .sortedWith(compareByDescending<TopArtistRow> { it.plays }.thenByDescending { it.lastPlayedAt })
}

/** Percent of listens in each part of the day; adds up to 100. */
data class DaySplit(val morning: Int, val afternoon: Int, val evening: Int, val night: Int)
