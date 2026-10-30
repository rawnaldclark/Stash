package com.stash.feature.settings.components

import java.net.URI

/** CookieManager follows host-only cookie scope, including SoundCloud's mobile sign-in. */
object SoundCloudLoginCookies {
    private val origins = listOf("https://soundcloud.com", "https://m.soundcloud.com",
        "https://www.soundcloud.com", "https://secure.soundcloud.com")

    fun token(pageUrl: String?, readCookies: (String) -> String?): String? {
        val uri = pageUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
        val origin = "https://${uri.host?.lowercase()}"
        if (origin !in origins) return null
        // Prefer the active page's cookie over a cookie left at another SoundCloud origin.
        return (listOf(origin) + origins).distinct().firstNotNullOfOrNull { url ->
            readCookies(url)?.split(';')?.firstNotNullOfOrNull { part ->
                val entry = part.trim()
                if (entry.substringBefore('=') == "oauth_token") {
                    entry.substringAfter('=', "").takeIf { it.isNotBlank() }
                } else null
            }
        }
    }

    /** Remove both domain cookies and host-only cookies before starting another login. */
    val removals: List<Pair<String, String>> = origins.map {
        it to "oauth_token=; Max-Age=0; path=/"
    } + ("https://soundcloud.com" to "oauth_token=; Max-Age=0; path=/; domain=.soundcloud.com")
}
