package com.stash.feature.nowplaying.remote

import androidx.lifecycle.ViewModel
import com.stash.core.data.weblink.remote.RemoteCard
import com.stash.core.data.weblink.remote.RemoteCommand
import com.stash.core.data.weblink.remote.WebRemote
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * "Playing on Chrome" (spec 2026-10-10 §6.7): the linked browser this phone can control, and its controls. The socket and the
 * card's rules live in [WebRemote]; this only hands them to the card and the sheet.
 */
@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val remote: WebRemote,
    private val browsers: com.stash.core.media.cast.BrowserOutputs,
) : ViewModel() {
    val card: StateFlow<RemoteCard?> = remote.card

    fun playPause(card: RemoteCard) = remote.send(if (card.playing) RemoteCommand.Pause else RemoteCommand.Play)

    fun next() = remote.send(RemoteCommand.Next)

    fun prev() = remote.send(RemoteCommand.Prev)

    fun seek(positionMs: Long) = remote.send(RemoteCommand.Seek(positionMs.coerceAtLeast(0)))

    fun volume(v: Float) = remote.send(RemoteCommand.Volume(v.toDouble().coerceIn(0.0, 1.0)))

    /**
     * "Play this phone's music here" (Remote v2): the card's browser plays this phone's queue from now on, as picking it in Play
     * on does. False when it can't after all (its tab closed, or Listen Together is on).
     */
    fun playPhoneHere(card: RemoteCard): Boolean = browsers.connect(card.device)
}
