package com.stash.data.download.lyrics

import kotlinx.coroutines.flow.Flow

/**
 * Seam so `:data:download` (retag) and the settings UI can drive the lyrics catch-up work without
 * depending on `:data:lyrics`. Same pattern as [LyricsFetchTrigger]; binding lives in `:app`.
 */
interface LyricsUpgradeTrigger {
    /**
     * Settings' "Re-check library for word-synced lyrics": one gentle, resumable sweep over stored
     * lyrics, on unmetered Wi-Fi with the battery not low. Unique work, KEEP: a tap while a lyrics
     * run is queued or active changes nothing. Only ever started by the user: no automatic walks.
     */
    fun enqueueLibraryRecheck()

    /** Stops the re-check sweep (queued or running); its cursor stays, so the next tap resumes. */
    fun cancelLibraryRecheck()

    /**
     * Manual run (Library Health button): also fetches missing lyrics, on any connected network.
     * REPLACES a queued/active run so one stuck waiting for unmetered Wi-Fi can be forced.
     */
    fun enqueueManualFetch()

    /**
     * Cancels a lyrics upgrade the retired automatic pass (once per release, and after a retag)
     * queued before this update, so an update never sets off a library walk nobody asked for.
     * User-started runs are left alone.
     */
    suspend fun cancelRetiredAutoRun()

    /** Live state of any lyrics run (Library Health or the re-check), for the Library Health card. */
    val status: Flow<LyricsFetchStatus>

    /** Live state of the re-check sweep only, for its Settings row. */
    val recheckStatus: Flow<LyricsFetchStatus>
}

sealed interface LyricsFetchStatus {
    data object Idle : LyricsFetchStatus
    /** Enqueued but waiting on constraints (network / battery). */
    data object Queued : LyricsFetchStatus
    data class Running(val done: Int, val total: Int) : LyricsFetchStatus
    data class Done(
        val upgraded: Int,
        val fetched: Int,
        val notFound: Int,
        val failed: Int,
        /** Stopped early because the lyrics service kept failing. */
        val bailed: Boolean,
    ) : LyricsFetchStatus
}