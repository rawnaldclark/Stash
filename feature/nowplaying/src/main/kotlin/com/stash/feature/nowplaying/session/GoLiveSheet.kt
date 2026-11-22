package com.stash.feature.nowplaying.session

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.feature.nowplaying.R
import java.util.Locale

/**
 * Go live, as any screen starts it: [begin] is the tap (Now Playing's action, the Sessions screen, Your station).
 * [available] while Community is on and the Worker says Sessions is; [busy] while a station is being read or a
 * session is opening.
 */
@Stable
class GoLive internal constructor(val available: Boolean, val busy: Boolean, val begin: () -> Unit)

/** The app's one [GoLive], from [GoLiveHost]; null outside it (a preview, a test). */
val LocalGoLive = compositionLocalOf<GoLive?> { null }

/**
 * Provides [LocalGoLive] to [content] (the screens and the player) and hosts the first-time sheet over it, once for
 * the whole app, so the Sessions screen, Your station and Now Playing share one go-live. [onOpened] runs when this
 * phone is on the air: the app opens the player on the session.
 */
@Composable
fun GoLiveHost(onOpened: () -> Unit, viewModel: GoLiveViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val opened by rememberUpdatedState(onOpened)
    val handle = remember(state.available, state.step != GoLiveViewModel.Step.Idle) {
        GoLive(available = state.available, busy = state.step != GoLiveViewModel.Step.Idle, begin = { viewModel.begin { opened() } })
    }
    CompositionLocalProvider(LocalGoLive provides handle) { content() }

    val context = LocalContext.current
    val note = when (state.note) {
        GoLiveViewModel.Note.END_PRIVATE_FIRST -> stringResource(com.stash.core.media.R.string.session_end_private_first)
        GoLiveViewModel.Note.LEAVE_FIRST -> stringResource(com.stash.core.media.R.string.session_leave_first)
        null -> null
    }
    // Toasts, as the engine's own lines about going live are: they show over the player and the sheet alike.
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.messageShown() }
    }
    LaunchedEffect(note) {
        note?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.noteShown() }
    }
    (state.step as? GoLiveViewModel.Step.Form)?.let { form ->
        GoLiveSheet(form = form, onSubmit = viewModel::submit, onDismiss = viewModel::dismiss)
    }
}

/** The Worker's limits (UTF-16 units, as String.length counts). */
private const val NAME_MAX = 40
private const val VIBE_MAX = 60

/**
 * The first go-live (the Go live mockup; UX §8): what going live shares, in one sentence; the station's name; the
 * name you go by; and "What's playing tonight?", the optional vibe line. Then Go live. Later go-lives are one tap,
 * and these are changed on Your station.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoLiveSheet(form: GoLiveViewModel.Step.Form, onSubmit: (name: String, hostName: String, vibe: String) -> Unit, onDismiss: () -> Unit) {
    // Local, synchronous state: a field fed from a flow drops keystrokes. Keyed on the station, so a sheet that
    // comes back after a refused go-live shows what was just saved.
    var name by rememberSaveable(form.station?.id) { mutableStateOf(form.station?.name.orEmpty()) }
    var hostName by rememberSaveable(form.station?.id) { mutableStateOf(form.hostName) }
    var vibe by rememberSaveable(form.station?.id) { mutableStateOf(form.station?.vibe.orEmpty()) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val chrome = LocalAlbumChrome.current
    val canGo = name.isNotBlank() && !form.sending
    // The app's sheet ground (as the Listen Together sheet), and its bars follow it: no white strip in dark.
    val ground = com.stash.core.ui.theme.StashTheme.extendedColors.elevatedSurface
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ground,
        properties = com.stash.core.ui.components.sheetBarsFollow(ground),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(LiveRed, CircleShape))
                Text(
                    stringResource(R.string.go_live_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.go_live_shares), style = MaterialTheme.typography.bodyMedium, color = muted)

            Spacer(Modifier.height(20.dp))
            FieldLabel(stringResource(R.string.go_live_name_label))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace('\n', ' ').take(NAME_MAX) },
                placeholder = { Text(stringResource(R.string.go_live_name_hint), color = muted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            Spacer(Modifier.height(18.dp))
            FieldLabel(stringResource(R.string.go_live_host_label))
            OutlinedTextField(
                value = hostName,
                onValueChange = { hostName = it.replace('\n', ' ').take(NAME_MAX) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.go_live_vibe_label), Modifier.weight(1f))
                Text(stringResource(R.string.go_live_vibe_count, vibe.length, VIBE_MAX), style = MaterialTheme.typography.labelSmall, color = muted)
            }
            OutlinedTextField(
                value = vibe,
                onValueChange = { vibe = it.replace('\n', ' ').take(VIBE_MAX) },
                placeholder = { Text(stringResource(R.string.go_live_vibe_hint), color = muted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.go_live_vibe_note),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .width(110.dp)
                        .height(52.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), CircleShape)
                        .clickable(role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.go_live_not_now),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(CircleShape)
                        .background(chrome.accent.copy(alpha = if (canGo) 1f else 0.4f))
                        .clickable(enabled = canGo, role = Role.Button) { onSubmit(name, hostName, vibe) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (form.sending) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = chrome.onAccent, strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.go_live_title), style = MaterialTheme.typography.titleMedium, color = chrome.onAccent)
                    }
                }
            }
        }
    }
}

/** A field's small label in capitals, as the app's other row labels. */
@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** The red of everything on the air (the same as Community's cards'). */
internal val LiveRed = Color(0xFFF05252)
