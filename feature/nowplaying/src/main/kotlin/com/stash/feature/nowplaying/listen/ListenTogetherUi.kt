package com.stash.feature.nowplaying.listen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.model.listen.RoomProtocol
import com.stash.core.model.listen.ServerMessage
import kotlin.random.Random
import kotlinx.coroutines.flow.SharedFlow

/** Starting a session: the only thing to decide is the name others will see (spec §1: no accounts). */
@Composable
fun ListenTogetherStartDialog(name: String, onNameChange: (String) -> Unit, onStart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listen together") },
        text = {
            Column {
                Text(
                    "Friends who open your invite hear what you play, in time with you. You stay in control and can hand it over.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Show my name as") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onStart) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The Android share sheet with the invite link (`https://…/l/{code}`, an App Link). */
fun shareInvite(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Listen with me on Stash: $url")
    context.startActivity(Intent.createChooser(send, "Invite friends").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Copies the invite link. Android 13+ shows its own clipboard confirmation; older versions get a toast. */
fun copyInvite(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Stash invite", url))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
}

/** The six reactions. The picker stays open for quick repeat taps; tapping outside closes it. */
@Composable
fun ReactionButton(onReact: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.AddReaction, contentDescription = "React") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(Modifier.padding(horizontal = 4.dp)) {
                RoomProtocol.EMOJI.forEach { emoji ->
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onReact(emoji)
                    }) { Text(emoji, fontSize = 22.sp) }
                }
            }
        }
    }
}

private data class Floater(val id: Long, val emoji: String, val lane: Float, val from: String)

private const val MAX_FLOATERS = 12

/**
 * Reactions float up and fade, each ringed in its sender's colour. With the ambient animation off they
 * don't travel: they show by the controls and fade, so nothing moves across the screen.
 */
@Composable
fun FloatingReactions(reactions: SharedFlow<ServerMessage.Reaction>, animate: Boolean, modifier: Modifier = Modifier) {
    val live = remember { mutableStateListOf<Floater>() }
    LaunchedEffect(reactions) {
        var next = 0L
        reactions.collect { r ->
            if (live.size >= MAX_FLOATERS) live.removeAt(0)
            live += Floater(next++, r.emoji, Random.nextFloat(), r.from)
        }
    }
    Box(modifier.clearAndSetSemantics { }) { // decorative: screen readers skip the confetti
        live.forEach { f ->
            key(f.id) {
                val progress = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    progress.animateTo(1f, tween(durationMillis = if (animate) 2_400 else 1_200, easing = LinearOutSlowInEasing))
                    live.remove(f)
                }
                Box(
                    modifier = Modifier
                        .align(if (animate) Alignment.BottomStart else Alignment.BottomEnd)
                        // Read in the draw phase: a frame of the float doesn't recompose the screen.
                        .graphicsLayer {
                            if (animate) {
                                translationX = (24 + f.lane * 240).dp.toPx()
                                translationY = -(120 + 320 * progress.value).dp.toPx()
                            } else {
                                translationX = -32.dp.toPx()
                                translationY = -150.dp.toPx()
                            }
                            alpha = 1f - progress.value
                        }
                        .size(44.dp)
                        .background(Color(0x990D0D18), CircleShape)
                        .border(2.dp, personColor(f.from), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = f.emoji, fontSize = 24.sp)
                }
            }
        }
    }
}
