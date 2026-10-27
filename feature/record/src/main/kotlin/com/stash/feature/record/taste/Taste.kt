package com.stash.feature.record.taste

import com.stash.core.model.weblink.Credits
import com.stash.core.model.weblink.JsString
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.RecordFlags
import kotlinx.serialization.Serializable

/**
 * A play as the taste reads it (stash-player `src/lib/spotify/taste.ts` `TastePlay`): an archive row, or one of this phone's own
 * plays (`f = 0`, [ms] its length, at least 30 s). Not a data class: two plays with the same words are two plays.
 */
class TastePlay(
    val t: String,
    val a: String,
    /** Start, epoch ms UTC. */
    val s: Long,
    /** Time played. */
    val ms: Long,
    /** [RecordFlags] bits. */
    val f: Int,
    val al: String? = null,
    val sid: String? = null,
)

/** taste.ts `EraParams`. */
data class EraParams(val join: Double = Taste.ERA_JOIN, val window: Int = Taste.ERA_WINDOW, val maxMonths: Int = Taste.ERA_MAX_MONTHS)

/**
 * taste.ts `computeTaste`'s options: [hidden], song keys hidden on Spotify (kept out unless kept or put back); [kept], song keys
 * in the library here (likes, playlists): never kept out, never "lost"; [putBack], song keys put back from "Kept out of your
 * mixes": never kept out, but not kept (a put-back favourite not heard in a year is still lost).
 */
data class TasteOptions(
    val now: Long,
    val hidden: Set<String> = emptySet(),
    val kept: Set<String> = emptySet(),
    val putBack: Set<String> = emptySet(),
    val era: EraParams = EraParams(),
)

/** taste.ts `SongStat`. */
@Serializable
data class SongStat(
    val key: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val sid: String? = null,
    val starts: Int,
    val plays: Int,
    val full: Int,
    val chosen: Int,
    val skips: Int,
    val years: Int,
    val listenedMs: Long,
    val first: Long,
    val last: Long,
    val peak: Int,
    val peakAt: Long,
    val weight: Double,
    val depth: Double,
    val avoid: Boolean,
)

/** taste.ts `Era`: [artists] the era's three biggest, biggest first; [share] the first one's part of the era's time. */
@Serializable
data class Era(
    val start: Long,
    val end: Long,
    val artist: String,
    val artists: List<String>,
    val share: Double,
    val listenedMs: Long,
    val songs: List<String>,
)

@Serializable
data class Obsession(val key: String, val n: Int, val at: Long)

@Serializable
data class Found(val year: Int, val artists: Int)

@Serializable
data class TasteYear(val year: Int, val plays: Int, val listenedMs: Long, val newArtists: Int)

@Serializable
data class TopArtist(val name: String, val key: String, val plays: Int, val listenedMs: Long)

@Serializable
data class YearTop(val year: Int, val artists: List<String>, val songs: List<String>)

