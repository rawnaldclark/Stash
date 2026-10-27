package com.stash.feature.record.imports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One writer at a time on Your record's archive (service.ts `locked`, the web's one lock across tabs): a Spotify commit, Remove
 * import, a Last.fm run, a slice of the lengths pass and Remove your Last.fm scrobbles all hold [mutex]. A Last.fm run holds it for
 * the whole run, as the web does (its pages are planned against the archive as it stands), so a Spotify commit asked for meanwhile
 * waits for it; the lengths pass holds it a slice at a time, and is stopped first by the others.
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

    private val _lengths = MutableStateFlow(false)

    /**
     * The lengths pass is timing songs (a row rewritten every second or so, for as long as it takes): the Home card holds the
     * plays as they were, and reads them again only when the pass shows what it timed ([lengthsShown]) and once it is over.
     */
    val lengthsRunning: StateFlow<Boolean> = _lengths.asStateFlow()

    internal fun setLengthsRunning(running: Boolean) {
        _lengths.value = running
    }

    private val _shown = MutableStateFlow(0)

    /** Moves on each time the lengths pass shows the songs it timed (service.ts: the taste and the record worked out again). */
    val lengthsShown: StateFlow<Int> = _shown.asStateFlow()

    internal fun showLengths() = _shown.update { it + 1 }
}
