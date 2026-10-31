package com.stash.feature.record.importer

import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.RecordFlags

/** A play the archive keeps, as the merge reads it (plays.ts `Kept`): its id, title, start, time played and flags. */
data class KeptPlay(val id: String, val t: String, val s: Long, val ms: Long, val f: Int)

/** The archive's plays, a page at a time (all of them, in any order). */
fun interface KeptPlays {
    suspend fun forEachPage(block: (List<KeptPlay>) -> Unit)
}

/** What importing an export does to the archive (plays.ts `MergePlan`). */
internal class MergePlan(
    /** The export's plays to write: indexes into its [ExportPlays], in the order the merge took them. */
    val add: IntArray,
    /** Kept plays that go: Account plays an Extended play now replaces, scrobbles a Spotify play covers. */
    val drop: List<String>,
    /** Plays kept already (or repeated inside the import), and Account plays whose Extended twin was kept already. */
    val same: Int,
    /** Account plays that are Extended plays in the same files (the two packages overlap by a year). */
    val overlap: Int,
    /** Each song's folded words (`textKey`, by the song's index in [ExportPlays]): a play's id is `textKey|start`. */
    val textKeys: Array<String>,
)

/**
 * plays.ts `planMerge`, ported rule for rule. Pure and order-independent: the same files in any order, in one import or several,
 * give the same archive.
 *
 * - **The same play twice** (an id kept already, or repeated in the files) is kept once.
 * - **Account vs Extended:** an Account play and an Extended play are one play when their titles fold the same (the artist is
 *   ignored: Extended names the album's artist, Account the song's) and their ends fall in the same or an adjacent UTC minute.
 *   The Extended one wins: Extended rows are taken before Account rows (each kind by start, then title, then artist, in plain
 *   UTF-16 order), an Extended play coming later drops the Account play kept before, and an Account play left beside its
 *   Extended twin in the archive (an import stopped between its writes) is dropped by the next merge.
 * - **Counting:** an Account row whose twin was kept already is `same`; one whose twin comes in the same files is `overlap`.
 * - Scrobbles a Spotify play covers (the same title, starting within 180 s of the play) go; Stash's own plays are another
 *   player's and never anyone's twin.
 *
 * Kept small for half a million plays: words are folded once per song and per title, and a play's id and its "title at a minute"
 * are each one number (the folded words' index beside the start or the minute), never a string per play. The archive is read
 * twice at most, a page at a time: once to index it, and once more only when it holds Account plays or scrobbles to drop.
 */
internal object PlanMerge {
    /** Account and Extended rows of one song ending this close are one play (plays.ts `SAME_PLAY_MS`). */
    const val SAME_PLAY_MS = 60_000L

    /** A scrobble within this before a play's start, or after its end, is that play (plays.ts `SCROBBLE_SLACK_MS`). */
    const val SCROBBLE_SLACK_MS = 180_000L

    suspend fun plan(kept: KeptPlays, plays: ExportPlays): MergePlan = Planner(plays).run(kept)

    private class Scrobble(val id: String, val s: Long)

    private class Planner(private val plays: ExportPlays) {
        private val textKeyIds = HashMap<String, Int>()
        private val foldIds = HashMap<String, Int>()
        private val foldOfTitle = HashMap<String, Int>()
        private val foldOfArtist = HashMap<String, String>()

        private val ids = LongSet()
        private val extAny = LongSet()
        private val extOld = LongSet()
        private val accAt = HashMap<Long, MutableList<String>>()
        private val scrobbles = HashMap<Int, MutableList<Scrobble>>()
        private val dropped = HashSet<String>()
        private val drop = ArrayList<String>()

        private fun textKeyId(k: String): Int = textKeyIds.getOrPut(k) {
            check(textKeyIds.size < MAX_KEYS) { "more songs than a merge can tell apart" }
            textKeyIds.size
        }

        private fun titleFold(t: String): Int = foldOfTitle.getOrPut(t) { foldIds.getOrPut(SongKey.fold(t)) { foldIds.size } }

