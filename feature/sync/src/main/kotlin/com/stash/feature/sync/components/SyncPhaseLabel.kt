package com.stash.feature.sync.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.stash.core.data.sync.SyncPhase
import com.stash.feature.sync.R

/** [phaseLabel], with the phases whose words live in strings.xml. */
@Composable
internal fun syncPhaseText(phase: SyncPhase): String =
    if (phase is SyncPhase.ContinuingShortly) stringResource(R.string.sync_continuing_shortly) else phaseLabel(phase, LocalContext.current)

/** Human-readable label for the current sync phase. */
internal fun phaseLabel(phase: SyncPhase, context: Context? = null): String = when (phase) {
    // "Sync now" tapped offline: it starts by itself when a connection returns.
    is SyncPhase.WaitingForNetwork ->
        context?.getString(R.string.sync_phase_waiting_network) ?: "Waiting for network..."
    is SyncPhase.Authenticating ->
        context?.getString(R.string.sync_phase_authenticating) ?: "Authenticating..."
    is SyncPhase.FetchingPlaylists -> {
        // No fixed total is known during fetch (Spotify's folder-walked
        // playlist count and YouTube's aren't known until the fetch itself
        // finishes) — so this is a live running count, not a fraction.
        // Still gives the user visible proof of forward progress on a
        // long fetch instead of a static "Fetching playlists..." that
        // looked frozen for 40+ minutes on large libraries.
        if (phase.playlistsFetched > 0) {
            context?.resources?.getQuantityString(
                R.plurals.sync_phase_fetching_playlists,
                phase.playlistsFetched,
                phase.playlistsFetched,
            ) ?: "Fetched ${phase.playlistsFetched} playlist${if (phase.playlistsFetched != 1) "s" else ""}..."
        } else {
            context?.getString(R.string.sync_phase_fetching_playlists_init) ?: "Fetching playlists..."
        }
    }
    is SyncPhase.Diffing -> {
        if (phase.totalPlaylists > 0) {
            context?.getString(
                R.string.sync_phase_comparing_changes_count,
                phase.playlistsDiffed,
                phase.totalPlaylists,
            ) ?: "Comparing changes (${phase.playlistsDiffed}/${phase.totalPlaylists})..."
        } else {
            context?.getString(R.string.sync_phase_comparing_changes) ?: "Comparing changes..."
        }
    }

    is SyncPhase.VerifyingLibrary ->
        context?.getString(
            R.string.sync_phase_verifying_library,
            phase.step,
            phase.total,
        ) ?: "Checking your library (${phase.step}/${phase.total})..."
    is SyncPhase.Downloading ->
        if (phase.deferred > 0) {
            context?.getString(
                R.string.sync_phase_downloading_deferred,
                phase.downloaded,
                phase.total,
                phase.deferred,
            ) ?: "Downloading ${phase.downloaded}/${phase.total} · ${phase.deferred} waiting..."
        } else {
            context?.getString(
                R.string.sync_phase_downloading_progress,
                phase.downloaded,
                phase.total,
            ) ?: "Downloading ${phase.downloaded}/${phase.total}..."
        }
    is SyncPhase.Finalizing ->
        context?.getString(R.string.sync_phase_finalizing) ?: "Finalizing..."
    is SyncPhase.Completed ->
        context?.getString(R.string.sync_phase_complete) ?: "Complete"
    is SyncPhase.Error ->
        context?.getString(R.string.sync_phase_error) ?: "Error"
    else ->
        context?.getString(R.string.sync_phase_syncing) ?: "Syncing..."
}
