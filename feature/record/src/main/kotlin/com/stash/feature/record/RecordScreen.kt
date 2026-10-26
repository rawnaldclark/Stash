package com.stash.feature.record

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.theme.StashBackground
import com.stash.core.ui.theme.StashTextPrimary
import com.stash.core.ui.theme.StashTextSecondary
import com.stash.feature.record.bridge.RecordEvent
import kotlinx.coroutines.launch

/** The record's ground: its own background (the fork's theme.css `--bg`, Stash's dark background), behind the system bars too. */
private val RecordGround = StashBackground

/** [RecordGround] for the WebView, which paints before the page does. */
internal val RECORD_GROUND_ARGB = 0xFF06060C.toInt()

/**
 * Your record, full screen (the scaffold hides the mini player and the tab bar on this route): the bundled record in a
 * WebView, fed by [RecordViewModel]'s host. Back asks the record to close what it has open first; its × and a back with
 * nothing open close the screen ([onClose]). The record is dark in both themes, like an album sleeve on a light shelf, so the
 * status bar's icons are light here.
 *
 * A phone whose Android System WebView is too old for the bridge says so instead. A renderer that dies (memory, a crash)
 * leaves a way to open the record again.
 */
@Composable
fun RecordScreen(
    onClose: () -> Unit,
    viewModel: RecordViewModel = hiltViewModel(),
) {
    val close by rememberUpdatedState(onClose)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                RecordEvent.Close -> close()
                // Not awaited: a close right behind a line still closes
                is RecordEvent.Say -> launch { snackbar.showSnackbar(e.text) }
            }
        }
    }
    val dark = LocalIsDarkTheme.current
    LaunchedEffect(dark) { viewModel.setTheme(light = !dark) }
    val supported = remember { bridgeSupported() }
    BackHandler { if (supported) viewModel.back() else close() }
    LightStatusBarIcons()

    Box(Modifier.fillMaxSize().background(RecordGround)) {
        if (!supported) {
            Notice(
                title = "This needs a newer Android System WebView",
                body = "Update Android System WebView from Google Play, then open Your record again.",
                action = null,
                onClose = close,
            )
        } else {
            // A new WebView each time the renderer dies and the record is opened again
            var generation by remember { mutableIntStateOf(0) }
            var gone by remember { mutableStateOf(false) }
            if (gone) {
                Notice(
                    title = "Your record stopped",
                    body = "The page ran out of room or crashed. Your plays are safe.",
                    action = "Open it again" to {
                        gone = false
                        generation++
                    },
                    onClose = close,
                )
            } else {
                key(generation) {
                    DisposableEffect(Unit) { onDispose { viewModel.detach() } }
                    AndroidView(
                        factory = { context ->
                            RecordWebView(
                                context,
                                onMessage = viewModel::onMessage,
                                onResponsive = viewModel::setResponsive,
                                onGone = {
                                    viewModel.detach()
                                    gone = true
                                },
                            )
                        },
                        onRelease = { it.release() },
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/** A plain page in the record's place: what happened, what to do, and the way back. */
@Composable
private fun Notice(title: String, body: String, action: Pair<String, () -> Unit>?, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = StashTextPrimary) }
        }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = StashTextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = StashTextSecondary)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = action.second) { Text(action.first) }
        }
        Spacer(Modifier.weight(1.4f))
    }
}

/** Light status bar icons while the record shows (its ground is dark in both themes); the theme's own come back after. */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        val beforeNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (before != null) controller.isAppearanceLightStatusBars = before
            if (beforeNav != null) controller.isAppearanceLightNavigationBars = beforeNav
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
