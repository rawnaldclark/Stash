package com.stash.feature.record.mixes

import com.stash.core.model.weblink.Credits
import com.stash.core.model.weblink.SongIdentity
import com.stash.core.model.weblink.SongIndex
import com.stash.core.model.weblink.SongKey
import com.stash.core.model.weblink.SongRef
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.importer.JsDate
import com.stash.feature.record.taste.Era
import com.stash.feature.record.taste.SongStat
import com.stash.feature.record.taste.Taste
import com.stash.feature.record.taste.TopArtist
import java.time.Instant
import java.time.ZoneOffset

/** stash-player `src/lib/spotify/mixes.ts` `HistoryMixKind`, by the word in a mix's id. */
enum class MixKind(val wire: String) {
    ERA("era"), CONSTANTS("constants"), LOST("lost"), REPEAT("repeat"), STARTED("started"), YEAR("year"), ARTIST("artist"), WEEK("week");

    companion object {
        fun of(wire: String): MixKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A song the library here holds (a like, a song of one of the listener's own playlists): a mix's song you have plays your copy
 * (mixes.ts `Have`, the library's `LibraryItem`). Known by its words and ISRC, as the web library knows it ([key]).
 */
data class LibrarySong(
    val trackId: Long,
    override val title: String,
    override val artist: String,
    val album: String? = null,
    override val isrc: String? = null,
    /** 0 when the row doesn't know it (the web's `durationMs` undefined). */
    val durationMs: Long = 0,
    val art: String? = null,
) : SongIdentity {
    /** identity.ts `keyOf`: the stored key of a library song, `isrc:…` or the words. */
    val libraryKey: String get() = SongKey.descriptorKey(this)
}

/**
 * One song of a history mix (a `LibraryItem` on the web): the library's own when you have it ([lib]), else the history's words
 * (mixes.ts `statItem`: no source knows it yet, its first play finds it).
 */
data class MixSong(
    val title: String,
    val artist: String,
    val album: String?,
    /** The item's key: the library song's own ([LibrarySong.libraryKey]), else `descriptorKey` of the words. */
    val key: String,
    /** The taste's key for the song ([SongKey.songKey]): what the mix chose it by (never the item's key: §6.11). */
    val songKey: String,
    /** The history's Spotify id (the app's own: the matcher finds the song by it). */
    val sid: String? = null,
    val lib: LibrarySong? = null,
)

/** mixes.ts `HistoryMix`. [id] is the same across days for the same mix ("era:1693526400000"). */
data class HistoryMix(val id: String, val kind: MixKind, val title: String, val sub: String, val items: List<MixSong>)

/** mixes.ts `Have`: the library's songs by `sameSong` ([haveIndex]). */
typealias Have = SongIndex<LibrarySong>

/**
 * Mixes only years of history can make (stash-player `src/lib/spotify/mixes.ts` and `src/lib/ui/spotify/mix.ts`, spec §4.5): your
 * eras, the songs that stayed, the favourites you lost, what you had on repeat, how it started with each of your favourite
 * artists, each year's most played, your top artists' most played, and this week in years past. Built from the taste as the
 * record shows it ([putBackIn]) and, for "this week", the record's plays. Pure, rule for rule: the same taste gives the same mixes
 * with the same words as the web's ([MixWords] holds the words; their English is the web's).
 */
object HistoryMixes {
    /** Songs per mix, and the fewest that make one. */
    const val MIX_SIZE = 50
    const val MIX_MIN = 8

    /** "This week, years ago": the days either side of today's date looked at, in every past year. */
    const val WEEK_SPAN_DAYS = 3

    /** How many Home shows at once. */
    const val HOME_MIXES = 3

    /** A mix for each of this many top artists (by time listened), and "How it started" across this many. */
    const val ARTIST_MIXES = 10
    const val STARTED_ARTISTS = 50

    private const val DAY = 86_400_000L

    /**
     * mixes.ts `putBackIn`: the taste as the mixes read it, the songs [putBack] (put back from "Kept out of your mixes", and the
     * library's songs now: liked, in a playlist) no longer kept out, at once. The same taste when none of them is kept out.
     */
    fun putBackIn(t: Taste, putBack: Set<String>): Taste {
        if (putBack.isEmpty() || t.songs.none { it.avoid && it.key in putBack }) return t
        return t.copy(songs = t.songs.map { if (it.avoid && it.key in putBack) it.copy(avoid = false) else it })
    }

    /** mixes.ts `haveIndex`: the library's songs (likes first, then the playlists' songs), each once by `sameSong`. */
    fun haveIndex(items: Iterable<LibrarySong>): Have {
        val idx = SongIndex<LibrarySong>()
        for (i in items) if (!idx.has(i)) idx.add(i, i)
        return idx
    }

    private fun utcYear(t: Long): Int = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC).year
    private fun utcMonth(t: Long): Int = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC).monthValue - 1

    /**
     * mixes.ts `eraWhen`: "Late 2023", "Early 2018", "June 2022", "June 2022 – March 2023": an era's time as a person would say
     * it (UTC, like the eras). Its middle month from the integer middle of the two times, as `new Date((start + end) / 2)` takes it.
     */
    fun eraWhen(start: Long, end: Long, w: MixWords): String {
        val y = utcYear(start)
        val mid = utcMonth(Math.floorDiv(start + end, 2L))
        if (y != utcYear(end)) return w.span(w.monthYear(start), w.monthYear(end))
        if (utcMonth(start) == utcMonth(end)) return w.monthYear(start)
        return when {
            mid <= 3 -> w.early(y)
            mid >= 8 -> w.late(y)
            else -> w.mid(y)
        }
    }

    /** mixes.ts `andList`: "A", "A & B", "A, B & C". */
    fun andList(xs: List<String>, w: MixWords): String =
        if (xs.size <= 1) xs.firstOrNull() ?: "" else w.and(xs.dropLast(1).joinToString(w.comma), xs.last())

    /** mixes.ts `statItem`: a song of the history as a library song (its key the words', `descriptorKey` with no ISRC). */
    private fun statItem(title: String, artist: String, album: String?, songKey: String, sid: String?) =
        MixSong(title, artist, album?.takeIf { it.isNotEmpty() }, SongKey.textKey(title, artist), songKey, sid)

    private fun libItem(lib: LibrarySong, songKey: String, sid: String?) = MixSong(lib.title, lib.artist, lib.album, lib.libraryKey, songKey, sid, lib)

    /** A taste song as a mix song: the library's own when it has it (looked up as mixes.ts does, by the stat's own key first). */
    private fun itemOf(s: SongStat, have: Have?): MixSong =
        have?.find(SongRef(s.title, s.artist, isrc = null, key = s.key))?.let { libItem(it, s.key, s.sid) }
            ?: statItem(s.title, s.artist, s.album, s.key, s.sid)

    /** The song keys a mix of [keys] plays, in their order: songs the taste knows, never an avoided one, at most [MIX_SIZE]. */
    private fun playable(keys: List<String>, stats: Map<String, SongStat>): List<String> {
        val out = ArrayList<String>()
        for (k in keys) {
            val s = stats[k]
            if (s == null || s.avoid) continue
            out += k
            if (out.size >= MIX_SIZE) break
        }
        return out
    }

    /** mixes.ts `byFirst`: the first listen, then the key (UTF-16 order). */
    private val BY_FIRST: Comparator<SongStat> = Comparator { a, b -> if (a.first != b.first) a.first.compareTo(b.first) else a.key.compareTo(b.key) }

    /** The artist key of each song's credit, folded once per spelling ([Taste.artistKey]). */
    private class ArtistKeys {
        private val keys = HashMap<String, String>()
        fun of(artist: String): String = keys.getOrPut(artist) { Taste.artistKey(artist) }
    }

    /** mixes.ts `artistSongs`: one artist's songs you played most, never the ones you always skip. */
    fun artistSongs(t: Taste, key: String, n: Int = MIX_SIZE): List<SongStat> = artistSongs(t, key, n, ArtistKeys())

    private fun artistSongs(t: Taste, key: String, n: Int, keys: ArtistKeys): List<SongStat> =
        t.songs.filter { !it.avoid && it.plays > 0 && keys.of(it.artist) == key }
            // plays desc, then key (`a.key < b.key ? -1 : 1`: the keys are a taste's, never two the same)
            .sortedWith { a, b -> if (a.plays != b.plays) b.plays.compareTo(a.plays) else a.key.compareTo(b.key) }
            .take(n)

    /** mixes.ts `artistMix`: a top artist's songs you played most, since you first played them. Null under [MIX_MIN] songs. */
    fun artistMix(t: Taste, a: TopArtist, have: Have?, w: MixWords): HistoryMix? = artistMix(t, a, have, w, ArtistKeys())

    private fun artistMix(t: Taste, a: TopArtist, have: Have?, w: MixWords, keys: ArtistKeys): HistoryMix? {
        val all = artistSongs(t, a.key, Int.MAX_VALUE, keys)
        if (all.size < MIX_MIN) return null
        val since = all.minOf { it.first }
        return HistoryMix(
            "artist:${a.key}", MixKind.ARTIST, w.artistTitle(a.name), w.artistSub(utcYear(since)),
            all.take(MIX_SIZE).map { itemOf(it, have) },
        )
    }

    /**
     * mixes.ts `firstSongs`, "How it started": the first song you listened to by each of your top [n] artists (an avoided one passed
     * over for their next), in the order you first played them.
     */
    fun firstSongs(t: Taste, n: Int = STARTED_ARTISTS): List<SongStat> = firstSongs(t, n, ArtistKeys())

    private fun firstSongs(t: Taste, n: Int, keys: ArtistKeys): List<SongStat> {
        val want = t.topArtists.take(n).mapTo(HashSet()) { it.key }
        val first = LinkedHashMap<String, SongStat>()
        for (s in t.songs) {
            if (s.avoid || s.plays == 0) continue
            val a = keys.of(s.artist)
            if (a !in want) continue
            val f = first[a]
            if (f == null || BY_FIRST.compare(s, f) < 0) first[a] = s
        }
        return first.values.sortedWith(BY_FIRST)
    }

    /**
     * mixes.ts `historyMixes`: every history mix the taste can make, in a fixed order: the eras (newest first), the ones that
     * stayed, rediscover, on repeat, how it started, the years (newest first), then your top artists'.
     */
    fun historyMixes(t: Taste, have: Have?, w: MixWords): List<HistoryMix> {
        val stats = HashMap<String, SongStat>(t.songs.size * 2)
        for (s in t.songs) stats[s.key] = s
        val keys = ArtistKeys()
        val out = ArrayList<HistoryMix>()
        /** Makes the mix when it has MIX_MIN songs; the song keys it plays (none when it isn't made). */
        fun add(id: String, kind: MixKind, title: String, sub: String, songs: List<String>): List<String> {
            val played = playable(songs, stats)
            if (played.size < MIX_MIN) return emptyList()
            out += HistoryMix(id, kind, title, sub, played.map { itemOf(stats.getValue(it), have) })
            return played
        }
        for (e in t.eras.asReversed()) add("era:${e.start}", MixKind.ERA, w.dot(e.artist, eraWhen(e.start, e.end, w)), andList(e.artists, w), e.songs)
        val stayed = add("constants", MixKind.CONSTANTS, w.constantsTitle, w.constantsSub, t.constants).toHashSet()
        // Not the songs "The ones that stayed" already plays, told apart by song key (never the items' keys: a song you have here
        // is your copy, keyed by its ISRC when it has one)
        add("lost", MixKind.LOST, w.lostTitle, w.lostSub, t.lost.filter { it !in stayed })
        add("repeat", MixKind.REPEAT, w.repeatTitle, w.repeatSub, t.obsessions.map { it.key })
        add("started", MixKind.STARTED, w.startedTitle, w.startedSub, firstSongs(t, STARTED_ARTISTS, keys).map { it.key })
        // The year the taste was worked out in is still going
        val thisYear = utcYear(t.computedAt)
        for (y in t.yearTops.asReversed()) {
            add("year:${y.year}", MixKind.YEAR, if (y.year == thisYear) w.yearSoFar(y.year) else w.year(y.year), andList(y.artists, w), y.songs)
        }
        for (a in t.topArtists.take(ARTIST_MIXES)) artistMix(t, a, have, w, keys)?.let { out += it }
        return out
    }

    /** The order [weekMix] reads the record's plays in: by start, then by id (IndexedDB's index on `s`; UTF-16 order). */
    val BY_START: Comparator<ArchivePlay> = Comparator { a, b -> if (a.s != b.s) a.s.compareTo(b.s) else a.id.compareTo(b.id) }

    /** One song's count in [weekMix], with the words of the first play of it read (the first spelling wins). */
    private class Count(val title: String, val artist: String, val album: String?, val sid: String?) {
        var n = 0
        val years = LinkedHashSet<Int>()
    }

    /**
     * The windows "this week, years ago" reads, newest year first: [mid − 3 days, mid + 4 days) around today's UTC date in each
     * past year back to the taste's first listen's ([Taste.first], 0: none, only this year). A date the year doesn't have
     * (29 February) runs on into the next day, as `Date.UTC` has it.
     */
    fun weekWindows(t: Taste, now: Long): List<Triple<Int, Long, Long>> {
        val d = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC)
        val thisYear = d.year
        val firstYear = if (t.first != 0L) utcYear(t.first) else thisYear
        val out = ArrayList<Triple<Int, Long, Long>>()
        for (y in thisYear - 1 downTo firstYear) {
            val mid = JsDate.utc(y, d.monthValue - 1, d.dayOfMonth).toLong()
            out += Triple(y, mid - WEEK_SPAN_DAYS * DAY, mid + (WEEK_SPAN_DAYS + 1) * DAY)
        }
        return out
    }

    /**
     * mixes.ts `weekMix`, "This week, years ago": what you played in the 7 days around today's date in each past year, most
     * played first. [between] gives the record's plays that started in [from, to), by start then id (the archive's index on
     * `s`; the first spelling read is the one the mix shows). Null with too little (a mix of three songs isn't one).
     */
    fun weekMix(between: (from: Long, to: Long) -> List<ArchivePlay>, t: Taste, now: Long, have: Have?, w: MixWords): HistoryMix? {
        val counts = LinkedHashMap<String, Count>()
        val keys = HashMap<String, String>()
        for ((y, from, to) in weekWindows(t, now)) {
            for (p in between(from, to)) {
                if (p.ms < Taste.LISTEN_MS) continue
                val k = keys.getOrPut(p.t + '\u0000' + p.a) { SongKey.songKey(p.t, p.a) }
                val c = counts.getOrPut(k) { Count(p.t, p.a, p.al?.takeIf { it.isNotEmpty() }, p.sid) }
                c.n++
                c.years += y
            }
        }
        val avoid = t.songs.filter { it.avoid }.mapTo(HashSet()) { it.key }
        val ranked = counts.entries.filter { it.key !in avoid }
            .sortedWith { a, b -> if (a.value.n != b.value.n) b.value.n.compareTo(a.value.n) else a.key.compareTo(b.key) }
        if (ranked.size < MIX_MIN) return null
        val years = LinkedHashSet<Int>()
        for (e in ranked) years += e.value.years
        val items = ranked.take(MIX_SIZE).map { (k, c) ->
            have?.find(SongRef(c.title, c.artist))?.let { libItem(it, k, c.sid) } ?: statItem(c.title, c.artist, c.album, k, c.sid)
        }
        val d = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC)
        val sub = if (years.size == 1) w.weekOneYear(d.monthValue - 1, d.dayOfMonth, years.first()) else w.weekYears(d.monthValue - 1, d.dayOfMonth, years.size)
        return HistoryMix("week", MixKind.WEEK, w.weekTitle, sub, items)
    }

