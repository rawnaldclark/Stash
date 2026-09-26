package com.stash.feature.nowplaying.listen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.ui.theme.StashTheme

/** Where an invite link lands: who's hosting, what's playing, your name, and one big Join (spec §5–§6). */
@Composable
fun JoinSessionScreen(onBack: () -> Unit, onJoined: () -> Unit, viewModel: JoinSessionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        AnimatedContent(targetState = state, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "join") { s ->
            when (s) {
                JoinSessionViewModel.UiState.Loading -> Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is JoinSessionViewModel.UiState.Ready -> Ready(s, viewModel, current, onJoined)
                JoinSessionViewModel.UiState.Full -> Problem(
                    Icons.Default.Groups, "This session is full", "Up to 10 people can listen together. Try again when someone leaves.",
                    action = "Close", onAction = onBack,
                )
                JoinSessionViewModel.UiState.Ended -> Problem(
                    Icons.Default.EventBusy, "This session has ended", "Ask your friend for a new invite.",
                    action = "Close", onAction = onBack,
                )
                JoinSessionViewModel.UiState.RateLimited -> Problem(
                    Icons.Default.HourglassTop, "Too many tries", "Wait a minute, then try again.",
                    action = "Try again", onAction = viewModel::retry,
                )
                JoinSessionViewModel.UiState.Failed -> Problem(
                    Icons.Default.CloudOff, "Couldn't reach the session", "Check your connection and try again.",
                    action = "Try again", onAction = viewModel::retry,
                )
            }
        }
    }
}

@Composable
private fun Ready(
    s: JoinSessionViewModel.UiState.Ready,
    viewModel: JoinSessionViewModel,
    current: ListenTogetherState,
    onJoined: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val host = s.hostName ?: "a friend"
    // Not while joining: the room lets us in a moment before Now Playing opens, and the form shouldn't jump.
    val here = viewModel.alreadyHere && !viewModel.joining
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // The host's face; their room colour comes from their member id, which the preview doesn't carry.
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).border(3.dp, HOST_RING, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(s.hostName), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 32.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (here) "You're in $host's session" else "Join $host's session",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(StashTheme.extendedColors.success, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(if (s.memberCount == 1) "1 listening now" else "${s.memberCount} listening now", style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        s.track?.let { t ->
            Spacer(Modifier.height(24.dp))
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(StashTheme.extendedColors.glassBackground)
                    .border(1.dp, StashTheme.extendedColors.glassBorder, shape)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(personColor(t.title + t.artist).copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF0D0D18).copy(alpha = 0.6f)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = muted, fontWeight = FontWeight.Bold)
                    Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (!here) {
            OutlinedTextField(
                value = viewModel.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Show my name as") },
                singleLine = true,
                enabled = !viewModel.joining,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.join(onJoined) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = { viewModel.join(onJoined) },
            enabled = !viewModel.joining,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (viewModel.joining) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("Joining…")
            } else {
                Text(if (here) "Open the session" else "Join")
            }
        }
        val note = when {
            here -> null
            current is ListenTogetherState.InRoom && (current as ListenTogetherState.InRoom).isHost ->
                "You're hosting another session. Joining this one hands yours to someone else."
            current !is ListenTogetherState.Idle -> "You'll leave your current session."
            else -> "Your own queue is set aside and comes back when you leave."
        }
        if (note != null) {
            Spacer(Modifier.height(12.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.Center)
        }
    }
}

/** A room that can't be joined: what happened, in plain words, and one way on. */
@Composable
private fun Problem(icon: ImageVector, title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 64.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(StashTheme.extendedColors.glassBackground),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp)) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}
