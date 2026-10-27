package com.stash.feature.record.imports

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.feature.record.R
import com.stash.feature.record.card.RecordArtwork
import com.stash.feature.record.card.RecordCard
import com.stash.feature.record.importer.ImportPreview
import com.stash.feature.record.importer.ImportProgress
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Where Spotify lets you ask for your data (both packages are on this page): service.ts `SPOTIFY_PRIVACY_URL`. */
const val SPOTIFY_PRIVACY_URL = "https://www.spotify.com/account/privacy/"

/** The sheet's and the intake's way to the import service ([RecordImports], one for the app). */
@HiltViewModel
class RecordImportViewModel @Inject constructor(val imports: RecordImports) : ViewModel()

/** The file types the picker offers: Spotify's zips (as either type a phone may name them) and loose JSON files. */
internal val IMPORT_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/json")

/**
 * Your record's import sheet (stash-player ImportSheet.svelte and ImportFlow.svelte), over whatever is showing: Home, the record
 * itself, Settings. One flow from three ways in (the Home card, the record's "Bring your Spotify", a zip opened or shared with
 * Stash), placed once by the app's scaffold.
 *
 * The steps: how it works (ask Spotify, wait for the emails, open the zip with Stash, or choose it here); honest progress while
 * the files are read, the year of the plays counting up; what they hold before anything is written; and a kind word when a file
 * can't be used. The sheet can be closed at any time: an import running goes on (the Home card shows how far it is). A finished
 * import says what it brought and, when the sheet was open, opens the record ([onOpenRecord]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordImportHost(onOpenRecord: () -> Unit, viewModel: RecordImportViewModel = hiltViewModel()) {
    val imports = viewModel.imports
    val open by imports.sheetOpen.collectAsStateWithLifecycle()
    val state by imports.state.collectAsStateWithLifecycle()
    val info by imports.info.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(imports) {
        imports.notes.collect { note ->
            val text = when (note) {
                ImportNote.NOT_AN_EXPORT -> R.string.record_intake_not_export
                ImportNote.BUSY -> R.string.record_intake_busy
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
    // Done: what came in, then the record (when the sheet was open to see it), and the sheet back to its start
    LaunchedEffect(state) {
        val done = state as? ImportState.Done ?: return@LaunchedEffect
        Toast.makeText(context, ImportText.doneLine(context.resources, done.listens), Toast.LENGTH_LONG).show()
        val wasOpen = imports.sheetOpen.value
        imports.closeSheet()
        imports.reset()
        if (wasOpen) onOpenRecord()
    }

    if (!open) return
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) imports.bring(uris)
    }
    val pick = { picker.launch(IMPORT_TYPES) }
    ModalBottomSheet(
        onDismissRequest = imports::closeSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = StashTheme.extendedColors.elevatedSurface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            AnimatedContent(
                targetState = state.step(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "import step",
            ) { step ->
                Column {
                    when (step) {
                        Step.HOW -> HowItWorks(
                            asked = info.asked,
                            onAsk = imports::markAsked,
                            onPick = pick,
                        )
                        Step.WORKING -> (state as? ImportState.Working)?.let { Working(it.progress) }
                        Step.PREVIEW -> (state as? ImportState.Preview)?.let {
                            PreviewStep(it.preview, onImport = imports::confirm, onCancel = imports::reset, onAdd = pick)
                        }
                        Step.FAILED -> (state as? ImportState.Failed)?.let {
                            FailedStep(it, onPick = pick, onAgain = imports::again, onBack = imports::reset)
                        }
                    }
                }
            }
        }
    }
}

private enum class Step { HOW, WORKING, PREVIEW, FAILED }

private fun ImportState.step(): Step = when (this) {
    is ImportState.Working -> Step.WORKING
    is ImportState.Preview -> Step.PREVIEW
    is ImportState.Failed -> Step.FAILED
    ImportState.Idle, is ImportState.Done -> Step.HOW
}

// ── how it works ────────────────────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HowItWorks(asked: Long?, onAsk: () -> Unit, onPick: () -> Unit) {
    val uri = LocalUriHandler.current
    val res = LocalContext.current.resources
    RecordArtwork(sleeve = null, label = null, modifier = Modifier.clearAndSetSemantics { })
    Spacer(Modifier.height(14.dp))
    Heading(stringResource(R.string.record_invite_title))
    Spacer(Modifier.height(6.dp))
    Body(stringResource(R.string.record_import_intro))
    Spacer(Modifier.height(18.dp))
    StepRow(1, stringResource(R.string.record_import_ask_title)) {
        val account = stringResource(R.string.record_import_account_data)
        val extended = stringResource(R.string.record_import_extended_history)
        Body(bolded(stringResource(R.string.record_import_ask_body, account, extended), account, extended))
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onAsk()
                    runCatching { uri.openUri(SPOTIFY_PRIVACY_URL) }
                },
                colors = ButtonDefaults.buttonColors(containerColor = StashPurple, contentColor = Color.White),
            ) { Text(stringResource(R.string.record_import_ask_button)) }
            if (asked != null) {
                Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = StashTheme.extendedColors.success, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.record_import_asked, ImportText.shortDate(res, asked)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    StepRow(2, stringResource(R.string.record_import_email_title)) {
        Body(stringResource(R.string.record_import_email_body))
    }
    StepRow(3, stringResource(R.string.record_import_open_title), last = true) {
        Body(stringResource(R.string.record_import_open_body))
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onPick) { Text(stringResource(R.string.record_import_choose)) }
        Spacer(Modifier.height(6.dp))
        Small(stringResource(R.string.record_import_each))
    }
    Spacer(Modifier.height(16.dp))
    Private(stringResource(R.string.record_import_private))
}

/** One numbered step: its number in a ring, its title, then what to do. */
@Composable
private fun StepRow(n: Int, title: String, last: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 16.dp)) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(StashPurple.copy(alpha = 0.16f))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = MaterialTheme.typography.labelLarge.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold), color = StashPurple)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

