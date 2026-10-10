package com.stash.feature.settings.weblink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.data.weblibrary.ImportSelection
import com.stash.core.data.weblink.WebLinkConfig
import com.stash.core.data.weblink.inbox.IncomingSend
import com.stash.core.data.weblink.inbox.WebLinkInbox
import com.stash.feature.settings.WebLibraryImportViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A received send being chosen from (the picker), or the line saying what an Add did. */
sealed interface InboxStep {
    data class Choosing(val send: IncomingSend, val pick: LibraryPick) : InboxStep
    data class Adding(val send: IncomingSend) : InboxStep
    data class Added(val text: String) : InboxStep
}

/**
 * Receiving a send (link-sync spec §2.3): "Chrome on Windows sent you 3 playlists and 40 likes." Add merges it as importing the
 * file does (adds, never removes); Choose opens the same picker as an import; Not now keeps it for 7 days without asking again;
 * Discard deletes it. Shared by the prompt over the app and the "Waiting for you" list on Link Stash on the web.
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val inbox: WebLinkInbox,
    config: WebLinkConfig,
) : ViewModel() {
    private val enabled = config.enabled

    /** The send to ask about over the app, or null (also while the feature is switched off). */
    val notice: StateFlow<IncomingSend?> = inbox.notice.map { it.takeIf { enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sends: StateFlow<List<IncomingSend>> = inbox.sends

    private val _step = MutableStateFlow<InboxStep?>(null)
    val step: StateFlow<InboxStep?> = _step.asStateFlow()

    fun refresh() {
        if (enabled) viewModelScope.launch { inbox.check(force = true) }
    }

    fun addAll(send: IncomingSend) = add(send, ImportSelection.ALL)

    fun choose(send: IncomingSend) {
        val c = send.content ?: return
        _step.value = InboxStep.Choosing(send, WebLibraryImportViewModel.pickOf(c))
    }

    fun updatePick(pick: LibraryPick) {
        val s = _step.value as? InboxStep.Choosing ?: return
        _step.value = s.copy(pick = pick)
    }

    fun addChosen() {
        val s = _step.value as? InboxStep.Choosing ?: return
        add(s.send, WebLibraryImportViewModel.selectionOf(s.pick))
    }

    private fun add(send: IncomingSend, selection: ImportSelection) {
        if (_step.value is InboxStep.Adding) return
        _step.value = InboxStep.Adding(send)
        viewModelScope.launch {
            val text = try {
                inbox.add(send.sendId, selection)?.let(WebLibraryImportViewModel::resultText) ?: "That send isn't here any more."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't add it. Try again."
            }
            _step.value = InboxStep.Added(text)
        }
    }

    fun later(send: IncomingSend) = inbox.later(send.sendId)

    fun discard(send: IncomingSend) {
        viewModelScope.launch { inbox.discard(send.sendId) }
    }

    fun stepDone() {
        if (_step.value !is InboxStep.Adding) _step.value = null
    }
}

/** The prompt over the app (StashScaffold) when a linked browser sent something here. */
@Composable
fun InboxPrompt(viewModel: InboxViewModel = hiltViewModel()) {
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val s = notice
    if (step == null && s != null) {
        SendDialog(
            send = s,
            onAdd = { viewModel.addAll(s) },
            onChoose = { viewModel.choose(s) },
            onLater = { viewModel.later(s) },
            onDiscard = { viewModel.discard(s) },
        )
    }
    InboxStepDialogs(step, viewModel)
}

/** "Chrome on Windows sent you 3 playlists and 40 likes." [Add to my library] [Choose…] [Not now] [Discard]. */
@Composable
fun SendDialog(send: IncomingSend, onAdd: () -> Unit, onChoose: () -> Unit, onLater: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(send.name) }
                    append(" sent you ")
                    append(send.summary)
                    append(".")
                },
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                if (send.content != null) "Adding never removes anything from your library." else send.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (send.content != null) {
                    TextButton(onClick = onAdd) { Text("Add to my library") }
                    TextButton(onClick = onChoose) { Text("Choose what to add") }
                    TextButton(onClick = onLater) { Text("Not now") }
                }
                TextButton(onClick = onDiscard, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Discard")
                }
            }
        },
    )
}

/** The picker for "Choose what to add", the wait while adding, and what it added. */
@Composable
fun InboxStepDialogs(step: InboxStep?, viewModel: InboxViewModel) {
    when (val st = step) {
        is InboxStep.Choosing -> LibraryPickerSheet(
            title = "What to add",
            intro = st.send.content?.let { WebLibraryImportViewModel.introOf(it, "${st.send.name} sent") },
            pick = st.pick,
            onPick = viewModel::updatePick,
            actions = listOf(PickerAction("Add to my library", viewModel::addChosen, primary = true)),
            onDismiss = viewModel::stepDone,
        )
        is InboxStep.Adding -> AlertDialog(
            onDismissRequest = {},
            containerColor = MaterialTheme.colorScheme.surface,
            text = { Text("Adding to your library…", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {},
        )
        is InboxStep.Added -> AlertDialog(
            onDismissRequest = viewModel::stepDone,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            text = { Text(st.text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = viewModel::stepDone) { Text("OK") } },
        )
        null -> Unit
    }
}
