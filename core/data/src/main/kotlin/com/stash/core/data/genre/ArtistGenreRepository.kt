package com.stash.core.data.genre

import android.content.Context
import com.stash.core.data.db.dao.ArtistGenreDao
import com.stash.core.data.db.entity.ArtistGenreEntity
import com.stash.core.data.sync.workers.ArtistGenreWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Genres of listened artists, and top genres for any range, for "Your listening" and Recap.
 *
 * The screens own the range and its top artists (ListeningHistoryDao, minutes per artist); this
 * repository only adds genres to them: [observeTopGenres] turns a flow of artist weights into top
 * genres, re-computing when either the artists or the cache change (a run of
 * [ArtistGenreWorker] filling in more artists updates an open screen by itself).
 *
 * The cache is decoded and keyed once per change and shared by every collector ([genreIndex]),
 * not once per screen and emission. The sharing stops 5 s after the last collector leaves.
 *
 * Genres come from Last.fm artist tags; screens showing them carry a "Genres from Last.fm" credit.
 */
@Singleton
class ArtistGenreRepository internal constructor(
    private val artistGenreDao: ArtistGenreDao,
    private val context: Context,
    scope: CoroutineScope,
) {

    /** App-lifetime scope inline, like LikeCoordinator: the codebase has no ApplicationScope qualifier. */
    @Inject
    constructor(artistGenreDao: ArtistGenreDao, @ApplicationContext context: Context) :
        this(artistGenreDao, context, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** Cached genres, keyed for lookup by any credit; artists without genres are absent. */
    val genreIndex: Flow<ArtistGenreIndex> =
        artistGenreDao.observeAll()
            .map(::toIndex)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 1)

    /**
     * Top genres of the artists [artistWeights] emits (minutes per artist for a range, typically),
     * at most [limit]. Check [TopGenres.isShowable] before showing them.
     */
    fun observeTopGenres(
        artistWeights: Flow<List<ArtistWeight>>,
        limit: Int = TopGenresCalculator.DEFAULT_LIMIT,
    ): Flow<TopGenres> =
        combine(artistWeights, genreIndex) { artists, index ->
            TopGenresCalculator.compute(artists, index, limit)
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /**
     * Asks for genres of newly listened artists now. Cheap to repeat. [unmeteredOnly]: wait for
     * Wi-Fi (Home's once-a-day background ask); without it any connection does (Your listening
     * opening, where the user is looking at the genres).
     */
    fun requestRefresh(unmeteredOnly: Boolean = false) = ArtistGenreWorker.requestNow(context, unmeteredOnly)

    internal companion object {
        /** Tagged rows only, decoded; unreadable or empty rows are left out. */
        fun toIndex(rows: List<ArtistGenreEntity>): ArtistGenreIndex =
            ArtistGenreIndex.fromStoredKeys(
                buildMap {
                    for (row in rows) {
                        if (row.status != ArtistGenreEntity.STATUS_TAGGED) continue
                        // Re-read through today's rules: rows from an older build may hold keys
                        // since folded ("rap" next to "emo rap" became a stray "Rap" bar).
                        val genres = GenreNormalizer.renormalize(WeightedGenreCodec.decode(row.genres))
                        if (genres.isNotEmpty()) put(row.artistName, genres)
                    }
                },
            )
    }
}
