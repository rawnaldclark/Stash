package com.stash.core.data.home

import android.util.Log
import com.stash.core.common.TrackCanonical
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * What "Because you like" saves ([HomeShelfStore]): the rows, the day they were built (-1 before a build enough
 * seeds answered), the newest listen and skip they had seen, so the next look can tell whether you've listened
 * enough since to build them again, and when a build last fell short (the next waits [BecauseYouLikeRepository.RETRY_MS]).
 */
@Serializable
internal data class BecauseFile(
    val day: Long,
    val lastEventId: Long,
    val lastSkipId: Long,
    val rows: List<BecauseRow>,
    val failedAt: Long = 0,
)

/** The reads "Because you like" makes of Stash's own history and library ([RoomBecauseHistory]; a fake in tests). */
interface BecauseHistory {
    /** This phone's plays since [sinceMs]: (track id, start). */
    suspend fun plays(sinceMs: Long): List<Pair<Long, Long>>

    /** The [limit] most recently liked track ids, newest first. */
    suspend fun likes(limit: Int): List<Long>

    /** Tracks skipped at least [minSkips] times since [sinceMs]. */
    suspend fun tired(minSkips: Int, sinceMs: Long): Set<Long>

    /** [trackIds] as seeds, those with a YouTube video only. */
    suspend fun seeds(trackIds: List<Long>): Map<Long, BecauseSeed>

    /** The videoIds of [songs] you have already (a library song with that video, or with the same words). */
    suspend fun known(songs: List<TrackSummary>): Set<String>

    /** Today's Daily Discover, as [BecauseYouLike.songKey]s: its songs aren't repeated. */
    suspend fun dailyDiscover(): Set<String>

    suspend fun lastEventId(): Long
    suspend fun lastSkipId(): Long
    suspend fun fullPlaysAfter(eventId: Long): Int
    suspend fun skipsAfter(skipId: Long): Int
}

/**
 * Builds and keeps Home's "Because you like ‹song›" rows ([BecauseYouLike]). A build scores your songs from this
 * phone's plays (faded with age) and recent likes, draws up to three seeds, one per artist, and reads each seed's
 * Related page on YouTube Music (two requests a seed: `next`, then the `MPTRt_` browse). Built again on the first
 * look of a new day, or once you've played [REBUILD_PLAYS] songs through or skipped [REBUILD_SKIPS] since the last
 * build: two counts against the newest ids the build saw, never a timer.
 *
 * Fail-soft: a build stamps the day (and the counts) only when at least half the seeds' pages answered; one that
 * fell short never replaces rows with none, and the next build waits [RETRY_MS] (Home's resume doesn't refire the
 * requests on every return). With nothing to seed from there are no rows, no file and no request.
 */
@Singleton
class BecauseYouLikeRepository internal constructor(
    private val store: HomeShelfStore,
    private val history: BecauseHistory,
    private val today: () -> Long,
    private val nowMs: () -> Long,
    private val related: suspend (String) -> List<TrackSummary>?,
    private val random: () -> Random,
) {

    @Inject
    constructor(store: HomeShelfStore, history: RoomBecauseHistory, api: YTMusicApiClient) : this(
        store = store,
        history = history,
        today = { LocalDate.now().toEpochDay() },
        nowMs = System::currentTimeMillis,
        related = api::getRelatedSongs,
        random = { Random.Default },
    )

    private val mutex = Mutex()

    /** The saved rows as they are: what Home paints before [shelf] answers. */
    suspend fun cached(): List<BecauseRow> = withContext(Dispatchers.IO) {
        store.read(FILE, BecauseFile.serializer())?.rows.orEmpty()
    }

    /** The rows now: the saved ones while they're today's and you haven't listened enough since, else a new build. */
    suspend fun shelf(): List<BecauseRow> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val day = today()
            val saved = store.read(FILE, BecauseFile.serializer())
            if (saved != null && saved.failedAt > 0 && nowMs() - saved.failedAt in 0 until RETRY_MS) return@withContext saved.rows
            if (saved != null && saved.day == day && !listenedSince(saved)) return@withContext saved.rows
            build(day, saved)
        }
    }

    /** Enough listening since [saved] was built to build again: a cheap count of two tables past two ids. */
    private suspend fun listenedSince(saved: BecauseFile): Boolean =
        history.fullPlaysAfter(saved.lastEventId) >= REBUILD_PLAYS || history.skipsAfter(saved.lastSkipId) >= REBUILD_SKIPS

    private suspend fun build(day: Long, saved: BecauseFile?): List<BecauseRow> {
        // What this build has seen: listening after these ids counts toward the next build
        val lastEvent = history.lastEventId()
        val lastSkip = history.lastSkipId()
        val now = nowMs()
        val scores = BecauseYouLike.scores(
            plays = history.plays(now - BecauseYouLike.HORIZON_DAYS * DAY_MS),
            likes = history.likes(BecauseYouLike.LIKES),
            tired = history.tired(BecauseYouLike.SKIPS, now - BecauseYouLike.SKIP_DAYS * DAY_MS),
            nowMs = now,
        )
        // Read enough of the best for one per artist to still fill the pool
        val best = scores.entries.sortedByDescending { it.value }.take(BecauseYouLike.POOL * SEED_READ_FACTOR)
        val seedsById = if (best.isEmpty()) emptyMap() else history.seeds(best.map { it.key })
        val candidates = best.mapNotNull { (id, score) -> seedsById[id]?.let { SeedCandidate(id, it, score) } }
        val seeds = BecauseYouLike.pickSeeds(candidates, random()).map { it.seed }
        if (seeds.isEmpty()) {
            // Nothing to seed from (a new install, or the history was cleared): no rows, and nothing of it kept
            store.delete(FILE)
            return emptyList()
        }
        val pages = coroutineScope {
            seeds.map { seed -> async { seed.videoId to relatedOf(seed) } }.awaitAll()
        }.filter { it.second != null }.associate { it.first to it.second!! }
        val enough = pages.size * 2 >= seeds.size
        val songs = pages.values.flatten()
        val known = if (songs.isEmpty()) emptySet() else history.known(songs)
        val built = if (pages.isEmpty()) {
            emptyList()
        } else {
            BecauseYouLike.rows(seeds, pages, known = { it.videoId in known }, exclude = history.dailyDiscover())
        }
        // A build some seeds didn't answer never replaces rows with none
        val rows = if (built.isEmpty() && pages.size < seeds.size && !saved?.rows.isNullOrEmpty()) saved!!.rows else built
        Log.d(TAG, "built ${rows.size} rows from ${seeds.size} seeds (${pages.size} answered)${if (enough) "" else ", short: not stamped"}")
        val file = if (enough) {
            BecauseFile(day, lastEvent, lastSkip, rows)
        } else {
            // Not stamped: the day and the counts stay as they were, so the next try (after the wait) builds again
            BecauseFile(saved?.day ?: -1, saved?.lastEventId ?: lastEvent, saved?.lastSkipId ?: lastSkip, rows, failedAt = nowMs())
        }
        store.write(FILE, BecauseFile.serializer(), file)
        return rows
    }

    private suspend fun relatedOf(seed: BecauseSeed): List<TrackSummary>? = try {
        related(seed.videoId)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Related page of '${seed.title}' failed: ${e.message}")
        null
    }

    internal companion object {
        private const val TAG = "BecauseYouLike"
        const val FILE = "because_you_like"
        private const val DAY_MS = 86_400_000L

        /** Songs played through since the last build that build the rows again (Mochy: 4). */
        const val REBUILD_PLAYS = 4

        /** Skips since the last build that build the rows again (Mochy: 2). */
        const val REBUILD_SKIPS = 2

        /** Best scores read as seed candidates, per pool place: one per artist needs more than the pool. */
        const val SEED_READ_FACTOR = 3

        /** After a build that fell short, the next one waits this long (Home's resume would otherwise refire it). */
        const val RETRY_MS = 60 * 60 * 1000L
    }
}

