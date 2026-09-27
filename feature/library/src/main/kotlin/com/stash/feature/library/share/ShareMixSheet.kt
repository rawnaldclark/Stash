package com.stash.feature.library.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.common.extensions.pluralize
import com.stash.core.model.community.PostTarget
import com.stash.core.ui.components.LocalPostToCommunity

/** Spec §5 share sheet. Keyed per playlist so each playlist gets its own ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareMixSheet(
    playlistId: Long,
    playlistName: String,
    trackCount: Int,
    onDismiss: () -> Unit,
    viewModel: ShareMixViewModel = hiltViewModel(key = "share-$playlistId"),
) {
    LaunchedEffect(playlistId) { viewModel.bind(playlistId, playlistName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Stash Community (spec 2026-09-26 §3): offered in both states while Community is on.
    val postToCommunity = LocalPostToCommunity.current
    // Spec §5: "Create link" goes straight on to the Android share sheet once the link exists.
    var justCreated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        val s = state
        if (justCreated && s is ShareMixUiState.Shared) { justCreated = false; sendLink(context, s.name, trackCount, s.url) }
        if (s is ShareMixUiState.NotShared && s.error != null) justCreated = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Share mix", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                ShareMixUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                is ShareMixUiState.NotShared -> {
                    var name by rememberSaveable { mutableStateOf(s.defaultName) }
                    var display by rememberSaveable { mutableStateOf(s.displayName.orEmpty()) }
                    var auto by rememberSaveable { mutableStateOf(true) }
                    OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(display, { display = it.take(40) }, label = { Text("Show my name as (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep it updated for followers", Modifier.weight(1f))
                        Switch(checked = auto, onCheckedChange = { auto = it })
                    }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { justCreated = true; viewModel.create(name, display.ifBlank { null }, auto) }, enabled = !s.working, modifier = Modifier.fillMaxWidth()) {
                        Text(if (s.working) "Creating link…" else "Create link")
                    }
                    postToCommunity?.let { post ->
                        OutlinedButton(onClick = { onDismiss(); post(PostTarget.Playlist(playlistId)) }, enabled = !s.working, modifier = Modifier.fillMaxWidth()) { Text("Post to Community") }
                    }
                }
                is ShareMixUiState.Shared -> {
                    Text(s.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { sendLink(context, s.name, trackCount, s.url) }) { Text("Share again") }
                        OutlinedButton(onClick = { copy(context, s.url) }) { Text("Copy link") }
                    }
                    postToCommunity?.let { post ->
                        OutlinedButton(onClick = { onDismiss(); post(PostTarget.Playlist(playlistId)) }) { Text("Post to Community") }
                    }
                    if (s.canManage) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep it updated for followers", Modifier.weight(1f))
                            Switch(checked = s.autoUpdate, onCheckedChange = viewModel::setAutoUpdate)
                        }
                        var confirm by remember { mutableStateOf(false) }
                        TextButton(onClick = { if (confirm) viewModel.stopSharing(onDismiss) else confirm = true }) {
                            Text(if (confirm) "Tap again to stop sharing" else "Stop sharing", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun sendLink(context: Context, name: String, count: Int, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$name: ${pluralize(count, "track")} on Stash · $url") }
    context.startActivity(Intent.createChooser(intent, "Share \"$name\""))
}

private fun copy(context: Context, url: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Stash mix", url))
}
