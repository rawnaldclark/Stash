package com.stash.feature.community.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.listen.RecapStore
import com.stash.core.model.listen.SessionRecap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The Sessions screen's Recent list: the recaps this phone kept (a week, 20 at most), newest first. */
@HiltViewModel
class RecentRecapsViewModel @Inject constructor(store: RecapStore) : ViewModel() {
    val recaps: StateFlow<List<SessionRecap>> =
        store.recaps().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
