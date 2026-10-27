package com.stash.feature.record.lastfm

import com.stash.feature.record.archive.JsText
import com.stash.feature.record.art.ArtText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger

/** A scrobble as the import reads it (plays.ts `Scrobble`, the Worker's `HistoryPlay`): its words, and when it started (epoch s). */
data class Scrobble(val t: String, val a: String, val al: String? = null, val uts: Long)

/** One page of the listener's scrobbles (lastfm.ts `HistoryPage`), newest first. */
data class HistoryPage(val plays: List<Scrobble>, val page: Int, val pages: Int, val total: Int)

/**
 * Last.fm's `user.getRecentTracks` answer read by the web player's rules, so the phone keeps the scrobbles Your record on the web
 * keeps, with the same words (stash-player `worker/lastfm-history.ts` `historyPage`, which the web's import reads through its
 * Worker; the phone asks Last.fm itself, [LastfmPages]):
 *
 * - the song playing now (listed first, `@attr.nowplaying`, no date) isn't a scrobble yet: left out;
 * - a title or an artist that isn't clean ([ArtText.clean]: whitespace runs one space, trimmed, at most 300 UTF-16 units, no
 *   control or bidi character) leaves the scrobble out; an album that isn't is simply dropped;
 * - a time before Last.fm began ([MIN_UTS]) or more than a day ahead of now is a broken row;
 * - Last.fm's "nothing to read here" (6: no such user; 17: a profile that hides its listening) is an empty last page; any other
 *   error, or an answer that isn't a page, is the Worker's "try again" ([read] returns null).
 *
 * Numbers are read as JavaScript's `Number()` reads them ([jsNumber]), then cut to whole ones.
 */
object LastfmHistory {
    /** Scrobbles asked for a page (Last.fm's most). */
    const val HISTORY_LIMIT = 200

    /** 2002-01-01 UTC, epoch s: Last.fm (Audioscrobbler) began then; a scrobble before it is a broken row. */
    const val MIN_UTS = 1_009_843_200L

    /** A scrobble may be stamped up to a day ahead (a phone's clock that runs fast). */
    const val AHEAD_S = 86_400L

    /** Last.fm's codes meaning "nothing to read here": invalid parameters (no such user), login required (a private profile). */
    val EMPTY_ERRORS = setOf(6, 17)

    /**
     * The page [body] is, for the request of page [page], read at [nowS] (epoch s); null when it isn't one (the Worker answers
     * "retry" then, and the import waits and asks again).
     */
    fun read(body: JsonObject, page: Int, nowS: Long): HistoryPage? {
        val code = int(body["error"])
        if (code != 0.0) return if (code == 6.0 || code == 17.0) HistoryPage(emptyList(), page, 0, 0) else null
        // JavaScript's typeof is "object" for an array too: one passes, with no fields of its own
        val rt = when (val v = body["recenttracks"]) {
            is JsonObject -> v
            is JsonArray -> JsonObject(emptyMap())
            else -> return null
        }
        val attr = rt["@attr"] as? JsonObject ?: JsonObject(emptyMap())
        val plays = ArrayList<Scrobble>()
        for (item in list(rt["track"])) {
            val o = item as? JsonObject ?: continue
            if ((o["@attr"] as? JsonObject)?.string("nowplaying") == "true") continue
            val t = ArtText.clean(o.string("name"))
            val a = ArtText.clean((o["artist"] as? JsonObject)?.string("#text"))
            val uts = int((o["date"] as? JsonObject)?.get("uts"))
            if (t == null || a == null || uts < MIN_UTS || uts > nowS + AHEAD_S) continue
            val al = ArtText.clean((o["album"] as? JsonObject)?.string("#text"))
            plays += Scrobble(t = t, a = a, al = al, uts = uts.toLong())
        }
        val at = int(attr["page"])
        return HistoryPage(
            plays = plays,
            page = if (at != 0.0) whole(at) else page,
            pages = whole(int(attr["totalPages"])),
            total = whole(int(attr["total"])),
        )
    }

    /** The Worker's `list`: an array as it is, an object as a list of one, anything else none. */
    private fun list(v: JsonElement?): List<JsonElement> = when (v) {
        is JsonArray -> v
        is JsonObject -> listOf(v)
        else -> emptyList()
    }

    /** The Worker's `int`: a string or a number as JavaScript's `Number()` reads it, cut to a whole number; anything else 0. */
    internal fun int(v: JsonElement?): Double {
        val p = v as? JsonPrimitive ?: return 0.0
        if (p is JsonNull) return 0.0
        val n = if (p.isString) jsNumber(p.content) else p.content.toDoubleOrNull() ?: Double.NaN
        return if (n.isFinite()) truncate(n) else 0.0
    }

    /** A whole JavaScript number as an Int, held to Int's range (no page count comes near it). */
    private fun whole(n: Double): Int = n.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()

    private fun truncate(n: Double): Double = if (n < 0) kotlin.math.ceil(n) else kotlin.math.floor(n)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val DECIMAL = Regex("[+-]?(?:[0-9]+\\.?[0-9]*(?:[eE][+-]?[0-9]+)?|\\.[0-9]+(?:[eE][+-]?[0-9]+)?)")
    private val RADIX_DIGITS = mapOf(16 to Regex("[0-9a-fA-F]+"), 8 to Regex("[0-7]+"), 2 to Regex("[01]+"))

    /**
     * JavaScript's `Number(string)`: trimmed as JavaScript trims; empty is 0; `Infinity` with an optional sign; `0x`, `0o` and
     * `0b` literals (no sign); a decimal literal with an optional sign and exponent; anything else NaN.
     */
    internal fun jsNumber(raw: String): Double {
        val s = JsText.trim(raw)
        if (s.isEmpty()) return 0.0
        when (s) {
            "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
            "-Infinity" -> return Double.NEGATIVE_INFINITY
        }
        if (s.length > 2 && s[0] == '0') {
            val radix = when (s[1]) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                'b', 'B' -> 2
                else -> 0
            }
            if (radix != 0) {
                val digits = s.substring(2)
                return if (RADIX_DIGITS.getValue(radix).matches(digits)) BigInteger(digits, radix).toDouble() else Double.NaN
            }
        }
        return if (DECIMAL.matches(s)) s.toDouble() else Double.NaN
    }
}
