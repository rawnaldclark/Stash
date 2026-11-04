package com.stash.feature.nowplaying.cast

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.cast.CastConnection
import com.stash.core.media.cast.CastDevice
import com.stash.core.media.cast.BrowserOutput
import com.stash.core.media.cast.BrowserSession
import com.stash.core.media.cast.WebCastRemote
import com.stash.feature.nowplaying.R
import com.stash.core.ui.components.SheetOptionRow

/**
 * The "Play on" icon for Now Playing's top bar (Remote v2 §3). Draws nothing when there is nowhere else to play: no Cast on
 * the phone (no Play Services) and no linked browser. Accented while the music plays elsewhere; a spinner on its way there.
 */
@Composable
internal fun CastButton(
    state: PlayOnState,
    tint: Color,
    accentColor: Color,
    onClick: () -> Unit,
) {
    if (!state.available) return
    val playingOn = state.playingOn
    IconButton(onClick = onClick) {
        if (state.startingOn != null) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = accentColor)
        } else {
            Icon(
                imageVector = if (playingOn != null) Icons.Default.CastConnected else Icons.Default.Cast,
                contentDescription = playingOn?.let { stringResource(R.string.play_on_button_playing, it) }
                    ?: stringResource(R.string.play_on_button),
                tint = if (playingOn != null) accentColor else tint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * "Play on" (Remote v2 §3): this phone, the linked browsers, and the Cast speakers (scanned for only while it is open, and
 * only on a phone that can Cast). The browser or speaker playing shows its controls in its section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CastSheet(
    viewModel: CastViewModel,
    onDismiss: () -> Unit,
) {
    val playOn by viewModel.playOn.collectAsStateWithLifecycle()
    val speakers by viewModel.speakers.collectAsStateWithLifecycle()
    val browsers by viewModel.browserList.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val browserVolume by viewModel.browserVolume.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }
    val extendedColors = com.stash.core.ui.theme.StashTheme.extendedColors
    val context = androidx.compose.ui.platform.LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = extendedColors.elevatedSurface,
        // Its bars follow the sheet, not Material's grey content colour: no white strip in dark.
        properties = com.stash.core.ui.components.sheetBarsFollow(extendedColors.elevatedSurface),
    ) {
        // Scrolls, so a long speaker list (30+ Cast devices on one network) can be reached (#367).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
        ) {
            Text(
                text = stringResource(R.string.play_on_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .align(Alignment.CenterHorizontally),
            )

            OutputRow(
                icon = Icons.Default.PhoneAndroid,
                name = stringResource(R.string.play_on_this_phone),
                status = null,
                current = !playOn.elsewhere,
                onClick = {
                    viewModel.playHere()
                    onDismiss()
                },
            )

            val session = playOn.browser
            if (browsers.isNotEmpty() || session != null) {
                SectionLabel(stringResource(R.string.play_on_browsers))
                browsers.forEach { browser ->
                    // The one this phone's queue plays on: how its session stands, instead of what picking it would do.
                    val playing = session?.takeIf { it.device == browser.device }
                    val driven = playing != null
                    val link = playing?.link
                    OutputRow(
                        icon = if (link == WebCastRemote.Link.NEEDS_CLICK || !driven && browser.kind == BrowserOutput.Kind.NEEDS_CLICK) {
                            Icons.Default.TouchApp
                        } else {
                            Icons.Default.Computer
                        },
                        name = browser.name,
                        status = if (playing != null) sessionLine(playing) else browserLine(browser),
                        current = driven,
                        busy = link == WebCastRemote.Link.STARTING || link == WebCastRemote.Link.RECONNECTING,
                        enabled = driven || browser.canPick,
                        onClick = {
                            if (!driven && !viewModel.connectBrowser(browser)) {
                                android.widget.Toast.makeText(context, R.string.play_on_browser_unavailable, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
                if (session != null) {
                    browserVolume?.let { level ->
                        VolumeSlider(
                            volume = level,
                            description = stringResource(R.string.play_on_browser_volume),
                            onVolume = viewModel::setBrowserVolume,
                        )
                    }
                    Text(
                        text = stringResource(R.string.play_on_browser_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }

            if (playOn.castSupported) {
                SectionLabel(stringResource(R.string.play_on_speakers))
                when (val state = playOn.connection) {
                    is CastConnection.Connected -> ConnectedControls(
                        deviceName = state.deviceName,
                        volume = volume,
                        onVolume = viewModel::setVolume,
                        onStop = {
                            viewModel.disconnect()
                            onDismiss()
                        },
                    )
                    is CastConnection.Connecting -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(16.dp))
                            Text("Connecting to ${state.deviceName}…", style = MaterialTheme.typography.bodyLarge)
                        }
                        SheetOptionRow(icon = Icons.Default.Close, label = "Cancel", onClick = viewModel::disconnect)
                    }
                    else -> SpeakerList(
                        speakers = speakers,
                        onPick = viewModel::connect,
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = "While casting, the speaker plays the music itself, so the equalizer, crossfade, " +
                        "loudness levelling and playback speed don't apply.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A browser's line in the sheet: what picking it would do. */
@Composable
private fun browserLine(browser: BrowserOutput): String = when (browser.kind) {
    BrowserOutput.Kind.READY -> stringResource(R.string.play_on_browser_ready)
    BrowserOutput.Kind.PLAYING -> stringResource(R.string.play_on_browser_playing, browser.title.orEmpty())
    BrowserOutput.Kind.NEEDS_CLICK -> stringResource(R.string.play_on_browser_needs_click)
    BrowserOutput.Kind.RELOAD -> stringResource(R.string.play_on_browser_reload)
    BrowserOutput.Kind.NOT_OPEN -> stringResource(R.string.play_on_browser_not_open)
}

/** The playing browser's line: how the session stands. Null once it plays (the tick says it). */
@Composable
internal fun sessionLine(session: BrowserSession): String? = when (session.link) {
    WebCastRemote.Link.READY -> null
    WebCastRemote.Link.STARTING -> stringResource(R.string.play_on_starting_on, session.name)
    WebCastRemote.Link.NEEDS_CLICK -> stringResource(R.string.play_on_click_tab, session.app)
    WebCastRemote.Link.RECONNECTING -> stringResource(R.string.play_on_reconnecting, session.app)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * One place the music can play: an icon, its name, an optional line, and a tick on the one it plays on. Greyed and not tappable
 * when it can't be picked ([enabled] false: a browser with no tab open, or a tab from before Remote v2).
 */
@Composable
private fun OutputRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    status: String?,
    current: Boolean,
    onClick: () -> Unit,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled && !current, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = if (status == null) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (current) accent else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
                current -> Icon(Icons.Default.Check, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A volume slider: local while dragging, sent once on release so a drag isn't dozens of round trips. */
@Composable
private fun VolumeSlider(volume: Float, description: String, onVolume: (Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
        Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, modifier = Modifier.size(20.dp))
        Slider(
            value = dragging ?: volume,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let(onVolume)
                dragging = null
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = description },
        )
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SpeakerList(speakers: List<CastDevice>, onPick: (CastDevice) -> Unit) {
    if (speakers.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(16.dp))
            Text("Looking for speakers…", style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = "Your phone and the speaker need to be on the same Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        speakers.forEach { speaker ->
            SheetOptionRow(icon = Icons.Default.Speaker, label = speaker.name, onClick = { onPick(speaker) })
        }
    }
}

@Composable
private fun ConnectedControls(
    deviceName: String,
    volume: Float?,
    onVolume: (Float) -> Unit,
    onStop: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Icon(
            Icons.Default.CastConnected,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            "Playing on $deviceName",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (volume != null) VolumeSlider(volume = volume, description = "Speaker volume", onVolume = onVolume)
    Spacer(Modifier.height(8.dp))
    SheetOptionRow(icon = Icons.Default.Close, label = "Stop casting", onClick = onStop)
}

/**
 * Where the music plays, in one short line (Remote v2 §6): "Playing on Chrome on Windows" under Now Playing's seek bar, or how a
 * browser session stands ("Starting on…", "Reconnecting to Chrome…", "Click the Stash tab once in Chrome"). Null on this phone.
 */
@Composable
internal fun playOnLine(state: PlayOnState): String? {
    state.browser?.let { return sessionLine(it) ?: stringResource(R.string.play_on_playing_on, it.name) }
    state.playingOn?.let { return stringResource(R.string.play_on_playing_on, it) }
    return state.startingOn?.let { stringResource(R.string.play_on_connecting_to, it) }
}

/** [playOnLine] between the seek bar's two times, in the accent; a tap opens Play on. Kept clear of the times. */
@Composable
internal fun PlayingOnLine(state: PlayOnState, accent: Color, onClick: () -> Unit) {
    val line = playOnLine(state) ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 52.dp)
            .clickable(onClick = onClick),
    ) {
        Icon(Icons.Default.CastConnected, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = line,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
