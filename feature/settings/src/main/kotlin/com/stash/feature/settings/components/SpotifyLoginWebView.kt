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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen Spotify login via WebView.
 *
 * Loads Spotify's standard login page with a desktop Chrome user-agent so
 * the full login form renders (email/password, Google SSO, Apple SSO, etc.).
 * After the user successfully authenticates, Spotify sets the `sp_dc` cookie
 * on the `.spotify.com` domain. This composable monitors the cookie jar on
 * every page load and extracts `sp_dc` as soon as it appears.
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

/** Desktop Chrome user-agent so Spotify shows the full login form. */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
            title = { Text("Sign in to Spotify") },
            navigationIcon = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
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
                text = "Login successful, connecting...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    // Clear any stale Spotify cookies from previous sessions so
                    // the user gets a fresh login form every time.
                    CookieManager.getInstance().apply {
                        removeAllCookies(null)
                        flush()
                    }

                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            userAgentString = DESKTOP_USER_AGENT
                            // These help the desktop Spotify page render properly
                            // on a mobile viewport:
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