// ── working ─────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Working(progress: ImportProgress) {
    val res = LocalContext.current.resources
    val line = ImportText.progressLine(res, progress)
    Heading(stringResource(R.string.record_import_working_title))
    Spacer(Modifier.height(18.dp))
    // The year of the plays read so far, counting up (DESIGN "2016 … 2017 …"); the bar is the bytes read, or the plays written
    val year = (progress as? ImportProgress.Read)?.year
    if (year != null) {
        val spoken = stringResource(R.string.record_import_year, year)
        AnimatedContent(targetState = year, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "year") { y ->
            Text(
                "$y",
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 44.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    Text(
        line,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(12.dp))
    val part = ImportText.part(progress)
    if (part == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = StashPurple)
    } else {
        LinearProgressIndicator(progress = { part }, modifier = Modifier.fillMaxWidth(), color = StashPurple)
    }
    Spacer(Modifier.height(20.dp))
    Private(stringResource(R.string.record_import_private))
}

// ── the preview ─────────────────────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewStep(p: ImportPreview, onImport: () -> Unit, onCancel: () -> Unit, onAdd: () -> Unit) {
    val res = LocalContext.current.resources
    Heading(stringResource(R.string.record_preview_title))
    ImportText.span(res, p.first, p.last)?.let {
        Spacer(Modifier.height(4.dp))
        Body(it)
    }
    Spacer(Modifier.height(16.dp))
    val hours = RecordCard.hours(p.listenedMs)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Figure(ImportText.count(res, hours), pluralStringResource(R.plurals.record_preview_hours, hours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), Modifier.weight(1f))
        Figure(ImportText.count(res, p.years.toLong()), pluralStringResource(R.plurals.record_preview_years, p.years), Modifier.weight(1f))
        Figure(ImportText.count(res, p.plays.toLong()), pluralStringResource(R.plurals.record_preview_plays, p.plays), Modifier.weight(1f))
    }
    if (p.same > 0) {
        Spacer(Modifier.height(12.dp))
        Body(pluralStringResource(R.plurals.record_preview_here, p.same, ImportText.count(res, p.same.toLong())))
    }
    if (!p.extended && p.plays > 0) {
        Spacer(Modifier.height(14.dp))
        Callout(stringResource(R.string.record_preview_account_title), stringResource(R.string.record_preview_account_body))
    }
    if (p.playlists.isNotEmpty() || p.likes > 0) {
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.record_preview_also),
            style = MaterialTheme.typography.labelMedium,
            color = StashTheme.extendedColors.textTertiary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(StashTheme.extendedColors.glassBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (pl in p.playlists) ListLine(pl.name, pluralStringResource(R.plurals.record_preview_songs, pl.songs, pl.songs))
            if (p.likes > 0) ListLine(stringResource(R.string.record_preview_liked), pluralStringResource(R.plurals.record_preview_songs, p.likes, p.likes))
        }
        Spacer(Modifier.height(6.dp))
        Small(stringResource(R.string.record_preview_later))
    }
    Spacer(Modifier.height(20.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onImport, enabled = p.anything, colors = ButtonDefaults.buttonColors(containerColor = StashPurple, contentColor = Color.White)) {
            Text(stringResource(R.string.record_preview_import))
        }
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.record_preview_cancel)) }
        TextButton(onClick = onAdd) { Text(stringResource(R.string.record_preview_add)) }
    }
    Spacer(Modifier.height(12.dp))
    Private(stringResource(R.string.record_preview_private))
}

