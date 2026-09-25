package com.stash.feature.nowplaying.listen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun JoinSessionScreen(onBack: () -> Unit, onJoined: () -> Unit, viewModel: JoinSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(horizontal = 24.dp),
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Spacer(Modifier.height(24.dp))
        Text("Listen together", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when (val s = state) {
            JoinSessionViewModel.UiState.Loading -> CircularProgressIndicator()
            is JoinSessionViewModel.UiState.Ready -> {
                Text("Join ${s.hostName ?: "a friend"}'s session", style = MaterialTheme.typography.titleMedium)
                Text("${s.memberCount} listening", style = MaterialTheme.typography.bodyMedium, color = muted)
                s.track?.let { Text("Now playing: ${it.title} · ${it.artist}", style = MaterialTheme.typography.bodyMedium, color = muted) }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = viewModel.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text("Show my name as") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.join(onJoined) }, modifier = Modifier.fillMaxWidth()) { Text("Join") }
                Text(
                    "Your own queue is set aside and comes back when you leave.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            JoinSessionViewModel.UiState.Full -> Text("This session is full", style = MaterialTheme.typography.titleMedium)
            JoinSessionViewModel.UiState.Ended -> Text("This session has ended", style = MaterialTheme.typography.titleMedium)
            JoinSessionViewModel.UiState.Failed -> {
                Text("Couldn't reach the session", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = viewModel::retry) { Text("Try again") }
            }
        }
    }
}
