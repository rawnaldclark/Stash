package com.stash.feature.settings.components

import android.annotation.SuppressLint
import android.util.Log
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stash.core.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Hosts a [WebView] pointed at `arcod.xyz` so the user can sign in with
 * Google (Supabase OAuth) without leaving the app, then harvests the
 * resulting Supabase session straight out of the page's `localStorage`.
 *
 * ARCOD is a single-page app whose Supabase client stashes the logged-in
 * session under a fixed `localStorage` key once the OAuth round-trip
 * completes. There is no cookie and no redirect we can intercept, so the
 * only reliable signal is to poll `localStorage` from JS and read the
 * value back. `domStorageEnabled` is therefore mandatory — Android
 * WebViews ship with DOM storage OFF, and without it `getItem` returns
 * null forever and the harvest never fires.
 *
 * On success the parsed `access_token` / `refresh_token` /
 * `expires_at` (epoch SECONDS in the payload → milliseconds here) are
 * forwarded to [onConnected]; the caller persists them via
 * `ArcodCredentialStore` and the ARCOD source/interceptor pick them up.
 */
@Composable
fun ArcodConnectScreen(
    onConnected: (accessToken: String, refreshToken: String, expiresAtMs: Long) -> Unit,
    onClose: () -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var statusText by remember {
        mutableStateOf("Sign in with Google to connect ARCOD. We'll detect it automatically.")
    }
    var captured by remember { mutableStateOf(false) }
    val connectedText = stringResource(R.string.status_arcod_connected)

    // ── Session polling ─────────────────────────────────────────────────
    // Supabase writes the session to localStorage after the OAuth round-
    // trip; there's no event we can hook from native code, so we poll the
    // key every second. evaluateJavascript hands the value back as a
    // JS-string-literal (quoted + escaped), or the literal "null".
    LaunchedEffect(Unit) {
        while (isActive && !captured) {
            delay(POLL_INTERVAL_MS)
            val wv = webViewRef ?: continue
            val raw = wv.readLocalStorage(SUPABASE_AUTH_TOKEN_KEY)
            // Unwrap evaluateJavascript's JS-string-literal quoting/escaping
            // into the raw inner localStorage value, then hand it to the
            // testable parser (which also handles supabase-js's base64- form).
            val inner = unwrapJsStringLiteral(raw)
            if (inner != null) {
                // Token-free shape log so Task-13 on-device debugging can see
                // WHAT we harvested (length + whether it's the base64- form)
                // without ever leaking the token itself.
                Log.d(
                    TAG,
                    "harvest shape: len=${inner.length} base64Prefixed=${inner.startsWith("base64-")}",
                )
            }
            val session = parseSupabaseSession(inner) ?: continue
            captured = true
            statusText = connectedText
            onConnected(session.accessToken, session.refreshToken, session.expiresAtMs)
            // Brief beat so the success text is visible before the pop.
            delay(400)
            onClose()
        }
    }

    // ── Back-press handling ────────────────────────────────────────────
    // Walk the WebView's own history first (the Google login flow is
    // several pages deep) and only exit the screen once it can't go back.
    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onClose()
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────────
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
                    contentDescription = stringResource(R.string.cd_close),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.title_connect_arcod),
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

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> buildArcodWebView(context).also { webViewRef = it } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Suspending wrapper over the async [WebView.evaluateJavascript] callback. */
private suspend fun WebView.readLocalStorage(key: String): String? =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript("localStorage.getItem('$key')") { result ->
            cont.resume(result)
        }
    }

@SuppressLint("SetJavaScriptEnabled")
private fun buildArcodWebView(context: android.content.Context): WebView =
    WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true   // Supabase SPA + Google OAuth need JS
            domStorageEnabled = true   // REQUIRED: the session lives in localStorage
            mediaPlaybackRequiresUserGesture = true
            // Google's OAuth page rejects the bare Android WebView UA with a
            // "this browser may not be secure" wall. Present a stock mobile
            // Chrome UA so the login flow completes.
            userAgentString = MOBILE_CHROME_UA
        }
        webViewClient = WebViewClient()
        loadUrl(ARCOD_URL)
    }

/**
 * Unwraps the value [WebView.evaluateJavascript] hands back. The result is a
 * JS string literal: double-quoted and backslash-escaped (or the bare token
 * `null` when the key is absent). Strips the outer quotes and unescapes so the
 * caller is left with the raw inner localStorage value.
 *
 * Unescape order matters: `\\` → `\` must run BEFORE `\"` → `"`, otherwise a
 * literal backslash-then-quote sequence is mis-decoded.
 */
private fun unwrapJsStringLiteral(evalResult: String?): String? {
    if (evalResult == null || evalResult == "null" || evalResult.isBlank()) return null
    return evalResult
        .removeSurrounding("\"")
        .replace("\\\\", "\\")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\/", "/")
}

/** Harvested Supabase session — access/refresh tokens + expiry in epoch ms. */
internal data class ArcodSupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
)

/**
 * Parses the raw localStorage value for `sb-<ref>-auth-token` into a session,
 * or null. The input is the RAW string value already unwrapped from
 * evaluateJavascript's JS-string quoting (see [unwrapJsStringLiteral]).
 *
 * Handles two storage shapes:
 *  - **Plain JSON** — older supabase-js wrote the session JSON directly.
 *  - **`base64-<base64url JSON>`** — modern supabase-js (auth-js v2) may store
 *    the value with a `base64-` prefix wrapping base64url-encoded JSON. The
 *    old regex-only parse silently found nothing in this form, so connect
 *    never fired.
 *
 * We don't pull a full JSON parser in for three well-known fields — once we
 * have the JSON string we regex the three values out. Returns null if the
 * value is absent/blank or any of the three fields is missing.
 */
internal fun parseSupabaseSession(rawLocalStorageValue: String?): ArcodSupabaseSession? {
    val raw = rawLocalStorageValue?.takeIf {
        it.isNotBlank() && it != "null"
    } ?: return null

    val json = if (raw.startsWith(BASE64_PREFIX)) {
        // base64url-decode the payload after the prefix. java.util.Base64 works
        // on both host JVM (unit tests) and device (minSdk 26+); android.util
        // .Base64 isn't available off-device.
        try {
            val payload = raw.substring(BASE64_PREFIX.length)
            String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
    } else {
        raw
    }

    val accessToken = ACCESS_TOKEN_RE.find(json)?.groupValues?.get(1) ?: return null
    val refreshToken = REFRESH_TOKEN_RE.find(json)?.groupValues?.get(1) ?: return null
    val expiresAtSeconds = EXPIRES_AT_RE.find(json)?.groupValues?.get(1)
        ?.toLongOrNull() ?: return null

    return ArcodSupabaseSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMs = expiresAtSeconds * 1000L,
    )
}

private const val BASE64_PREFIX = "base64-"

// `"field":"value"` — capture the string value.
private val ACCESS_TOKEN_RE = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
private val REFRESH_TOKEN_RE = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"")

// `"expires_at":1234567890` — capture the integer epoch-seconds value.
private val EXPIRES_AT_RE = Regex("\"expires_at\"\\s*:\\s*(\\d+)")

private const val ARCOD_URL = "https://arcod.xyz/"
private const val TAG = "ArcodConnect"
private const val SUPABASE_AUTH_TOKEN_KEY = "sb-fnlghyzwyoklfqyhqlav-auth-token"
private const val POLL_INTERVAL_MS = 1_000L
private const val MOBILE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 6 Pro) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
