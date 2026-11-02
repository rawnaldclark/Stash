package com.stash.feature.settings.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stash.core.data.update.AppUpdates
import com.stash.core.data.update.AvailableUpdate
import com.stash.core.data.update.UpdateState
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R

/*
 * The update sheet's pinned actions, one set per step of the update: Update now with Later and Skip this version; the
 * download; Android's one-time permission; the install; a failure. Split from UpdateSheet.kt (review on #814).
 */

@Composable
internal fun ActionArea(update: AvailableUpdate, state: UpdateState, colours: SheetColours, actions: UpdateSheetActions) {
    when (state) {
        UpdateState.Idle -> {
            PrimaryButton(Icons.Rounded.Download, stringResource(R.string.update_sheet_update_now), colours, actions.update)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton(stringResource(R.string.update_sheet_later), actions.later, Modifier.weight(1f))
                QuietButton(stringResource(R.string.update_sheet_skip), actions.skip, Modifier.weight(1f))
            }
        }
        is UpdateState.Downloading -> {
            DownloadProgress(state.fraction, update.sizeBytes, colours.accent)
            QuietButton(stringResource(R.string.update_sheet_hide), actions.hide, Modifier.fillMaxWidth())
        }
        UpdateState.NeedsPermission -> {
            PermissionSteps(colours.ink)
            PrimaryButton(Icons.Rounded.Settings, stringResource(R.string.update_sheet_open_settings), colours, actions.openSettings)
            // A way out that isn't Android's settings: ask again tomorrow, the download kept for then.
            QuietButton(stringResource(R.string.update_sheet_later), actions.later, Modifier.fillMaxWidth())
        }
        UpdateState.Installing -> {
            Bar(fraction = null, fill = colours.accent)
            Text(
                stringResource(R.string.update_sheet_installing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is UpdateState.Failed -> {
            Failure(state.reason)
            if (state.reason == UpdateState.Failed.Reason.ALREADY_NEWER) {
                SecondaryButton(stringResource(R.string.update_sheet_close), null, actions.close)
            } else {
                PrimaryButton(Icons.Rounded.Refresh, stringResource(R.string.update_sheet_try_again), colours, actions.retry)
                SecondaryButton(stringResource(R.string.update_sheet_github), Icons.AutoMirrored.Rounded.OpenInNew) {
                    actions.openUrl(update.pageUrl)
                }
            }
        }
    }
}

/** "82 of 174 MB" (the 82 in the strong ink) over the 6 dp bar, read by TalkBack as one progress indicator. */
@Composable
private fun DownloadProgress(fraction: Float?, sizeBytes: Long, fill: Color) {
    val strong = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    val text = when {
        // The template's first argument is the one in strong ink, wherever a translation puts it.
        fraction != null && sizeBytes > 0 -> formatWithSpans(
            stringResource(R.string.update_sheet_progress),
            listOf((fraction * sizeBytes / 1_000_000).toInt().toString(), AppUpdates.sizeLabel(sizeBytes)),
            styled = mapOf(0 to strong),
        )
        fraction != null -> AnnotatedString(stringResource(R.string.update_sheet_downloading_pct, (fraction * 100).toInt()))
        else -> AnnotatedString(stringResource(R.string.update_sheet_downloading))
    }
    val sentence = text.text
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = sentence
            progressBarRangeInfo = if (fraction != null) ProgressBarRangeInfo(fraction, 0f..1f) else ProgressBarRangeInfo.Indeterminate
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Bar(fraction, fill)
        Text(
            stringResource(R.string.update_sheet_downloading_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [template] ("%1$d of %2$s", unformatted) with [args] put in at their positions, each argument in [styled] (by its
 * 0-based index) in its style. The spans follow the arguments, so a translation that moves them, or repeats a digit
 * elsewhere, can't bold the wrong text. `%%` is a percent sign; anything else is left as written.
 */
internal fun formatWithSpans(template: String, args: List<String>, styled: Map<Int, SpanStyle> = emptyMap()): AnnotatedString =
    buildAnnotatedString {
        var at = 0
        for (m in FORMAT_ARG.findAll(template)) {
            append(template.substring(at, m.range.first))
            at = m.range.last + 1
            if (m.value == "%%") {
                append('%')
                continue
            }
            val index = m.groupValues[1].toInt() - 1
            val value = args.getOrNull(index) ?: m.value
            val style = styled[index]
            if (style != null) withStyle(style) { append(value) } else append(value)
        }
        append(template.substring(at))
    }

private val FORMAT_ARG = Regex("""%%|%(\d+)\$[ds]""")

/** The Support card's 6 dp bar in [fill] on the bright glass track; moving when [fraction] isn't known. */
@Composable
private fun Bar(fraction: Float?, fill: Color) {
    val track = StashTheme.extendedColors.glassBorderBright
    if (fraction == null) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(BarHeight).clip(CircleShape),
            color = fill,
            trackColor = track,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
        return
    }
    Box(Modifier.fillMaxWidth().height(BarHeight).clip(CircleShape).background(track)) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(fill))
        }
    }
}

/** Android's one-time "Install unknown apps" step, as three numbered lines. */
@Composable
private fun PermissionSteps(ink: Color) {
    GlassCard {
        Text(
            stringResource(R.string.update_sheet_permission_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        listOf(
            R.string.update_sheet_permission_step_1,
            R.string.update_sheet_permission_step_2,
            R.string.update_sheet_permission_step_3,
        ).forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    color = ink,
                    modifier = Modifier.widthIn(min = 20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(step),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** What went wrong, in plain words, beside a quiet warning mark (not a wall of red). */
@Composable
private fun Failure(reason: UpdateState.Failed.Reason) {
    GlassCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = StashTheme.extendedColors.warning,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(
                    when (reason) {
                        UpdateState.Failed.Reason.NOT_AN_UPDATE -> R.string.update_failed_not_an_update
                        UpdateState.Failed.Reason.ALREADY_NEWER -> R.string.update_failed_already_newer
                        UpdateState.Failed.Reason.DOWNLOAD -> R.string.update_failed_download
                        UpdateState.Failed.Reason.INSTALL -> R.string.update_failed_install
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The main action: full width, the album's accent (neutral ink idle), an 18 dp icon, as the Support card's Donate. */
@Composable
private fun PrimaryButton(icon: ImageVector, label: String, colours: SheetColours, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colours.accent, contentColor = colours.onAccent),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** A second way out: outlined in the bright glass border. */
@Composable
private fun SecondaryButton(label: String, icon: ImageVector?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        border = BorderStroke(1.dp, StashTheme.extendedColors.glassBorderBright),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Later, Skip this version, Hide: text only, in the secondary ink. */
@Composable
private fun QuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

private val BarHeight = 6.dp