/** taste.ts `Taste`. [songs]: every song, by weight, best first. */
data class Taste(
    val v: Int = 1,
    val computedAt: Long,
    val first: Long,
    val last: Long,
    val plays: Int,
    val listenedMs: Long,
    val artists: Int,
    val songs: List<SongStat>,
    val eras: List<Era>,
    val constants: List<String>,
    val lost: List<String>,
    val obsessions: List<Obsession>,
    val found: Found?,
    val years: List<TasteYear>,
    val topArtists: List<TopArtist>,
    /** Artists by the summed weight of their songs (who you love now, years of it counted), best first: the mixes' seeds. */
    val seedArtists: List<String>,
    val yearTops: List<YearTop>,
) {
    companion object {
        private const val DAY = 86_400_000L
        private const val YEAR = 365.25 * DAY

        /** The taste weight's recency: halved every two years since the last listen. */
        const val RECENCY_HALF_LIFE = 2 * YEAR

        /** A play you picked counts this many times a play that rolled on. */
        const val CHOSEN_W = 2

        /** Each extra calendar year a song was played in adds this much. */
        const val LONGEVITY_W = 0.15

        /** Never in mixes: started at least this often and left early this often... */
        const val AVOID_STARTS = 6
        const val AVOID_RATE = 0.75

        /** ...or an interlude, intro, outro, skit or commentary left early half the time over 3 starts. */
        const val AVOID_INTERLUDE_STARTS = 3
        const val AVOID_INTERLUDE_RATE = 0.5

        /** A listen, not a skip (plays.ts `LISTEN_MS`). */
        const val LISTEN_MS = 30_000L

        /** A lost favourite: listened to at least this often, not once in the last year. */
        const val LOST_PLAYS = 8
        const val LOST_AFTER = 365 * DAY

        /** An obsession: this many listens inside 14 days. */
        const val OBSESSION_PLAYS = 12
        const val OBSESSION_WINDOW = 14 * DAY

        /** The year you found the most new artists counts from this many. */
        const val FOUND_MIN = 20

        /** Eras (spec §4.4). */
        const val ERA_MONTH_MIN_MS = 2 * 3_600_000L
        const val ERA_JOIN = 0.4
        const val ERA_MAX_GAP_MONTHS = 3
        const val ERA_MIN_MONTHS = 2
        const val ERA_WINDOW = 2
        const val ERA_MAX_MONTHS = 4
        const val ERA_MIN_MS = 8 * 3_600_000L
        const val ERA_SONGS = 50

        /** An era's lead stands out: at least this many times the time of the era's average artist. */
        const val ERA_STANDOUT = 3

        /** A year's songs, most listened first: at most this many. */
        const val YEAR_SONGS = 50

        /** At most this many songs kept per list. */
        const val LIST_MAX = 500
        const val SEED_ARTISTS = 30

        /**
         * taste.ts `INTERLUDE`, `/\b(?:interlude|intro|outro|skit|commentary)\b/i`, with JavaScript's classes spelled out: its
         * `\b` is the boundary of ASCII `\w` (`[A-Za-z0-9_]`; ICU's and Java's newer `\b` are Unicode-aware), its `/i` folds ASCII
         * letters only. Tested against the song's display title (its most common spelling).
         */
        val INTERLUDE: Regex = Regex(
            "(?<![A-Za-z0-9_])(?:" + listOf("interlude", "intro", "outro", "skit", "commentary").joinToString("|") { JsString.ascii(it) } +
                ")(?![A-Za-z0-9_])",
        )

        /** taste.ts `artistKey`: the primary artist, folded. */
        fun artistKey(artist: String): String = SongKey.fold(Credits.primary(artist))

        /** plays.ts `listened`. */
        fun listened(p: TastePlay): Boolean = p.ms >= LISTEN_MS

        /** plays.ts `wasSkipped`: Spotify's skipped flag, or under 30 s. */
        fun wasSkipped(p: TastePlay): Boolean = p.f and RecordFlags.SKIPPED != 0 || p.ms < LISTEN_MS

        /** taste.ts `round9`: `Math.round(x · 1e9) / 1e9`, half up (towards +∞), as JavaScript's `Math.round` rounds. */
        fun round9(x: Double): Double {
            val y = x * 1e9
            val r = Math.floor(y)
            return (if (y - r >= 0.5) r + 1.0 else r) / 1e9
        }

        /** The play order on every platform (taste.ts): start, then title, then artist (UTF-16 units), then length. Stable. */
        val ORDER: Comparator<TastePlay> = Comparator { x, y ->
            when {
                x.s != y.s -> x.s.compareTo(y.s)
                x.t != y.t -> x.t.compareTo(y.t)
                x.a != y.a -> x.a.compareTo(y.a)
                else -> x.ms.compareTo(y.ms)
            }
        }

        /** taste.ts `computeTaste`. */
        fun compute(plays: List<TastePlay>, opts: TasteOptions): Taste = Computation(opts).run(plays)
    }
}

/** A count per value in the order first seen (taste.ts's `Map<string, number>` of spellings). */
private class Counts {
    private val keys = ArrayList<String>(1)
    private val counts = ArrayList<Int>(1)

    fun add(k: String) {
        // a song has a spelling or two: a scan beats a map
        for (i in keys.indices) {
            if (keys[i] == k) {
                counts[i] = counts[i] + 1
                return
            }
        }
        keys += k
        counts += 1
    }

    /** taste.ts `mode`: the most frequent value; a tie to the one seen first. */
    fun mode(): String? {
        var best: String? = null
        var n = -1
        for (i in keys.indices) if (counts[i] > n) {
            best = keys[i]
            n = counts[i]
        }
        return best
    }
}

