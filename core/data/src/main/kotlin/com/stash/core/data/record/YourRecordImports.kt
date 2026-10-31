package com.stash.core.data.record

import kotlinx.coroutines.flow.StateFlow

/** What Settings shows of Your record's imports (stash-player `src/routes/settings/Spotify.svelte`). */
data class YourRecordImportState(
    /** Read from storage (until then Settings shows nothing that could change its mind). */
    val loaded: Boolean = false,
    /** Something was imported (an import record exists). */
    val imported: Boolean = false,
    /** Listens (30 s or more) that came from Spotify. */
    val spotifyPlays: Int = 0,
    /** An import is running: removing waits until it is done. */
    val importing: Boolean = false,
    /** "Press your record" was closed on Home with its ×. */
    val inviteHidden: Boolean = false,
)

/**
 * Your record's imports as Settings › Library & Storage reaches them. feature:record does the work (its import service binds
 * this), so Settings needs no dependency on it: it shows [settings], opens the import sheet, removes the imported history, and
 * brings the Home card's invitation back.
 */
interface YourRecordImports {
    val settings: StateFlow<YourRecordImportState>

    /** The import sheet, over whatever is showing. */
    fun openImport()

    /** Every imported play and the import record go (when Spotify was asked stays). False, and nothing done, while an import runs. */
    suspend fun removeImport(): Boolean

    /** "Press your record" is shown on Home again. */
    fun showInvite()
}
