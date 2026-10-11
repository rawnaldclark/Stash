package com.stash.feature.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.data.prefs.NowPlayingAction
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.ui.components.SheetOptionRow

// ---------------------------------------------------------------------------
// How each action looks
// ---------------------------------------------------------------------------

/** The live facts an action's icon and label reflect. */
internal data class ActionVisualState(
    val radioActive: Boolean = false,
    val radioTuning: Boolean = false,
    val lyricsOpen: Boolean = false,
    val together: ListenTogetherState = ListenTogetherState.Idle,
    val speed: Float = 1f,
    val isDownloaded: Boolean = false,
)

/** An action's icon and label right now; [active] tints it with the album accent. */
internal data class ActionVisual(val icon: ImageVector, val label: String, val active: Boolean = false)

internal fun actionVisual(action: NowPlayingAction, s: ActionVisualState): ActionVisual = when (action) {
    NowPlayingAction.RADIO -> ActionVisual(
        Icons.Default.Radio,
        when {
            s.radioTuning -> "Tuning radio"
            s.radioActive -> "Stop radio"
            else -> "Start radio"
        },
        active = s.radioActive || s.radioTuning,
    )
    NowPlayingAction.LYRICS -> ActionVisual(Icons.Default.Lyrics, if (s.lyricsOpen) "Hide lyrics" else "Lyrics", active = s.lyricsOpen)
    NowPlayingAction.QUEUE -> ActionVisual(Icons.AutoMirrored.Filled.QueueMusic, "Queue")
    NowPlayingAction.LISTEN_TOGETHER -> ActionVisual(
        Icons.Default.Groups,
        when (s.together) {
            is ListenTogetherState.InRoom -> "Listening together"
            is ListenTogetherState.Connecting -> "Connecting…"
            ListenTogetherState.Idle -> "Listen together"
        },
        active = s.together is ListenTogetherState.InRoom,
    )
    NowPlayingAction.SAVE_TO_PLAYLIST -> ActionVisual(Icons.Default.BookmarkBorder, "Save to Playlist")
    NowPlayingAction.SHARE -> ActionVisual(Icons.Default.Share, "Share song")
    NowPlayingAction.POST_TO_COMMUNITY -> ActionVisual(Icons.Default.Public, "Post to Community")
    NowPlayingAction.VIEW_ALBUM -> ActionVisual(Icons.Default.Album, "View Album")
    // Shows the speed when it isn't 1x.
    NowPlayingAction.PLAYBACK_SPEED -> ActionVisual(
        Icons.Default.Speed,
        if (s.speed == 1f) "Playback speed" else "Playback speed · ${formatSpeed(s.speed)}",
        active = s.speed != 1f,
    )
    NowPlayingAction.DOWNLOAD -> ActionVisual(
        if (s.isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
        if (s.isDownloaded) "Remove download" else "Download",
    )
    NowPlayingAction.FLAG_WRONG_MATCH -> ActionVisual(Icons.Default.Flag, "Flag as Wrong Match")
}

/** The pin editor's name for an action: what it is, not its current state ("Radio", not "Stop radio"). */
internal fun editorLabel(action: NowPlayingAction): String = when (action) {
    NowPlayingAction.RADIO -> "Radio"
    NowPlayingAction.LYRICS -> "Lyrics"
    NowPlayingAction.QUEUE -> "Queue"
    NowPlayingAction.LISTEN_TOGETHER -> "Listen together"
    NowPlayingAction.SAVE_TO_PLAYLIST -> "Save to Playlist"
    NowPlayingAction.SHARE -> "Share song"
    NowPlayingAction.POST_TO_COMMUNITY -> "Post to Community"
    NowPlayingAction.VIEW_ALBUM -> "View Album"
    NowPlayingAction.PLAYBACK_SPEED -> "Playback speed"
    NowPlayingAction.DOWNLOAD -> "Download"
    NowPlayingAction.FLAG_WRONG_MATCH -> "Flag as Wrong Match"
}

// ---------------------------------------------------------------------------
// The top row
// ---------------------------------------------------------------------------

/** A slot's full size, and the least it shrinks to when a full row meets a narrow phone. */
private val SlotMax = 48.dp
private val SlotMin = 36.dp

/**
 * Top row: Dismiss on the left; then Cast, the pinned actions, ⋮ (opens
 * [NowPlayingOptionsSheet]) and, when pinned, Queue at the far right.
 *
 * Every slot is 48dp while the row fits. A full row (five pins plus Cast) is
 * eight slots, wider than a 360dp phone allows, so the slots share the width
 * instead, down to [SlotMin]: the icons stay 24dp and simply sit closer.
 *
 * @param castVisible Whether [castButton] draws anything (it does not where Cast is unavailable).
 * @param isDownloading A download is in flight: its spinner replaces the
 *   Download pin, or ⋮ when Download sits in the menu, so it isn't a silent job.
 */
@Composable
internal fun NowPlayingTopBar(
    row: TopRowActions,
    visual: (NowPlayingAction) -> ActionVisual,
    hasTrack: Boolean,
    isDownloading: Boolean,
    radioTuning: Boolean,
    radioLock: Boolean,
    ink: Color,
    accent: Color,
    dismissDescription: String,
    onDismiss: () -> Unit,
    onMoreClick: () -> Unit,
    onAction: (NowPlayingAction) -> Unit,
    castVisible: Boolean,
    castButton: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val slots = 1 + (if (hasTrack) (if (castVisible) 1 else 0) + row.size + 1 else 0)
        val slot = (maxWidth / slots).coerceIn(SlotMin, SlotMax)
        // The row sizes its own slots; Material's 48dp floor would push them back apart.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TopBarIcon(Icons.Default.KeyboardArrowDown, dismissDescription, ink, slot, iconSize = 28.dp, onClick = onDismiss)

                Spacer(modifier = Modifier.weight(1f))

                if (!hasTrack) return@Row

                // Cast: automatic, never pinned or unpinned. Draws nothing where Cast isn't available.
                if (castVisible) Box(Modifier.size(slot), contentAlignment = Alignment.Center) { castButton() }

                row.leading.forEach { action ->
                    ActionSlot(action, visual(action), slot, ink, accent, isDownloading, radioTuning, radioLock, onAction)
                }

                // ⋮ — always present. While a download is in flight and Download is not pinned,
                // a spinner stands in for it, as before pins.
                if (isDownloading && NowPlayingAction.DOWNLOAD !in row.leading) {
                    SlotSpinner(slot, ink)
                } else {
                    TopBarIcon(Icons.Default.MoreVert, "More actions", ink, slot, onClick = onMoreClick)
                }

                row.trailing.forEach { action ->
                    ActionSlot(action, visual(action), slot, ink, accent, isDownloading, radioTuning, radioLock, onAction)
                }
            }
        }
    }
}

