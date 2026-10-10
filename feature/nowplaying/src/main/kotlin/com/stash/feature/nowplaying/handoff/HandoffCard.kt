package com.stash.feature.nowplaying.handoff

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.stash.core.media.handoff.HandoffOffer
import com.stash.core.ui.theme.StashTheme
import kotlinx.coroutines.delay

/**
 * "Continue from Chrome on Windows" (link-sync spec §2.5): a card above the mini player, on the mini player's surface, with
 * the other device's song and where it is. ▶ continues that queue here; ✕ dismisses it. After a continue that left songs
 * out, the same place shows the one-line note once.
 */
@Composable
fun HandoffCard(modifier: Modifier = Modifier, viewModel: HandoffViewModel = hiltViewModel()) {
    val offer by viewModel.offer.collectAsStateWithLifecycle()
    val note by viewModel.note.collectAsStateWithLifecycle()

    Column(modifier) {
        AnimatedVisibility(visible = offer != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            // Keeps the last offer while the card animates out.
            val shown = remember { HoldLast<HandoffOffer>() }.update(offer) ?: return@AnimatedVisibility
            OfferCard(shown, onContinue = viewModel::accept, onDismiss = viewModel::dismiss)
        }
        AnimatedVisibility(visible = note != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val text = remember { HoldLast<String>() }.update(note) ?: return@AnimatedVisibility
            // A screen reader user gets the note read out (live region) and the time the system recommends to act on it, which
            // with TalkBack on is long enough to find the close button.
            val a11y = LocalAccessibilityManager.current
            LaunchedEffect(text) {
                delay(a11y?.calculateRecommendedTimeoutMillis(NOTE_MS, containsIcons = true, containsText = true, containsControls = true) ?: NOTE_MS)
                viewModel.noteShown()
            }
            CardSurface {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = viewModel::noteShown) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = StashTheme.extendedColors.textTertiary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private class HoldLast<T : Any> {
    private var last: T? = null
    fun update(v: T?): T? {
        if (v != null) last = v
        return last
    }
}

@Composable
private fun CardSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        // The mini player's surface, with the design system's glass hairline.
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, StashTheme.extendedColors.glassBorder),
        content = content,
    )
}

@Composable
private fun OfferCard(offer: HandoffOffer, onContinue: () -> Unit, onDismiss: () -> Unit) {
    // While the other device still plays, the position moves on screen (UI only: no timer outside the card).
    var elapsed by remember(offer) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (offer.stillPlaying) {
        // Only while the app is on screen: in the background the card stays composed, and a plain loop would wake the main
        // thread every second (the phone has no timers for handoff, spec §8.1).
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(offer, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    elapsed = SystemClock.elapsedRealtime()
                    delay(1_000)
                }
            }
        }
    }
    val position = offer.positionAt(elapsed)
    val extended = StashTheme.extendedColors
    CardSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onContinue)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
                .semantics { contentDescription = "Continue ${offer.song.title} from ${offer.deviceName}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The web lists covers smallest first: a middle one is sharp at 44 dp without the biggest download.
            val art = offer.song.artwork.getOrNull(offer.song.artwork.size / 2)
            if (art != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(art).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        append(if (offer.stillPlaying) "Playing now on " else "Continue from ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append(offer.deviceName) }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = extended.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The position never ellipsizes: a long title or artist gives way first.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(offer.song.title) }
                            append(" · ")
                            append(offer.song.artist)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        " · " + clock(position),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
            FilledIconButton(
                onClick = onContinue,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Continue here", tint = MaterialTheme.colorScheme.onPrimary)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss Continue from ${offer.deviceName}", tint = extended.textTertiary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** 2:31, or 1:02:31 past an hour. */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private const val NOTE_MS = 8_000L
