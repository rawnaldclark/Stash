package com.stash.data.ytmusic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which URLs may receive the user's YouTube cookies and SAPISIDHASH header.
 *
 * The watch-history ping's URL (the /player response's
 * `videostatsPlaybackUrl`) gets the credentials only when it is https, on the
 * default port, with no user info, and its host, as OkHttp parses it for the
 * request, is youtube.com or one of its subdomains.
 */
object YouTubeCredentialUrl {

    private const val YOUTUBE_DOMAIN = "youtube.com"

    /** [url] parsed, or null when it may not receive YouTube credentials. Never throws. */
    fun parse(url: String?): HttpUrl? = url?.toHttpUrlOrNull()?.takeIf(::isAllowed)

    /** True when [url] may receive YouTube credentials. */
    fun isAllowed(url: HttpUrl): Boolean =
        url.isHttps &&
            url.port == 443 &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            (url.host == YOUTUBE_DOMAIN || url.host.endsWith(".$YOUTUBE_DOMAIN"))

    /**
     * `scheme://host` of [url], for logs: never its path, query or user info,
     * which can carry session state.
     */
    fun describeForLog(url: String?): String =
        url?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "an unparseable URL"
}
