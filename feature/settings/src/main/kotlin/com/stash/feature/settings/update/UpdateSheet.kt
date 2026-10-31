package com.stash.feature.settings.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.update.AppUpdates
import com.stash.core.data.update.AvailableUpdate
import com.stash.core.data.update.UpdateState
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R

/**
 * The update sheet (owner decisions 10-29: a sheet when the app opens, Later asks again tomorrow, any connection with the
 * size shown): the version, its size and what's new, then Update now. The download's progress, Android's one-time "Install
 * unknown apps" step and a failure are shown in place of the buttons.
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
    ModalBottomSheet(
        onDismissRequest = viewModel::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = StashTheme.extendedColors.elevatedSurface,
    ) {
        UpdateSheetContent(
            update = u,
            state = state,
            onUpdate = viewModel::update,
            onLater = viewModel::dismiss,
            onRetry = viewModel::retry,
            permissionIntent = viewModel::permissionIntent,
        )
    }
}

@Composable
private fun UpdateSheetContent(
    update: AvailableUpdate,
    state: UpdateState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onRetry: () -> Unit,
    permissionIntent: () -> Intent,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.update_sheet_title, update.version),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AppUpdates.sizeLabel(update.sizeBytes).takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (update.notes.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.update_sheet_whats_new),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                update.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            )
        }
        Spacer(Modifier.height(8.dp))
        when (state) {
            UpdateState.Idle -> {
                WideButton(stringResource(R.string.update_sheet_update_now), onUpdate)
                TextButton(onClick = onLater, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.update_sheet_later))
                }
            }
            is UpdateState.Downloading -> {
                val f = state.fraction
                if (f != null) {
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                }
                Text(
                    if (f != null) stringResource(R.string.update_sheet_downloading_pct, (f * 100).toInt()) else stringResource(R.string.update_sheet_downloading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            UpdateState.NeedsPermission -> {
                Text(
                    stringResource(R.string.update_sheet_permission),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                WideButton(stringResource(R.string.update_sheet_open_settings)) {
                    runCatching { context.startActivity(permissionIntent()) }
                }
            }
            UpdateState.Installing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                Text(
                    stringResource(R.string.update_sheet_installing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UpdateState.Failed -> {
                Text(
                    stringResource(
                        when (state.reason) {
                            UpdateState.Failed.Reason.NOT_AN_UPDATE -> R.string.update_failed_not_an_update
                            UpdateState.Failed.Reason.ALREADY_NEWER -> R.string.update_failed_already_newer
                            UpdateState.Failed.Reason.DOWNLOAD -> R.string.update_failed_download
                            UpdateState.Failed.Reason.INSTALL -> R.string.update_failed_install
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                WideButton(stringResource(R.string.update_sheet_try_again), onRetry)
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text(stringResource(R.string.update_sheet_github)) }
            }
        }
    }
}

@Composable
private fun WideButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Text(label, modifier = Modifier.padding(vertical = 4.dp))
    }
}

private const val RELEASES_PAGE = "https://github.com/rawnaldclark/Stash/releases/latest"