    /** mixes.ts `family`: Home's four groups. */
    private fun family(k: MixKind): String = when (k) {
        MixKind.ERA, MixKind.YEAR, MixKind.ARTIST -> k.wire
        else -> "one"
    }

    /** mixes.ts `rotation`: Home's turn through the mixes: an era, one of the single mixes, a year, an artist, and round again. */
    fun rotation(all: List<HistoryMix>): List<HistoryMix> {
        val groups = LinkedHashMap<String, MutableList<HistoryMix>>()
        for (m in all) groups.getOrPut(family(m.kind)) { ArrayList() } += m
        val out = ArrayList<HistoryMix>(all.size)
        var i = 0
        while (out.size < all.size) {
            for (g in groups.values) if (i < g.size) out += g[i]
            i++
        }
        return out
    }

    /**
     * mixes.ts `homePicks`: the few Home shows today: "this week" when there is one, then a daily turn through the rest in
     * [rotation]'s order (stable all day), starting at the local day number [dayNumber] (days since 1970 in the phone's zone).
     */
    fun homePicks(all: List<HistoryMix>, week: HistoryMix?, dayNumber: Long, n: Int = HOME_MIXES): List<HistoryMix> {
        val rest = rotation(all)
        val picks = ArrayList<HistoryMix>()
        if (week != null) picks += week
        if (rest.isEmpty()) return picks
        val start = Math.floorMod(dayNumber, rest.size.toLong()).toInt()
        var i = 0
        while (picks.size < n && i < rest.size) {
            picks += rest[(start + i) % rest.size]
            i++
        }
        return picks
    }