        suspend fun run(kept: KeptPlays): MergePlan {
            // Each export song's folded words, once
            val songs = plays.songs
            val songKey = IntArray(songs)
            val songFold = IntArray(songs)
            val textKeys = Array(songs) { s ->
                val title = plays.titleOfSong(s)
                val artist = plays.artistOfSong(s)
                val tk = "${SongKey.fold(title)}|${foldOfArtist.getOrPut(artist) { SongKey.fold(artist) }}"
                songKey[s] = textKeyId(tk)
                songFold[s] = titleFold(title)
                tk
            }

            // The archive, indexed (plays.ts `index(r, true)`)
            kept.forEachPage { page ->
                for (r in page) {
                    keptId(r.id)?.let(ids::add)
                    // The phone's plays are another player's: never an Account or Extended twin
                    if (r.f and RecordFlags.STASH != 0) continue
                    val fold = titleFold(r.t)
                    if (r.f and RecordFlags.LASTFM != 0) {
                        scrobbles.getOrPut(fold) { ArrayList(2) } += Scrobble(r.id, r.s)
                        continue
                    }
                    val k = spot(fold, r.s + r.ms) ?: continue
                    if (r.f and RecordFlags.ACCOUNT != 0) {
                        accAt.getOrPut(k) { ArrayList(1) } += r.id
                    } else {
                        extAny.add(k)
                        extOld.add(k)
                    }
                }
            }

            // The archive's own leftovers: an Account play an Extended one kept already covers, a scrobble a Spotify play covers
            if (accAt.isNotEmpty() || scrobbles.isNotEmpty()) {
                kept.forEachPage { page ->
                    for (r in page) {
                        if (r.f and (RecordFlags.ACCOUNT or RecordFlags.LASTFM) == 0) dropAround(titleFold(r.t), r.s + r.ms)
                        if (r.f and RecordFlags.LASTFM == 0) dropScrobbles(titleFold(r.t), r.s, r.ms)
                    }
                }
            }

            // Extended first (then an Account row in the same import finds its Extended twin), each kind by start, then words
            val order = (0 until plays.size).sortedWith { a, b ->
                val sa = if (plays.isAccount(a)) 1 else 0
                val sb = if (plays.isAccount(b)) 1 else 0
                when {
                    sa != sb -> sa - sb
                    plays.start(a) != plays.start(b) -> plays.start(a).compareTo(plays.start(b))
                    else -> plays.title(a).compareTo(plays.title(b)).takeIf { it != 0 } ?: plays.artist(a).compareTo(plays.artist(b))
                }
            }
            val add = IntArray(plays.size)
            var added = 0
            var same = 0
            var overlap = 0
            for (i in order) {
                val song = plays.songOf(i)
                val start = plays.start(i)
                val end = plays.end(i)
                val id = pack(songKey[song], start) ?: error("a play outside the times the import reads")
                if (ids.contains(id)) {
                    same++
                    continue
                }
                val fold = songFold[song]
                if (plays.isAccount(i)) {
                    var twin = false
                    var keptTwin = false
                    forNear(fold, end) { k ->
                        if (extAny.contains(k)) {
                            twin = true
                            if (extOld.contains(k)) keptTwin = true
                        }
                    }
                    if (twin) {
                        if (keptTwin) same++ else overlap++
                        continue
                    }
                } else {
                    dropAround(fold, end)
                }
                dropScrobbles(fold, start, plays.ms(i))
                // plays.ts `index(r, false)`. An Account play added here is never looked for again: every Extended play,
                // the only kind that drops one, came before it.
                ids.add(id)
                if (!plays.isAccount(i)) spot(fold, end)?.let(extAny::add)
                add[added++] = i
            }
            return MergePlan(add.copyOf(added), drop, same, overlap, textKeys)
        }

        /** plays.ts `dropAround`: the Account plays kept at this title in this minute or the ones beside it go. */
        private fun dropAround(fold: Int, endedAt: Long) {
            forNear(fold, endedAt) { k ->
                accAt.remove(k)?.forEach { if (dropped.add(it)) drop += it }
            }
        }

