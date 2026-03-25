package com.stash.core.data.seed

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SourceAccountDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SourceAccountEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncState
import com.stash.core.model.SyncTrigger
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Populates the database with realistic test data on first launch.
 *
 * Only seeds when the tracks table is empty, ensuring existing user data
 * is never overwritten. Call [seedIfEmpty] from [Application.onCreate].
 */
@Singleton
class DatabaseSeeder @Inject constructor(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val sourceAccountDao: SourceAccountDao,
    private val syncHistoryDao: SyncHistoryDao,
) {

    /**
     * Seeds the database with sample tracks, playlists, source accounts,
     * and sync history if the tracks table is currently empty.
     */
    suspend fun seedIfEmpty() {
        if (trackDao.getCount() > 0) return

        val now = Instant.now()

        // ── Source accounts ──────────────────────────────────────────────
        sourceAccountDao.insert(
            SourceAccountEntity(
                source = MusicSource.SPOTIFY,
                displayName = "Alex",
                email = "alex@example.com",
                isConnected = true,
                connectedAt = now.minus(30, ChronoUnit.DAYS),
                lastSyncAt = now.minus(2, ChronoUnit.HOURS),
            )
        )
        sourceAccountDao.insert(
            SourceAccountEntity(
                source = MusicSource.YOUTUBE,
                displayName = "Alex YT",
                email = "alex@gmail.com",
                isConnected = true,
                connectedAt = now.minus(25, ChronoUnit.DAYS),
                lastSyncAt = now.minus(2, ChronoUnit.HOURS),
            )
        )

        // ── Tracks ──────────────────────────────────────────────────────
        val trackIds = trackDao.insertAll(buildTrackList(now))

        // ── Playlists ───────────────────────────────────────────────────
        val dailyMix1Id = playlistDao.insert(
            PlaylistEntity(
                name = "Daily Mix 1",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:dailymix1",
                type = PlaylistType.DAILY_MIX,
                mixNumber = 1,
                lastSynced = now.minus(2, ChronoUnit.HOURS),
                trackCount = 8,
                isActive = true,
            )
        )
        val dailyMix2Id = playlistDao.insert(
            PlaylistEntity(
                name = "Daily Mix 2",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:dailymix2",
                type = PlaylistType.DAILY_MIX,
                mixNumber = 2,
                lastSynced = now.minus(2, ChronoUnit.HOURS),
                trackCount = 7,
                isActive = true,
            )
        )
        val spotifyLikedId = playlistDao.insert(
            PlaylistEntity(
                name = "Liked Songs",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify:collection:tracks",
                type = PlaylistType.LIKED_SONGS,
                lastSynced = now.minus(2, ChronoUnit.HOURS),
                trackCount = 12,
                isActive = true,
            )
        )
        val ytMixId = playlistDao.insert(
            PlaylistEntity(
                name = "My Mix",
                source = MusicSource.YOUTUBE,
                sourceId = "RDMM",
                type = PlaylistType.DAILY_MIX,
                mixNumber = 1,
                lastSynced = now.minus(3, ChronoUnit.HOURS),
                trackCount = 8,
                isActive = true,
            )
        )
        val ytLikedId = playlistDao.insert(
            PlaylistEntity(
                name = "Liked Music",
                source = MusicSource.YOUTUBE,
                sourceId = "LM",
                type = PlaylistType.LIKED_SONGS,
                lastSynced = now.minus(3, ChronoUnit.HOURS),
                trackCount = 10,
                isActive = true,
            )
        )

        // ── Cross-references (link tracks to playlists) ─────────────────
        // Daily Mix 1: first 8 Spotify tracks
        val spotifyTrackIds = trackIds.take(15)
        val youtubeTrackIds = trackIds.drop(15)

        linkTracks(dailyMix1Id, spotifyTrackIds.take(8), now)
        linkTracks(dailyMix2Id, spotifyTrackIds.drop(3).take(7), now)
        linkTracks(spotifyLikedId, spotifyTrackIds.take(12), now)
        linkTracks(ytMixId, youtubeTrackIds.take(8), now)
        linkTracks(ytLikedId, youtubeTrackIds, now)

        // ── Sync history ────────────────────────────────────────────────
        syncHistoryDao.insert(
            SyncHistoryEntity(
                startedAt = now.minus(2, ChronoUnit.HOURS),
                completedAt = now.minus(2, ChronoUnit.HOURS).plus(45, ChronoUnit.SECONDS),
                status = SyncState.COMPLETED,
                playlistsChecked = 5,
                newTracksFound = trackIds.size,
                tracksDownloaded = trackIds.size,
                tracksFailed = 0,
                bytesDownloaded = trackIds.size.toLong() * 4_500_000L,
                trigger = SyncTrigger.MANUAL,
            )
        )
    }

    /**
     * Links a list of track IDs to a playlist, assigning sequential positions.
     */
    private suspend fun linkTracks(playlistId: Long, trackIds: List<Long>, addedAt: Instant) {
        trackIds.forEachIndexed { index, trackId ->
            playlistDao.insertCrossRef(
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = trackId,
                    position = index,
                    addedAt = addedAt,
                )
            )
        }
    }

    /**
     * Builds the list of 25 realistic seed tracks across Spotify and YouTube.
     */
    private fun buildTrackList(now: Instant): List<TrackEntity> {
        val spotifyTracks = listOf(
            TrackData("Blinding Lights", "The Weeknd", "After Hours", 200_040, "spotify:track:0VjIjW4GlUZAMYd2vXMi3b"),
            TrackData("Levitating", "Dua Lipa", "Future Nostalgia", 203_064, "spotify:track:39LLxExYz6ewLAo9BUIFDA"),
            TrackData("Save Your Tears", "The Weeknd", "After Hours", 215_627, "spotify:track:5QO79kh1waicV47BqGRL3g"),
            TrackData("Peaches", "Justin Bieber", "Justice", 198_082, "spotify:track:4iJyoBOLtHEmRTsufTwYQo"),
            TrackData("drivers license", "Olivia Rodrigo", "SOUR", 242_014, "spotify:track:5wANPM4fQCJwkGd4rN57mH"),
            TrackData("Kiss Me More", "Doja Cat", "Planet Her", 208_867, "spotify:track:748mdHapucXQri7IAO8yFK"),
            TrackData("Montero", "Lil Nas X", "Montero", 137_876, "spotify:track:67BtfxlNbhBmCDR2L2l8qd"),
            TrackData("Stay", "The Kid LAROI & Justin Bieber", "F*CK LOVE 3", 141_806, "spotify:track:5PjdY0CKGZdEuoNab3yDmX"),
            TrackData("good 4 u", "Olivia Rodrigo", "SOUR", 178_147, "spotify:track:4ZtFanR9U6ndgddUvNcjcG"),
            TrackData("Heat Waves", "Glass Animals", "Dreamland", 238_805, "spotify:track:02MWAaffLxlfxAUY7c5dvx"),
            TrackData("Industry Baby", "Lil Nas X & Jack Harlow", "Montero", 212_000, "spotify:track:27NovPIUIRrOZoCHxABJwK"),
            TrackData("Shivers", "Ed Sheeran", "=", 207_853, "spotify:track:50nfwC4TAxpBJhNXiVPnLZ"),
            TrackData("Bad Habits", "Ed Sheeran", "=", 230_747, "spotify:track:6PQ88X9TkUIAUIZJHW2upE"),
            TrackData("Butter", "BTS", "Butter", 164_442, "spotify:track:3YAfJvDFUiiWnHbUFQqeAz"),
            TrackData("Happier Than Ever", "Billie Eilish", "Happier Than Ever", 298_899, "spotify:track:4RVwu0g32PAqgUiJoXsdF8"),
        )
        val youtubeTracks = listOf(
            TrackData("Bohemian Rhapsody", "Queen", "A Night at the Opera", 354_000, ytId = "fJ9rUzIMcZQ"),
            TrackData("Lose Yourself", "Eminem", "8 Mile Soundtrack", 326_000, ytId = "_Yhyp-_hX2s"),
            TrackData("Shape of You", "Ed Sheeran", "Divide", 233_713, ytId = "JGwWNGJdvx8"),
            TrackData("Despacito", "Luis Fonsi ft. Daddy Yankee", "Vida", 228_000, ytId = "kJQP7kiw5Fk"),
            TrackData("Uptown Funk", "Mark Ronson ft. Bruno Mars", "Uptown Special", 269_000, ytId = "OPf0YbXqDm0"),
            TrackData("See You Again", "Wiz Khalifa ft. Charlie Puth", "Furious 7 OST", 237_000, ytId = "RgKAFK5djSk"),
            TrackData("Gangnam Style", "PSY", "Psy 6 Rules Pt. 1", 219_000, ytId = "9bZkp7q19f0"),
            TrackData("Sorry", "Justin Bieber", "Purpose", 200_217, ytId = "fRh_vgS2dFE"),
            TrackData("Sugar", "Maroon 5", "V", 235_493, ytId = "09R8_2nJtjg"),
            TrackData("Thinking Out Loud", "Ed Sheeran", "x", 281_000, ytId = "lp-EO5I60KA"),
        )

        return spotifyTracks.mapIndexed { i, t ->
            TrackEntity(
                title = t.title,
                artist = t.artist,
                album = t.album,
                durationMs = t.durationMs,
                filePath = "/storage/emulated/0/Stash/${t.artist} - ${t.title}.opus",
                fileFormat = "opus",
                qualityKbps = 128,
                fileSizeBytes = (t.durationMs * 16L), // ~128kbps approximation
                source = MusicSource.SPOTIFY,
                spotifyUri = t.spotifyUri,
                dateAdded = now.minus((25 - i).toLong(), ChronoUnit.DAYS),
                isDownloaded = true,
                canonicalTitle = t.title.lowercase(),
                canonicalArtist = t.artist.lowercase(),
                matchConfidence = 1.0f,
            )
        } + youtubeTracks.mapIndexed { i, t ->
            TrackEntity(
                title = t.title,
                artist = t.artist,
                album = t.album,
                durationMs = t.durationMs,
                filePath = "/storage/emulated/0/Stash/${t.artist} - ${t.title}.opus",
                fileFormat = "opus",
                qualityKbps = 128,
                fileSizeBytes = (t.durationMs * 16L),
                source = MusicSource.YOUTUBE,
                youtubeId = t.ytId,
                dateAdded = now.minus((20 - i).toLong(), ChronoUnit.DAYS),
                isDownloaded = true,
                canonicalTitle = t.title.lowercase(),
                canonicalArtist = t.artist.lowercase(),
                matchConfidence = 1.0f,
            )
        }
    }

    /** Simple holder for track seed data. */
    private data class TrackData(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val spotifyUri: String? = null,
        val ytId: String? = null,
    )
}
