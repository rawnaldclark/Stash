package com.stash.feature.settings.addons

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.addons.AddonSees
import com.stash.core.addons.BlockReason
import com.stash.core.ui.theme.StashTheme
import com.stash.data.download.lossless.addon.AddonLinks
import com.stash.data.download.lossless.addon.AddonOverview
import com.stash.feature.settings.R

// The words of Settings › Add-ons (add-ons plan phase 4 §2.1, §2.7, §2.9). The data layer hands over
// reasons and states, never text: every sentence a user reads about an add-on is picked here, from
// string resources, and none of it is text an add-on or the signed index wrote.

/** The sentence for a link that can't be added (§2.1). */
@StringRes
internal fun refusalText(reason: AddonLinks.Refusal): Int = when (reason) {
    AddonLinks.Refusal.NOT_AN_ADDON, AddonLinks.Refusal.NOT_AGREED -> R.string.settings_addons_refused_not_addon
    AddonLinks.Refusal.NOT_HTTPS -> R.string.settings_addons_refused_https
    AddonLinks.Refusal.LOCAL_ADDRESS -> R.string.settings_addons_refused_local
    AddonLinks.Refusal.ONLY_BITCHORD -> R.string.settings_addons_refused_only_bitchord
    AddonLinks.Refusal.NEEDS_NEWER_STASH -> R.string.settings_addons_refused_newer
    AddonLinks.Refusal.BLOCKED_HARMFUL -> R.string.settings_addons_blocked_harmful
    AddonLinks.Refusal.BLOCKED_BROKEN -> R.string.settings_addons_blocked_broken
    AddonLinks.Refusal.BLOCKED_IMPERSONATION -> R.string.settings_addons_blocked_impersonation
    AddonLinks.Refusal.BLOCKED -> R.string.settings_addons_blocked
    AddonLinks.Refusal.UNREACHABLE -> R.string.settings_addons_refused_unreachable
    AddonLinks.Refusal.NOTHING_TO_PLAY -> R.string.settings_addons_refused_nothing
    AddonLinks.Refusal.RUNS_ON_PHONE -> R.string.settings_addons_refused_phone
    AddonLinks.Refusal.ALREADY_ADDED -> R.string.settings_addons_refused_already
    AddonLinks.Refusal.DIFFERENT_ADDON -> R.string.settings_addons_refused_different
    AddonLinks.Refusal.NEEDS_PASSWORD -> R.string.settings_addons_refused_needs_password
    AddonLinks.Refusal.BAD_PASSWORD -> R.string.settings_addons_refused_bad_password
    AddonLinks.Refusal.COULD_NOT_SAVE -> R.string.settings_addons_could_not_save
}

/** The fixed sentence for a block's reason (§1.6): the app's own words, never the file's. */
@StringRes
internal fun blockedText(reason: BlockReason?): Int = when (reason) {
    BlockReason.BROKEN -> R.string.settings_addons_blocked_broken
    BlockReason.IMPERSONATION -> R.string.settings_addons_blocked_impersonation
    BlockReason.HARMFUL -> R.string.settings_addons_blocked_harmful
    // A reason from a later list, or none known: the plain sentence.
    BlockReason.OTHER, null -> R.string.settings_addons_blocked
}

/** "Its server will see" (§2.7), one whole sentence for each set so each can be translated as one. */
@StringRes
internal fun seesText(sees: Set<AddonSees>): Int {
    val search = AddonSees.SEARCHES in sees
    val play = AddonSees.PLAYS in sees
    val save = AddonSees.SAVES in sees
    return when {
        search && play && save -> R.string.settings_addons_sees_search_play_save
        search && play -> R.string.settings_addons_sees_search_play
        search && save -> R.string.settings_addons_sees_search_save
        play && save -> R.string.settings_addons_sees_play_save
        search -> R.string.settings_addons_sees_search
        play -> R.string.settings_addons_sees_play
        save -> R.string.settings_addons_sees_save
        else -> R.string.settings_addons_sees_address
    }
}

@StringRes
internal fun useText(use: AddonLinks.Use): Int = when (use) {
    AddonLinks.Use.PLAYS_SONGS -> R.string.settings_addons_use_plays
    AddonLinks.Use.LOSSLESS -> R.string.settings_addons_use_lossless
    AddonLinks.Use.DOWNLOADS -> R.string.settings_addons_use_downloads
    AddonLinks.Use.LYRICS -> R.string.settings_addons_use_lyrics
}

/** A row's name: the add-on's own, or the app's words for the two named rows. */
@Composable
internal fun AddonOverview.Row.title(): String = when (kind) {
    AddonOverview.Kind.QOBUZ -> stringResource(R.string.settings_addons_row_qobuz)
    AddonOverview.Kind.TIDAL -> stringResource(if (ownServer) R.string.settings_addons_row_tidal_own else R.string.settings_addons_row_tidal)
    AddonOverview.Kind.ADDON -> name.orEmpty()
}

/** "Official", or "Not made by Stash · host"; the user's own account or server has no mark, only its host when there is one. */
@Composable
internal fun AddonOverview.Row.markLine(): String? = when (mark) {
    AddonOverview.Mark.OFFICIAL -> stringResource(R.string.settings_addons_official)
    AddonOverview.Mark.NOT_MADE_BY_STASH ->
        host?.let { stringResource(R.string.settings_addons_not_made_host, it) } ?: stringResource(R.string.settings_addons_not_made)
    AddonOverview.Mark.NONE -> host
}

/** The row's status in a few words; null while there is nothing to say (working, or simply switched off: the switch says that). */
@Composable
internal fun AddonOverview.Row.problemLine(): String? = when (status) {
    AddonOverview.Status.WORKING, AddonOverview.Status.OFF -> null
    AddonOverview.Status.TURNED_OFF_BY_STASH -> stringResource(blockedText(blockReason))
    AddonOverview.Status.WANTS_MORE -> stringResource(R.string.settings_addons_status_wants_more)
    AddonOverview.Status.NEEDS_NEW_LINK -> stringResource(R.string.settings_addons_status_new_link)
    AddonOverview.Status.NEEDS_NEWER_STASH -> stringResource(R.string.settings_addons_status_newer)
    AddonOverview.Status.ONLY_BITCHORD -> stringResource(R.string.settings_addons_refused_only_bitchord)
    AddonOverview.Status.CHANGED -> stringResource(R.string.settings_addons_status_changed)
    AddonOverview.Status.REPLACED -> stringResource(R.string.settings_addons_status_replaced)
    AddonOverview.Status.NOT_REACHABLE -> stringResource(R.string.settings_addons_not_loaded)
}

/**
 * An add-on's picture: the first letter of its name. An add-on's own icon is not fetched: for one
 * Stash doesn't run, that would tell its server (or another one) about the phone before the user
 * has agreed to anything.
 */
@Composable
internal fun AddonAvatar(name: String, size: Dp = 36.dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(StashTheme.extendedColors.glassBorder),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** "Couldn't save. Try again.", once for each change that could not be saved ([failures] counts them). */
@Composable
internal fun SaveFailedToast(failures: Int) {
    val context = LocalContext.current
    LaunchedEffect(failures) {
        if (failures > 0) Toast.makeText(context, R.string.settings_addons_could_not_save, Toast.LENGTH_LONG).show()
    }
}

/** A small pill naming one thing Stash uses from an add-on ("Plays songs", "Lossless"). */
@Composable
internal fun UsePill(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(StashTheme.extendedColors.glassBorder)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
