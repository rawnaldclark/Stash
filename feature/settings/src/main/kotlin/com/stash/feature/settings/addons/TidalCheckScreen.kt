package com.stash.feature.settings.addons

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stash.data.download.lossless.tidal.TidalInstallPass
import com.stash.feature.settings.R
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsRowPadH

/**
 * The card above the play order when this phone should pass Stash's Tidal's quick check: what it is, Check, and why
 * the last try didn't go through. While the solved check is handed in, Check waits.
 */
@Composable
internal fun TidalCheckCard(check: TidalCheck, onCheck: () -> Unit) {
    SettingsGroupCard(
        rows = listOf(
            {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_addons_tidal_check_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_addons_tidal_check_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (check is TidalCheck.Failed) {
                        Text(
                            text = check.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        val sending = check == TidalCheck.Sending
                        TextButton(onClick = onCheck, enabled = !sending, modifier = Modifier.testTag("tidal_check")) {
                            Text(
                                stringResource(
                                    if (sending) R.string.settings_addons_tidal_check_sending else R.string.settings_addons_tidal_check_action,
                                ),
                            )
                        }
                    }
                }
            },
        ),
    )
}

/**
 * Stash's Tidal's quick check: the add-on's own Turnstile page ([page], `<origin>/register`) in a WebView. Solving it
 * makes the page go to `<origin>/register/done#turnstile=<token>`; that navigation is caught here and never loaded
 * ([TidalInstallPass.turnstileToken]), and the token goes to [onSolved]. The page may not lead anywhere else: any
 * other top-level address is refused. Its cookies and storage are Cloudflare's own and are left alone (the WebView
 * profile is shared with sign-ins elsewhere in Settings).
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TidalCheckScreen(
    page: String,
    onSolved: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var solved by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(onBack = onDismiss)
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_addons_tidal_check_screen_title)) },
            navigationIcon = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        if (loading && !failed) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        }
        if (failed) {
            Text(
                text = stringResource(R.string.settings_addons_tidal_check_screen_unreachable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            cacheMode = WebSettings.LOAD_NO_CACHE
                            allowFileAccess = false
                            allowContentAccess = false
                            setSupportMultipleWindows(false)
                            javaScriptCanOpenWindowsAutomatically = false
                        }
                        webViewClient = object : WebViewClient() {
                            /** True when [url] was the hand-back: its token is taken, once, and nothing is loaded. */
                            private fun caught(url: String): Boolean {
                                if (!TidalInstallPass.isHandBack(page, url)) return false
                                val token = TidalInstallPass.turnstileToken(page, url)
                                if (token != null && !solved) {
                                    solved = true
                                    onSolved(token)
                                }
                                return true
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return true
                                if (caught(url)) return true
                                // Only the check page itself in the top frame; the challenge's own frame loads as it likes.
                                return request.isForMainFrame && url.substringBefore('#') != page
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                if (url != null && caught(url)) {
                                    view?.stopLoading()
                                    return
                                }
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true) failed = true
                            }
                        }
                        loadUrl(page)
                    }.also { webView = it }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
