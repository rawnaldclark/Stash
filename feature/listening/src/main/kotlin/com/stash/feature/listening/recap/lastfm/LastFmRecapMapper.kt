package com.stash.feature.listening.recap.lastfm

import com.stash.core.data.db.dao.DayCount
import com.stash.core.data.db.dao.ListeningTotals
import com.stash.core.data.db.dao.ObsessionRow
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.db.entity.ArtistImageEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.genre.TopGenresCalculator
import com.stash.core.data.lastfm.LastFmScrobble
import com.stash.core.data.lastfm.recap.LastFmSongAsk
import com.stash.core.data.listening.ListeningStatsCalculator
import com.stash.feature.listening.recap.RecapData
import com.stash.feature.listening.recap.RecapDeck
import com.stash.feature.listening.recap.RecapPeriod
import com.stash.feature.listening.recap.RecapPicks
import com.stash.feature.listening.recap.RecapViewModel
import com.stash.feature.listening.stats.ListeningInsights
import com.stash.feature.listening.stats.artistsWithPhotos
import java.time.Instant
import java.time.ZoneId

/** Everything one Last.fm recap is built from, read once (LastFmRecapLoader). */
internal data class LastFmRecapInput(
    val period: RecapPeriod,
    val today: Long,
    val zone: ZoneId,
    val scrobbles: List<LastFmScrobble>,
    /** The library's rows by song key ([LastFmRecapMapper.localIndex]), matched READ-ONLY. */
    val localTracks: Map<String, TrackEntity>,
    /** Song lengths from Last.fm (track.getInfo) by song key. */
    val durations: Map<String, Long>,
    val photos: List<ArtistImageEntity>,
    val genreIndex: ArtistGenreIndex,
)

/**
 * Turns Last.fm scrobbles into the same [RecapData] the Stash recap is built from, so the deck,
 * cards, music and share images are reused as they are (LASTFM-RECAP.md §2). Pure: no I/O, no
 * clock, the zone passed in.
 *
 * Nothing here is ever written anywhere: a song's row is either a COPY of a matching library
 * track (same id, so its file / cached stream make the story's music cheap) or an in-memory
 * [TrackEntity] with a negative id, which can never meet a real row or a stream cache entry.
 *
 * Time: a scrobble says nothing about how long it was heard, so each counts as its song's length
 * (the library track's → Last.fm's → the average of the known lengths → [DEFAULT_SONG_MS]) and
 * every listen is marked estimated: every Last.fm time reads "about".
 */
internal object LastFmRecapMapper {

    /** A song's length when nothing better is known: 3:30. */
    const val DEFAULT_SONG_MS = 210_000L

    /**
     * In-memory songs' ids: this minus their rank. The negative id space is already shared out:
     * search results use `videoId.hashCode()` (the Int range, down to -2^31) and scanned files use
     * -2^32 and below ([com.stash.core.model.SCANNED_ID_BASE]: RecapAudio and the player treat
     * those as local files and won't stream them). This band sits between the two, clear of both,
     * with room for two billion songs.
     */
    const val SYNTHETIC_ID_BASE = -2_200_000_000L

    /** Songs whose length is asked of Last.fm per story (most scrobbled first, no library length). */
    const val DURATION_ASKS = 20

    /** Artists whose genres the story asks about (most scrobbled first). */
    const val GENRE_ARTISTS = 40

    /** The key songs are grouped by: the same title + main act key Your listening uses. */
    fun songKey(title: String, artist: String): String = ListeningInsights.songKey(title, artist)

    /**
     * The library's rows by song key; when several rows are one song, the best stand-in: a
     * downloaded one with a file first (story music plays it), then one with a length, then one
     * with art, then the oldest.
     */
    fun localIndex(tracks: List<TrackEntity>): Map<String, TrackEntity> =
        tracks.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .groupBy { songKey(it.title, it.artist) }
            .mapValues { (_, rows) ->
                rows.sortedWith(
                    compareByDescending<TrackEntity> { it.isDownloaded && !it.filePath.isNullOrBlank() }
                        .thenByDescending { it.durationMs > 0 }
                        .thenByDescending { !it.albumArtPath.isNullOrBlank() || !it.albumArtUrl.isNullOrBlank() }
                        .thenBy { it.id },
                ).first()
            }