    /** The era a mix's id names ("era:{start}"), when the taste still has it. */
    private fun eraOf(m: HistoryMix, t: Taste): Era? = if (m.kind == MixKind.ERA) t.eras.firstOrNull { "era:${it.start}" == m.id } else null

    /** mix.ts `mixHeading`: an era's is its lead artist alone (the line under it says when); else the mix's own title. */
    fun mixHeading(m: HistoryMix, t: Taste): String = eraOf(m, t)?.artist ?: m.title

    /** mix.ts `mixLine`: an era's time and its artists ("Late 2023 · Sepultura, Slayer & Deftones"), else its own line. */
    fun mixLine(m: HistoryMix, t: Taste, w: MixWords): String =
        eraOf(m, t)?.let { w.dot(eraWhen(it.start, it.end, w), andList(it.artists, w)) } ?: m.sub

    /** mix.ts `GUESS_MS`, `LONGEST_MS`. */
    private const val GUESS_MS = 210_000.0
    private const val LONGEST_MS = 20 * 60_000.0

    /**
     * mix.ts `aboutLength`: about how long the mix plays: each song's own length, else what a listen of it lasted on average in
     * your history, else a guess. "about 45 min", "about 2 hr 50 min" (to five minutes, `Math.round` half up).
     */
    fun aboutLength(items: List<MixSong>, t: Taste, w: MixWords): String {
        val stats = HashMap<String, SongStat>(t.songs.size * 2)
        for (s in t.songs) stats[s.key] = s
        var ms = 0.0
        for (i in items) {
            val d = i.lib?.durationMs ?: 0L
            if (d != 0L) {
                ms += d
                continue
            }
            val s = stats[SongKey.songKey(i.title, i.artist)]
            val avg = if (s != null && s.plays != 0) s.listenedMs.toDouble() / s.plays else 0.0
            ms += if (avg > 0 && avg < LONGEST_MS) avg else GUESS_MS
        }
        val min = maxOf(5L, jsRound(ms / 300_000) * 5)
        return w.about(min.toInt())
    }

