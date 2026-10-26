package com.stash.feature.record.archive

/**
 * The record page's own reading of the rows the app sends (stash-on-record `src/stash/app.ts` `archiveRow`): a row it can't
 * take is left out of the record, so the Home card leaves it out too. Words up to [MAX_TEXT] UTF-16 units (none empty), a
 * start, a time played from 0 to [MAX_MS], flags that are a 31-bit integer. It applies no time bounds past a start: the app's
 * rows never start before 2006 or more than two days ahead ([PhoneRows.read] drops those, as the web's import does).
 */
object PageRows {
    /** app.ts `MAX_TEXT`. */
    const val MAX_TEXT = 300

    /** app.ts `MAX_MS` for the app's rows: a phone's play is kept up to a day (a long mix, an audiobook chapter). */
    const val MAX_MS = 24 * 3_600_000L

    /** app.ts `MAX_FLAGS`. */
    const val MAX_FLAGS = 0x7fffffff

    fun accepts(r: ArchivePlay): Boolean =
        r.t.isNotEmpty() && r.t.length <= MAX_TEXT &&
            r.a.isNotEmpty() && r.a.length <= MAX_TEXT &&
            (r.al?.length ?: 0) <= MAX_TEXT && (r.sid?.length ?: 0) <= MAX_TEXT &&
            r.s > 0 && r.ms in 0..MAX_MS && r.f in 0..MAX_FLAGS
}

/** An artist as the record ranks them: by the name exactly as written, with the time played. */
data class RankedArtist(val name: String, val ms: Long)

/**
 * What the record shows on its first page, from the rows it keeps: how many it keeps (its `stash:ready` count), how many of
 * those are streams (30 s or more: the count every screen of the web's record calls "plays", and the card's), the time played,
 * the earliest start, and the artists played most (its `topItems(plays, 'artist', 'time')`).
 */
data class RecordSummary(
    val plays: Int,
    val streams: Int,
    val totalMs: Long,
    /** The earliest start, epoch ms; null with no rows. */
    val first: Long?,
    val topArtists: List<RankedArtist>,
)

/**
 * Builds a [RecordSummary] from the rows as the page receives them (stash-on-record `src/stash/app.ts` `archiveReceiver` at
 * 9dff865), one at a time, keeping totals and the ids seen.
 *
 * The page keeps the first row with an id and leaves out any later one, then hands the rows on sorted by id (IndexedDB's
 * order on the web) and sorts its plays by end time (start + time played), stably. `topItems` keys an artist by the name
 * exactly as written (no trim, no fold, credits never split) and ranks by the time summed, ties in the order each artist first
 * appears in that sorted list: the artist whose earliest-ending play ends first, then the one whose such play has the smaller
 * id (plain UTF-16 order). So each artist keeps its least (end, id).
 */
class RecordSummaryBuilder {
    private class Artist(val name: String, var ms: Long, var firstEnd: Long, var firstId: String)

    private val artists = HashMap<String, Artist>()
    private val ids = HashSet<String>()
    private var plays = 0
    private var streams = 0
    private var totalMs = 0L
    private var first = Long.MAX_VALUE

    fun add(r: ArchivePlay) {
        if (!PageRows.accepts(r) || !ids.add(r.id)) return
        plays++
        if (r.ms >= STREAM_MS) streams++
        totalMs += r.ms
        if (r.s < first) first = r.s
        val end = r.s + r.ms
        val a = artists[r.a]
        if (a == null) {
            artists[r.a] = Artist(r.a, r.ms, end, r.id)
        } else {
            a.ms += r.ms
            if (end < a.firstEnd || (end == a.firstEnd && r.id < a.firstId)) {
                a.firstEnd = end
                a.firstId = r.id
            }
        }
    }

    fun build(top: Int = TOP_ARTISTS): RecordSummary = RecordSummary(
        plays = plays,
        streams = streams,
        totalMs = totalMs,
        first = first.takeIf { plays > 0 },
        topArtists = artists.values
            .sortedWith(
                compareByDescending<Artist> { it.ms }
                    .thenBy { it.firstEnd }
                    .thenComparator { x, y -> x.firstId.compareTo(y.firstId) },
            )
            .take(top)
            .map { RankedArtist(it.name, it.ms) },
    )

    companion object {
        /** The card's row of faces (record-card.ts `CARD_ARTISTS`); the first is "Most played". */
        const val TOP_ARTISTS = 5

        /** A play of this long or more is a stream (stats.ts `COUNTED_PLAY_MS`). */
        const val STREAM_MS = 30_000L
    }
}
