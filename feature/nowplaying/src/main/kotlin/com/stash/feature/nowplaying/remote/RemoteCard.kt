package com.stash.feature.nowplaying.remote

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.stash.core.data.weblink.remote.RemoteCard
import com.stash.core.ui.theme.StashTheme
import kotlinx.coroutines.delay

/**
 * "Playing on Chrome on Windows" (spec 2026-10-10 §6.7): a mini-player-like card above the mini player while a linked browser
 * advertises itself (its tab open, "Let my phone control this browser" on). Cover, title, where it plays, ▶/⏸ and ⏭; a tap opens
 * the remote sheet with the seek bar, the volume and ⏮ ⏯ ⏭. A tab nobody clicked yet says "Click the Stash tab once in Chrome".
 *
 * @return whether a card is showing, or still leaving (the scaffold leaves out the handoff card until it is gone: one card per
 *   browser, and never two drawn over each other during the exit animation).
 */
@Composable
fun RemoteCardHost(modifier: Modifier = Modifier, viewModel: RemoteViewModel = hiltViewModel()): Boolean {
    val card by viewModel.card.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(false) }
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = card != null
    Column(modifier) {
        AnimatedVisibility(visibleState = visible, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val shown = remember { HoldLast<RemoteCard>() }.update(card) ?: return@AnimatedVisibility
            MiniRemote(shown, onOpen = { sheet = true }, onPlayPause = { viewModel.playPause(shown) }, onNext = viewModel::next)
        }
    }
    val c = card
    if (sheet && c != null) RemoteSheet(c, viewModel, onDismiss = { sheet = false })
    LaunchedEffect(c == null) { if (c == null) sheet = false } // the browser went away: so does its sheet
    return visible.currentState || visible.targetState
}

private class HoldLast<T : Any> {
    private var last: T? = null
    fun update(v: T?): T? {
        if (v != null) last = v
        return last
    }
}

/** The card's position moves on while the browser plays: a 1 s tick while the app is on screen, nothing in the background. */
@Composable
private fun rememberPosition(card: RemoteCard): Long {
    var elapsed by remember(card) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (card.playing) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(card, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    elapsed = SystemClock.elapsedRealtime()
                    delay(1_000)
                }
            }
        }
    }
    return card.positionAt(elapsed)
}

@Composable
private fun Cover(url: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    if (url != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(if (size > 100.dp) 16.dp else 8.dp)),
        )
    } else {
        Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.size(size))
    }
}

@Composable
private fun MiniRemote(card: RemoteCard, onOpen: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    val extended = StashTheme.extendedColors
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        // The mini player's surface, with the design system's glass hairline (as the handoff card).
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, extended.glassBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
                .semantics { contentDescription = "${card.title} by ${card.artist}. ${card.status}. Open the remote" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(card.art, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (card.needsGesture) Icons.Default.TouchApp else Icons.Default.Computer,
                        contentDescription = null,
                        tint = if (card.needsGesture) MaterialTheme.colorScheme.primary else extended.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        card.status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (card.needsGesture) MaterialTheme.colorScheme.primary else extended.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FilledIconButton(
                onClick = onPlayPause,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    if (card.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (card.playing) "Pause on ${card.deviceName}" else "Play on ${card.deviceName}",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next song on ${card.deviceName}", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteSheet(card: RemoteCard, viewModel: RemoteViewModel, onDismiss: () -> Unit) {
    val extended = StashTheme.extendedColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = extended.elevatedSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (card.playing) "Playing on ${card.deviceName}" else "Paused on ${card.deviceName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Cover(card.art, 220.dp)
            Spacer(Modifier.height(16.dp))
            Text(card.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(card.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card.needsGesture) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${card.status}: browsers only start music in a tab you've touched.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            SeekBar(card, onSeek = viewModel::seek)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::prev, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous song", modifier = Modifier.size(32.dp))
                }
                FilledIconButton(
                    onClick = { viewModel.playPause(card) },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        if (card.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (card.playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = viewModel::next, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next song", modifier = Modifier.size(32.dp))
                }
            }
            card.volume?.let { VolumeBar(it, card.deviceName, onVolume = viewModel::volume) }
        }
    }
}

/** The browser's position, moving on while it plays; the seek goes out once, on release. */
@Composable
private fun SeekBar(card: RemoteCard, onSeek: (Long) -> Unit) {
    val position = rememberPosition(card)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = card.durationMs.coerceAtLeast(1)
    Slider(
        value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onSeek((it * duration).toLong()) }
            dragging = null
        },
        enabled = card.canSeek,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Position in ${card.title}" },
    )
    Row(Modifier.fillMaxWidth()) {
        val shown = dragging?.let { (it * duration).toLong() } ?: position
        Text(clock(shown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (card.durationMs > 0) Text(clock(card.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** As the Cast sheet's: local while dragging, a few updates a second so the browser follows the finger, the last on release. */
@Composable
private fun VolumeBar(volume: Float, deviceName: String, onVolume: (Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var lastSent by remember { mutableLongStateOf(0L) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, modifier = Modifier.size(20.dp))
        Slider(
            value = dragging ?: volume,
            onValueChange = {
                dragging = it
                val t = SystemClock.elapsedRealtime()
                if (t - lastSent >= VOLUME_EVERY_MS) {
                    lastSent = t
                    onVolume(it)
                }
            },
            onValueChangeFinished = {
                dragging?.let(onVolume)
                dragging = null
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = "Volume on $deviceName" },
        )
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** 2:31, or 1:02:31 past an hour. */
private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** The server takes 10 frames a second per socket; 4 volume steps a second leave room for everything else. */
private const val VOLUME_EVERY_MS = 250L
