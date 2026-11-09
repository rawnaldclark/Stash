package com.stash.feature.sync.components

import com.stash.core.data.sync.SyncPhase

/** Human-readable label for the current sync phase. */
internal fun phaseLabel(phase: SyncPhase): String = when (phase) {
    // "Sync now" tapped offline: it starts by itself when a connection returns.
    is SyncPhase.WaitingForNetwork -> "Waiting for network..."
    is SyncPhase.Authenticating -> "Authenticating..."
    is SyncPhase.FetchingPlaylists -> {
        // No fixed total is known during fetch (Spotify's folder-walked
        // playlist count and YouTube's aren't known until the fetch itself
        // finishes) — so this is a live running count, not a fraction.
        // Still gives the user visible proof of forward progress on a
        // long fetch instead of a static "Fetching playlists..." that
        // looked frozen for 40+ minutes on large libraries.
        if (phase.playlistsFetched > 0) {
            "Fetched ${phase.playlistsFetched} playlist${if (phase.playlistsFetched != 1) "s" else ""}..."
        } else {
            "Fetching playlists..."
        }
    }
    is SyncPhase.Diffing -> {
        if (phase.totalPlaylists > 0) {
            "Comparing changes (${phase.playlistsDiffed}/${phase.totalPlaylists})..."
        } else {
            "Comparing changes..."
        }
    }
    
    is SyncPhase.VerifyingLibrary -> "Checking your library (${phase.step}/${phase.total})..."
    is SyncPhase.Downloading ->
        if (phase.deferred > 0) {
            "Downloading ${phase.downloaded}/${phase.total} · ${phase.deferred} waiting..."
        } else {
            "Downloading ${phase.downloaded}/${phase.total}..."
        }
    is SyncPhase.Finalizing -> "Finalizing..."
    is SyncPhase.Completed -> "Complete"
    is SyncPhase.Error -> "Error"
    else -> "Syncing..."
}
