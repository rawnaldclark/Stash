package com.stash.core.data.home

// Seed scoring, weighted seed sampling and the row picking below follow Mochy Music's HomeRecommender
// (TasteProfile, weightedSample, pick, arrange), github.com/realidkroo/Mochy-Music, GPL-3.0, as Stash is.
// Rewritten against Stash's own history and models; the numbers are Mochy's unless a comment says otherwise.

import com.stash.core.common.TrackCanonical
import com.stash.core.common.primaryArtist
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** A song a row is seeded from: one you play a lot lately, or liked lately. */
@Serializable
data class BecauseSeed(
    val videoId: String,
    val title: String,
    val artist: String,
    val artUrl: String?,
)

/** One "Because you like ‹seed›" row: the seed and YouTube's related songs picked for it. */
@Serializable
data class BecauseRow(val seed: BecauseSeed, val songs: List<TrackSummary>)

/** A library song as a seed candidate, with its score ([BecauseYouLike.scores]). */
data class SeedCandidate(val trackId: Long, val seed: BecauseSeed, val score: Double)

/**
 * Home's "Because you like ‹song›": two or three rows, each seeded from a song you play a lot lately or liked lately,
 * each YouTube Music's Related page for it, mostly songs you don't have yet.
 */
object BecauseYouLike {
    private const val DAY_MS = 86_400_000L

    /** A play older than this counts for nothing (Mochy: 90 days). */
    const val HORIZON_DAYS = 90

    /** A play fades by e every this many days: two weeks old it counts about a third. */
    const val FADE_DAYS = 14.0

    /** What a like adds (Mochy: a like says more than a play). Only the newest [LIKES] count. */
    const val LIKE_WEIGHT = 1.5
    const val LIKES = 50

    /** A song skipped this often in [SKIP_DAYS] is no seed: you're tired of it. */
    const val SKIPS = 2
    const val SKIP_DAYS = 14

    /** Seeds are drawn from this many best scores, one per artist (Mochy: 14). */
    const val POOL = 14

    /** Rows: 2-3 (the brief); Mochy seeds 4. */
    const val ROWS = 3

    /** Cards per row: 10-15 (the brief); Mochy shows 10. */
    const val CARDS = 12

    /** At most this many songs by one artist in a row (Mochy: 2). */
    const val PER_ARTIST = 2

    /** A row with fewer cards than this is dropped (Mochy's `arrange`). */
    const val MIN_CARDS = 4

    /**
     * Each song's seed score: every play of it in the last [HORIZON_DAYS] counts `e^(-age / FADE_DAYS)` (a play
     * today 1, two weeks ago about a third, three months ago next to nothing), and a like among the newest
     * [LIKES] adds [LIKE_WEIGHT]. Songs in [tired] (skipped [SKIPS] times in [SKIP_DAYS] days) score nothing.
     *
     * @param plays (track id, epoch-ms start) of this phone's plays; [likes] the liked track ids, newest first.
     */
    fun scores(plays: List<Pair<Long, Long>>, likes: List<Long>, tired: Set<Long>, nowMs: Long): Map<Long, Double> {
        val scores = HashMap<Long, Double>()
        for ((trackId, startedAt) in plays) {
            val ageDays = (nowMs - startedAt).coerceAtLeast(0L).toDouble() / DAY_MS
            if (ageDays > HORIZON_DAYS) continue
            scores.merge(trackId, exp(-ageDays / FADE_DAYS), Double::plus)
        }
        for (trackId in likes.take(LIKES)) scores.merge(trackId, LIKE_WEIGHT, Double::plus)
        tired.forEach(scores::remove)
        return scores
    }

