package com.stash.feature.settings.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Hosts a [WebView] pointed at `qobuz.squid.wtf` so the user can solve
 * the ALTCHA proof-of-work without leaving the app. The squid.wtf site
 * itself only triggers the captcha popup when the user clicks Download
 * on a track — so the in-app instructions tell them to do exactly that.
 *
 * Once the server sets `captcha_verified_at` (HttpOnly cookie, ~30-min
 * sliding window), a polling coroutine reads it from [CookieManager]
 * and forwards the value to [onCookieCaptured]. Caller saves it into
 * `LosslessSourcePreferences.captchaCookieValue` and the existing
 * `SquidWtfCaptchaInterceptor` picks it up reactively.
 *
 * Polling is the only reliable signal: the cookie is `HttpOnly` so JS
 * on the page can't read or notify us, and the squid.wtf bundle isn't
 * ours to add hooks to. 500-ms cadence is cheap (`CookieManager` reads
 * are in-process and instant) and gives near-instant feedback when the
 * user solves.
 */
@Composable
fun SquidWtfCaptchaScreen(
    onCookieCaptured: (String) -> Unit,
    onClose: () -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var statusText by remember {
        mutableStateOf("Click Download on any track and solve the captcha. The cookie will save automatically.")
    }
    var captured by remember { mutableStateOf(false) }

    // ── Cookie polling ──────────────────────────────────────────────────
    // Cheap in-process read, no network. Stops as soon as the cookie
    // appears so we don't keep firing after the screen dismisses.
    LaunchedEffect(Unit) {
        while (isActive && !captured) {
            delay(POLL_INTERVAL_MS)
            val cookies = CookieManager.getInstance().getCookie(SQUID_WTF_URL)
            val match = COOKIE_REGEX.find(cookies.orEmpty())
            if (match != null) {
                captured = true
                statusText = "Got it — saving and closing."
                onCookieCaptured(match.groupValues[1])
                // Slight delay so the user sees the success text flash
                // by before the route pops.
                delay(400)
                onClose()
            }
        }
    }

    // ── Back-press handling ────────────────────────────────────────────
    // Forward back gestures to the WebView until it can't go back, then
    // exit the screen. Without this, back always exits — annoying when
    // the user has navigated 3 pages deep into the squid.wtf UI.
    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onClose()
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────────
    // WebView holds a Context reference and a hefty render surface;
    // explicitly destroying on dispose prevents the leak watchdog from
    // flagging Activity recreations during the captcha flow.
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                destroy()
            }
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // -- Top bar with close + instructions --
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Verify squid.wtf captcha",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (captured) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
        }

        // -- The WebView itself --
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> buildWebView(context).also { webViewRef = it } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildWebView(context: android.content.Context): WebView {
    // Enable cookies BEFORE the WebView starts loading. setAcceptCookie
    // is a global flag (process-wide); third-party cookies are set per-
    // instance and matter even on a same-origin load because Cloudflare
    // edge providers occasionally route assets through CDN subdomains.
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
    }

    return WebView(context).apply {
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        settings.apply {
            javaScriptEnabled = true        // ALTCHA solver is a JS PoW
            domStorageEnabled = true        // squid.wtf bundle uses localStorage
            // Keep the default User-Agent — squid.wtf doesn't fingerprint
            // it and sending a custom UA risks bot-detect heuristics.
            mediaPlaybackRequiresUserGesture = true
        }
        webViewClient = WebViewClient()
        loadUrl(SQUID_WTF_URL)
    }
}

private const val SQUID_WTF_URL = "https://qobuz.squid.wtf/"
private const val POLL_INTERVAL_MS = 500L
private val COOKIE_REGEX = Regex("captcha_verified_at=([^;\\s]+)")
