package com.stash.feature.library.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.util.formatDuration

/**
 * A song someone shared (a Stash song link), with a Library-style header: the cover full width fading into
 * the page, the title bold, the artist and album, then Play and Add to library side by side.
 */
@Composable
fun SharedTrackScreen(onBack: () -> Unit, viewModel: SharedTrackViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liked by viewModel.liked.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val t = when (val s = state) {
        SharedTrackUiState.Loading -> {
            BareScreen(onBack) { CircularProgressIndicator() }
            return
        }
        is SharedTrackUiState.Error -> {
            BareScreen(onBack) {
                Text(s.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (s.retryable) { Spacer(Modifier.height(16.dp)); Button(onClick = viewModel::load) { Text("Retry") } }
            }
            return
        }
        is SharedTrackUiState.Loaded -> s.track
    }
    val cover = remember(t) { listOfNotNull(t.coverUrl()) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottomChromePadding(24.dp)),
    ) {
        SharedHeroCover(cover, onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = t.addedBy?.let { "Shared by $it" } ?: "Shared song",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                t.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                t.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(t.album?.takeIf { it.isNotBlank() }, t.durationMs?.takeIf { it > 0 }?.let(::formatDuration))
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { viewModel.play() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                OutlinedButton(
                    onClick = { viewModel.like() },
                    enabled = !liked,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(if (liked) Icons.Default.Check else Icons.Default.FavoriteBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (liked) "In your library" else "Add to library", maxLines = 1)
                }
            }
            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Loading and error: Back, and [content] centred. */
@Composable
internal fun BareScreen(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    }
}