    /** JavaScript's `Math.round`: the nearest whole number, a half up (towards +∞). */
    private fun jsRound(x: Double): Long {
        val r = Math.floor(x)
        return (if (x - r >= 0.5) r + 1 else r).toLong()
    }

    /** mix.ts `MixCard`: what a mix's card on the Mixes tab says: its small line, its big words, the line under them, its foot, and its hue. */
    data class MixCard(val `when`: String, val who: String, val line: String, val foot: String, val hue: Int)

    /** format.ts `hueOf`: a hue from a name. */
    fun hueOf(s: String): Int {
        var h = 0
        for (c in s) h = (h * 31 + c.code) % 360
        return h
    }

    /** years.ts `sharePart`: the lead's part of the era, "34%", only from 10%. */
    private fun sharePart(e: Era): Int? = jsRound(e.share * 100).toInt().takeIf { it >= 10 }

    /** years.ts `hours`: whole hours, at least 1 once anything was listened to. */
    fun hours(ms: Long): Long = if (ms <= 0) 0 else maxOf(1L, jsRound(ms.toDouble() / 3_600_000))

    /**
     * mix.ts `card` (the Mixes tab's card). An artist's mix falls through to the "Lost favourites" words, as on the web (its card
     * is never shown: the artists' mixes open from Top artists).
     */
    fun card(m: HistoryMix, t: Taste, w: MixWords, stats: Map<String, SongStat> = t.songs.associateBy { it.key }): MixCard {
        val foot = w.songs(m.items.size)
        eraOf(m, t)?.let { e ->
            val others = e.artists.filter { it != e.artist }
            val share = sharePart(e)
            return MixCard(eraWhen(e.start, e.end, w), e.artist, if (others.isNotEmpty()) w.cardWith(andList(others, w)) else "", share?.let { w.cardShare(it) } ?: foot, hueOf(e.artist))
        }
        return when (m.kind) {
            MixKind.CONSTANTS -> {
                val most = maxOf(0, t.constants.maxOfOrNull { stats[it]?.years ?: 0 } ?: 0)
                MixCard(w.cardConstants, m.title, if (most != 0) w.cardUpToYears(m.sub, most) else m.sub, foot, 152)
            }
            MixKind.REPEAT -> {
                val top = t.obsessions.firstOrNull()
                val s = top?.let { stats[it.key] }
                MixCard(w.cardRepeat, m.title, if (s != null) w.cardObsession(s.title, top.n, w.monthYear(top.at)) else m.sub, foot, 330)
            }
            MixKind.WEEK -> MixCard(w.cardWeek, m.title, m.sub, foot, 200)
            MixKind.YEAR -> {
                val year = m.id.removePrefix("year:").toInt()
                val y = t.years.firstOrNull { it.year == year }
                val now = utcYear(t.computedAt) == year
                MixCard(if (now) w.cardYearNow else w.cardYear, w.number(year), m.sub, y?.let { w.cardHours(hours(it.listenedMs)) } ?: foot, Math.floorMod(year * 67, 360))
            }
            MixKind.STARTED -> {
                val f = firstSongs(t)
                val a = f.firstOrNull()
                val z = f.lastOrNull()
                val line = if (a != null && z != null && a !== z) w.cardFromTo(Credits.primary(a.artist), utcYear(a.first), Credits.primary(z.artist), utcYear(z.first)) else m.sub
                MixCard(w.cardStarted, m.title, line, foot, 265)
            }
            else -> MixCard(w.cardLost, m.title, m.sub, foot, 28)
        }
    }

    /** The Mixes tab's two lists (routes/spotify/+page.svelte): "This week" first, the eras oldest first, then the single mixes; and the years, oldest first. */
    fun mixesTab(all: List<HistoryMix>, week: HistoryMix?): Pair<List<HistoryMix>, List<HistoryMix>> {
        val eras = all.filter { it.kind == MixKind.ERA }.asReversed()
        val mixes = listOfNotNull(week) + eras + all.filter { it.kind != MixKind.ERA && it.kind != MixKind.YEAR && it.kind != MixKind.ARTIST }
        return mixes to all.filter { it.kind == MixKind.YEAR }.asReversed()
    }
}
