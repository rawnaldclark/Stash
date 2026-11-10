// Vendored from stash-addon-spec types/kotlin at 8ca8e83 (Stash Add-ons v1, draft 2). Edit the spec, then copy it here again.
package com.stash.addons.contract

import kotlinx.serialization.Serializable

/** The body of every non-2xx answer (SPEC.md 7). */
@Serializable
public data class ErrorBody(val error: ErrorInfo)

@Serializable
public data class ErrorInfo(
    /** Raw: a newer add-on may send a code this host does not know. Use [ErrorCode.of]. */
    val code: String,
    val message: String,
    /** Whole seconds, 1 to 86 400. Required with rate_limited and quota_exceeded. */
    val retryAfter: Int? = null,
    val quota: Quota? = null,
)

/** Codes an add-on sends, with their HTTP status. [isMiss]: the host may remember the miss. */
public enum class ErrorCode(public val wire: String, public val status: Int, public val isMiss: Boolean) {
    BAD_REQUEST("bad_request", 400, false),
    UNAUTHORIZED("unauthorized", 401, false),
    NOT_FOUND("not_found", 404, true),
    /** The song is there, but not within [minTier, tier] in an accepted delivery: a miss for that range only. */
    QUALITY_UNAVAILABLE("quality_unavailable", 404, true),
    REGION_UNAVAILABLE("region_unavailable", 451, true),
    RATE_LIMITED("rate_limited", 429, false),
    QUOTA_EXCEEDED("quota_exceeded", 429, false),
    UNAVAILABLE("unavailable", 503, false);

    public companion object {
        /**
         * The code a host acts on (SPEC 7). A 5xx other than 503 is `unavailable` whatever its
         * body says. Otherwise the body's code when the host knows it, else by HTTP status
         * (401 unauthorized, 404 not_found, 429 rate_limited, 451 region_unavailable,
         * other 4xx bad_request, 5xx unavailable).
         */
        public fun of(status: Int, bodyCode: String?): ErrorCode {
            if (status in 500..599 && status != 503) return UNAVAILABLE
            entries.firstOrNull { it.wire == bodyCode }?.let { return it }
            return when {
                status == 404 -> NOT_FOUND
                status == 429 -> RATE_LIMITED
                status == 451 -> REGION_UNAVAILABLE
                status == 401 -> UNAUTHORIZED
                status in 400..499 -> BAD_REQUEST
                else -> UNAVAILABLE
            }
        }
    }
}

/** Failures a host assigns itself; add-ons never send them. */
public enum class HostFailure { TIMEOUT, NETWORK, INVALID_RESPONSE }
