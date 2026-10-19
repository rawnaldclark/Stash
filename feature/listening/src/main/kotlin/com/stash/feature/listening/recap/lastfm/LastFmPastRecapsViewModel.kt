package com.stash.feature.listening.recap.lastfm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.lastfm.recap.LastFmRecapAccount
import com.stash.core.data.lastfm.recap.LastFmRecapRepository
import com.stash.core.data.lastfm.recap.LastFmRecapUser
import com.stash.core.data.lastfm.recap.LastFmSpanTotal
import com.stash.feature.listening.recap.PastRecap
import com.stash.feature.listening.recap.RecapAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject

/**
 * The "On Last.fm" group under Your listening's Past recaps: the months and years of the
 * connected Last.fm account with enough scrobbles for a recap ([RecapAvailability.MIN_PLAYS]),
 * newest months first, then the years (this one "so far" included).
 *
 * Empty when Last.fm isn't connected, and — calmly, without a message — when Last.fm can't be
 * read (private profile, offline, busy): the row just isn't there.
 */
@HiltViewModel
class LastFmPastRecapsViewModel internal constructor(
    user: Flow<LastFmRecapUser?>,
    private val availability: suspend (LastFmRecapUser, ZoneId) -> List<LastFmSpanTotal>,
    private val zone: () -> ZoneId,
) : ViewModel() {

    @Inject
    constructor(account: LastFmRecapAccount, repository: LastFmRecapRepository) :
        this(account.user, { u, zone -> repository.availability(u.name, zone, u.sessionKey) }, ZoneId::systemDefault)

    @OptIn(ExperimentalCoroutinesApi::class)
    val recaps: StateFlow<List<PastRecap>> = user
        .flatMapLatest { user -> if (user == null) flowOf(emptyList()) else flow { emit(read(user)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun read(user: LastFmRecapUser): List<PastRecap> = try {
        offered(availability(user, zone()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.i(TAG, "Last.fm recaps unavailable: ${e.message}")
        emptyList()
    }

    companion object {
        private const val TAG = "LastFmPastRecaps"

        /** The spans worth a recap, in the order counted (months newest first, then years). */
        internal fun offered(totals: List<LastFmSpanTotal>): List<PastRecap> =
            totals.filter { it.total >= RecapAvailability.MIN_PLAYS }.map { PastRecap(it.span.toPeriod(), it.total) }
    }
}
