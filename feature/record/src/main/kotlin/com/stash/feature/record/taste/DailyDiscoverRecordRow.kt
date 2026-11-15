package com.stash.feature.record.taste

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.feature.record.R
import com.stash.feature.record.imports.RecordImportStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Daily Discover's page: "Use your record in mixes", the same switch as Settings › Library & Storage › Your record (one
 * preference, one [RecordInMixes.set], so the two can't disagree). It still shapes every Stash Mix, as in Settings, and its
 * line says so; a flip remakes today's Daily Discover after [RecordInMixes.PAUSE_MS], and the page's songs follow.
 */
@HiltViewModel
class DailyDiscoverRecordViewModel @Inject constructor(
    store: RecordImportStore,
    private val inMixes: RecordInMixes,
    recipes: StashMixRecipeDao,
) : ViewModel() {
    /** There is an import: the row shows. */
    val imported: StateFlow<Boolean> =
        store.info.map { it.record != null }.stateIn(viewModelScope, SharingStarted.Eagerly, store.info.value.record != null)

    /** The switch (null until read). */
    val on: StateFlow<Boolean?> = inMixes.on.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Daily Discover's playlist, as [RecordTaste.dailyDiscover] finds it (null: not made yet, or switched off). */
    val dailyDiscoverPlaylistId: StateFlow<Long?> = recipes.observeAll()
        .map { all -> all.firstOrNull { it.isBuiltin && it.isActive && it.name == RecordTaste.DAILY_DISCOVER }?.playlistId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setOn(value: Boolean) {
        inMixes.set(value)
    }
}

/**
 * The toggle beside Daily Discover's Download button, on that page only and only after a Your record import; nothing on any
 * other mix. The library page hands its playlist id in (feature:library doesn't depend on this module: the app passes the row).
 */
@Composable
fun DailyDiscoverRecordRow(
    playlistId: Long,
    modifier: Modifier = Modifier,
    viewModel: DailyDiscoverRecordViewModel = hiltViewModel(),
) {
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val on by viewModel.on.collectAsStateWithLifecycle()
    val dailyDiscover by viewModel.dailyDiscoverPlaylistId.collectAsStateWithLifecycle()
    val switchOn = on ?: return
    if (!imported || dailyDiscover != playlistId) return
    RecordInMixesPageRow(on = switchOn, onChange = viewModel::setOn, modifier = modifier)
}

/**
 * The toggle itself, beside the Download button and in that button's shape: outlined, 12 dp corners, 16/12 padding, a 20 dp
 * icon, labelLarge. Off: the vinyl icon in onSurface; on: a check in primary, as "Downloaded". The whole thing toggles;
 * TalkBack reads it as a switch.
 */
@Composable
internal fun RecordInMixesPageRow(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .border(ButtonDefaults.outlinedButtonBorder(), shape)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (on) Icons.Rounded.CheckCircle else Icons.Rounded.Album,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.record_in_mixes_page_label),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
        )
    }
}
