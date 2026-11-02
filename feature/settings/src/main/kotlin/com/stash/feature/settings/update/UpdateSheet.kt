package com.stash.feature.settings.update

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.update.AppUpdates
import com.stash.core.data.update.AvailableUpdate
import com.stash.core.data.update.ReleaseNotes
import com.stash.core.data.update.UpdateState
import com.stash.core.ui.ambient.LocalAlbumChrome
import com.stash.core.ui.components.sheetBarsFollow
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R
import kotlinx.coroutines.launch

/**
 * The update sheet (owner 11-02: "aesthetic and easy to understand", in the Donate sheet's style). Over the whole app,
 * held by the scaffold inside the album colours.
 *
 * Top to bottom: Stash's icon beside "Update available", "Stash 0.9.123" and "You have 0.9.122 · 174 MB"; the release's
 * first line; its first three changes in a glass card; "+4 new · 4 improvements · 10 fixes ›", which opens the full user
 * notes in the sheet (never GitHub's PR list, @handles or links: [ReleaseNotes]). The actions stay pinned at the bottom
 * while the rest scrolls: Update now in the album's colour (neutral with nothing playing, as the Support card's Donate),
 * Later and Skip this version. The download, Android's one-time permission, the install and a failure each take the
 * actions' place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheetHost(viewModel: UpdateViewModel = hiltViewModel()) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val update by viewModel.available.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val u = update
    if (!visible || u == null) return
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // A button that closes the sheet slides it down first, then acts (Later, Skip, Hide).
    val closing: (() -> Unit) -> () -> Unit = { action -> { scope.launch { sheetState.hide() }.invokeOnCompletion { action() } } }
    ModalBottomSheet(
        onDismissRequest = viewModel::dismiss,
        sheetState = sheetState,
        containerColor = StashTheme.extendedColors.elevatedSurface,
        properties = sheetBarsFollow(StashTheme.extendedColors.elevatedSurface),
    ) {
        UpdateSheetContent(
            update = u,
            installedVersion = viewModel.installedVersion,
            state = state,
            actions = UpdateSheetActions(
                update = viewModel::update,
                later = closing(viewModel::dismiss),
                skip = closing(viewModel::skip),
                hide = closing(viewModel::hide),
                close = closing(viewModel::dismiss),
                retry = viewModel::retry,
                openSettings = { runCatching { context.startActivity(viewModel.permissionIntent()) } },
                openUrl = { url ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
            ),
        )
    }
}

/** What the sheet's buttons do. */
internal class UpdateSheetActions(
    val update: () -> Unit,
    val later: () -> Unit,
    val skip: () -> Unit,
    val hide: () -> Unit,
    val close: () -> Unit,
    val retry: () -> Unit,
    val openSettings: () -> Unit,
    val openUrl: (String) -> Unit,
)

/** The colours the sheet wears: neutral ink with nothing playing, the album's while a song plays (the Support card's recipe). */
private class SheetColours(val accent: Color, val onAccent: Color, val ink: Color)

@Composable
private fun sheetColours(): SheetColours {
    val scheme = MaterialTheme.colorScheme
    val chrome = LocalAlbumChrome.current
    val tint = chrome.tint
    return SheetColours(
        accent = lerp(scheme.onSurface, chrome.accent, tint),
        onAccent = lerp(scheme.surface, chrome.onAccent, tint),
        ink = lerp(scheme.onSurfaceVariant, chrome.ink, tint),
    )
}

@Composable
internal fun UpdateSheetContent(
    update: AvailableUpdate,
    installedVersion: String?,
    state: UpdateState,
    actions: UpdateSheetActions,
) {
    val notes = remember(update.notes) { ReleaseNotes.parse(update.notes) }
    val colours = sheetColours()
    // "+4 new · …" opens the full notes in the sheet; Back closes them again before it closes the sheet.
    var allNotes by rememberSaveable(update.tag) { mutableStateOf(false) }
    BackHandler(enabled = allNotes) { allNotes = false }
    val scroll = rememberScrollState()
    val surface = StashTheme.extendedColors.elevatedSurface

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        // One scrolling column above the pinned actions: nothing is cut off at any font size.
        Box(Modifier.weight(1f, fill = false)) {
            Column(
                modifier = Modifier
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(update, installedVersion, colours.ink)
                if (notes.headline.isNotEmpty()) {
                    Text(notes.headline, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                if (allNotes) {
                    AllNotes(notes, colours.ink, onOpenGitHub = { actions.openUrl(update.pageUrl) })
                } else {
                    if (notes.highlights.isNotEmpty()) Highlights(notes.highlights, colours.ink)
                    if (notes.hasMore) MoreLine(notes, onClick = { allNotes = true })
                }
            }
            if (scroll.canScrollForward) {
                // A short fade where the notes run under the actions: there's more above them.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface))),
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionArea(update, state, colours, actions)
        }
    }
}

// ── Header ──────────────────────────────────────────────────────────────────

/** Stash's icon beside "Update available", "Stash 0.9.123" and "You have 0.9.122 · 174 MB". */
@Composable
private fun Header(update: AvailableUpdate, installedVersion: String?, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Modifier.size(AppIconSize))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.update_sheet_eyebrow),
                style = MaterialTheme.typography.labelMedium,
                color = ink,
            )
            Text(
                stringResource(R.string.update_sheet_title, update.version),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            val facts = listOfNotNull(
                installedVersion?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.update_sheet_you_have, it) },
                AppUpdates.sizeLabel(update.sizeBytes).takeIf { it.isNotEmpty() },
            )
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The app's own launcher icon (the vinyl), as the phone draws it. Decorative: the title names the app. */
@Composable
private fun AppIcon(modifier: Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { AppIconSize.roundToPx() }
    val icon: ImageBitmap? = remember(context, px) {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(px, px).asImageBitmap() }.getOrNull()
    }
    if (icon != null) Image(icon, contentDescription = null, modifier = modifier)
}

