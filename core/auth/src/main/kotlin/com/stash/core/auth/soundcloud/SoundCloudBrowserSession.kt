package com.stash.core.auth.soundcloud

import android.webkit.CookieManager
import com.stash.data.soundcloud.SoundCloudCredentials
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only adopt browser credentials when its access cookie matches the connected account's token. */
@Singleton
class SoundCloudBrowserSession @Inject constructor() {
    private val origins = listOf("https://m.soundcloud.com", "https://soundcloud.com", "https://www.soundcloud.com")
    suspend fun refreshToken(expectedAccess: String): String? = withContext(Dispatchers.Main) {
        origins.firstNotNullOfOrNull { origin ->
            val cookies = CookieManager.getInstance().getCookie(origin).orEmpty().split(';').map { it.trim() }
                .mapNotNull { if ('=' in it) it.substringBefore('=') to it.substringAfter('=') else null }.toMap()
            if (cookies["oauth_token"] == expectedAccess) cookies["oauth_refresh_token"]?.takeIf { it.isNotBlank() } else null
        }
    }
    suspend fun update(expectedAccess: String, credentials: SoundCloudCredentials) = withContext(Dispatchers.Main) {
        val jar = CookieManager.getInstance()
        for (origin in origins) {
            if (jar.getCookie(origin).orEmpty().split(';').none { it.trim() == "oauth_token=$expectedAccess" }) continue
            jar.setCookie(origin, "oauth_token=${credentials.accessToken}; Path=/; Secure; SameSite=Lax")
            jar.setCookie(origin, "oauth_refresh_token=${credentials.refreshToken}; Path=/; Secure; HttpOnly; SameSite=Lax")
        }
        jar.flush()
    }
}