/** A sum per key in the order first added (a JavaScript `Map<string, number>`). */
private typealias Sums = LinkedHashMap<String, Long>

private fun Sums.plus(k: String, x: Long) {
    put(k, (get(k) ?: 0L) + x)
}

private class Computation(private val opts: TasteOptions) {
    private class Acc(val key: String, val firstTitle: String, val firstArtist: String) {
        var sid: String? = null
        var starts = 0
        var plays = 0
        var full = 0
        var chosen = 0
        var skips = 0
        var listenedMs = 0L
        var first = Long.MAX_VALUE
        var last = 0L
        val titles = Counts()
        val artists = Counts()
        var albums: Counts? = null
        val yearSet = HashSet<Int>(4)
        var times = LongArray(4)
        var n = 0

        fun time(t: Long) {
            if (n == times.size) times = times.copyOf(times.size * 2)
            times[n++] = t
        }
    }

    private class ArtistAgg {
        val names = Counts()
        var plays = 0
        var ms = 0L
    }

    private class YearAgg {
        var plays = 0
        var ms = 0L
        var newArtists = 0
    }

    // Words worked out once per spelling: the folds are the costly part, and a history has far fewer spellings than plays
    private val songKeys = HashMap<String, HashMap<String, String>>()
    private val artistKeys = HashMap<String, String>()
    private val primaries = HashMap<String, String>()

    private fun songKey(t: String, a: String): String = songKeys.getOrPut(t) { HashMap(2) }.getOrPut(a) { SongKey.songKey(t, a) }

    private fun artistKey(a: String): String = artistKeys.getOrPut(a) { Taste.artistKey(a) }

    private fun primary(a: String): String = primaries.getOrPut(a) { Credits.primary(a) }

