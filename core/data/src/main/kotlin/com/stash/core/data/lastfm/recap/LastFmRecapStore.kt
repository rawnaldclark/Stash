package com.stash.core.data.lastfm.recap

import android.content.Context
import android.util.Log
import com.stash.core.data.genre.WeightedGenre
import com.stash.core.data.lastfm.LastFmScrobble
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Last.fm recap's own storage: small JSON files under `filesDir/lastfm_recap/`, entirely
 * apart from the main database (owner rule: the Last.fm recap never touches Stash's listening
 * data, and the main DB's version must not change).
 *
 * Why files and not a second Room database (LASTFM-RECAP.md §1): a month of scrobbles is written
 * once and always read whole, a year is its twelve months read whole, and nothing is ever queried
 * by column. Files need no schema or migrations, test on the plain JVM, and forgetting Last.fm is
 * deleting one folder.
 *
 * Layout: `<root>/u/<user>/counts.json` (registration date + totals per month/year),
 * `<root>/u/<user>/m-2026-10.json` (one month's scrobbles), and two catalog caches shared by every
 * user: `<root>/durations.json` (song lengths) and `<root>/genres.json` (artist genres).
 *
 * Writes go to a temp file renamed over the old one, so a crash mid-write leaves the previous
 * version. An unreadable file (corrupt, from a future format) reads as a cache miss. Not
 * thread-safe on its own: [LastFmRecapRepository] serialises access.
 */
@Singleton
class LastFmRecapStore internal constructor(private val root: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR))

    // ── Per user ──────────────────────────────────────────────────────

    fun readCounts(user: String): CountsFile? = read(userDir(user).resolve(COUNTS), CountsFile.serializer())

    fun writeCounts(user: String, counts: CountsFile) = write(userDir(user).resolve(COUNTS), CountsFile.serializer(), counts)

    fun readMonth(user: String, month: YearMonth): MonthFile? = read(monthFile(user, month), MonthFile.serializer())

    fun writeMonth(user: String, month: YearMonth, file: MonthFile) = write(monthFile(user, month), MonthFile.serializer(), file)

    // ── Shared catalog caches ─────────────────────────────────────────

    fun readDurations(): DurationsFile = read(root.resolve(DURATIONS), DurationsFile.serializer()) ?: DurationsFile()

    fun writeDurations(file: DurationsFile) = write(root.resolve(DURATIONS), DurationsFile.serializer(), file)

    fun readGenres(): GenresFile = read(root.resolve(GENRES), GenresFile.serializer()) ?: GenresFile()

    fun writeGenres(file: GenresFile) = write(root.resolve(GENRES), GenresFile.serializer(), file)

    // ── Plumbing ──────────────────────────────────────────────────────

    private fun monthFile(user: String, month: YearMonth): File =
        userDir(user).resolve("m-%04d-%02d.json".format(month.year, month.monthValue))

    /** One folder per Last.fm user (names are letters, digits, "_" and "-"; anything else is folded). */
    private fun userDir(user: String): File = root.resolve("u").resolve(safeName(user))

    private fun <T> read(file: File, serializer: KSerializer<T>): T? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            // A broken or future-format file is a cache miss, never a crash.
            Log.w(TAG, "unreadable ${file.name}: ${e.message}")
            null
        }
    }

    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            if (!tmp.renameTo(file)) {
                // Windows-style rename-over refusal (and some FUSE mounts): replace by copy.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // A cache that can't be written only costs a refetch next time.
            Log.w(TAG, "couldn't write ${file.name}: ${e.message}")
        }
    }

    /** Forgets every user's scrobbles and totals (the shared song-length/genre catalog stays). */
    fun clearUsers() {
        if (!root.resolve("u").deleteRecursively()) Log.w(TAG, "couldn't clear every user's scrobbles")
    }

    companion object {
        private const val TAG = "LastFmRecapStore"
        const val DIR = "lastfm_recap"

        /**
         * [clearUsers] for code without a store instance (LastFmSessionPreference.clear): on
         * disconnect, the account's scrobbles leave the phone with it.
         */
        fun clearUsers(filesDir: File) {
            File(filesDir, DIR).resolve("u").deleteRecursively()
        }
        private const val COUNTS = "counts.json"
        private const val DURATIONS = "durations.json"
        private const val GENRES = "genres.json"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** The folder name for [user]: lower case, anything outside [a-z0-9_-] as "_". */
        internal fun safeName(user: String): String =
            user.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_").ifEmpty { "_" }
    }
}

/** A user's registration date and the scrobble totals of the months/years asked about. */
@Serializable
data class CountsFile(
    /** The zone the totals were counted in: month borders move with it, so another zone = stale. */
    val zone: String,
    /** When the user joined Last.fm, UNIX seconds; null = not asked yet / Last.fm didn't say. */
    val registeredAt: Long? = null,
    /** Last.fm answered the registration question (even without a date): don't ask again. */
    val registeredChecked: Boolean = false,
    /** By [LastFmRecapSpan.key]. */
    val spans: Map<String, SpanCount> = emptyMap(),
)

@Serializable
data class SpanCount(val total: Int, val fetchedAt: Long)

/**
 * One month's scrobbles, as fetched at [fetchedAt] in [zone]. [complete]: every page read and
 * the rows add up to Last.fm's own total; only a complete month may be kept for good once settled
 * (a short read stays on the short TTL). Files without the flag read as incomplete.
 */
@Serializable
data class MonthFile(
    val zone: String,
    val fetchedAt: Long,
    val scrobbles: List<LastFmScrobble>,
    val complete: Boolean = false,
)

/** Song lengths from track.getInfo, by the caller's song key; 0 = Last.fm had none. */
@Serializable
data class DurationsFile(val songs: Map<String, CachedValue<Long>> = emptyMap())

/** Artist genres from artist.getTopTags, by ArtistGenreKey (lower case); empty = none. */
@Serializable
data class GenresFile(val artists: Map<String, CachedValue<List<WeightedGenre>>> = emptyMap())

@Serializable
data class CachedValue<T>(val value: T, val fetchedAt: Long)
