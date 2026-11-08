package com.stash.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import com.stash.core.ui.R

/** This phone's part in a Listen Together session (spec 2026-09-24 §5), provided once at the app root. */
enum class ListenTogetherRole {
    NONE,
    HOST,

    /** A guest whose Play next and Add to queue go straight into the room's queue (spec 2026-11-04). */
    GUEST,

    /** A guest whose Play next goes to the host as a suggestion: the host doesn't let guests add, or its app is older. */
    LISTENER,
    ;

    /** Anyone but the host in a session: play, pause, seek and skip are the host's. */
    val isGuest: Boolean get() = this == GUEST || this == LISTENER

    /** "Add to queue" means something: outside a session, for the host, and for a guest whose adds go straight in. */
    val canQueue: Boolean get() = this != LISTENER
}

val LocalListenTogetherRole = compositionLocalOf { ListenTogetherRole.NONE }

/** The host's name, for a listener's "Suggest to Rawn"; null outside a session or when the host has no name. */
val LocalListenTogetherHostName = compositionLocalOf<String?> { null }

/**
 * "Play next" in a session: the room's Play next for the host and for a guest who can add (the same rules as solo,
 * so the same words); a suggestion to the host otherwise.
 */
@Composable
fun playNextLabel(role: ListenTogetherRole, default: String): String = when (role) {
    ListenTogetherRole.NONE, ListenTogetherRole.HOST, ListenTogetherRole.GUEST -> default
    ListenTogetherRole.LISTENER -> LocalListenTogetherHostName.current
        ?.let { stringResource(R.string.listen_suggest_to, it) }
        ?: stringResource(R.string.listen_suggest_to_host)
}