    fun run(input: List<TastePlay>): Taste {
        val now = opts.now
        val sorted = input.sortedWith(Taste.ORDER)
        val by = LinkedHashMap<String, Acc>()
        val artistFirst = HashMap<String, Long>()
        val artistAgg = LinkedHashMap<String, ArtistAgg>()
        val yearAgg = LinkedHashMap<Int, YearAgg>()
        val months = LinkedHashMap<Int, Sums>()
        val monthSongs = LinkedHashMap<Int, Sums>()
        val yearSongs = LinkedHashMap<Int, Sums>()
        val yearArtists = LinkedHashMap<Int, Sums>()
        var total = 0
        var totalMs = 0L
        var firstListen = 0L
        var lastListen = 0L
        for (p in sorted) {
            val key = songKey(p.t, p.a)
            val s = by.getOrPut(key) { Acc(key, p.t, p.a) }
            s.starts++
            s.titles.add(p.t)
            s.artists.add(p.a)
            if (!p.al.isNullOrEmpty()) (s.albums ?: Counts().also { s.albums = it }).add(p.al)
            if (!p.sid.isNullOrEmpty() && s.sid == null) s.sid = p.sid
            if (Taste.wasSkipped(p)) s.skips++
            if (!Taste.listened(p)) continue
            s.plays++
            val reasons = p.f and RecordFlags.REASONS != 0
            if (if (reasons) p.f and RecordFlags.DONE != 0 else true) s.full++
            if (reasons && p.f and RecordFlags.CHOSEN != 0) s.chosen++
            s.listenedMs += p.ms
            val y = UtcDate.yearOf(p.s)
            s.yearSet += y
            yearSongs.getOrPut(y) { Sums() }.plus(key, 1)
            s.first = minOf(s.first, p.s)
            s.last = maxOf(s.last, p.s)
            s.time(p.s)
            total++
            totalMs += p.ms
            if (firstListen == 0L) firstListen = p.s
            lastListen = p.s
            val ak = artistKey(p.a)
            if (ak.isNotEmpty()) {
                val ya = yearAgg.getOrPut(y) { YearAgg() }
                ya.plays++
                ya.ms += p.ms
                if (!artistFirst.containsKey(ak)) {
                    artistFirst[ak] = p.s
                    ya.newArtists++
                }
                yearArtists.getOrPut(y) { Sums() }.plus(ak, p.ms)
                val aa = artistAgg.getOrPut(ak) { ArtistAgg() }
                aa.names.add(primary(p.a))
                aa.plays++
                aa.ms += p.ms
                val m = UtcDate.monthOf(p.s)
                months.getOrPut(m) { Sums() }.plus(ak, p.ms)
                monthSongs.getOrPut(m) { Sums() }.plus(key, 1)
            }
        }

        val songs = ArrayList<SongStat>(by.size)
        for (s in by.values) {
            // the busiest 14 days (times are in order: the plays were sorted)
            var peak = 0
            var peakAt = 0L
            var j = 0
            for (i in 0 until s.n) {
                while (s.times[i] - s.times[j] > Taste.OBSESSION_WINDOW) j++
                if (i - j + 1 > peak) {
                    peak = i - j + 1
                    peakAt = s.times[j]
                }
            }
            val years = s.yearSet.size
            val first = if (s.plays == 0) 0L else s.first
            val last = if (s.plays == 0) 0L else s.last
            val title = s.titles.mode() ?: s.firstTitle
            val artist = s.artists.mode() ?: s.firstArtist
            val album = s.albums?.mode()
            val love = StrictMath.log1p((s.full + Taste.CHOSEN_W * s.chosen).toDouble()) * (1 + Taste.LONGEVITY_W * maxOf(0, years - 1))
            // Rounded to 9 places: ties are exact ties on every platform (the phone's log and pow may differ in the last bit)
            val depth = Taste.round9(love)
            val weight = if (s.plays > 0) {
                Taste.round9(love * StrictMath.pow(0.5, maxOf(0L, now - last).toDouble() / Taste.RECENCY_HALF_LIFE))
            } else {
                0.0
            }
            val rate = if (s.starts > 0) s.skips.toDouble() / s.starts else 0.0
            val never = s.key in opts.kept || s.key in opts.putBack
            val avoid = !never && (
                s.key in opts.hidden ||
                    (s.starts >= Taste.AVOID_STARTS && rate >= Taste.AVOID_RATE) ||
                    (Taste.INTERLUDE.containsMatchIn(title) && s.starts >= Taste.AVOID_INTERLUDE_STARTS && rate >= Taste.AVOID_INTERLUDE_RATE)
                )
            songs += SongStat(
                key = s.key, title = title, artist = artist, album = album, sid = s.sid,
                starts = s.starts, plays = s.plays, full = s.full, chosen = s.chosen, skips = s.skips, years = years,
                listenedMs = s.listenedMs, first = first, last = last, peak = peak, peakAt = peakAt,
                weight = weight, depth = depth, avoid = avoid,
            )
        }
        songs.sortWith { a, b ->
            when {
                a.weight != b.weight -> b.weight.compareTo(a.weight)
                a.plays != b.plays -> b.plays.compareTo(a.plays)
                else -> a.key.compareTo(b.key)
            }
        }
        val stat = HashMap<String, SongStat>(songs.size * 2)
        for (s in songs) stat[s.key] = s
        fun avoided(k: String) = stat[k]?.avoid == true

        // Constants: in at least half the years you listened (3 to 5 of them), best kept first.
        val activeYears = yearAgg.size
        val needYears = minOf(5, maxOf(3, (activeYears + 1) / 2))
        val constants = songs.filter { !it.avoid && it.years >= needYears }
            .sortedWith { a, b ->
                when {
                    a.years != b.years -> b.years.compareTo(a.years)
                    a.plays != b.plays -> b.plays.compareTo(a.plays)
                    else -> a.key.compareTo(b.key)
                }
            }
            .take(Taste.LIST_MAX).map { it.key }

        // Lost: loved, not heard for a year, and not in your library here.
        val lost = songs.filter { !it.avoid && it.plays >= Taste.LOST_PLAYS && now - it.last >= Taste.LOST_AFTER && it.key !in opts.kept }
            .sortedWith { a, b -> if (a.depth != b.depth) b.depth.compareTo(a.depth) else a.key.compareTo(b.key) }
            .take(Taste.LIST_MAX).map { it.key }

        val obsessions = songs.filter { it.peak >= Taste.OBSESSION_PLAYS }
            .sortedWith { a, b -> if (a.peak != b.peak) b.peak.compareTo(a.peak) else a.key.compareTo(b.key) }
            .take(100).map { Obsession(it.key, it.peak, it.peakAt) }

        val byCountThenKey = Comparator<Map.Entry<String, Long>> { a, b ->
            if (a.value != b.value) b.value.compareTo(a.value) else a.key.compareTo(b.key)
        }
        fun nameOf(k: String) = artistAgg[k]?.names?.mode() ?: k

        val yearTops = yearSongs.entries.sortedBy { it.key }.map { (year, m) ->
            YearTop(
                year = year,
                artists = yearArtists[year].orEmpty().entries.sortedWith(byCountThenKey).take(3).map { nameOf(it.key) },
                songs = m.entries.filter { !avoided(it.key) }.sortedWith(byCountThenKey).take(Taste.YEAR_SONGS).map { it.key },
            )
        }

        val years = yearAgg.entries.sortedBy { it.key }.map { (year, y) -> TasteYear(year, y.plays, y.ms, y.newArtists) }
        // Everything is new in your first year: the year you found the most counts from the second.
        var found: Found? = null
        for (y in years.drop(1)) if (y.newArtists >= Taste.FOUND_MIN && (found == null || y.newArtists > found.artists)) found = Found(y.year, y.newArtists)

        val topArtists = artistAgg.entries.map { (key, a) -> TopArtist(a.names.mode() ?: key, key, a.plays, a.ms) }
            .sortedWith { a, b -> if (a.listenedMs != b.listenedMs) b.listenedMs.compareTo(a.listenedMs) else a.key.compareTo(b.key) }
            .take(100)

        val artistWeight = LinkedHashMap<String, Double>()
        for (s in songs) if (!s.avoid && s.weight > 0) {
            val k = artistKey(s.artist)
            artistWeight[k] = (artistWeight[k] ?: 0.0) + s.weight
        }
        val seedArtists = artistWeight.entries
            .sortedWith { a, b -> if (a.value != b.value) b.value.compareTo(a.value) else a.key.compareTo(b.key) }
            .take(Taste.SEED_ARTISTS).map { nameOf(it.key) }

        return Taste(
            computedAt = now,
            first = firstListen,
            last = lastListen,
            plays = total,
            listenedMs = totalMs,
            artists = artistAgg.size,
            songs = songs,
            eras = Eras.find(months, monthSongs, ::nameOf, ::avoided, opts.era),
            constants = constants,
            lost = lost,
            obsessions = obsessions,
            found = found,
            years = years,
            topArtists = topArtists,
            seedArtists = seedArtists,
            yearTops = yearTops,
        )
    }
}

