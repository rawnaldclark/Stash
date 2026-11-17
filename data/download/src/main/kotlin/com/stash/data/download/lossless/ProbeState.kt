package com.stash.data.download.lossless

/** What an on-demand check of one lossless source found. Never persisted. */
sealed interface ProbeState {
    data object Checking : ProbeState
    data object Ready : ProbeState
    data object Paused : ProbeState
    data class LimitReached(val resetsAtMs: Long) : ProbeState
    data object NotAnswering : ProbeState
    data object Off : ProbeState
}