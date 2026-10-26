package com.stash.feature.record.archive

import com.stash.core.model.weblink.SongKey

/**
 * One of the phone's plays as the web reads it from the library file's `listens` (stash-player `src/lib/spotify/export.ts`
 * `PhonePlay`): the words, when it started, how long it played when known ([ms]) and the song's length when known ([d]).
 */
data class PhonePlay(
    val t: String,
    val a: String,
    val al: String? = null,
    val s: Long,
    val ms: Long? = null,
    val d: Long? = null,
)

/**
 * The web's rules for the phone's history, ported exactly so the record here keeps the rows Your record on the web would keep
 * from this phone's export, with the same ids:
 *
 * - [read]: export.ts `phoneRows`, one entry at a time (words cleaned and cut, times checked, lengths held to a day);
 * - [phoneRow]: plays.ts `phoneRow` (its time, else the song's length, else a guess marked estimated; skips `STASH|SKIPPED`;
 *   DONE is never set, since the phone doesn't say how a play ended);
 * - [planPhone]: plays.ts `planPhone` into an empty archive (each id once: plays before skips, each by start then title).
 */
object PhoneRows {
    /** Longest title, artist or album (export.ts `MAX_TEXT`), in UTF-16 units. */
    const val MAX_TEXT = 300

    /** A phone's play is kept up to a day (export.ts: a long mix or an audiobook chapter is real). */
    const val MAX_MS = 24 * 3_600_000L

    /** Plays before Spotify existed (2006-01-01 UTC, export.ts `MIN_T`) are a broken row. */
    const val MIN_T = 1_136_073_600_000L

    /** ...and so are plays in the future, past this slack (export.ts `okTime`). */
    const val FUTURE_MS = 2 * 86_400_000L

    /** export.ts `str`: each control character a space, trimmed as JavaScript trims, cut to [MAX_TEXT]; null when nothing is left. */
    fun str(v: String?): String? {
        if (v == null) return null
        val spaced = buildString(v.length) { for (c in v) append(if (c <= '\u001F' || c == '\u007F') ' ' else c) }
        val s = JsText.trim(spaced)
        return if (s.isEmpty()) null else JsText.slice(s, MAX_TEXT)
    }

    /** export.ts `okTime`: after 2006, and no later than two days from [now]. */
    fun okTime(t: Long, now: Long): Boolean = t >= MIN_T && t <= now + FUTURE_MS

    /**
     * One entry of the file's `plays` ([skip] false) or `skips` as the web takes it, or null where the web drops it as a broken
     * row: no words, a time out of range, or a skip that doesn't say how far it got.
     */
    fun read(t: String?, a: String?, al: String?, s: Long, ms: Long?, d: Long?, now: Long, skip: Boolean): PhonePlay? {
        val title = str(t) ?: return null
        val artist = str(a) ?: return null
        if (!okTime(s, now)) return null
        val played = ms?.takeIf { it >= 0 }?.coerceAtMost(MAX_MS)
        if (skip && played == null) return null
        return PhonePlay(t = title, a = artist, al = str(al), s = s, ms = played, d = d?.takeIf { it in 1..MAX_MS })
    }

    /** plays.ts `phoneRow`. [textKey] is the song's folded words ([SongKey.textKey]), passed in when the caller keeps them. */
    fun phoneRow(p: PhonePlay, skip: Boolean, textKey: String = SongKey.textKey(p.t, p.a)): ArchivePlay {
        val estimated = p.ms == null && !skip
        val f = RecordFlags.STASH or (if (skip) RecordFlags.SKIPPED else 0) or (if (estimated) RecordFlags.ESTIMATED else 0)
        return ArchivePlay(
            id = "$textKey|${p.s}",
            t = p.t,
            a = p.a,
            al = p.al?.takeIf { it.isNotEmpty() },
            s = p.s,
            ms = p.ms ?: p.d ?: GUESS_MS,
            f = f,
        )
    }

    /**
     * planPhone's order: by start, then title in plain UTF-16 order (never a Collator). Stable, as JavaScript's sort is, so
     * equal rows keep the file's order.
     */
    val ORDER: Comparator<PhonePlay> = compareBy<PhonePlay> { it.s }.thenComparator { x, y -> x.t.compareTo(y.t) }

    /** plays.ts `planPhone` into an empty archive: the rows to add, each id once (a play wins over a skip with its id). */
    fun planPhone(plays: List<PhonePlay>, skips: List<PhonePlay>): List<ArchivePlay> {
        val ids = HashSet<String>()
        val out = ArrayList<ArchivePlay>(plays.size + skips.size)
        for ((list, skip) in listOf(plays to false, skips to true)) {
            for (p in list.sortedWith(ORDER)) {
                val r = phoneRow(p, skip)
                if (ids.add(r.id)) out += r
            }
        }
        return out
    }
}