/**
 * taste.ts `findEras` (spec §4.4): months (UTC) as vectors of time per artist. Months with under 2 h are quiet and skipped. A
 * month joins the era running when it is like its last 2 active months (cosine ≥ 0.4), is no more than 3 months after its last
 * and the era started under 4 months before; else a new era starts. An era needs 2 months, 8 h and a lead artist who stands out
 * (3× the era's average artist). Two eras led by the same artist in a row are one.
 *
 * Every map is in insertion order and every sum and cosine runs in that order, as JavaScript's `Map`s do: a float's last bit
 * depends on the order it was added up in.
 */
private object Eras {
    private class Run(val from: Int, var to: Int, val months: ArrayList<Int>, val sum: Sums, var ms: Long)

    private class Kept(val start: Long, var end: Long, val artist: String, var listenedMs: Long, val lead: String, val runMonths: ArrayList<Int>)

    private fun sumOf(vs: List<Sums>): LinkedHashMap<String, Long> {
        val o = LinkedHashMap<String, Long>()
        for (v in vs) for ((k, x) in v) o.plus(k, x)
        return o
    }

    /** taste.ts `cosine`, rounded like the weights: a month right at the threshold falls on the same side on every platform. */
    fun cosine(a: Map<String, Long>, b: Map<String, Long>): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for ((k, v) in a) {
            val x = v.toDouble()
            na += x * x
            val w = b[k]
            if (w != null && w != 0L) dot += x * w.toDouble()
        }
        for (v in b.values) {
            val x = v.toDouble()
            nb += x * x
        }
        return if (na != 0.0 && nb != 0.0) Taste.round9(dot / Math.sqrt(na * nb)) else 0.0
    }

    fun find(
        months: LinkedHashMap<Int, Sums>,
        monthSongs: LinkedHashMap<Int, Sums>,
        nameOf: (String) -> String,
        avoided: (String) -> Boolean,
        p: EraParams,
    ): List<Era> {
        val runs = ArrayList<Run>()
        var cur: Run? = null
        for (m in months.keys.sorted()) {
            val v = months.getValue(m)
            var ms = 0L
            for (x in v.values) ms += x
            if (ms < Taste.ERA_MONTH_MIN_MS) continue
            val c = cur
            if (c != null && m - c.to <= Taste.ERA_MAX_GAP_MONTHS && m - c.from < p.maxMonths &&
                cosine(v, sumOf(c.months.takeLast(p.window).map { months.getValue(it) })) >= p.join
            ) {
                c.to = m
                c.months += m
                c.ms += ms
                for ((k, x) in v) c.sum.plus(k, x)
                continue
            }
            val next = Run(m, m, arrayListOf(m), Sums(v), ms)
            runs += next
            cur = next
        }
        val eras = ArrayList<Kept>()
        for (r in runs) {
            if (r.months.size < Taste.ERA_MIN_MONTHS || r.ms < Taste.ERA_MIN_MS) continue
            // the lead: the most time, a tie to the key first in string order
            var best = ""
            var n = -1L
            for ((k, x) in r.sum) if (x > n || (x == n && k < best)) {
                best = k
                n = x
            }
            if (n.toDouble() < Taste.ERA_STANDOUT * (r.ms.toDouble() / r.sum.size)) continue
            val prev = eras.lastOrNull()
            if (prev != null && prev.lead == best) {
                prev.end = UtcDate.monthStart(r.to + 1) - 1
                prev.listenedMs += r.ms
                prev.runMonths += r.months
                continue
            }
            eras += Kept(UtcDate.monthStart(r.from), UtcDate.monthStart(r.to + 1) - 1, nameOf(best), r.ms, best, ArrayList(r.months))
        }
        val byCountThenKey = Comparator<Map.Entry<String, Long>> { a, b ->
            if (a.value != b.value) b.value.compareTo(a.value) else a.key.compareTo(b.key)
        }
        return eras.map { e ->
            val counts = Sums()
            val leadMs = Sums()
            for (m in e.runMonths) {
                for ((k, c) in monthSongs[m].orEmpty()) counts.plus(k, c)
                for ((k, x) in months[m].orEmpty()) leadMs.plus(k, x)
            }
            val songs = counts.entries.filter { !avoided(it.key) }.sortedWith(byCountThenKey).take(Taste.ERA_SONGS).map { it.key }
            val top = leadMs.entries.sortedWith(byCountThenKey).take(3).map { nameOf(it.key) }
            Era(
                start = e.start,
                end = e.end,
                artist = e.artist,
                artists = top,
                share = if (e.listenedMs != 0L) (leadMs[e.lead] ?: 0L).toDouble() / e.listenedMs else 0.0,
                listenedMs = e.listenedMs,
                songs = songs,
            )
        }
    }
}

/** JavaScript's `Date` in UTC for what the taste asks of it: the year, the month (`y·12 + m`) and `Date.UTC(y, m, 1)`. */
internal object UtcDate {
    private const val DAY = 86_400_000L

    /** The civil date of epoch day [z] (Howard Hinnant's `civil_from_days`): year and month (1..12). */
    private fun civil(z0: Long): Pair<Long, Int> {
        val z = z0 + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return (if (m <= 2) y + 1 else y) to m
    }

    fun yearOf(t: Long): Int = civil(Math.floorDiv(t, DAY)).first.toInt()

    /** `getUTCFullYear() * 12 + getUTCMonth()`. */
    fun monthOf(t: Long): Int {
        val (y, m) = civil(Math.floorDiv(t, DAY))
        return (y * 12 + (m - 1)).toInt()
    }

    /** `Date.UTC(floor(m / 12), m % 12, 1)`. */
    fun monthStart(m: Int): Long {
        val y = Math.floorDiv(m, 12).toLong()
        val mo = Math.floorMod(m, 12) + 1
        return daysFromCivil(y, mo) * DAY
    }

    /** Howard Hinnant's `days_from_civil` (month 1..12, day 1). */
    private fun daysFromCivil(y0: Long, m: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
