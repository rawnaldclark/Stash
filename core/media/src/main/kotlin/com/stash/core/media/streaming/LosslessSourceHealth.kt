package com.stash.core.media.streaming

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Process-lifetime health signal for lossless as a whole (qbdlx, direct Qobuz, and the
 * Tidal rung after it), fed by [StreamSourceRegistry] as it works through the resolver chain.
 *
 * Drives exactly one thing: the Home lossless-offline banner. When the shared
 * path dies (as the shipped token pool did at scale in 2026-08 — community
 * reports of days without FLAC), every user who owns NO lossless source of
 * their own silently loses lossless: the chain falls through to YouTube and
 * the fix — connecting an account or a relay endpoint — is buried in
 * Settings › Audio. This signal lets Home offer that fix at the moment it
 * matters. Whether the user already owns a source is
 * `LosslessAvailability.anyUserOwned`, combined with this in HomeViewModel.
 *
 * "Down" means a streak of consecutive resolves where EVERY lossless rung in the
 * chain produced nothing: a song Tidal serves after qbdlx missed is lossless all the
 * same (the banner said "No lossless" while Tidal served FLAC, 10-29). A single miss is
 * routinely a catalog gap; [LOSSLESS_DOWN_THRESHOLD] misses in a row across arbitrary
 * tracks is the shared path being dead.
 * In-memory only, deliberately: a fresh process re-detects within a few
 * tracks, and persisting an "outage" flag risks a stale banner outliving
 * the outage.
 */
// ponytail: counts registry-level misses, so it can't tell "dead credential"
// from "obscure library full of catalog gaps". Refine inside
// QbdlxStreamResolver (403-vs-no_match) if false positives ever matter — the
// banner is dismissible and its advice (connect your own Qobuz account) is
// sound either way.
@Singleton
class LosslessSourceHealth @Inject constructor() {

    private val consecutiveLosslessMisses = MutableStateFlow(0)

    /** True while the last [LOSSLESS_DOWN_THRESHOLD]+ resolves all came out of the lossless rungs with nothing. */
    val losslessLooksDown: Flow<Boolean> = consecutiveLosslessMisses
        .map { it >= LOSSLESS_DOWN_THRESHOLD }
        .distinctUntilChanged()

    /** A lossless rung (qbdlx or Tidal) served the song. */
    fun recordLosslessServed() {
        consecutiveLosslessMisses.value = 0
    }

    /** Every lossless rung in the chain missed the song. */
    fun recordLosslessMiss() {
        consecutiveLosslessMisses.update { it + 1 }
    }

    companion object {
        const val LOSSLESS_DOWN_THRESHOLD = 6
    }
}
