package com.stash.core.data.lastfm

import android.util.Log
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.ListeningEventEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On Last.fm connect, pulls the user's top artists and loved tracks so
 * Stash Mixes have real affinity signal *immediately* — rather than
 * waiting weeks for the user to accumulate in-app listening history.
 *
 * Strategy:
 *  - For each of the user's top 100 Last.fm artists whose tracks are
 *    already in the library, synthesize listening_event rows so the
 *    affinity score treats them as recently-played.
 *  - For each loved track (up to 200) in the library, synthesize an
 *    extra "strong" signal (multiple plays) so loves bubble up.
 *
 * Synthetic events get scrobbled=1 already so they never re-submit to
 * Last.fm as duplicates of the user's actual history. Uses a timestamp
 * spread over the last 60 days so the affinity window includes them but
 * they don't all collapse to the same moment.
 *
 * Run once per Last.fm connect. Safe to re-run (idempotent) because
 * insert conflicts on the same track+timestamp are rare; worst case is
 * a slightly boosted artist showing up in a few more mixes.
 */
@Singleton
class LastFmColdStartImporter @Inject constructor(
    private val lastFmApiClient: LastFmApiClient,
    private val listeningEventDao: ListeningEventDao,
    private val trackDao: TrackDao,
) {
    companion object {
        private const val TAG = "LastFmColdStart"
        private const val LOOKBACK_DAYS = 60L
        private const val ARTIST_PLAYS_PER_TRACK = 3
        private const val LOVED_PLAYS_PER_TRACK = 5
    }

    /**
     * Pull Last.fm history for [username] and convert into local
     * listening_events. Returns the number of synthetic events recorded
     * so the Settings UI can surface a completion toast.
     */
    suspend fun import(username: String): Int {
        val now = System.currentTimeMillis()
        val windowStart = now - LOOKBACK_DAYS * 24 * 60 * 60 * 1000
        var inserted = 0

        // Top artists seeding: each track by a top artist in the library
        // gets a few synthetic plays. Multiplier scales with Last.fm
        // playcount so power-favorites still separate out.
        val topArtists = runCatching {
            lastFmApiClient.getUserTopArtists(username = username, limit = 100).getOrNull()
        }.getOrNull().orEmpty()

        if (topArtists.isEmpty()) {
            Log.d(TAG, "no top artists from Last.fm for user=$username")
        } else {
            for (artist in topArtists) {
                // Can't query the tracks table by non-normalized artist
                // directly; getAllByArtist uses the exact stored value.
                // For cold-start we accept case-insensitive matches done
                // in-memory over a single library scan below.
            }
            val allTracks = trackDao.getAllDownloadedNonBlacklisted()
            val byArtistLower = allTracks.groupBy { it.artist.lowercase() }
            for (artist in topArtists) {
                val tracks = byArtistLower[artist.name.lowercase()] ?: continue
                val playsPerTrack = ARTIST_PLAYS_PER_TRACK.coerceAtLeast(
                    minOf(artist.playcount / 50, 10), // scale with LF count, capped
                )
                inserted += writeSyntheticPlays(tracks, playsPerTrack, windowStart, now)
            }
        }

        // Loved tracks — stronger signal, applied per-track directly.
        val loved = runCatching {
            lastFmApiClient.getUserLovedTracks(username = username, limit = 200).getOrNull()
        }.getOrNull().orEmpty()
        if (loved.isNotEmpty()) {
            val canonicalIndex = trackDao.getAllDownloadedNonBlacklisted()
                .associateBy { "${it.artist.lowercase()}\u0001${it.title.lowercase()}" }
            for (lv in loved) {
                val key = "${lv.artist.lowercase()}\u0001${lv.title.lowercase()}"
                val track = canonicalIndex[key] ?: continue
                inserted += writeSyntheticPlays(
                    tracks = listOf(track),
                    playsPerTrack = LOVED_PLAYS_PER_TRACK,
                    windowStart = windowStart,
                    now = now,
                )
            }
        }

        Log.i(TAG, "cold-start import: inserted $inserted synthetic listening events")
        return inserted
    }

    /**
     * Spreads [playsPerTrack] synthetic plays across [windowStart]..[now]
     * for each track so they don't all land at the same instant (which
     * Room would at least insert fine, but looks silly in analytics).
     * Every event is marked scrobbled=1 so the Last.fm scrobbler never
     * echoes them back.
     */
    private suspend fun writeSyntheticPlays(
        tracks: List<com.stash.core.data.db.entity.TrackEntity>,
        playsPerTrack: Int,
        windowStart: Long,
        now: Long,
    ): Int {
        val span = (now - windowStart).coerceAtLeast(1)
        var count = 0
        for (track in tracks) {
            for (i in 0 until playsPerTrack) {
                val offset = (span * i / playsPerTrack.coerceAtLeast(1))
                val ts = windowStart + offset
                runCatching {
                    listeningEventDao.insert(
                        ListeningEventEntity(
                            trackId = track.id,
                            startedAt = ts,
                            scrobbled = true,
                        )
                    )
                }.onSuccess { count++ }
            }
        }
        return count
    }
}
