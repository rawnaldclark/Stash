package com.stash.feature.record.imports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One writer at a time on Your record's archive (service.ts `locked`, the web's one lock across tabs): a Spotify commit, Remove
 * import, a Last.fm run and Remove your Last.fm scrobbles all hold [mutex]. A Last.fm run holds it for the whole run, as the web
 * does (its pages are planned against the archive as it stands), so a Spotify commit asked for meanwhile waits for it.
 */
@Singleton
class RecordWrites @Inject constructor() {
    val mutex = Mutex()

    private val _lastfm = MutableStateFlow(false)

    /** A Last.fm run holds the lock now: Remove import is refused meanwhile, as the web refuses it while an import runs. */
    val lastfmRunning: StateFlow<Boolean> = _lastfm.asStateFlow()

    internal fun setLastfmRunning(running: Boolean) {
        _lastfm.value = running
    }
}
