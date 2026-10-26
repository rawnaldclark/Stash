package com.stash.feature.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.WebLibraryExportDao
import com.stash.feature.record.archive.OwnArchive
import com.stash.feature.record.art.RecordArt
import com.stash.feature.record.bridge.RecordEvent
import com.stash.feature.record.bridge.RecordHost
import com.stash.feature.record.bridge.RecordPlayback
import com.stash.feature.record.bridge.RecordPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

/**
 * The record's screen: its [RecordHost] (the bridge's app side) for as long as the screen is open, so a page reloaded by a
 * rotation finds the same host. The page's messages come in through [onMessage] on the main thread.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    plays: WebLibraryExportDao,
    art: RecordArt,
    playback: RecordPlayback,
) : ViewModel() {
    private val _events = MutableSharedFlow<RecordEvent>(extraBufferCapacity = 8)

    /** Close, and lines for the listener. */
    val events: SharedFlow<RecordEvent> = _events.asSharedFlow()

    private val host = RecordHost(
        scope = viewModelScope,
        archive = OwnArchive(plays),
        covers = { songs, retry, onBatch -> art.covers(songs, retry, onBatch) },
        player = playback,
        events = { _events.tryEmit(it) },
    )

    fun onMessage(raw: String, from: RecordPort) = host.onMessage(raw, from)

    fun back() = host.back()

    fun setTheme(light: Boolean) = host.setTheme(light)

    /** The page's renderer stopped answering ([responsive] false) or answers again. */
    fun setResponsive(responsive: Boolean) {
        host.unresponsive = !responsive
    }

    /** The page is gone (the screen closed, or its renderer died). */
    fun detach() = host.detach()
}
