package com.stash.feature.nowplaying.handoff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.media.PlayerRepository
import com.stash.core.media.handoff.HandoffOffer
import com.stash.core.media.handoff.HandoffOffers
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The continue card above the mini player (link-sync spec §2.5): another device's state while this phone isn't playing.
 * One tap continues it here; ✕ dismisses that exact state.
 */
@HiltViewModel
class HandoffViewModel @Inject constructor(
    private val offers: HandoffOffers,
    player: PlayerRepository,
) : ViewModel() {
    private val playing = player.playerState.map { it.isPlaying }.distinctUntilChanged()

    val offer: StateFlow<HandoffOffer?> = combine(offers.offer, playing) { o, p -> o.takeUnless { p } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val note: StateFlow<String?> = offers.note

    init {
        // This phone started playing: its own state is now the newest, so the card goes for good.
        viewModelScope.launch { playing.collect { if (it) offers.clear() } }
    }

    fun accept() {
        viewModelScope.launch { offers.accept() }
    }

    fun dismiss() = offers.dismiss()

    fun noteShown() = offers.noteShown()
}
