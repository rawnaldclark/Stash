package com.stash.feature.settings.addons

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

/**
 * "Its server will see" (§2.7), one whole sentence for each set so each can be translated as one.
 * It says what Stash sends and about which songs, never "what you search for": nothing typed in
 * Search goes to an add-on. The same sentence on the sheet and on the add-on's page.
 */
@StringRes
internal fun seesText(sees: Set<AddonSees>): Int {
    val next = AddonSees.NEXT_UP in sees
    val play = AddonSees.PLAYS in sees || next
    val save = AddonSees.SAVES in sees
    return when {
        next && save -> R.string.settings_addons_sees_play_next_save
        next -> R.string.settings_addons_sees_play_next
        play && save -> R.string.settings_addons_sees_play_save
        play -> R.string.settings_addons_sees_play
        save -> R.string.settings_addons_sees_save
        else -> R.string.settings_addons_sees_address
    }
}

/**
 * What an add-on's page says first under "What it can see" while Stash is not using it; null while
 * it is, and the page says what its server will see. "Its server sees nothing" is true only of an
 * add-on that is asked nothing at all: one of the user's switched off, or one Stash turned off. Any
 * other that is not used (waiting for the user's review, its link now another add-on's, BitChord
 * only, replaced by Stash's own) and a default the user switched off still have their manifest read
 * now and then, so their server sees the phone's internet address (review-885 round 2, N5).
 */
@StringRes
internal fun notAskedText(row: AddonOverview.Row): Int? {
    if (row.on && row.status != AddonOverview.Status.WANTS_MORE) return null
    val askedNothing = row.status == AddonOverview.Status.TURNED_OFF_BY_STASH ||
        (row.status == AddonOverview.Status.OFF && row.userAdded)
    return if (askedNothing) R.string.settings_addons_page_sees_nothing_now else R.string.settings_addons_page_sees_address_only
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
    AddonOverview.Status.SENT_WRONG_FILE -> wrongFileLine()
    AddonOverview.Status.ONLY_BITCHORD -> stringResource(R.string.settings_addons_refused_only_bitchord)
    AddonOverview.Status.CHANGED -> stringResource(R.string.settings_addons_status_changed)
    AddonOverview.Status.REPLACED -> stringResource(R.string.settings_addons_status_replaced)
    AddonOverview.Status.NOT_REACHABLE -> stringResource(R.string.settings_addons_not_loaded)
}

/**
 * "Sent a file that wasn't what it said. Not used until 14:30.": the add-on handed over a file
 * that was not what it said (the player's check of its stream, or a download's), and is set aside
 * until then. The time in the phone's own 12- or 24-hour style.
 */
@Composable
internal fun AddonOverview.Row.wrongFileLine(): String {
    val until = setAsideUntilMs ?: return stringResource(R.string.settings_addons_status_wrong_file_short)
    val context = LocalContext.current
    val time = remember(until) { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(until)) }
    return stringResource(R.string.settings_addons_status_wrong_file, time)
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

/**
 * "Couldn't save. Try again.", once for each change that could not be saved ([failures] counts them).
 *
 * The count lives in the view model, which outlives the screen: how many of them were already said
 * is kept with the screen's own saved state, so coming back to the screen or turning the phone says
 * nothing again (review-885 N8).
 */
@Composable
internal fun SaveFailedToast(failures: Int) {
    val context = LocalContext.current
    var said by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(failures) {
        if (failures > said) Toast.makeText(context, R.string.settings_addons_could_not_save, Toast.LENGTH_LONG).show()
        // Also when the count started over (a new view model after the app was closed): never above it.
        said = failures
    }
}

/**
 * The space under a page's last card. A page in `SettingsScaffold` ends exactly where the glass mini
 * player begins, which left the add-on page's last card (Replace link, Remove) hard against it on a
 * phone; the Settings hub keeps 16 dp there. Put last in the scaffold's column, this earns the
 * column's own gap between cards ([SettingsGroupGap]) as that space, so it needs no height itself.
 */
@Composable
internal fun PageEndSpace() {
    Spacer(modifier = Modifier.height(0.dp).testTag(PAGE_END_SPACE_TAG))
}

internal const val PAGE_END_SPACE_TAG = "addons-page-end"

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

/**
 * Remove asks first (review-881 M1): what goes with the add-on, and that downloads stay. For the
 * user's own Tidal server it is "Use Stash's Tidal instead", and says a server with no add-on link
 * can't be added back in this version. One dialog for the add-on's page and for the Remove on a row
 * Stash turned off.
 */
@Composable
internal fun RemoveAddonDialog(row: AddonOverview.Row, title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val action = stringResource(if (row.ownServer) R.string.settings_addons_tidal_use_stash else R.string.settings_addons_remove)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                text = if (row.ownServer) stringResource(R.string.settings_addons_tidal_use_stash_title) else stringResource(R.string.settings_addons_remove_title, title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = stringResource(if (row.ownServer) R.string.settings_addons_tidal_use_stash_body else R.string.settings_addons_remove_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(action)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_addons_cancel)) }
        },
    )
}
