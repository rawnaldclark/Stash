package com.stash.feature.record

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import android.view.View
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.theme.StashBackground
import com.stash.core.ui.theme.StashTextPrimary
import com.stash.core.ui.theme.StashTextSecondary
import com.stash.feature.record.bridge.RecordEvent
import com.stash.feature.record.bridge.RecordNote
import com.stash.feature.record.bridge.text
import com.stash.feature.record.mixes.RecordMixesTab
import com.stash.feature.record.mixes.RecordMixesTabViewModel
import kotlinx.coroutines.launch

/** The record's ground: its own background (the fork's theme.css `--bg`, Stash's dark background), behind the system bars too. */
private val RecordGround = StashBackground

/** [RecordGround] for the WebView, which paints before the page does. */
internal val RECORD_GROUND_ARGB = 0xFF06060C.toInt()

/**
 * Your record, full screen (the scaffold hides the mini player and the tab bar on this route): the bundled record in a
 * WebView, fed by [RecordViewModel]'s host. The WebView is the ViewModel's, so a configuration change shows the same page
 * again instead of pressing the record anew; it pauses and resumes with this screen.
 *
 * Back asks the record to close what it has open first; its ×, a back with nothing open (or a page that doesn't answer back)
 * close the screen ([onClose]). The record covers the mini player, so what a tap starts, and what a save did, show as a line
 * over it. The record is dark in both themes, like an album sleeve on a light shelf, so the system bars' icons are light here.
 *
 * A phone whose Android System WebView is too old for the bridge says so instead. A renderer that dies (memory, a crash)
 * leaves a way to open the record again.
 *
 * Two tabs over it, "The record | Mixes" (stash-player `src/routes/spotify/+page.svelte`): the source switches show on the
 * record's tab only, and the record stays pressed (its WebView kept, only hidden) while Mixes shows ([RecordMixesTab]: the
 * history mixes, each opening its page with [onOpenMix]). Back on Mixes closes the screen.
 */
@Composable
fun RecordScreen(
    onClose: () -> Unit,
    onOpenMix: (String) -> Unit = {},
    viewModel: RecordViewModel = hiltViewModel(),
    mixesViewModel: RecordMixesTabViewModel = hiltViewModel(),
) {
    val close by rememberUpdatedState(onClose)
    val snackbar = remember { SnackbarHostState() }
    val screenScope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val say: suspend (RecordNote) -> Unit = { note ->
        // Not awaited by what calls it: a close right behind a line still closes
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(note.text(resources), duration = SnackbarDuration.Short)
    }
    LaunchedEffect(viewModel) {
        launch {
            viewModel.events.collect { e ->
                when (e) {
                    RecordEvent.Close -> close()
                    is RecordEvent.Say -> launch { say(e.note) }
                    RecordEvent.Import -> Unit // the view model opens the import sheet
                }
            }
        }
        viewModel.playNotes.collect { launch { say(it) } }
    }
    val dark = LocalIsDarkTheme.current
    LaunchedEffect(dark) { viewModel.setTheme(light = !dark) }
    val supported = remember { bridgeSupported() }
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    BackHandler { if (supported && tab == RecordTab.RECORD) viewModel.back() else close() }
    LightSystemBarIcons()
    PauseWithScreen(viewModel)
    // A share sheet opens only while Stash is in front (Android refuses one from the background): a video that finished
    // while the listener was elsewhere waits here and opens when they come back
    val share by viewModel.shareReady.collectAsStateWithLifecycle()
    val activity = LocalContext.current
    LaunchedEffect(share) {
        val ready = share ?: return@LaunchedEffect
        try {
            activity.startActivity(ready.chooser)
        } catch (e: ActivityNotFoundException) {
            say(RecordNote.SaveFailed)
        }
        viewModel.shareShown(ready)
    }

    Box(Modifier.fillMaxSize().background(RecordGround)) {
        val gone by viewModel.gone.collectAsStateWithLifecycle()
        val switches by viewModel.switches.collectAsStateWithLifecycle()
        // The bar's last switches stay drawn while it folds away
        val shown = remember { mutableStateOf(switches) }
        switches?.let { shown.value = it }
        val onRecord = tab == RecordTab.RECORD
        Column(Modifier.fillMaxSize()) {
            // The status bar is the tabs' now, not the page's
            RecordTabsBar(
                tab = tab,
                onPick = viewModel::pick,
                onClose = close,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The record's tab, laid out the same whichever tab shows: the Mixes tab lies over it, so the hidden WebView is
                // never resized (its page would reflow) and the record stays pressed underneath
                Column(Modifier.fillMaxSize().then(if (onRecord) Modifier else Modifier.clearAndSetSemantics { })) {
                    AnimatedVisibility(
                        visible = switches != null && supported && !gone,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        shown.value?.let { s ->
                            RecordSourceBar(
                                switches = s,
                                onFlip = { src -> if (s.isLast(src)) screenScope.launch { say(RecordNote.OneSourceStays) } else viewModel.flip(src) },
                                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                            )
                        }
                    }
                    val body = Modifier.weight(1f).fillMaxWidth()
                    when {
                        !supported -> Notice(
                            title = stringResource(R.string.record_webview_old_title),
                            body = stringResource(R.string.record_webview_old_body),
                            action = null,
                            onClose = close,
                            modifier = body,
                        )
                        gone -> Notice(
                            title = stringResource(R.string.record_stopped_title),
                            body = stringResource(R.string.record_stopped_body),
                            action = stringResource(R.string.record_open_again) to viewModel::reopen,
                            onClose = close,
                            modifier = body,
                        )
                        else -> AndroidView(
                            factory = { context -> viewModel.webView(context) },
                            onRelease = { viewModel.letGo(it) },
                            // Hidden (never gone) while Mixes shows: no touches, nothing read out
                            update = { it.visibility = if (onRecord) View.VISIBLE else View.INVISIBLE },
                            modifier = body.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                        )
                    }
                }
                if (!onRecord) {
                    // Over the record's tab, taking every touch (the source switches lie under its top)
                    RecordMixesTab(
                        mixesViewModel,
                        onOpenMix = onOpenMix,
                        modifier = Modifier.fillMaxSize().background(RecordGround).pointerInput(Unit) { detectTapGestures { } },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/** The WebView pauses with this screen (its own timers and media: never the process-wide ones, other WebViews use those). */
@Composable
private fun PauseWithScreen(viewModel: RecordViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.resume()
                Lifecycle.Event.ON_PAUSE -> viewModel.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** A plain page in the record's place: what happened, what to do, and the way back. */
@Composable
private fun Notice(title: String, body: String, action: Pair<String, () -> Unit>?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // Under the tabs: the status bar is theirs
    Column(modifier.navigationBarsPadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.record_close), tint = StashTextPrimary)
            }
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

/** Light system bar icons while the record shows (its ground is dark in both themes); the theme's own come back after. */
@Composable
private fun LightSystemBarIcons() {
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
