package com.stash.feature.record.taste

import com.stash.core.data.db.entity.RecordTasteEntity
import com.stash.core.data.db.entity.RecordTasteSongEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The taste as the database keeps it: its songs one row each (`record_taste_songs`, in its order by [RecordTasteSongEntity.rank])
 * and the rest as one JSON body (`record_taste`), which Your record's history mixes read (phase 5c).
 */
object TasteRows {
    /** The body's shape: the taste without its songs. */
    @Serializable
    data class Body(
        val first: Long,
        val last: Long,
        val plays: Int,
        val listenedMs: Long,
        val artists: Int,
        val eras: List<Era>,
        val constants: List<String>,
        val lost: List<String>,
        val obsessions: List<Obsession>,
        val found: Found?,
        val years: List<TasteYear>,
        val yearTops: List<YearTop>,
        val topArtists: List<TopArtist>,
        val seedArtists: List<String>,
    )

    private val json = Json { encodeDefaults = true }

    fun body(t: Taste): String = json.encodeToString(
        Body.serializer(),
        Body(t.first, t.last, t.plays, t.listenedMs, t.artists, t.eras, t.constants, t.lost, t.obsessions, t.found, t.years, t.yearTops, t.topArtists, t.seedArtists),
    )

    fun readBody(s: String): Body = Json { ignoreUnknownKeys = true }.decodeFromString(Body.serializer(), s)

    fun row(t: Taste): RecordTasteEntity = RecordTasteEntity(v = t.v, computedAt = t.computedAt, body = body(t))

    fun songs(t: Taste): List<RecordTasteSongEntity> = t.songs.mapIndexed { i, s ->
        RecordTasteSongEntity(
            key = s.key, rank = i, title = s.title, artist = s.artist, album = s.album, sid = s.sid,
            artistKey = Taste.artistKey(s.artist),
            starts = s.starts, plays = s.plays, full = s.full, chosen = s.chosen, skips = s.skips, years = s.years,
            listenedMs = s.listenedMs, first = s.first, last = s.last, peak = s.peak, peakAt = s.peakAt,
            weight = s.weight, depth = s.depth, avoid = s.avoid,
        )
    }
}

/** Why a song is kept out of mixes (stash-player `src/lib/spotify/kept-out.ts` `KeptOutWhy`), by the rule that keeps it out. */
sealed interface KeptOutWhy {
    /** Hidden on Spotify (or no rule explains it: a taste older than the hidden list, so it can only have been hidden). */
    data object Hidden : KeptOutWhy

    /** Started [starts] times, left early [skips] of them. */
    data class Skips(val skips: Int, val starts: Int) : KeptOutWhy

    /** An interlude, intro, outro, skit or commentary left early. */
    data object Interlude : KeptOutWhy
}

/** One row of "Kept out of your mixes" (kept-out.ts `KeptOutSong`). */
data class KeptOutSong(val key: String, val title: String, val artist: String, val album: String?, val why: KeptOutWhy)

/** kept-out.ts: the songs the taste keeps out of mixes now, each with why. */
object KeptOut {
    /** kept-out.ts `whyKeptOut`: the first rule that holds, in taste.ts's order (hidden, skips, an interlude); else hidden. */
    fun why(key: String, title: String, starts: Int, skips: Int, hidden: Set<String>): KeptOutWhy {
        if (key in hidden) return KeptOutWhy.Hidden
        val rate = if (starts > 0) skips.toDouble() / starts else 0.0
        if (starts >= Taste.AVOID_STARTS && rate >= Taste.AVOID_RATE) return KeptOutWhy.Skips(skips, starts)
        if (Taste.INTERLUDE.containsMatchIn(title) && starts >= Taste.AVOID_INTERLUDE_STARTS && rate >= Taste.AVOID_INTERLUDE_RATE) return KeptOutWhy.Interlude
        return KeptOutWhy.Hidden
    }

    /**
     * kept-out.ts `keptOut`: the taste's avoided songs minus those [putBack], in the taste's order (the songs that mean the most
     * first, so a mistake is near the top). [avoided]: the avoided songs in that order.
     */
    fun of(avoided: List<RecordTasteSongEntity>, hidden: Set<String>, putBack: Set<String>): List<KeptOutSong> =
        avoided.filter { it.avoid && it.key !in putBack }.map {
            KeptOutSong(it.key, it.title, it.artist, it.album, why(it.key, it.title, it.starts, it.skips, hidden))
        }

    /** [of] over a taste worked out here (the vectors). */
    fun ofTaste(t: Taste, hidden: Set<String>, putBack: Set<String>): List<KeptOutSong> =
        t.songs.filter { it.avoid && it.key !in putBack }.map {
            KeptOutSong(it.key, it.title, it.artist, it.album, why(it.key, it.title, it.starts, it.skips, hidden))
        }
}