/** [BecauseHistory] from Room: read-only queries on listening events, skips, tracks and Daily Discover. */
class RoomBecauseHistory @Inject constructor(
    private val listeningEventDao: ListeningEventDao,
    private val skipDao: TrackSkipEventDao,
    private val trackDao: TrackDao,
    private val recipeDao: StashMixRecipeDao,
    private val playlistDao: PlaylistDao,
) : BecauseHistory {

    override suspend fun plays(sinceMs: Long): List<Pair<Long, Long>> =
        listeningEventDao.playStampsSince(sinceMs).map { it.trackId to it.startedAt }

    override suspend fun likes(limit: Int): List<Long> = trackDao.recentlyLikedIds(limit)

    override suspend fun tired(minSkips: Int, sinceMs: Long): Set<Long> = skipDao.trackIdsSkippedAtLeast(minSkips, sinceMs).toSet()

    override suspend fun seeds(trackIds: List<Long>): Map<Long, BecauseSeed> =
        trackDao.getByIds(trackIds).mapNotNull { t ->
            val videoId = t.youtubeId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            t.id to BecauseSeed(videoId = videoId, title = t.title, artist = t.artist, artUrl = t.albumArtUrl ?: t.albumArtPath)
        }.toMap()

    override suspend fun known(songs: List<TrackSummary>): Set<String> {
        val identities = songs.map { TrackCanonical.title(it.title) to TrackCanonical.artist(it.artist) }
        // The Spotify side of the lookup is never wanted: a never-matching value keeps its IN () non-empty
        val rows = trackDao.findExistingForBatch(listOf(NO_SPOTIFY_URI), songs.map { it.videoId }.distinct(), identities)
        val videoIds = rows.mapNotNullTo(HashSet()) { it.youtubeId }
        val words = rows.mapTo(HashSet()) { it.canonicalTitle to it.canonicalArtist }
        return songs.filter { it.videoId in videoIds || (TrackCanonical.title(it.title) to TrackCanonical.artist(it.artist)) in words }
            .mapTo(HashSet()) { it.videoId }
    }

    override suspend fun dailyDiscover(): Set<String> {
        // Home's hero reads the same: the first built-in recipe's playlist (HomeViewModel)
        val playlistId = recipeDao.observeAll().first().filter { it.isBuiltin }.minByOrNull { it.id }?.playlistId ?: return emptySet()
        return playlistDao.getTracksForPlaylist(playlistId).mapTo(HashSet()) { BecauseYouLike.songKey(it.title, it.artist) }
    }

    override suspend fun lastEventId(): Long = listeningEventDao.lastEventId()
    override suspend fun lastSkipId(): Long = skipDao.lastSkipId()
    override suspend fun fullPlaysAfter(eventId: Long): Int = listeningEventDao.countFullPlaysAfter(eventId)
    override suspend fun skipsAfter(skipId: Long): Int = skipDao.countSkipsAfter(skipId)

    private companion object {
        const val NO_SPOTIFY_URI = "\u0000__stash_never_match__"
    }
}
