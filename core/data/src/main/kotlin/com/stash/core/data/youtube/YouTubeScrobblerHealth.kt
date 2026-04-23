package com.stash.core.data.youtube

/**
 * UI-facing health of the YouTube Music history scrobbler. Derived
 * reactively in [YouTubeHistoryScrobbler] from the combination of
 * (opt-in toggle, YT auth state, kill-switch reason, pending count,
 * most recent ping outcome).
 *
 *  - [OK]: last ping succeeded and no blocking condition.
 *  - [OFFLINE]: last ping failed transiently (retrying) or pending > 0
 *    with no recent success.
 *  - [AUTH_FAILED]: last non-transient failure was 401/403 — the cookie
 *    is stale; user action required.
 *  - [PROTOCOL_BROKEN]: kill-switch tripped after 5 consecutive
 *    non-auth failures. Cleared on next app update.
 *  - [DISABLED]: user has the opt-in toggle off, OR they never connected
 *    YouTube Music. Scrobbler is dormant.
 */
enum class YouTubeScrobblerHealth {
    OK,
    OFFLINE,
    AUTH_FAILED,
    PROTOCOL_BROKEN,
    DISABLED,
}
