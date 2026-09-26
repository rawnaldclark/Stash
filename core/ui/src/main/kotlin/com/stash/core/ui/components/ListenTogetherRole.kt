package com.stash.core.ui.components

import androidx.compose.runtime.compositionLocalOf

/** This phone's part in a Listen Together session (spec 2026-09-24 §5), provided once at the app root. */
enum class ListenTogetherRole { NONE, HOST, LISTENER }

val LocalListenTogetherRole = compositionLocalOf { ListenTogetherRole.NONE }

/** "Play next" in a session: a host's pick joins the room's queue; a listener's goes to the host as a suggestion. */
fun playNextLabel(role: ListenTogetherRole, default: String): String = when (role) {
    ListenTogetherRole.NONE -> default
    ListenTogetherRole.HOST -> "Add to session queue"
    ListenTogetherRole.LISTENER -> "Suggest to the host"
}
