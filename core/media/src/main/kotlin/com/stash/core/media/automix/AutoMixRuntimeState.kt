package com.stash.core.media.automix

import com.stash.core.data.prefs.AutoMixMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AutoMixRuntimeState(
    val isMixing: Boolean = false,
    val mode: AutoMixMode? = null,
    val outgoingMediaId: String? = null,
    val incomingMediaId: String? = null,
    val outgoingBpm: Float? = null,
    val incomingBpm: Float? = null,
    val sharedBpm: Float? = null,
    val tempoReturnMediaId: String? = null,
    val tempoReturnEndsAtElapsedMs: Long = 0L,
) {
    fun isTempoReturnFor(mediaId: String?, nowElapsedMs: Long): Boolean =
        mediaId != null && mediaId == tempoReturnMediaId && nowElapsedMs < tempoReturnEndsAtElapsedMs
}

@Singleton
class AutoMixStateBus @Inject constructor() {
    private val _state = MutableStateFlow(AutoMixRuntimeState())
    val state: StateFlow<AutoMixRuntimeState> = _state.asStateFlow()

    fun beginMixing(
        mode: AutoMixMode,
        outgoingMediaId: String,
        incomingMediaId: String,
        outgoingBpm: Float?,
        incomingBpm: Float?,
        sharedBpm: Float?,
    ) {
        _state.value = AutoMixRuntimeState(
            isMixing = true,
            mode = mode,
            outgoingMediaId = outgoingMediaId,
            incomingMediaId = incomingMediaId,
            outgoingBpm = outgoingBpm,
            incomingBpm = incomingBpm,
            sharedBpm = sharedBpm,
        )
    }

    fun beginTempoReturn(mediaId: String, durationMs: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            isMixing = false,
            tempoReturnMediaId = mediaId,
            tempoReturnEndsAtElapsedMs = now + durationMs + 250L,
        )
    }

    fun finishTempoReturn(mediaId: String?) {
        if (mediaId == null || _state.value.tempoReturnMediaId == mediaId) clear()
    }

    fun clear() {
        _state.value = AutoMixRuntimeState()
    }
}
