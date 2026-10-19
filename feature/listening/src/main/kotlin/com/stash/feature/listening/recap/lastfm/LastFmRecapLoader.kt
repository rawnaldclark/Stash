package com.stash.feature.listening.recap.lastfm

import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.recap.LastFmRecapAccount
import com.stash.core.data.lastfm.recap.LastFmRecapException
import com.stash.core.data.lastfm.recap.LastFmRecapRepository
import com.stash.core.data.lastfm.recap.LastFmRecapSpan
import com.stash.feature.listening.recap.RecapData
import com.stash.feature.listening.recap.RecapDeck
import com.stash.feature.listening.recap.RecapPeriod
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** How far a Last.fm recap has got while it loads (the story's loading line). */
sealed interface LastFmRecapProgress {
    /** Reading scrobbles: [read] so far of [expected] (0 = not known yet). */
    data class Reading(val read: Int, val expected: Int) : LastFmRecapProgress

    /** Scrobbles read; looking up song lengths and genres. */
    data object Finishing : LastFmRecapProgress
}

/** The outcome of loading a Last.fm recap. */
sealed interface LastFmRecapLoad {
    data class Ready(val data: RecapData) : LastFmRecapLoad

    /** No Last.fm account connected (any more). */
    data object NotConnected : LastFmRecapLoad

    /** Fewer than [RecapDeck.MIN_PLAYS] scrobbles in the period. */
    data class NotEnough(val scrobbles: Int) : LastFmRecapLoad

    data class Failed(val reason: LastFmRecapException.Reason) : LastFmRecapLoad
}

/** Loads a Last.fm recap's [RecapData]: an interface so the ViewModel tests stand in for it. */
interface LastFmRecapSource {
    suspend fun load(period: RecapPeriod, today: Long, onProgress: (LastFmRecapProgress) -> Unit): LastFmRecapLoad

    /** No Last.fm (tests of Stash's own recap): always "not connected". */
    object None : LastFmRecapSource {
        override suspend fun load(period: RecapPeriod, today: Long, onProgress: (LastFmRecapProgress) -> Unit) =
            LastFmRecapLoad.NotConnected
    }
}

/**
 * The Last.fm recap's data, separate from Stash's own listening (owner rule): the connected
 * account's scrobbles from Last.fm (via [LastFmRecapRepository] and its own file cache), matched
 * READ-ONLY against the library for song lengths, files and art, then mapped by
 * [LastFmRecapMapper].
 *
 * What it touches in the main database: READS of `tracks` (one `SELECT *` to match songs:
 * [TrackDao.getAllForIntegrityScan]), `artist_images` (photos) and, inside the repository,
 * `artist_genres`; and, through the repository's catalog look-ups, Last.fm's catalog answers
 * cached in `lastfm_cache` (song lengths, artist tags: catalog data, never listening data). It
 * has no handle on listening_events at all and calls no TrackDao write.
 */
internal class LastFmRecapLoader(
    private val account: LastFmRecapAccount,
    private val repository: LastFmRecapRepository,
    private val trackDao: TrackDao,
    private val artistImages: ArtistImageDao,
    private val zone: () -> ZoneId,
) : LastFmRecapSource {

    @Inject
    constructor(
        account: LastFmRecapAccount,
        repository: LastFmRecapRepository,
        trackDao: TrackDao,
        artistImages: ArtistImageDao,
    ) : this(account, repository, trackDao, artistImages, ZoneId::systemDefault)

    override suspend fun load(period: RecapPeriod, today: Long, onProgress: (LastFmRecapProgress) -> Unit): LastFmRecapLoad {
        val user = account.user.first() ?: return LastFmRecapLoad.NotConnected
        val zone = zone()
        return try {
            val scrobbles = repository.scrobbles(user.name, period.toSpan(), zone, user.sessionKey) { read, expected ->
                onProgress(LastFmRecapProgress.Reading(read, expected))
            }
            if (scrobbles.size < RecapDeck.MIN_PLAYS) return LastFmRecapLoad.NotEnough(scrobbles.size)
            onProgress(LastFmRecapProgress.Finishing)
            val local = LastFmRecapMapper.localIndex(trackDao.getAllForIntegrityScan())
            val durations = repository.durations(LastFmRecapMapper.durationAsks(scrobbles, local))
            val genres = repository.genreIndex(LastFmRecapMapper.genreArtists(scrobbles))
            val photos = artistImages.observeAll().first()
            LastFmRecapLoad.Ready(
                LastFmRecapMapper.build(LastFmRecapInput(period, today, zone, scrobbles, local, durations, photos, genres)),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: LastFmRecapException) {
            LastFmRecapLoad.Failed(e.reason)
        }
    }
}

/** The repository's span for a recap period. */
internal fun RecapPeriod.toSpan(): LastFmRecapSpan = when (this) {
    is RecapPeriod.MonthOf -> LastFmRecapSpan.Month(YearMonth.of(year, month))
    is RecapPeriod.YearOf -> LastFmRecapSpan.Year(year)
}

/** The recap period of a repository span. */
internal fun LastFmRecapSpan.toPeriod(): RecapPeriod = when (this) {
    is LastFmRecapSpan.Month -> RecapPeriod.MonthOf(month.year, month.monthValue)
    is LastFmRecapSpan.Year -> RecapPeriod.YearOf(year)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LastFmRecapSourceModule {
    @Binds
    abstract fun bind(impl: LastFmRecapLoader): LastFmRecapSource
}