/** One pinned action. Radio carries its radar sweep; Download its spinner. */
@Composable
private fun ActionSlot(
    action: NowPlayingAction,
    v: ActionVisual,
    slot: Dp,
    ink: Color,
    accent: Color,
    isDownloading: Boolean,
    radioTuning: Boolean,
    radioLock: Boolean,
    onAction: (NowPlayingAction) -> Unit,
) {
    val tint by animateColorAsState(if (v.active) accent else ink, tween(250), label = "pin-tint")
    when (action) {
        // The radar sweep spins around the icon while the station is being built.
        NowPlayingAction.RADIO -> Box(Modifier.size(slot), contentAlignment = Alignment.Center) {
            com.stash.feature.nowplaying.ui.RadarSweep(
                tuning = radioTuning,
                lock = radioLock,
                color = accent,
                modifier = Modifier.matchParentSize(),
            )
            TopBarIcon(v.icon, v.label, tint, slot) { if (!radioTuning) onAction(action) }
        }
        NowPlayingAction.DOWNLOAD -> if (isDownloading) SlotSpinner(slot, ink) else {
            TopBarIcon(v.icon, v.label, tint, slot) { onAction(action) }
        }
        else -> TopBarIcon(v.icon, v.label, tint, slot) { onAction(action) }
    }
}

/** A borderless icon button of [slot] width, with Material's unbounded ripple. */
@Composable
private fun TopBarIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    slot: Dp,
    iconSize: Dp = 24.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(slot)
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = slot / 2),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun SlotSpinner(slot: Dp, ink: Color) {
    Box(modifier = Modifier.size(slot), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = ink)
    }
}

// ---------------------------------------------------------------------------
// The ⋮ menu and its pin editor
// ---------------------------------------------------------------------------