/** One of the preview's three big figures: the number, set large, and its word under it. */
@Composable
private fun Figure(n: String, word: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(StashTheme.extendedColors.glassBackground)
            .border(1.dp, StashTheme.extendedColors.glassBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        // A third of the sheet's width: a long number ("330,827") steps down a size or two instead of being cut
        val size = when {
            n.length <= 5 -> MaterialTheme.typography.headlineSmall
            n.length <= 7 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        }
        Text(
            n,
            style = size.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(word, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun ListLine(name: String, detail: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Callout(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StashTheme.extendedColors.warning.copy(alpha = 0.12f))
            .border(1.dp, StashTheme.extendedColors.warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(14.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Body(body)
    }
}

// ── when it can't be used ───────────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailedStep(f: ImportState.Failed, onPick: () -> Unit, onAgain: () -> Unit, onBack: () -> Unit) {
    val (title, body) = failureWords(f)
    Heading(stringResource(title))
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
    Spacer(Modifier.height(18.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val saving = f.saving && (f.reason == ImportFailure.STORAGE || f.reason == ImportFailure.OTHER)
        Button(onClick = if (saving) onAgain else onPick, colors = ButtonDefaults.buttonColors(containerColor = StashPurple, contentColor = Color.White)) {
            Text(stringResource(if (saving) R.string.record_error_try_again else R.string.record_error_choose_another))
        }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.record_error_back)) }
    }
}

/** ImportFlow.svelte's ERRORS and SAVE_ERRORS: a title and a kind line for each way a file can't be used. */
internal fun failureWords(f: ImportState.Failed): Pair<Int, Int> {
    if (f.saving) {
        when (f.reason) {
            ImportFailure.STORAGE -> return R.string.record_error_storage_title to R.string.record_error_storage_saving_body
            ImportFailure.OTHER -> return R.string.record_error_saving_title to R.string.record_error_saving_body
            else -> Unit
        }
    }
    return when (f.reason) {
        ImportFailure.NOTHING_NEW -> R.string.record_error_nothing_new_title to R.string.record_error_nothing_new_body
        ImportFailure.NOT_EXPORT -> R.string.record_error_not_export_title to R.string.record_error_not_export_body
        ImportFailure.NO_PLAYS -> R.string.record_error_no_plays_title to R.string.record_error_no_plays_body
        ImportFailure.UNREADABLE -> R.string.record_error_unreadable_title to R.string.record_error_unreadable_body
        ImportFailure.STORAGE -> R.string.record_error_storage_title to R.string.record_error_storage_body
        ImportFailure.OTHER -> R.string.record_error_other_title to R.string.record_error_other_body
    }
}

// ── words ───────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Body(text: String) = Body(AnnotatedString(text))

@Composable
private fun Body(text: AnnotatedString) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = StashTheme.extendedColors.textTertiary)
}

/** The privacy line, under a small lock: Stash reads the file here and uploads nothing. */
@Composable
private fun Private(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { }) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = StashTheme.extendedColors.textTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Small(text)
    }
}

/** [text] with each of [parts] set bold where it appears (Spotify's names for its two downloads, in any language's order). */
private fun bolded(text: String, vararg parts: String): AnnotatedString = buildAnnotatedString {
    append(text)
    for (p in parts) {
        val at = text.indexOf(p)
        if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), at, at + p.length)
    }
}
