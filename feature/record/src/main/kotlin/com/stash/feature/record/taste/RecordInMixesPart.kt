package com.stash.feature.record.taste

import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.ui.components.StashSwitch
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.record.R
import com.stash.feature.record.card.RecordCard
import com.stash.feature.record.imports.RecordImportStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What Put back came to, for its toast. */
sealed interface PutBackNote {
    data class Back(val title: String) : PutBackNote

    data object Failed : PutBackNote
}

/**
 * Settings › Your record's taste rows (stash-player `src/routes/settings/RecordInMixes.svelte`): "Use your record in mixes"
 * (D2) and "Kept out of your mixes (N)" (D1), while there is an import, the switch on or off.
 */
@HiltViewModel
class RecordInMixesViewModel @Inject constructor(
    private val taste: RecordTaste,
    private val inMixes: RecordInMixes,
    store: RecordImportStore,
) : ViewModel() {
    /** There is an import: the rows show. */
    val imported: StateFlow<Boolean> = store.info.map { it.record != null }.stateIn(viewModelScope, SharingStarted.Eagerly, store.info.value.record != null)

    val on: StateFlow<Boolean?> = inMixes.on.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _list = MutableStateFlow<List<KeptOutSong>?>(null)

    /** The songs kept out now (null until read), read again whenever the taste, the songs put back or the import change. */
    val list: StateFlow<List<KeptOutSong>?> = _list.asStateFlow()

    /** Put back from here: never shown again by a read that started before its write (until the import goes, which clears them). */
    private val back = HashSet<String>()

    private val _putting = MutableStateFlow<String?>(null)

    /** The song being put back (its write waits for an import or a lengths pass holding the lock). */
    val putting: StateFlow<String?> = _putting.asStateFlow()

    private val _notes = MutableSharedFlow<PutBackNote>(extraBufferCapacity = 4)
    val notes: SharedFlow<PutBackNote> = _notes.asSharedFlow()

    init {
        viewModelScope.launch {
            taste.changes.collect { (_, _, imported) ->
                if (!imported) {
                    synchronized(back) { back.clear() }
                    _list.value = null
                    return@collect
                }
                val read = try {
                    taste.keptOut()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                _list.value = synchronized(back) { read.filter { it.key !in back } }
            }
        }
    }

    fun setOn(value: Boolean) {
        inMixes.set(value)
    }

    /** Put back (kept-out.ts): off the list at once when it worked, a toast either way. */
    fun putBack(s: KeptOutSong) {
        if (_putting.value != null) return
        _putting.value = s.key
        viewModelScope.launch {
            val ok = taste.putBack(s.key)
            _putting.value = null
            if (!ok) {
                _notes.emit(PutBackNote.Failed)
                return@launch
            }
            synchronized(back) { back += s.key }
            _list.value = _list.value?.filter { it.key != s.key }
            _notes.emit(PutBackNote.Back(s.title))
        }
    }
}

/** years.ts `whyLine`, in the app's language: "Hidden on Spotify", "Skipped 9 of 11 times", "An interlude you skip". */
internal fun whyLine(res: Resources, w: KeptOutWhy, locale: Locale): String = when (w) {
    KeptOutWhy.Hidden -> res.getString(R.string.record_kept_out_hidden)
    is KeptOutWhy.Skips -> res.getQuantityString(R.plurals.record_kept_out_skipped, w.starts, RecordCard.count(w.skips.toLong(), locale), RecordCard.count(w.starts.toLong(), locale))
    KeptOutWhy.Interlude -> res.getString(R.string.record_kept_out_interlude)
}

/**
 * Settings › Library & Storage › Your record: the taste's rows in the group's own style, while there is an import. "Use your
 * record in mixes" with its switch; "Kept out of your mixes (N)" with its line, and Show the songs: each song with why it is kept
 * out and Put back, 50 at a time. Settings needs no dependency on this module: the app hands these rows over.
 */
@Composable
fun recordInMixesSettingsRows(viewModel: RecordInMixesViewModel = hiltViewModel()): List<@Composable () -> Unit> {
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val on by viewModel.on.collectAsStateWithLifecycle()
    val list by viewModel.list.collectAsStateWithLifecycle()
    val putting by viewModel.putting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.notes.collect { n ->
            val text = when (n) {
                is PutBackNote.Back -> context.getString(R.string.record_kept_out_put_back_done, n.title)
                PutBackNote.Failed -> context.getString(R.string.record_kept_out_put_back_failed)
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
    val switchOn = on ?: return emptyList()
    if (!imported) return emptyList()
    val rows = mutableListOf<@Composable () -> Unit>()
    rows += {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Switch) { viewModel.setOn(!switchOn) }
                .padding(horizontal = ROW_PAD_H, vertical = ROW_PAD_V),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.record_in_mixes_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.record_in_mixes_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            StashSwitch(checked = switchOn, onCheckedChange = viewModel::setOn)
        }
    }
    val songs = list
    // With the switch off too: Your record's own mixes still leave these out
    if (songs != null) rows += { KeptOutRow(songs, switchOn, putting, viewModel::putBack) }
    return rows
}

@Composable
private fun KeptOutRow(songs: List<KeptOutSong>, on: Boolean, putting: String?, putBack: (KeptOutSong) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val res = LocalContext.current.resources
    var open by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    val n = songs.size
    Column(Modifier.fillMaxWidth().padding(horizontal = ROW_PAD_H, vertical = ROW_PAD_V)) {
        Text(
            stringResource(R.string.record_kept_out_title, RecordCard.count(n.toLong(), locale)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(
                when {
                    n == 0 -> R.string.record_kept_out_empty
                    on -> R.string.record_kept_out_on
                    else -> R.string.record_kept_out_off
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (n == 0) return@Column
        if (open) {
            Spacer(Modifier.height(8.dp))
            for (s in songs.take(shown)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.record_kept_out_row, s.artist, whyLine(res, s.why, locale)),
                            style = MaterialTheme.typography.bodySmall,
                            color = StashTheme.extendedColors.textTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    val label = stringResource(R.string.record_kept_out_put_back_label, s.title, s.artist)
                    OutlinedButton(
                        onClick = { putBack(s) },
                        enabled = putting == null,
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(36.dp).semantics { contentDescription = label },
                    ) {
                        Text(
                            stringResource(if (putting == s.key) R.string.record_kept_out_putting_back else R.string.record_kept_out_put_back),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                open = !open
                shown = PAGE
            }) { Text(stringResource(if (open) R.string.record_kept_out_hide else R.string.record_kept_out_show)) }
            if (open && n > shown) OutlinedButton(onClick = { shown += PAGE }) { Text(stringResource(R.string.record_kept_out_more)) }
        }
    }
}

/** Songs shown at a time (RecordInMixes.svelte `PAGE`). */
private const val PAGE = 50

/** Settings' row padding (feature:settings' SettingsRowPadH and SettingsRowPadV), so these rows sit in its group as its own do. */
private val ROW_PAD_H = 15.dp
private val ROW_PAD_V = 14.dp