    /**
     * The seeds: from the [POOL] best [candidates] (one per artist, each with a YouTube video), [count] drawn by
     * weight (Efraimidis-Spirakis: the largest `ln(u) / weight` keys), so the rows don't seed from the same songs
     * every day while the songs you play most still come up most. Best score first.
     */
    fun pickSeeds(candidates: List<SeedCandidate>, random: Random, count: Int = ROWS): List<SeedCandidate> {
        val artists = HashSet<String>()
        val pool = candidates
            .filter { it.score > 0 && it.seed.videoId.isNotBlank() }
            .sortedByDescending { it.score }
            .filter { artists.add(artistKey(it.seed.artist)) }
            .take(POOL)
        return pool
            .map { it to ln(random.nextDouble().coerceAtLeast(Double.MIN_VALUE)) / it.score }
            .sortedByDescending { it.second }
            .take(count)
            .map { it.first }
            .sortedByDescending { it.score }
    }

    /**
     * The rows, seed by seed: each the seed's [related] songs that are songs (YouTube's `ATV`, never a video), never a
     * seed again (not the song, nor its other performances, in any row), not in [exclude] (Daily Discover's songs, as
     * [songKey]s), and not already in an earlier row (a song shows only in the first row that has it), each once, at
     * most [PER_ARTIST] per artist. About three quarters are songs you don't have ([known] false): known ones are at
     * most a quarter of [cards] and at most a third as many as the new ones, so a row is never mostly songs you have.
     * YouTube's order stands within a row. A row left with fewer than [MIN_CARDS] is dropped.
     */
    fun rows(
        seeds: List<BecauseSeed>,
        related: Map<String, List<TrackSummary>>,
        known: (TrackSummary) -> Boolean,
        exclude: Set<String>,
        cards: Int = CARDS,
    ): List<BecauseRow> {
        val used = HashSet<String>(exclude)
        seeds.forEach { used += songKey(it.title, it.artist) }
        val familiarCap = cards / 4
        // No seed is a card, nor its other performances (remastered, covered, translated), in any row
        val seedTitles = seeds.map { TrackCanonical.title(it.title) }.filter { it.isNotBlank() }
        return seeds.mapNotNull { seed ->
            val perArtist = HashMap<String, Int>()
            // A song YouTube lists twice (the album cut and the soundtrack cut) shows once
            val inRow = HashSet<String>()
            val eligible = related[seed.videoId].orEmpty().filter { song ->
                val title = TrackCanonical.title(song.title)
                song.musicVideoType == "ATV" &&
                    song.videoId != seed.videoId &&
                    seedTitles.none { title == it || title.startsWith("$it ") } &&
                    songKey(song.title, song.artist) !in used &&
                    inRow.add(songKey(song.title, song.artist))
            }.filter { song ->
                val artist = artistKey(song.artist)
                val n = perArtist.getOrDefault(artist, 0)
                (n < PER_ARTIST).also { if (it) perArtist[artist] = n + 1 }
            }
            val (fresh, familiar) = eligible.partition { !known(it) }
            // New songs fill the row but the familiar quarter; known ones are then at most a third as many as new ones
            val freshTaken = fresh.take(cards - minOf(familiarCap, familiar.size))
            val familiarTaken = familiar.take(minOf(familiarCap, freshTaken.size / 3))
            val picked = (freshTaken + familiarTaken).mapTo(HashSet()) { it.videoId }
            val songs = eligible.filter { it.videoId in picked }
            if (songs.size < MIN_CARDS) {
                null
            } else {
                songs.forEach { used += songKey(it.title, it.artist) }
                BecauseRow(seed, songs)
            }
        }
    }

    /**
     * [rows] without the songs in [shown] (what another Home shelf shows now, [HomeShownSongs]), each row dropped
     * when that leaves it under [MIN_CARDS].
     */
    fun without(rows: List<BecauseRow>, shown: Set<String>): List<BecauseRow> =
        if (shown.isEmpty()) {
            rows
        } else {
            rows.mapNotNull { row ->
                val songs = row.songs.filter { songKey(it.title, it.artist) !in shown }
                if (songs.size < MIN_CARDS) null else row.copy(songs = songs)
            }
        }

    /** A song's identity across Home's shelves: its main act and title, both in their loose canonical form. */
    fun songKey(title: String, artist: String): String = artistKey(artist) + "|" + TrackCanonical.title(title)

    private fun artistKey(artist: String): String = TrackCanonical.artist(artist.primaryArtist())
}
