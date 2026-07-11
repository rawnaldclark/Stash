package com.stash.feature.settings.components

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stash.core.ui.R

/**
 * Full-screen YouTube Music login via WebView.
 *
 * Mirrors [SpotifyLoginWebView] but targets Google's sign-in. Loads
 * `https://m.youtube.com/` so the user can tap the in-page Sign-In button —
 * this path bypasses the stricter `accounts.google.com/ServiceLogin`
 * fingerprint check that Google uses to block "insecure" embedded browsers.
 *
 * After successful login, Google sets the standard browser session cookies
 * on `.youtube.com`/`.google.com`. We need **two** to consider login complete:
 *  - `SAPISID` (or `__Secure-3PAPISID`) — drives the InnerTube SAPISIDHASH
 *    auth header.
 *  - `LOGIN_INFO` — yt-dlp's "cookies still valid" gate. Without it the
 *    audio-download path warns and fails closed.
 *
 * Once both appear in the cookie jar we hand the full cookie string to
 * [onCookieExtracted], which feeds it into
 * [com.stash.core.auth.TokenManager.connectYouTubeWithCookie] for
 * validation + persistence — the same downstream path the manual paste
 * flow uses today.
 *
 * @param onCookieExtracted Called once with the full `name=value; ...`
 *   cookie string after successful login.
 * @param onDismiss Called when the user taps Cancel or backs out.
 * @param onManualFallback Called when the user taps "Paste cookie" — the
 *   legacy manual-paste dialog opens for users who'd rather extract cookies
 *   themselves.
 */
private const val TAG = "YouTubeLogin"

/**
 * Stock Chrome-Android UA. The default WebView UA contains `wv` which
 * Google's anti-embedded-browser detection flags as "This browser or app
 * may not be secure." Spoofing to a real Chrome-Android string is the
 * difference between a working login and a hard block.
 */
private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

/**
 * Mobile YouTube entry point. Tapping the in-page Sign-In button from here
 * triggers Google's mobile-web sign-in flow, which empirically clears the
 * embedded-browser block more often than landing directly on
 * `accounts.google.com/ServiceLogin`.
 */
private const val LOGIN_URL = "https://m.youtube.com/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeLoginWebView(
    onCookieExtracted: (String) -> Unit,
    onDismiss: () -> Unit,
    onManualFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isLoading by remember { mutableStateOf(true) }
    var cookieFound by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_sign_in_youtube)) },
            navigationIcon = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            actions = {
                TextButton(onClick = onManualFallback) {
                    Text(
                        "Paste cookie",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (cookieFound) {
            Text(
                text = stringResource(R.string.status_login_successful),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    // Fresh session every launch — stale Google cookies cause
                    // the page to bounce to the consent screen or show a
                    // half-signed-in state that's hard to recover from.
                    CookieManager.getInstance().apply {
                        removeAllCookies(null)
                        flush()
                        setAcceptCookie(true)
                    }

                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )

                        // Per-WebView third-party cookie acceptance is
                        // separate from the global setAcceptCookie above —
                        // Google's sign-in redirects across google.com /
                        // youtube.com / accounts.google.com domains and the
                        // session cookie won't stick without this.
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            userAgentString = MOBILE_USER_AGENT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            cacheMode = WebSettings.LOAD_NO_CACHE
                            // Letting the WebView make framework choices on
                            // mixed-content keeps Google's CDN scripts loading
                            // without surprising security overrides.
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                isLoading = true
                                Log.d(TAG, "onPageStarted: $url")
                                checkCookies(url, onCookieExtracted) {
                                    cookieFound = true
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                Log.d(TAG, "onPageFinished: $url")
                                checkCookies(url, onCookieExtracted) {
                                    cookieFound = true
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                // Stay inside the WebView for the whole
                                // sign-in chain (google.com ↔ youtube.com
                                // redirects). External-app intents would
                                // break the cookie capture.
                                return false
                            }
                        }

                        loadUrl(LOGIN_URL)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Polls the cookie jar across both `music.youtube.com` and `youtube.com`
 * origins. Login is considered complete once **both** SAPISID (or its
 * `__Secure-3PAPISID` alias) and LOGIN_INFO appear — that's the minimum
 * set the InnerTube + yt-dlp paths need.
 *
 * Logs only lengths, never values, so the cookie content doesn't end up
 * in logcat.
 */
private fun checkCookies(
    url: String?,
    onExtracted: (String) -> Unit,
    onFound: () -> Unit,
) {
    val cookieJar = CookieManager.getInstance()
    val cookies = cookieJar.getCookie("https://music.youtube.com")
        ?: cookieJar.getCookie("https://www.youtube.com")
        ?: return

    val hasSapiSid = cookies.contains("SAPISID=") || cookies.contains("__Secure-3PAPISID=")
    val hasLoginInfo = cookies.contains("LOGIN_INFO=")
    if (!hasSapiSid || !hasLoginInfo) {
        Log.d(
            TAG,
            "checkCookies @ $url: jarLen=${cookies.length} " +
                "sapi=$hasSapiSid login=$hasLoginInfo (waiting)",
        )
        return
    }
    Log.i(
        TAG,
        "YouTube cookies extracted from $url (len=${cookies.length}, " +
            "sapi=$hasSapiSid, login=$hasLoginInfo)",
    )
    onFound()
    onExtracted(cookies)
}
