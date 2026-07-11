package com.stash.feature.settings.components

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stash.core.ui.R

/**
 * Full-screen Spotify login via WebView.
 *
 * Loads Spotify's standard login page. After the user successfully
 * authenticates, Spotify sets the `sp_dc` cookie on the `.spotify.com`
 * domain. This composable monitors the cookie jar on every page load and
 * extracts `sp_dc` as soon as it appears.
 *
 * The login page runs invisible reCAPTCHA v3 (issue #231): a low bot-score
 * makes Spotify reject the email→Continue step with "Oops! Something went
 * wrong." Two things here are tuned to keep that score up: (1) a consistent
 * mobile Chrome user-agent that matches the WebView's actual engine (a
 * desktop-UA spoof on a mobile WebView is a fingerprint inconsistency
 * reCAPTCHA penalises), and (2) clearing only Spotify's session cookies on
 * launch rather than ALL cookies, so Google's `_GRECAPTCHA` reputation
 * cookie survives across attempts. Devices that still can't pass (no GMS,
 * old WebView) have the manual "Paste cookie" path, which bypasses the
 * in-WebView challenge entirely.
 *
 * The extracted cookie value is passed to [onCookieExtracted] which feeds it
 * into the existing [TokenManager.connectSpotifyWithCookie] validation flow.
 *
 * @param onCookieExtracted Called with the raw sp_dc cookie value once login succeeds.
 * @param onDismiss Called when the user taps "Cancel" or presses back.
 * @param onManualFallback Called when the user taps "Paste cookie manually" to
 *   switch to the legacy cookie-paste dialog.
 */
private const val TAG = "SpotifyLogin"

/**
 * Mobile Chrome user-agent that MATCHES the WebView's real Blink engine and
 * form factor (and omits the "wv" WebView token). Consistency is what keeps
 * the invisible reCAPTCHA v3 score up — the previous desktop-Windows spoof on
 * an Android WebView was a fingerprint mismatch that dragged the score toward
 * "bot" and helped trigger the #231 login failures. Spotify's responsive
 * login still renders email + Continue + Google/Apple SSO under this UA.
 */
private const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"

/** Spotify session cookies to clear on launch (so a fresh login form shows). */
private val SPOTIFY_SESSION_COOKIES = listOf("sp_dc", "sp_key", "sp_t", "sp_ac", "sp_landing")

/** Spotify domains those cookies live on. */
private val SPOTIFY_COOKIE_DOMAINS = listOf(
    "https://open.spotify.com",
    "https://accounts.spotify.com",
    "https://www.spotify.com",
)

/** The URL that loads Spotify's login page. */
private const val LOGIN_URL = "https://accounts.spotify.com/login"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLoginWebView(
    onCookieExtracted: (String) -> Unit,
    onDismiss: () -> Unit,
    onManualFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isLoading by remember { mutableStateOf(true) }
    var cookieFound by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_sign_in_spotify)) },
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
                    // Clear ONLY Spotify's session cookies so the user gets a
                    // fresh login form, while preserving everything else — in
                    // particular Google's `_GRECAPTCHA` reputation cookie, which
                    // the old `removeAllCookies` wiped on every launch, hurting
                    // the invisible reCAPTCHA v3 score (issue #231).
                    CookieManager.getInstance().apply {
                        SPOTIFY_COOKIE_DOMAINS.forEach { domain ->
                            SPOTIFY_SESSION_COOKIES.forEach { name ->
                                setCookie(
                                    domain,
                                    "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/",
                                )
                            }
                        }
                        flush()
                    }

                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )

                        // reCAPTCHA runs in a cross-origin google.com iframe;
                        // its `_GRECAPTCHA` reputation cookie is a third-party
                        // cookie relative to the spotify.com page. Without this
                        // the WebView blocks it, so reCAPTCHA can't build/read
                        // reputation and scores the session as a bot (#231).
                        CookieManager.getInstance()
                            .setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = MOBILE_USER_AGENT
                            // Mobile viewport rendering for the responsive login page.
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            cacheMode = WebSettings.LOAD_NO_CACHE
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                isLoading = true
                                checkForSpDcCookie(url, onCookieExtracted) {
                                    cookieFound = true
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                checkForSpDcCookie(url, onCookieExtracted) {
                                    cookieFound = true
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                // Stay inside the WebView for all Spotify domains.
                                // External links (e.g. "Create account") should also
                                // stay in the WebView so the user doesn't leave the
                                // login flow.
                                return false
                            }
                        }

                        loadUrl(LOGIN_URL)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Persistent escape hatch. Spotify's login runs invisible reCAPTCHA v3
        // that some devices (no GMS, old WebView) can't pass, showing "Oops!
        // Something went wrong" (issue #231). Keep the manual-cookie path
        // visible at all times — not buried in the top bar — so a user who
        // hits that wall has an obvious way through instead of a dead end.
        HorizontalDivider()
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.label_signin_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onManualFallback) {
                    Text(stringResource(R.string.action_paste_cookie_instead))
                }
            }
        }
    }
}

/**
 * Checks the cookie jar for the `.spotify.com` domain. If `sp_dc` is
 * found, extracts its value and invokes [onExtracted].
 *
 * Called on both [WebViewClient.onPageStarted] and [onPageFinished] to
 * catch the cookie as early as possible — Spotify may set it during a
 * redirect before the final page finishes loading.
 */
private fun checkForSpDcCookie(
    url: String?,
    onExtracted: (String) -> Unit,
    onFound: () -> Unit,
) {
    val cookieString = CookieManager.getInstance().getCookie("https://open.spotify.com")
        ?: CookieManager.getInstance().getCookie("https://accounts.spotify.com")
        ?: return

    // Cookie string format: "name1=value1; name2=value2; ..."
    val spDcValue = cookieString.split(";")
        .map { it.trim() }
        .firstOrNull { it.startsWith("sp_dc=") }
        ?.substringAfter("sp_dc=")
        ?.trim()

    if (!spDcValue.isNullOrBlank()) {
        Log.i(TAG, "sp_dc cookie extracted from $url (length=${spDcValue.length})")
        onFound()
        onExtracted(spDcValue)
    }
}
