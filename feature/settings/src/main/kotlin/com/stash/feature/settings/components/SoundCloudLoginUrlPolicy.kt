package com.stash.feature.settings.components

import java.net.URI

/** Main-frame sign-in navigation only; embedded captcha assets retain the browser's normal policy. */
fun isAllowedSoundCloudLoginUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return false
    val host = uri.host?.lowercase() ?: return false
    return listOf("soundcloud.com", "google.com", "apple.com", "facebook.com").any {
        host == it || host.endsWith(".$it")
    }
}