/**
 * The ⋮ menu: the current track's actions that aren't pinned, and an Edit
 * mode that moves actions between the top row and this menu.
 *
 * Edit lists every action in two groups, "In the top bar" (in row order) and
 * "In this menu", each with a pin toggle; a tap moves the row between groups.
 * The row holds [NowPlayingAction.MAX_PINS]: at the cap, the empty pins dim,
 * the caption says why, and a refused tap answers with a reject haptic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingOptionsSheet(
    menu: List<NowPlayingAction>,
    editable: List<NowPlayingAction>,
    pins: Set<NowPlayingAction>,
    visual: (NowPlayingAction) -> ActionVisual,
    hiddenReason: (NowPlayingAction) -> String?,
    accent: Color,
    onAction: (NowPlayingAction) -> Unit,
    onTogglePin: (NowPlayingAction) -> Boolean,
    onResetPins: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState,
) {
    val extendedColors = com.stash.core.ui.theme.StashTheme.extendedColors
    var editing by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = extendedColors.elevatedSurface,
    ) {
        AnimatedContent(
            targetState = editing,
            transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "options-edit",
        ) { isEditing ->
            if (isEditing) {
                PinEditor(
                    editable = editable,
                    pins = pins,
                    hiddenReason = hiddenReason,
                    accent = accent,
                    onTogglePin = onTogglePin,
                    onResetPins = onResetPins,
                    onDone = { editing = false },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 36.dp),
                ) {
                    SheetHeader(title = "Track Options", action = "Edit", onAction = { editing = true })
                    menu.forEachIndexed { i, action ->
                        if (i > 0) Spacer(modifier = Modifier.height(8.dp))
                        val v = visual(action)
                        SheetOptionRow(icon = v.icon, label = v.label, onClick = { onAction(action) })
                    }
                }
            }
        }
    }
}

/** Title centred, with a quiet text action at the trailing edge ("Edit", "Done"). */
@Composable
private fun SheetHeader(title: String, action: String, onAction: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center),
        )
        TextButton(onClick = onAction, modifier = Modifier.align(Alignment.CenterEnd)) {
            Text(action, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PinEditor(
    editable: List<NowPlayingAction>,
    pins: Set<NowPlayingAction>,
    hiddenReason: (NowPlayingAction) -> String?,
    accent: Color,
    onTogglePin: (NowPlayingAction) -> Boolean,
    onResetPins: () -> Unit,
    onDone: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val full = pinsFull(pins)
    // The pinned group reads in row order (Queue last, as it sits at the far right).
    val pinned = editable.filter { it in pins }.sortedBy { if (it == NowPlayingAction.QUEUE) 1 else 0 }
    val unpinned = editable.filter { it !in pins }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        SheetHeader(title = "Customize top bar", action = "Done", onAction = onDone)
        // The cap, said before it bites; at the cap it turns accent and says what to do.
        val captionColor by animateColorAsState(
            if (full) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            tween(250),
            label = "pin-caption",
        )
        Text(
            text = if (full) {
                "The top bar is full. Unpin one to make room."
            } else {
                "Pin up to ${NowPlayingAction.MAX_PINS} actions to the top bar. The rest stay in this menu."
            },
            style = MaterialTheme.typography.bodySmall,
            color = captionColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            item(key = "h-pinned") { GroupLabel("In the top bar · ${pins.size} of ${NowPlayingAction.MAX_PINS}", Modifier.animateItem()) }
            if (pinned.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "Nothing pinned. The top bar keeps just the menu button.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.animateItem().padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
            items(pinned, key = { it.name }) { action ->
                PinRow(action, pinned = true, dimmed = false, hiddenReason(action), accent, Modifier.animateItem()) {
                    onTogglePin(action)
                }
            }
            item(key = "h-menu") { GroupLabel("In this menu", Modifier.animateItem().padding(top = 12.dp)) }
            items(unpinned, key = { it.name }) { action ->
                PinRow(action, pinned = false, dimmed = full, hiddenReason(action), accent, Modifier.animateItem()) {
                    if (!onTogglePin(action)) haptics.performHapticFeedback(HapticFeedbackType.Reject)
                }
            }
            if (pins != NowPlayingAction.DefaultPins) {
                item(key = "reset") {
                    Box(Modifier.fillMaxWidth().animateItem().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onResetPins) { Text("Reset to Radio and Queue") }
                    }
                }
            }
            // Clear of the 3-button / gesture bar, so the last row can scroll fully into view.
            item(key = "foot") { Spacer(Modifier.navigationBarsPadding().height(28.dp)) }
        }
    }
}

@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
    )
}

/**
 * An editor row in [SheetOptionRow]'s proportions, with a pin toggle at the
 * trailing edge (filled + accent when pinned). The whole row toggles, and reads
 * to TalkBack as a switch: "Share song, Pinned to the top bar".
 */
@Composable
private fun PinRow(
    action: NowPlayingAction,
    pinned: Boolean,
    dimmed: Boolean,
    note: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val label = editorLabel(action)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val pinTint by animateColorAsState(
        when {
            pinned -> accent
            dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        tween(200),
        label = "pin",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = pinned, role = Role.Switch, onValueChange = { onToggle() })
            .semantics {
                contentDescription = label
                stateDescription = if (pinned) "Pinned to the top bar" else "In the menu"
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = actionVisual(action, ActionVisualState()).icon,
            contentDescription = null,
            tint = onSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = onSurface)
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = null,
            tint = pinTint,
            modifier = Modifier.size(22.dp),
        )
    }
}
