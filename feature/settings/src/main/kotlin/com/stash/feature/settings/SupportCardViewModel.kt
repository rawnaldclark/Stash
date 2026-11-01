package com.stash.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.supporter.SupporterRepository
import com.stash.core.data.tipjar.DonationGoalRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.ui.ambient.tintedWhilePlaying
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.feature.settings.components.SupportCardState
import com.stash.feature.settings.components.supportCardState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Feeds Settings' Support card ([com.stash.feature.settings.components.SupportBanner]): the supporter state (the
 * same flow as Lossless sources' row, [supporterRows]) with this month's goal ([DonationGoalRepository], asked of
 * stashfm.app at most once a day), and whether a song is playing, for the album colour.
 */
@HiltViewModel
class SupportCardViewModel @Inject constructor(
    private val goals: DonationGoalRepository,
    supporter: SupporterRepository,
    quota: LosslessQuota,
    config: LosslessConfigFetcher,
    playerRepository: PlayerRepository,
) : ViewModel() {

    val state: StateFlow<SupportCardState> = combine(supporterRows(quota, config, supporter), goals.goal) { row, goal -> supportCardState(row, goal) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SupportCardState())

    /**
     * Whether a song is playing, by the rule the wordmark and the selected tab follow ([tintedWhilePlaying]): the card
     * wears the album colour then, and is neutral otherwise.
     */
    val playing: StateFlow<Boolean> = playerRepository.playerState
        .tintedWhilePlaying()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Settings opens: the goal is asked for if today hasn't asked yet (the repository keeps it to once a day).
        viewModelScope.launch {
            try {
                goals.refreshIfDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "goal refresh failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "SupportCard"
    }
}
