package com.stash.core.auth.discord

import okhttp3.Request
import java.util.Base64
import java.util.TimeZone

/**
 * Issue #16 fix: upstream sent bare Authorization headers with nothing else,
 * which doesn't resemble real Discord client traffic at all — Discord's abuse
 * detection profiles the FULL header shape (User-Agent, the base64
 * X-Super-Properties blob, locale/timezone, etc), and a request missing all
 * of it is a much easier "this is a bot" signal than the request pattern
 * itself. This exists to make our requests look like the browser session
 * they're standing in for — not to defeat any actual anti-abuse control.
 *
 * `client_build_number` / `client_version` are a point-in-time snapshot of a
 * real desktop client release and WILL drift stale as Discord ships new
 * builds. Low-risk if so (informational field, not something known to be
 * validated server-side) but worth knowing if this ever needs revisiting.
 */
object DiscordHeaders {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) discord/1.0.9187 Chrome/128.0.6613.186 " +
            "Electron/32.2.6 Safari/537.36"

    private val superPropertiesJson = """
        {"os":"Windows","browser":"Discord Client","release_channel":"stable",
        "client_version":"1.0.9187","os_version":"10","os_arch":"x64",
        "system_locale":"en-US","browser_user_agent":"$USER_AGENT",
        "browser_version":"32.2.6","client_build_number":400420,
        "native_build_number":78568,"client_event_source":null}
    """.trimIndent().replace("\n", "")

    private val superPropertiesHeader: String =
        Base64.getEncoder().encodeToString(superPropertiesJson.toByteArray())

    /**
     * Applies the full header set a real client sends on every call.
     * [token] is attached RAW (no "Bearer " prefix) for user-session-auth
     * calls (the oauth2/authorize step of the connect flow) — pass
     * [bearer] = true only for calls using the exchanged OAuth access token
     * (headless-sessions itself). That split matches what the real client
     * actually does; it is not the "improper headers" issue #16 flagged —
     * that was the MISSING headers below, which is what this fixes.
     */
    fun apply(builder: Request.Builder, token: String? = null, bearer: Boolean = false): Request.Builder {
        builder
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US")
            .header("X-Discord-Locale", "en-US")
            .header("X-Discord-Timezone", TimeZone.getDefault().id)
            .header("X-Debug-Options", "bugReporterEnabled")
            .header("X-Super-Properties", superPropertiesHeader)
            .header("Origin", "https://discord.com")
            .header("Referer", "https://discord.com/channels/@me")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
        if (token != null) builder.header("Authorization", if (bearer) "Bearer $token" else token)
        return builder
    }
}