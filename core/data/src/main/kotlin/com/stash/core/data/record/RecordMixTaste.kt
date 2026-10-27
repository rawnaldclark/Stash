package com.stash.core.data.record

import com.stash.core.data.db.dao.RecordTasteDao
import com.stash.core.data.prefs.RecordInMixesPreference
import com.stash.core.model.weblink.Credits
import com.stash.core.model.weblink.SongKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Your record's taste says to the mixes (stash-player `src/lib/discover/mix.ts` `MixTaste`): the artists loved for years
 * ([artists], the taste's seed artists, best first), the songs never to offer ([avoid], song keys), and how much each song means
 * now ([weights], song key to 0..1). Songs are matched by their words only ([keyOf]: [SongKey.songKey], remaster marks dropped),
 * as the web matches them.
 *
 * Null wherever a mix reads it means no taste at all, and every mix is made exactly as it was before the record fed mixes.
 */
class MixTaste(
    val artists: List<String>,
    val avoid: Set<String>,
    val weights: Map<String, Double>,
) {
    /** A song's key, worked out once per spelling for the life of this taste (one mix run reads it for every recipe). */
    private val keys = ConcurrentHashMap<String, String>()

    fun keyOf(title: String, artist: String): String = keys.getOrPut("$title\u0000$artist") { SongKey.songKey(title, artist) }

    /** mix.ts `avoided`: the taste keeps this song out of mixes. */
    fun avoids(title: String, artist: String): Boolean = avoid.isNotEmpty() && keyOf(title, artist) in avoid

    /** The song's weight, 0..1 (0 when the taste doesn't know it). */
    fun weightOf(title: String, artist: String): Double = if (weights.isEmpty()) 0.0 else weights[keyOf(title, artist)] ?: 0.0

    companion object {
        /** mix.ts `SEEDS_MAX` (the app's TOP_ARTISTS_LIMIT): seed artists of a Daily Discover. */
        const val SEEDS_MAX = 8

        /** mix.ts `TASTE_SEEDS`: the taste's seed artists keep at least this many places beside this phone's own. */
        const val TASTE_SEEDS = 3

        /** mix.ts `TASTE_AFFINITY`: a song's taste weight counts this much of a song played here (the library slice). */
        const val TASTE_AFFINITY = 0.6

        /** One song of a taste as [of] reads it. */
        data class Song(val key: String, val weight: Double, val avoid: Boolean)

        /**
         * stash-player `src/lib/spotify/mix-taste.ts` `mixTasteOf`: [artists] the taste's seed artists; avoid the songs the taste
         * keeps out minus those [putBack]; each song's weight over the top weight of every song (avoided ones too), for the songs
         * not kept out with a weight above 0. A song put back has its weight like any other.
         */
        fun of(songs: List<Song>, artists: List<String>, putBack: Set<String> = emptySet()): MixTaste {
            var max = 0.0
            for (s in songs) max = maxOf(max, s.weight)
            val avoid = HashSet<String>()
            val weights = HashMap<String, Double>()
            for (s in songs) {
                val out = s.avoid && s.key !in putBack
                if (out) avoid += s.key else if (max > 0 && s.weight > 0) weights[s.key] = s.weight / max
            }
            return MixTaste(artists, avoid, weights)
        }

        /**
         * mix.ts `seedArtists`' blend: [own], this phone's seeds (its last 48 hours, then the longer run: at most [SEEDS_MAX]),
         * and the taste's [taste] artists that aren't among them (by `fold(primary(name))`), which keep `max(3, 8 − own)` places
         * (fewer when there are fewer); own fills the rest, first. With no own seeds, the taste alone.
         */
        fun blendSeeds(own: List<String>, taste: List<String>): List<String> {
            val ownKeys = own.mapTo(HashSet()) { SongKey.fold(Credits.primary(it)) }
            val fromTaste = taste.filter { SongKey.fold(Credits.primary(it)) !in ownKeys }
            val keep = minOf(fromTaste.size, maxOf(TASTE_SEEDS, SEEDS_MAX - own.size))
            return own.take(SEEDS_MAX - keep) + fromTaste.take(keep)
        }
    }
}

/**
 * The seam between Your record (feature:record, which works the taste out) and the mixes (core:data): the taste as a mix reads
 * it, or null. Read once per mix run.
 */
interface RecordMixTaste {
    /**
     * The taste for the mixes now: null with no import (no taste) or with "Use your record in mixes" off. Kept out = the taste's
     * avoided songs minus the songs put back since, at once (the next taste has them so too).
     */
    suspend fun current(): MixTaste?

    companion object {
        /** No record: every mix as before. */
        val NONE: RecordMixTaste = object : RecordMixTaste {
            override suspend fun current(): MixTaste? = null
        }
    }
}

/**
 * [RecordMixTaste] over the database: the taste's songs (key, weight, avoid) and seed artists (its body), read once per taste
 * (by its `computed_at`), the songs put back on every read, and the switch. The taste exists only while there is an import
 * (feature:record makes it then, and clears it with the import), so no taste row is no import.
 */
@Singleton
class RoomRecordMixTaste @Inject constructor(
    private val dao: RecordTasteDao,
    private val switch: RecordInMixesPreference,
) : RecordMixTaste {
    private class Cached(val computedAt: Long, val songs: List<MixTaste.Companion.Song>, val artists: List<String>)

    @Volatile private var cached: Cached? = null

    override suspend fun current(): MixTaste? {
        if (!switch.current()) return null
        return stored()
    }

    /** The taste as the mixes would read it with the switch on (null: none). */
    suspend fun stored(): MixTaste? {
        val at = dao.computedAt() ?: return null
        val c = cached?.takeIf { it.computedAt == at } ?: run {
            val snap = dao.snapshot() ?: return null
            Cached(snap.computedAt, snap.songs.map { MixTaste.Companion.Song(it.key, it.weight, it.avoid) }, seedArtistsOf(snap.body))
                .also { cached = it }
        }
        return MixTaste.of(c.songs, c.artists, dao.keepKeys().toHashSet())
    }

    companion object {
        /** The body's `seedArtists` (none when it has none, or can't be read). */
        internal fun seedArtistsOf(body: String): List<String> = runCatching {
            (Json.parseToJsonElement(body).jsonObject["seedArtists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        }.getOrDefault(emptyList())
    }
}
