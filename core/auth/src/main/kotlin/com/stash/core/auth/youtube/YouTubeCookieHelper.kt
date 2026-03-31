package com.stash.core.auth.youtube

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility for YouTube Music cookie-based InnerTube authentication.
 *
 * YouTube Music's InnerTube API accepts authentication via browser cookies
 * and a SAPISIDHASH authorization header. This is the same approach used by
 * the popular ytmusicapi Python library and works without any Google Cloud
 * project setup.
 *
 * The user logs into music.youtube.com in their browser, copies their cookies,
 * and pastes them into the app. This class extracts the SAPISID value and
 * generates the required authorization header for each request.
 */
@Singleton
class YouTubeCookieHelper @Inject constructor() {

    companion object {
        private const val ORIGIN = "https://music.youtube.com"
    }

    /**
     * Extracts the SAPISID value from a cookie string.
     *
     * Tries `SAPISID` first, then falls back to `__Secure-3PAPISID`.
     * Both contain the same value; availability depends on the browser.
     *
     * @param cookies The full cookie header value from the browser.
     * @return The SAPISID value, or null if not found.
     */
    fun extractSapiSid(cookies: String): String? {
        val pairs = cookies.split(";").map { it.trim() }
        return pairs.firstOrNull { it.startsWith("SAPISID=") }
            ?.substringAfter("SAPISID=")
            ?: pairs.firstOrNull { it.startsWith("__Secure-3PAPISID=") }
                ?.substringAfter("__Secure-3PAPISID=")
    }

    /**
     * Checks whether the cookie string contains `LOGIN_INFO`, which yt-dlp
     * requires to consider YouTube cookies valid.
     *
     * Without `LOGIN_INFO`, yt-dlp warns "cookies are no longer valid" even
     * when SAPISID is present. The InnerTube API doesn't need LOGIN_INFO
     * (it uses SAPISIDHASH auth), but yt-dlp does.
     */
    fun hasLoginInfo(cookies: String): Boolean {
        return cookies.split(";").any { it.trim().startsWith("LOGIN_INFO=") }
    }

    /**
     * Generates the SAPISIDHASH authorization header value.
     *
     * The hash is computed as: `SHA-1(timestamp + " " + SAPISID + " " + origin)`
     * and formatted as: `SAPISIDHASH timestamp_hash`
     *
     * @param sapiSid The SAPISID cookie value.
     * @return The full Authorization header value.
     */
    fun generateAuthHeader(sapiSid: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val input = "$timestamp $sapiSid $ORIGIN"
        val digest = MessageDigest.getInstance("SHA-1")
        val hash = digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$hash"
    }
}