    /** The most scrobbled songs without a library length, to ask Last.fm about (at most [limit]). */
    fun durationAsks(scrobbles: List<LastFmScrobble>, local: Map<String, TrackEntity>, limit: Int = DURATION_ASKS): List<LastFmSongAsk> =
        group(scrobbles).asSequence()
            .filter { (local[it.key]?.durationMs ?: 0L) <= 0L }
            .take(limit)
            .map { LastFmSongAsk(it.key, it.artist, it.title) }
            .toList()

    /** Raw artist credits, most scrobbled first (the genre look-ups' order). */
    fun genreArtists(scrobbles: List<LastFmScrobble>, limit: Int = GENRE_ARTISTS): List<String> =
        scrobbles.groupingBy { it.artist }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }

    fun build(input: LastFmRecapInput): RecapData {
        val days = input.period.dayRange(input.today)
        // Local day and hour of each scrobble, in the phone's zone; only the period's days count.
        val stamped = input.scrobbles.mapNotNull { s ->
            val local = Instant.ofEpochSecond(s.uts).atZone(input.zone)
            val day = local.toLocalDate().toEpochDay()
            if (day in days) Stamped(s, day, local.hour, songKey(s.title, s.artist)) else null
        }
        if (stamped.isEmpty()) return RecapData(input.period, days, input.today, ListeningTotals.EMPTY, emptyList(), emptyList(), 0)

        val groups = group(stamped.map { it.scrobble })
        val photoByArtist = HashMap<String, String>()
        for (p in input.photos) p.imageUrl?.let { photoByArtist[ListeningInsights.artistKey(p.artistName)] = it }

        // Song lengths: the library's, else Last.fm's; the rest get the average of those known.
        val known = groups.associate { g -> g.key to ((input.localTracks[g.key]?.durationMs ?: 0L).takeIf { it > 0 } ?: input.durations[g.key]?.takeIf { it > 0 }) }
        val knownValues = known.values.filterNotNull()
        val average = if (knownValues.isEmpty()) DEFAULT_SONG_MS else knownValues.average().toLong()
        fun lengthOf(key: String): Long = known[key] ?: average

        // One row per song, most scrobbled first; songs not in the library get ids from the very
        // bottom of the range (never a real row, never search's own synthetic negative ids).
        val tracks = HashMap<String, TrackEntity>()
        val rows = groups.mapIndexed { i, g ->
            val track = trackFor(g, input.localTracks[g.key], lengthOf(g.key), photoByArtist, syntheticId = SYNTHETIC_ID_BASE - i)
            tracks[g.key] = track
            TopTrackRow(track, g.plays, g.lastUts * 1000, g.plays * lengthOf(g.key))
        }
        val songs = ListeningInsights.mergeSameSongs(rows)

        // Artists: raw credits first (genres weigh those), then main acts (the cards show those).
        val lastUtsByKey = groups.associate { it.key to it.lastUts }
        val credits = stamped.groupBy { it.scrobble.artist }.map { (artist, list) ->
            // The credit's cover: its most scrobbled song (then the latest) that has art, else that song.
            val ranked = list.groupingBy { it.key }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { lastUtsByKey[it.key] ?: 0L })
                .map { tracks.getValue(it.key) }
            val leadTrack = ranked.firstOrNull { !it.albumArtPath.isNullOrBlank() || !it.albumArtUrl.isNullOrBlank() } ?: ranked.first()
            TopArtistRow(
                artist = artist,
                plays = list.size,
                songs = list.map { it.key }.distinct().size,
                lastPlayedAt = list.maxOf { it.scrobble.uts } * 1000,
                albumArtUrl = leadTrack.albumArtUrl,
                albumArtPath = leadTrack.albumArtPath,
                listenedMs = list.sumOf { lengthOf(it.key) },
            )
        }
        val acts = ListeningInsights.byMainArtist(credits)
        val topArtists = artistsWithPhotos(acts.take(RecapDeck.RANKED), input.photos)
        val genres = TopGenresCalculator.compute(ListeningInsights.artistWeights(credits), input.genreIndex, RecapDeck.GENRES_SHOWN)

        val topArtistKey = topArtists.firstOrNull()?.key
        val topArtistDays = if (topArtistKey == null) emptySet() else
            stamped.filter { ListeningInsights.artistKey(it.scrobble.artist) == topArtistKey }.mapTo(HashSet()) { it.day }

        val hourly = IntArray(24).also { h -> stamped.forEach { h[it.hour]++ } }.toList()
        val dayCounts = stamped.groupBy { it.day }.map { (day, list) -> DayCount(day, list.size, list.sumOf { lengthOf(it.key) }) }
        val busiest = ListeningStatsCalculator.busiestDay(dayCounts)
        val busiestSongs = busiest?.let { b ->
            stamped.filter { it.day == b.day }.groupBy { it.key }
                .map { (key, list) -> TopTrackRow(tracks.getValue(key), list.size, list.maxOf { it.scrobble.uts } * 1000, list.size * lengthOf(key)) }
                .sortedWith(compareByDescending<TopTrackRow> { it.plays }.thenByDescending { it.lastPlayedAt })
                .take(RecapDeck.DAY_TOP)
        }.orEmpty()
        // On repeat: the song scrobbled most times in one day (the latest such day on a tie, as Stash's).
        val obsession = stamped.groupBy { it.day to it.key }
            .maxWithOrNull(compareBy<Map.Entry<Pair<Long, String>, List<Stamped>>> { it.value.size }.thenBy { it.key.first })
            ?.let { (k, list) -> ObsessionRow(tracks.getValue(k.second), k.first, list.size) }

        val totals = ListeningTotals(
            plays = stamped.size,
            songs = groups.size,
            artists = acts.size,
            days = dayCounts.size,
            listenedMs = stamped.sumOf { lengthOf(it.key) },
            // Every scrobble's time is its song's length: all estimated, so every time says "about".
            estimatedPlays = stamped.size,
        )
        return RecapData(
            period = input.period,
            days = days,
            today = input.today,
            totals = totals,
            topSongs = songs.take(RecapViewModel.PLAYLIST_SIZE),
            topArtists = topArtists,
            artistCount = acts.size,
            genres = genres,
            artistTopSongs = RecapPicks.artistTopSongs(songs, topArtists),
            genreSong = RecapPicks.genreSong(songs, credits, input.genreIndex, genres),
            topArtistDays = topArtistDays,
            hourly = hourly,
            longestStreak = ListeningStatsCalculator.longestStreak(dayCounts),
            busiestDay = busiest,
            busiestDaySongs = busiestSongs,
            obsession = obsession,
            // "New to you" and "first played" need the whole history: not knowable from one period.
            newSongs = 0,
            newFinds = emptyList(),
            topSongFirstListen = null,
        )
    }

    /**
     * A song's row: a copy of the library's track when one matches (its id, file and stream, with
     * Last.fm's spelling and art), else an in-memory track with [syntheticId] (negative). Art:
     * Last.fm's album image → the library track's art → the artist's photo → none (Stash purple).
     */
    private fun trackFor(
        g: SongGroup,
        local: TrackEntity?,
        /** The song's length, known or estimated: story music starts a third of the way in. */
        length: Long,
        photoByArtist: Map<String, String>,
        syntheticId: Long,
    ): TrackEntity {
        val photo = photoByArtist[ListeningInsights.artistKey(g.artist)]
        return if (local != null) {
            val lastFmArt = g.image
            local.copy(
                title = g.title,
                artist = g.artist,
                album = g.album.ifBlank { local.album },
                albumArtUrl = lastFmArt ?: local.albumArtUrl ?: photo.takeIf { local.albumArtPath.isNullOrBlank() },
                albumArtPath = if (lastFmArt != null) null else local.albumArtPath,
            )
        } else {
            TrackEntity(
                id = syntheticId,
                title = g.title,
                artist = g.artist,
                album = g.album,
                durationMs = length,
                albumArtUrl = g.image ?: photo,
            )
        }
    }

    /** Scrobbles grouped into songs, most scrobbled first (then the latest), spelt as last scrobbled. */
    private fun group(scrobbles: List<LastFmScrobble>): List<SongGroup> =
        scrobbles.groupBy { songKey(it.title, it.artist) }
            .map { (key, list) ->
                val latest = list.maxBy { it.uts }
                SongGroup(
                    key = key,
                    title = latest.title,
                    artist = latest.artist,
                    album = latest.album,
                    image = list.sortedByDescending { it.uts }.firstNotNullOfOrNull { it.image },
                    plays = list.size,
                    lastUts = latest.uts,
                )
            }
            .sortedWith(compareByDescending<SongGroup> { it.plays }.thenByDescending { it.lastUts }.thenBy { it.key })

    private data class SongGroup(
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val image: String?,
        val plays: Int,
        val lastUts: Long,
    )

    private data class Stamped(val scrobble: LastFmScrobble, val day: Long, val hour: Int, val key: String)
}
