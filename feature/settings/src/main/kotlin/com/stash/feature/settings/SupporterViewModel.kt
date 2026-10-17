package com.stash.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.supporter.ClaimResult
import com.stash.core.data.supporter.SupporterRepository
import com.stash.data.download.lossless.quota.LosslessAllowance
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.quota.LosslessQuotaRefresher
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings → Audio & Quality's supporter row, under the "Lossless today" line. */
sealed interface SupporterRow {
    /** No owner relay configured: there is no allowance to lift. */
    data object Hidden : SupporterRow

    /** "Unlimited lossless for supporters", opening the claim sheet. [hasToken]: one is stored but not honoured. */
    data class Offer(val hasToken: Boolean) : SupporterRow

    /** The relays report unlimited. [hasToken]: this device holds the code (it can be removed). */
    data class Supporter(val hasToken: Boolean) : SupporterRow
}

/** The claim sheet: the email step, then the code step. */
data class SupporterClaimUi(
    val step: Step = Step.Email,
    /** The address the code went to, shown on the code step. */
    val email: String = "",
    val busy: Boolean = false,
    /** A plain error or status line, or null. */
    val message: String? = null,
    /** The relays confirmed unlimited: the sheet closes and the row flips. */
    val done: Boolean = false,
) {
    enum class Step { Email, Code }
}

/**
 * The Ko-fi supporter claim ([SupporterRepository]) for Settings. Status comes from the relays,
 * not the token: after a right code (or a removal) the quota is asked again
 * ([LosslessQuotaRefresher.refreshNow]) and the row follows [LosslessQuota]'s limit.
 */
@HiltViewModel
class SupporterViewModel @Inject constructor(
    private val supporter: SupporterRepository,
    private val refresher: LosslessQuotaRefresher,
    private val quota: LosslessQuota,
    config: LosslessConfigFetcher,
) : ViewModel() {

    val row: StateFlow<SupporterRow> = combine(
        quota.state,
        combine(config.relays, config.tidalRelay) { relays, tidal -> relays.isNotEmpty() || tidal != null },
        supporter.token,
    ) { allowance, relayConfigured, token ->
        supporterRow(allowance, relayConfigured, token != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SupporterRow.Hidden)

    private val _claim = MutableStateFlow(SupporterClaimUi())
    val claim: StateFlow<SupporterClaimUi> = _claim.asStateFlow()

    /** Asks the tip jar to email a code to [email]. */
    fun sendCode(email: String) = launchStep { supporter.claimStart(email).let { r -> _claim.value.after(r, email.trim()) } }

    /** Checks [code]; a right one is then confirmed with the relays. */
    fun unlock(code: String) {
        val email = _claim.value.email
        launchStep {
            when (val r = supporter.claimVerify(email, code)) {
                ClaimResult.Unlocked -> {
                    refresher.refreshNow()
                    _claim.value.unlocked(confirmed = quota.current()?.unlimited == true)
                }
                else -> _claim.value.after(r, email)
            }
        }
    }

    /** Back to the email step (a different address, or a code that can no longer be used). */
    fun startOver() = _claim.update { SupporterClaimUi() }

    /** The sheet was closed: the next open starts fresh. */
    fun reset() = _claim.update { if (it.busy) it else SupporterClaimUi() }

    /** Removes the code from this device; the relays then count it as free. */
    fun removeCode() {
        viewModelScope.launch {
            supporter.clear()
            refresher.refreshNow()
        }
        _claim.update { SupporterClaimUi() }
    }

    /** Runs [step] once at a time with the sheet busy, and shows what it returns. */
    private fun launchStep(step: suspend () -> SupporterClaimUi) {
        if (_claim.value.busy) return
        _claim.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            _claim.value = step().copy(busy = false)
        }
    }
}

/** The supporter row for what the relays last said ([allowance]: limit null = unlimited). */
internal fun supporterRow(allowance: LosslessAllowance?, relayConfigured: Boolean, hasToken: Boolean): SupporterRow = when {
    !relayConfigured -> SupporterRow.Hidden
    allowance?.unlimited == true -> SupporterRow.Supporter(hasToken)
    else -> SupporterRow.Offer(hasToken)
}

/** The sheet after [r] for [email]: the next step, or the same step with a plain message. */
internal fun SupporterClaimUi.after(r: ClaimResult, email: String): SupporterClaimUi = when (r) {
    ClaimResult.CodeSent -> SupporterClaimUi(step = SupporterClaimUi.Step.Code, email = email)
    ClaimResult.Unlocked -> unlocked(confirmed = true)
    ClaimResult.BadEmail -> copy(message = "That doesn't look like an email address. Check it and try again.")
    ClaimResult.BadCode -> copy(message = "That code isn't right. Check the email and try again.")
    ClaimResult.StartOver -> SupporterClaimUi(email = email, message = "That code can't be used any more. Ask for a new one.")
    is ClaimResult.RateLimited -> copy(message = rateLimitedMessage(r.retryAfterSec))
    ClaimResult.NotConfigured -> copy(message = "Unlocking isn't available right now. Please try again later.")
    ClaimResult.Unreachable -> copy(message = "Couldn't reach Stash. Check your connection and try again.")
}

/**
 * A right code: [confirmed] when the relays now report unlimited (the sheet closes); otherwise
 * the code is kept and the sheet says the relays have not confirmed it yet.
 */
internal fun SupporterClaimUi.unlocked(confirmed: Boolean): SupporterClaimUi =
    if (confirmed) copy(done = true, message = null)
    else copy(message = "Code accepted. Unlimited lossless shows here once Stash confirms it.")

private fun rateLimitedMessage(retryAfterSec: Long?): String {
    val minutes = retryAfterSec?.let { (it + 59) / 60 }
    return when {
        minutes == null -> "Too many codes asked for. Please try again in a little while."
        minutes <= 1 -> "Too many codes asked for. Please try again in a minute."
        else -> "Too many codes asked for. Please try again in $minutes minutes."
    }
}
