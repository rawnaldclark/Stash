package com.stash.feature.record.lastfm

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.record.R
import com.stash.feature.record.imports.ImportState
import com.stash.feature.record.imports.RecordImports
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Last.fm part's way to its service ([RecordLastfm]) and the Spotify import it waits for. */
@HiltViewModel
class RecordLastfmViewModel @Inject constructor(val lastfm: RecordLastfm, val imports: RecordImports) : ViewModel()

/** What the Last.fm part shows now, worked out once for the sheet and Settings alike (LastfmPart.svelte's `body` and `actions`). */
private class LastfmView(
    /** The line: how far it is, why it stopped, what came in or what there is. */
    val line: String,
    /** The line is why it stopped: a title set bold before it. */
    val failure: Pair<String, String>?,
    val working: Boolean,
    val progress: LastfmProgress?,
    /** The main button, and what it does. */
    val label: Int,
    val act: () -> Unit,
    /** It waits for the Spotify import (which holds the archive meanwhile). */
    val blocked: Boolean,
    val done: Boolean,
    /** Something came in: it can be removed again. */
    val removable: Boolean,
)

/** Reads the service and puts what the part shows together; null until the account is read (nothing that could change its mind). */
@Composable
private fun lastfmView(viewModel: RecordLastfmViewModel, onConnect: () -> Unit, settings: Boolean): LastfmView? {
    val lastfm = viewModel.lastfm
    if (!lastfm.configured) return null
    val connected by lastfm.account.collectAsStateWithLifecycle()
    val mark by lastfm.mark.collectAsStateWithLifecycle()
    val state by lastfm.state.collectAsStateWithLifecycle()
    val spotify by viewModel.imports.state.collectAsStateWithLifecycle()
    val read = connected ?: return null
    val res = LocalContext.current.resources
    val name = read.account?.name
    val idle = LastfmText.idle(res, name, mark)
    val st = state
    val failed = (st as? LastfmState.Failed)?.reason
    val failure = failed?.let { LastfmText.failure(it) }?.let { (t, b) -> stringResource(t) to stringResource(b) }
    val (label, act) = when {
        st is LastfmState.Working -> R.string.record_lastfm_stop to lastfm::stop
        failed == LastfmFailure.RECONNECT -> R.string.record_lastfm_reconnect to onConnect
        failed != null -> R.string.record_error_try_again to lastfm::bringIn
        idle.action == LastfmAction.CONNECT -> R.string.record_lastfm_connect to onConnect
        idle.action == LastfmAction.BRING -> R.string.record_lastfm_bring to lastfm::bringIn
        idle.action == LastfmAction.CARRY_ON -> R.string.record_lastfm_carry_on to lastfm::bringIn
        else -> R.string.record_lastfm_newer to lastfm::bringIn
    }
    val working = st is LastfmState.Working
    val line = when (st) {
        is LastfmState.Working -> LastfmText.progressLine(res, st.progress)
        is LastfmState.Failed -> failure!!.second
        // The sheet says what the run brought; Settings, what there is
        is LastfmState.Done -> if (settings) idle.line else LastfmText.doneLine(res, st.added, st.total, st.missing)
        LastfmState.Idle -> idle.line
    }
    return LastfmView(
        line = line,
        failure = failure,
        working = working,
        progress = (st as? LastfmState.Working)?.progress,
        label = label,
        act = act,
        blocked = !working && act != onConnect && name != null && spotify is ImportState.Working,
        done = st is LastfmState.Done,
        removable = !working && mark != null,
    )
}

/**
 * Last.fm in Your record's import sheet (stash-player LastfmPart.svelte, under the sheet's Last.fm choice), the second way in
 * beside Bring your Spotify. Not connected: Connect, the app's own Last.fm sign-in (Settings › Accounts & Sync, [onConnect]); a
 * new one is never built here. Connected: everything scrobbled as that name, with honest progress and Stop (it carries on from
 * that page next time), then what came in and the way to the record. Shown only where the app can talk to Last.fm at all.
 * Settings has its own rows of the same ([recordLastfmSettingsRows]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordLastfmPart(
    onConnect: () -> Unit,
    onSeeRecord: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RecordLastfmViewModel = hiltViewModel(),
) {
    val v = lastfmView(viewModel, onConnect, settings = false) ?: return
    Column(modifier) {
        Line(v, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant)
        if (v.working) {
            Spacer(Modifier.height(8.dp))
            Progress(v.progress)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = stringResource(v.label)
            if (v.working) {
                OutlinedButton(onClick = v.act) { Text(label) }
            } else {
                Button(
                    onClick = v.act,
                    enabled = !v.blocked,
                    colors = ButtonDefaults.buttonColors(containerColor = StashPurple, contentColor = Color.White),
                ) { Text(label) }
                if (v.done) OutlinedButton(onClick = onSeeRecord) { Text(stringResource(R.string.record_lastfm_see)) }
            }
        }
        if (v.blocked) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.record_lastfm_after_spotify), style = MaterialTheme.typography.bodySmall, color = StashTheme.extendedColors.textTertiary)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.record_lastfm_hours_note), style = MaterialTheme.typography.bodySmall, color = StashTheme.extendedColors.textTertiary)
    }
}

/**
 * Settings › Library & Storage › Your record: Last.fm's rows in the group's own style (LastfmPart.svelte `settings`), beside
 * "Import from Spotify" and "Remove imported history": "Import from Last.fm", what there is (or how far it is) and its one action
 * as a button at its end, as Accounts & Sync's rows carry theirs; and, once something came in, "Remove your Last.fm
 * scrobbles", asked first. None where the app can't talk to Last.fm, or until the account is read. Settings needs no
 * dependency on this module: the app hands these rows over.
 */
