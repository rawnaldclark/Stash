package com.stash.core.addons

import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.Quota

/** What one add-on call came to: the checked answer, or why there is none. */
sealed interface AddonResult<out T> {
    data class Ok<T>(val value: T) : AddonResult<T>
    data class Failed(val failure: AddonFailure) : AddonResult<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value
}

/**
 * Why an add-on call has no answer (SPEC.md 7, plus the host's own failures). Nothing here ever
 * holds an add-on's URL or token: these values reach logs.
 */
sealed interface AddonFailure {
    /**
     * The add-on answered with an error. [code] is the one the host acts on (SPEC.md 7): a 5xx
     * other than 503 is `unavailable` whatever its body says; otherwise the body's code when this
     * host knows it, else by status.
     */
    data class Refused(val code: ErrorCode, val status: Int, val retryAfterSec: Int?, val quota: Quota?) : AddonFailure

    /** No answer within the route's time (SPEC.md 3.1). */
    data object Timeout : AddonFailure

    /**
     * No answer at all: refused connection, no route, a bad redirect, or a request that could not
     * be built (a token or header value a request can't carry).
     */
    data object Network : AddonFailure

    /** The answer broke the contract ([AddonValidator]) and was dropped whole. */
    data class InvalidResponse(val path: String, val reason: String) : AddonFailure

    /** Not asked: the add-on (or, for metered routes, its allowance) is paused ([AddonRateGate]). */
    data object Paused : AddonFailure

    /** Not asked: the add-on's manifest doesn't list the resource. */
    data object NotOffered : AddonFailure

    /** A miss the host may remember (not_found, quality_unavailable, region_unavailable). Never a failure of the add-on. */
    val isMiss: Boolean get() = this is Refused && code.isMiss

    /**
     * The add-on was asked and could not answer: a timeout, no connection, an invalid answer, or
     * `unavailable`. A caller that files "nothing here" must not do so over one of these. Not
     * asking at all ([Paused], [NotOffered]) and the add-on's own refusals (rate limits, quota,
     * unauthorized, bad request) are not outages: the caller treats the add-on as absent.
     */
    val isOutage: Boolean
        get() = this == Timeout || this == Network || this is InvalidResponse ||
            (this is Refused && code == ErrorCode.UNAVAILABLE)
}