        /** plays.ts `dropScrobbles`: the scrobbles of this title that this play covers go. */
        private fun dropScrobbles(fold: Int, s: Long, ms: Long) {
            val list = scrobbles[fold] ?: return
            for (x in list) {
                if (x.s >= s - SCROBBLE_SLACK_MS && x.s <= s + ms + SCROBBLE_SLACK_MS && dropped.add(x.id)) drop += x.id
            }
        }

        /** The "title#minute" keys of plays.ts `near`: this minute and the ones either side. */
        private inline fun forNear(fold: Int, endedAt: Long, block: (Long) -> Unit) {
            val m = Math.floorDiv(endedAt, SAME_PLAY_MS)
            for (x in m - 1..m + 1) spotAt(fold, x)?.let(block)
        }

        /** plays.ts `spot`: a title at the minute a play ended, as one number. */
        private fun spot(fold: Int, endedAt: Long): Long? = spotAt(fold, Math.floorDiv(endedAt, SAME_PLAY_MS))

        private fun spotAt(fold: Int, minute: Long): Long? =
            if (minute < Int.MIN_VALUE || minute > Int.MAX_VALUE) null else (fold.toLong() shl 32) or (minute and 0xffffffffL)

        /**
         * A kept play's id as the number [pack] makes of an export play's, or null when it can't be one: no export play's id
         * has another shape (`textKey|start`, the start written as JavaScript writes a whole number), or a start outside the
         * times an export's plays can have.
         */
        private fun keptId(id: String): Long? {
            val bar = id.lastIndexOf('|')
            if (bar <= 0 || bar == id.length - 1) return null
            val digits = id.substring(bar + 1)
            if (digits.length > 18 || (digits.length > 1 && digits[0] == '0') || digits.any { it !in '0'..'9' }) return null
            return pack(textKeyId(id.substring(0, bar)), digits.toLong())
        }

        /** The folded words' index beside the start: one number per id (starts from 2006 less six hours, 69 years on). */
        private fun pack(key: Int, start: Long): Long? {
            val offset = start - START_BASE
            return if (offset < 0 || offset >= (1L shl START_BITS)) null else (key.toLong() shl START_BITS) or offset
        }
    }

    /** Ids pack a start in [START_BITS] bits from here: no export play starts before 2006 less its six-hour longest play. */
    private const val START_BASE = 1_136_073_600_000L - 6 * 3_600_000L - 1

    private const val START_BITS = 41

    /** Songs a merge's packed ids can tell apart (63 bits less the start's). */
    private const val MAX_KEYS = 1 shl (63 - START_BITS)
}

/** A set of longs with no boxing (open addressing): a merge holds one per play. */
internal class LongSet(initial: Int = 1024) {
    private var keys = LongArray(capacityFor(initial)).also { it.fill(EMPTY) }
    private var count = 0

    val size: Int get() = count

    /** True when [v] was new. */
    fun add(v: Long): Boolean {
        require(v != EMPTY)
        if ((count + 1) * 2 > keys.size) rehash(keys.size * 2)
        val mask = keys.size - 1
        var i = mix(v) and mask
        while (true) {
            val k = keys[i]
            if (k == EMPTY) {
                keys[i] = v
                count++
                return true
            }
            if (k == v) return false
            i = (i + 1) and mask
        }
    }

    fun contains(v: Long): Boolean {
        val mask = keys.size - 1
        var i = mix(v) and mask
        while (true) {
            val k = keys[i]
            if (k == EMPTY) return false
            if (k == v) return true
            i = (i + 1) and mask
        }
    }

    private fun rehash(n: Int) {
        val old = keys
        keys = LongArray(n).also { it.fill(EMPTY) }
        count = 0
        for (k in old) if (k != EMPTY) add(k)
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE

        fun capacityFor(n: Int): Int {
            var c = 16
            while (c < n * 2) c = c shl 1
            return c
        }

        /** A 64-bit mix (the finaliser of MurmurHash3) folded to an int. */
        fun mix(v: Long): Int {
            var h = v
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL
            h = h xor (h ushr 33)
            return h.toInt()
        }
    }
}