@Composable
fun recordLastfmSettingsRows(onConnect: () -> Unit, viewModel: RecordLastfmViewModel = hiltViewModel()): List<@Composable () -> Unit> {
    val v = lastfmView(viewModel, onConnect, settings = true) ?: return emptyList()
    var confirming by rememberSaveable { mutableStateOf(false) }
    if (confirming) RemoveDialog(viewModel, working = v.working, onDone = { confirming = false })
    val rows = mutableListOf<@Composable () -> Unit>()
    rows += {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ROW_PAD_H, vertical = ROW_PAD_V),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.record_lastfm_settings_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Line(v, MaterialTheme.typography.bodySmall, StashTheme.extendedColors.textTertiary, blockedHint = true)
                if (v.working) {
                    Spacer(Modifier.height(6.dp))
                    Progress(v.progress)
                }
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = v.act,
                enabled = !v.blocked,
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(36.dp),
            ) { Text(stringResource(v.label), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    if (v.removable) {
        rows += {
            Row(
                Modifier.fillMaxWidth().clickable { confirming = true }.padding(horizontal = ROW_PAD_H, vertical = ROW_PAD_V),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.record_lastfm_remove), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.record_lastfm_remove_subtitle), style = MaterialTheme.typography.bodySmall, color = StashTheme.extendedColors.textTertiary)
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = StashTheme.extendedColors.textTertiary)
            }
        }
    }
    return rows
}

/** Settings' row padding (feature:settings' SettingsRowPadH and SettingsRowPadV), so these rows sit in its group as its own do. */
private val ROW_PAD_H = 15.dp
private val ROW_PAD_V = 14.dp

/** The part's line, a live region so a screen reader hears it change (the web's `role="status"`); why it stopped, its title bold. */
@Composable
private fun Line(v: LastfmView, style: androidx.compose.ui.text.TextStyle, color: Color, blockedHint: Boolean = false) {
    val text = buildAnnotatedString {
        v.failure?.let { (title, _) -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$title. ") } }
        append(v.line)
    }
    val hint = stringResource(R.string.record_lastfm_after_spotify)
    Text(
        if (blockedHint && v.blocked) buildAnnotatedString { append(hint) } else text,
        style = style,
        color = color,
        modifier = Modifier.semantics { liveRegion = if (v.failure != null) LiveRegionMode.Assertive else LiveRegionMode.Polite },
    )
}

/** Honest progress: the pages read of all, or a moving bar before Last.fm has said how many there are. */
@Composable
private fun Progress(p: LastfmProgress?) {
    if (p != null && p.pages > 0) {
        val part = (p.page.toFloat() / p.pages).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { part },
            color = StashPurple,
            modifier = Modifier.fillMaxWidth().semantics { progressBarRangeInfo = ProgressBarRangeInfo(part, 0f..1f) },
        )
    } else {
        LinearProgressIndicator(color = StashPurple, modifier = Modifier.fillMaxWidth())
    }
}

/** Remove your Last.fm scrobbles, asked first (LastfmPart.svelte's `confirming`): then what it did, as a toast. */
@Composable
private fun RemoveDialog(viewModel: RecordLastfmViewModel, working: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var removing by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!removing) onDone() },
        title = { Text(stringResource(R.string.record_lastfm_remove_title), modifier = Modifier.semantics { heading() }) },
        text = { Text(stringResource(R.string.record_lastfm_remove_body)) },
        confirmButton = {
            TextButton(
                enabled = !removing && !working,
                onClick = {
                    removing = true
                    // The removal itself runs in the app's scope (RecordLastfm.remove): only the wait for its answer is here
                    scope.launch {
                        val note = try {
                            if (viewModel.lastfm.remove()) R.string.record_lastfm_removed else R.string.record_lastfm_remove_busy
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            R.string.record_lastfm_remove_failed
                        }
                        removing = false
                        Toast.makeText(context, note, Toast.LENGTH_SHORT).show()
                        if (note == R.string.record_lastfm_removed) onDone()
                    }
                },
            ) {
                Text(
                    stringResource(if (removing) R.string.record_lastfm_removing else R.string.record_lastfm_remove_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !removing, onClick = onDone) { Text(stringResource(R.string.record_lastfm_cancel)) }
        },
    )
}
