package com.stash.feature.record.lastfm

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Last.fm in Your record (stash-player LastfmPart.svelte), the second way in beside Bring your Spotify: in the import sheet
 * (under its Last.fm choice) and as Settings' row ([settings]). Not connected: Connect, the app's own Last.fm sign-in
 * (Settings › Accounts & Sync, [onConnect]); a new one is never built here. Connected: everything scrobbled as that name, with
 * honest progress and Stop (it carries on from that page next time), then what came in. Settings also removes the scrobbles
 * again, asked first. Shown only where the app can talk to Last.fm at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordLastfmPart(
    settings: Boolean,
    onConnect: () -> Unit,
    onSeeRecord: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RecordLastfmViewModel = hiltViewModel(),
) {
    val lastfm = viewModel.lastfm
    if (!lastfm.configured) return
    val connected by lastfm.account.collectAsStateWithLifecycle()
    val mark by lastfm.mark.collectAsStateWithLifecycle()
    val state by lastfm.state.collectAsStateWithLifecycle()
    val spotify by viewModel.imports.state.collectAsStateWithLifecycle()
    // Read from storage first: nothing that could change its mind
    val read = connected ?: return
    val res = LocalContext.current.resources
    val name = read.account?.name
    val working = state is LastfmState.Working
    // The Spotify import holds the lock meanwhile: Last.fm waits for it
    val waiting = name != null && spotify is ImportState.Working
    var confirming by rememberSaveable { mutableStateOf(false) }
    val idle = LastfmText.idle(res, name, mark)

    Column(modifier) {
        if (settings) {
            Text(
                stringResource(R.string.record_lastfm_settings_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
        }
        // The line: how far it is, why it stopped, or (the sheet) what the run brought and (Settings) what there is
        when (val st = state) {
            is LastfmState.Working -> {
                Line(LastfmText.progressLine(res, st.progress), settings)
                Spacer(Modifier.height(8.dp))
                val p = st.progress
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
            is LastfmState.Failed -> {
                val (title, body) = LastfmText.failure(st.reason)
                val t = stringResource(title)
                val b = stringResource(body)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$t. ") }
                        append(b)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            else -> Line(if (st is LastfmState.Done && !settings) LastfmText.doneLine(res, st.added) else idle.line, settings)
        }
        Spacer(Modifier.height(12.dp))

        if (confirming) {
            RemoveDialog(viewModel, working = working, onDone = { confirming = false })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (working) {
                OutlinedButton(onClick = lastfm::stop) { Text(stringResource(R.string.record_lastfm_stop)) }
            } else {
                val failed = (state as? LastfmState.Failed)?.reason
                val (label, act) = when {
                    failed == LastfmFailure.RECONNECT -> R.string.record_lastfm_reconnect to onConnect
                    failed != null -> R.string.record_error_try_again to lastfm::bringIn
                    idle.action == LastfmAction.CONNECT -> R.string.record_lastfm_connect to onConnect
                    idle.action == LastfmAction.BRING -> R.string.record_lastfm_bring to lastfm::bringIn
                    idle.action == LastfmAction.CARRY_ON -> R.string.record_lastfm_carry_on to lastfm::bringIn
                    else -> R.string.record_lastfm_newer to lastfm::bringIn
                }
                val main = stringResource(label)
                val blocked = waiting && act != onConnect
                if (settings) {
                    OutlinedButton(onClick = act, enabled = !blocked) { Text(main) }
                } else {
                    Button(
                        onClick = act,
                        enabled = !blocked,
                        colors = ButtonDefaults.buttonColors(containerColor = StashPurple, contentColor = Color.White),
                    ) { Text(main) }
                }
                if (!settings && state is LastfmState.Done) {
                    OutlinedButton(onClick = onSeeRecord) { Text(stringResource(R.string.record_lastfm_see)) }
                }
                if (settings && mark != null) {
                    TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.record_lastfm_remove)) }
                }
            }
        }
        if (waiting && !working) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.record_lastfm_after_spotify), style = MaterialTheme.typography.bodySmall, color = StashTheme.extendedColors.textTertiary)
        }
        if (!settings) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.record_lastfm_hours_note), style = MaterialTheme.typography.bodySmall, color = StashTheme.extendedColors.textTertiary)
        }
    }
}

/** The part's line, a live region so a screen reader hears it change (the web's `role="status"`). */
@Composable
private fun Line(text: String, settings: Boolean) {
    Text(
        text,
        style = if (settings) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
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

/** Settings › Library & Storage › Your record: the Last.fm row, padded as Settings' rows are (it sits in their group). */
@Composable
fun RecordLastfmSettingsRow(onConnect: () -> Unit) {
    RecordLastfmPart(settings = true, onConnect = onConnect, modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp))
}