// ── Notes ───────────────────────────────────────────────────────────────────

/** The first changes in a glass card, read by TalkBack as a list. */
@Composable
private fun Highlights(highlights: List<ReleaseNotes.Note>, ink: Color) {
    GlassCard(Modifier.semantics { collectionInfo = CollectionInfo(highlights.size, 1) }) {
        highlights.forEachIndexed { i, note ->
            if (i > 0) Hairline()
            NoteRow(note, ink, Modifier.semantics(mergeDescendants = true) { collectionItemInfo = CollectionItemInfo(i, 1, 0, 1) })
        }
    }
}

/** "+4 new · 4 improvements · 10 fixes ›", opening the full notes. */
@Composable
private fun MoreLine(notes: ReleaseNotes, onClick: () -> Unit) {
    val parts = buildList {
        if (notes.moreNew > 0) add(stringResource(R.string.update_sheet_more_new, notes.moreNew))
        if (notes.improvements > 0) add(pluralStringResource(R.plurals.update_sheet_improvements, notes.improvements, notes.improvements))
        if (notes.fixes > 0) add(pluralStringResource(R.plurals.update_sheet_fixes, notes.fixes, notes.fixes))
    }
    val all = stringResource(R.string.update_sheet_all_notes)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = all, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            parts.joinToString(" · ").ifEmpty { all },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = StashTheme.extendedColors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The full user notes: each section's heading over a glass card of its changes, then the release page on GitHub. */
@Composable
private fun AllNotes(notes: ReleaseNotes, ink: Color, onOpenGitHub: () -> Unit) {
    notes.sections.forEach { section ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            section.heading?.let { title ->
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
                    color = ink,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
                )
            }
            GlassCard(Modifier.semantics { collectionInfo = CollectionInfo(section.notes.size, 1) }) {
                section.notes.forEachIndexed { i, note ->
                    if (i > 0) Hairline()
                    NoteRow(note, ink, Modifier.semantics(mergeDescendants = true) { collectionItemInfo = CollectionItemInfo(i, 1, 0, 1) })
                }
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onOpenGitHub)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.update_sheet_notes_on_github),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = StashTheme.extendedColors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** One change: a dot in the album's ink, its bold lead, its text, and the points under it. */
@Composable
private fun NoteRow(note: ReleaseNotes.Note, ink: Color, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val title = note.title
    val lead = if (title != null) type.titleSmall else type.bodyMedium
    // The dot sits on the first line's middle, whatever the font size.
    val dotTop = with(LocalDensity.current) { (lead.lineHeight.toDp() - DotSize) / 2 }
    Row(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(top = dotTop.coerceAtLeast(0.dp))
                .size(DotSize)
                .clip(CircleShape)
                .background(ink),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) {
                Text(title, style = type.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (note.body.isNotEmpty()) {
                    Text(note.body, style = type.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(note.body, style = type.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            note.details.forEach { point ->
                Row(Modifier.padding(top = 2.dp)) {
                    Text("–", style = type.bodyMedium, color = StashTheme.extendedColors.textTertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(point, style = type.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** The Donate sheet's card: the glass fill, a 1 dp hairline, 16 dp corners and padding. */
@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val ext = StashTheme.extendedColors
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ext.glassBackground)
            .border(BorderStroke(1.dp, ext.glassBorder), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(StashTheme.extendedColors.glassBorder))
}

// ── Actions ─────────────────────────────────────────────────────────────────

@Composable
private fun ActionArea(update: AvailableUpdate, state: UpdateState, colours: SheetColours, actions: UpdateSheetActions) {
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
    val sentence = when {
        fraction != null && sizeBytes > 0 ->
            stringResource(R.string.update_sheet_progress, (fraction * sizeBytes / 1_000_000).toInt(), AppUpdates.sizeLabel(sizeBytes))
        fraction != null -> stringResource(R.string.update_sheet_downloading_pct, (fraction * 100).toInt())
        else -> stringResource(R.string.update_sheet_downloading)
    }
    val strong = if (fraction != null && sizeBytes > 0) (fraction * sizeBytes / 1_000_000).toInt().toString() else null
    val text = buildAnnotatedString {
        append(sentence)
        val at = strong?.let { sentence.indexOf(it) } ?: -1
        if (strong != null && at >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface), at, at + strong.length)
        }
    }
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

private val AppIconSize = 56.dp
private val DotSize = 6.dp
private val BarHeight = 6.dp
