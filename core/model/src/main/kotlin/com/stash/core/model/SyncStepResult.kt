package com.stash.core.model

import kotlinx.serialization.Serializable

/**
 * Records what happened during a single step of the sync pipeline.
 * A list of these is JSON-serialized into the sync_history diagnostics column.
 */
@Serializable
data class SyncStepResult(
    val service: String,
    val step: String,
    val status: StepStatus,
    val itemCount: Int = 0,
    val errorMessage: String? = null,
    val httpCode: Int? = null,
    /**
     * What a step did that is worth knowing but is no error, e.g. how many
     * Spotify playlists weren't re-read because nothing changed. Diagnostics
     * only; null for most steps, and left out of the JSON then.
     */
    val note: String? = null,
    /**
     * The playlists this step listed but couldn't read the songs of, by name (the
     * first [UNREAD_NAMES_STORED]). Each was kept as it was and is read again next
     * sync; the Sync History row counts and names them. Empty for most steps, and
     * left out of the JSON then.
     */
    val unreadPlaylists: List<String> = emptyList(),
    /** How many playlists [unreadPlaylists] stands for; it holds only the first names. */
    val unreadPlaylistCount: Int = 0,
) {
    companion object {
        /** Names kept per step: the row shows five, a whole failed library would bloat every row. */
        const val UNREAD_NAMES_STORED = 20
    }
}

/**
 * [SKIPPED] means the user switched that step off — it is NOT evidence of
 * anything working or failing. Distinct from [EMPTY] (the call ran and returned
 * nothing, which can be a real bug) so a diagnostics bundle doesn't send anyone
 * hunting an API that was simply never called.
 */
@Serializable
enum class StepStatus { SUCCESS, EMPTY, ERROR, SKIPPED }
