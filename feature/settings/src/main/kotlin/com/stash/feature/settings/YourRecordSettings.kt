package com.stash.feature.settings

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.record.YourRecordImportState
import com.stash.core.data.record.YourRecordImports
import com.stash.feature.settings.components.SettingsGroupCard
import com.stash.feature.settings.components.SettingsNavRow
import com.stash.feature.settings.components.SettingsSectionLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What Settings tells the person after a Your record action (a toast). */
enum class YourRecordNote { REMOVED, REMOVE_BUSY, REMOVE_FAILED, SHOWN }

/**
 * Library & Storage › Your record (stash-player `src/routes/settings/Spotify.svelte`): the imported history and a newer copy,
 * Remove imported history (asked first; refused while an import runs), and Show on Home once the card's × hid the invitation.
 * The work is feature:record's ([YourRecordImports]).
 */
@HiltViewModel
class YourRecordSettingsViewModel @Inject constructor(
    private val imports: YourRecordImports,
    lastFmCredentials: LastFmCredentials,
) : ViewModel() {
    val state: StateFlow<YourRecordImportState> = imports.settings

    /** This build can reach Last.fm: Your record's Last.fm row shows (the web shows it only where the server has Last.fm). */
    val lastfmConfigured: Boolean = lastFmCredentials.isConfigured

    private val _removing = MutableStateFlow(false)
    val removing: StateFlow<Boolean> = _removing.asStateFlow()

    private val _notes = MutableSharedFlow<YourRecordNote>(extraBufferCapacity = 4)
    val notes: SharedFlow<YourRecordNote> = _notes.asSharedFlow()

    fun openImport() = imports.openImport()

    fun remove() {
        if (_removing.value) return
        _removing.value = true
        viewModelScope.launch {
            try {
                _notes.emit(if (imports.removeImport()) YourRecordNote.REMOVED else YourRecordNote.REMOVE_BUSY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notes.emit(YourRecordNote.REMOVE_FAILED)
            } finally {
                _removing.value = false
            }
        }
    }

    fun showOnHome() {
        imports.showInvite()
        _notes.tryEmit(YourRecordNote.SHOWN)
    }
}

/**
 * The rows, as a section of Library & Storage (the app's place closest to the web's "Your library"): Your record (once
 * something was imported; opens it), Import from Spotify or Bring a newer copy (the import sheet), Remove imported history
 * (confirmed first), Show on Home (only when the invitation was hidden and nothing is imported), and last Your record's Last.fm
 * rows ([lastfmRows], feature:record's own, which the app hands over: Import from Last.fm, with its status and its action, and
 * Remove your Last.fm scrobbles once some came in).
 */
@Composable
fun YourRecordSettingsSection(
    viewModel: YourRecordSettingsViewModel,
    onOpenRecord: (() -> Unit)?,
    lastfmRows: (@Composable () -> List<@Composable () -> Unit>)? = null,
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val removing by viewModel.removing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirming by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.notes.collect { n ->
            val text = when (n) {
                YourRecordNote.REMOVED -> R.string.settings_your_record_removed
                YourRecordNote.REMOVE_BUSY -> R.string.settings_your_record_remove_busy
                YourRecordNote.REMOVE_FAILED -> R.string.settings_your_record_remove_failed
                YourRecordNote.SHOWN -> R.string.settings_your_record_shown
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            if (n == YourRecordNote.REMOVED) confirming = false
        }
    }
    if (!s.loaded) return
    val locale = LocalConfiguration.current.locales[0]
    SettingsSectionLabel(stringResource(R.string.settings_your_record_section))
    val rows = mutableListOf<@Composable () -> Unit>()
    if (s.imported && onOpenRecord != null) {
        rows += {
            SettingsNavRow(
                title = stringResource(R.string.settings_your_record_open),
                // What Spotify brought (Last.fm's row says what it brought)
                subtitle = if (s.spotifyPlays > 0) pluralStringResource(R.plurals.settings_your_record_summary, s.spotifyPlays, String.format(locale, "%,d", s.spotifyPlays)) else null,
                onClick = onOpenRecord,
            )
        }
    }
    rows += {
        SettingsNavRow(
            title = stringResource(if (s.spotifyPlays > 0) R.string.settings_your_record_newer else R.string.settings_your_record_import),
            subtitle = stringResource(R.string.settings_your_record_import_subtitle),
            onClick = viewModel::openImport,
        )
    }
    if (s.imported) {
        rows += {
            SettingsNavRow(
                title = stringResource(R.string.settings_your_record_remove),
                subtitle = stringResource(R.string.settings_your_record_remove_subtitle),
                onClick = { if (s.importing) Toast.makeText(context, R.string.settings_your_record_remove_busy, Toast.LENGTH_SHORT).show() else confirming = true },
            )
        }
    } else if (s.inviteHidden) {
        rows += {
            SettingsNavRow(
                title = stringResource(R.string.settings_your_record_show),
                subtitle = stringResource(R.string.settings_your_record_show_subtitle),
                onClick = viewModel::showOnHome,
            )
        }
    }
    if (lastfmRows != null && viewModel.lastfmConfigured) rows += lastfmRows()
    SettingsGroupCard(rows = rows)

    if (confirming) {
        AlertDialog(
            onDismissRequest = { if (!removing) confirming = false },
            title = { Text(stringResource(R.string.settings_your_record_remove_title)) },
            text = { Text(stringResource(R.string.settings_your_record_remove_body)) },
            confirmButton = {
                TextButton(enabled = !removing && !s.importing, onClick = viewModel::remove) {
                    Text(stringResource(R.string.settings_your_record_remove_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !removing, onClick = { confirming = false }) { Text(stringResource(R.string.settings_your_record_cancel)) }
            },
        )
    }
}
