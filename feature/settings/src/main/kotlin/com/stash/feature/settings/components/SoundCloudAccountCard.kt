package com.stash.feature.settings.components

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.UserInfo
import com.stash.feature.settings.R
import com.stash.feature.settings.SoundCloudAccountViewModel
import kotlinx.coroutines.delay

@Composable
fun SoundCloudAccountCard(viewModel: SoundCloudAccountViewModel = hiltViewModel()) {
    val account by viewModel.session.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var profile by rememberSaveable { mutableStateOf("") }
    var disconnect by rememberSaveable { mutableStateOf(false) }
    AccountConnectionCard(serviceName = "SoundCloud", icon = Icons.Rounded.MusicNote,
        accentColor = Color(0xFFFF5500),
        authState = if (ui.busy) AuthState.Connecting else account?.let {
            AuthState.Connected(UserInfo(it.userId, it.displayName, it.avatarUrl))
        } ?: AuthState.NotConnected,
        onConnect = viewModel::open, onDisconnect = { disconnect = true })
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false },
        title = { Text(stringResource(R.string.soundcloud_disconnect)) },
        text = { Text(stringResource(R.string.soundcloud_disconnect_body)) },
        confirmButton = { TextButton(onClick = { disconnect = false; viewModel.disconnect() }) {
            Text(stringResource(R.string.soundcloud_disconnect)) } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text(stringResource(R.string.soundcloud_cancel)) } })
    if (ui.open && !ui.web) AlertDialog(onDismissRequest = viewModel::close,
        title = { Text("SoundCloud") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::web, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.soundcloud_connect))
                }
                Text(stringResource(R.string.soundcloud_public_profile))
                OutlinedTextField(profile, { profile = it }, singleLine = true, enabled = !ui.busy,
                    label = { Text(stringResource(R.string.soundcloud_profile_url)) })
                if (ui.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (ui.failed) Text(stringResource(R.string.soundcloud_connect_failed), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { viewModel.connectProfile(profile) }, enabled = !ui.busy && profile.isNotBlank()) {
            Text(stringResource(R.string.soundcloud_use_public_likes)) } },
        dismissButton = { TextButton(onClick = viewModel::close) { Text(stringResource(R.string.soundcloud_cancel)) } })
    if (ui.web) SoundCloudLogin(onToken = viewModel::connectToken, onClose = viewModel::closeWeb)
}

/** No Javascript bridge, file access, exported callback or token saved in Compose state. */
@Composable
private fun SoundCloudLogin(onToken: (String) -> Unit, onClose: () -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var delivered by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { browser?.stopLoading(); browser?.destroy(); browser = null } }
    LaunchedEffect(browser) {
        while (!delivered) {
            val cookie = SoundCloudLoginCookies.token(browser?.url, CookieManager.getInstance()::getCookie)
            if (cookie != null) { delivered = true; onToken(cookie) }
            delay(500)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.soundcloud_cancel)) }
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context -> WebView(context).also { view ->
                    browser = view
                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.settings.allowFileAccess = false
                    view.settings.allowContentAccess = false
                    view.settings.userAgentString = view.settings.userAgentString.replace("; wv", "")
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: android.webkit.WebResourceRequest): Boolean =
                            request.isForMainFrame && !isAllowedSoundCloudLoginUrl(request.url.toString())
                    }
                    val cookies = CookieManager.getInstance()
                    fun clearCookie(index: Int) {
                        if (index == SoundCloudLoginCookies.removals.size) {
                            view.post { if (browser === view) view.loadUrl("https://soundcloud.com/signin") }
                            return
                        }
                        val (origin, header) = SoundCloudLoginCookies.removals[index]
                        cookies.setCookie(origin, header) { clearCookie(index + 1) }
                    }
                    clearCookie(0)
                } })
            }
        }
        BackHandler { if (browser?.canGoBack() == true) browser?.goBack() else onClose() }
    }
}
