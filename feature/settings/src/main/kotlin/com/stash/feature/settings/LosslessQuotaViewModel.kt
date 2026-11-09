package com.stash.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.data.download.lossless.quota.LosslessAllowance
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.quota.LosslessQuotaText
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Settings → Audio & Quality's "Tidal today" line: the daily allowance (FLAC and AAC) the relays
 * last reported ([LosslessQuota]). Re-read every minute so the line turns over at the reset
 * while the screen is open.
 */
@HiltViewModel
class LosslessQuotaViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val quota: LosslessQuota,
    config: LosslessConfigFetcher,
) : ViewModel() {

    private val minuteTicks = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    /** The line, or null when it should not show. */
    val line: StateFlow<String?> = combine(
        quota.state,
        config.tidalRelay.map { it != null },
        minuteTicks,
    ) { allowance, relayConfigured, _ ->
        // On the relay's clock, like the rung: the line turns over when its day does.
        losslessTodayLine(allowance, relayConfigured, quota.nowMs()) { LosslessQuotaText.resetTime(context, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private companion object {
        const val TICK_MS = 60_000L
    }
}

/**
 * "Tidal today: 212 / 300 · resets 8:00 PM" for [allowance] as of [nowMs] (a day that has
 * reset reads 0 used), or "… Unlimited (supporter)". Null — the line is hidden — when no relay
 * is configured or nothing is known yet. [time] formats the reset in local time.
 */
internal fun losslessTodayLine(
    allowance: LosslessAllowance?,
    relayConfigured: Boolean,
    nowMs: Long,
    time: (Long) -> String,
): String? {
    if (!relayConfigured || allowance == null) return null
    val today = LosslessQuota.asOf(allowance, nowMs)
    return LosslessQuotaText.todayLine(today, time(today.resetsAtMs))
}
