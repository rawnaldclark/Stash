package com.stash.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.components.DownloadManagementRow

@Composable
fun DownloadManagementScreen(
    onBack: () -> Unit,
    viewModel: DownloadManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DownloadsHeader(
                state = state,
                onBack = onBack,
                onSelectTab = viewModel::selectTab,
            )
            when (state.selectedTab) {
                DownloadManagementTab.QUEUE -> QueueContent(
                    state = state,
                    onCancel = viewModel::cancel,
                    onRetry = viewModel::retry,
                    onClearQueue = viewModel::clearQueue,
                )
                DownloadManagementTab.HISTORY -> HistoryContent(
                    state = state,
                    onRetry = viewModel::retry,
                    onClearHistory = viewModel::clearHistory,
                )
            }
        }
    }
}

@Composable
private fun DownloadsHeader(
    state: DownloadManagementUiState,
    onBack: () -> Unit,
    onSelectTab: (DownloadManagementTab) -> Unit,
) {
    val extendedColors = StashTheme.extendedColors
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .background(extendedColors.glassBackground, CircleShape),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.sync_back))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.sync_downloads_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        val downloadingStr = pluralStringResource(R.plurals.sync_downloads_downloading, state.downloading.size, state.downloading.size)
        val queuedStr = pluralStringResource(R.plurals.sync_downloads_queued, state.queued.size, state.queued.size)
        Text(
            text = when {
                state.downloading.isNotEmpty() -> stringResource(R.string.sync_downloads_downloading_and_queued, downloadingStr, queuedStr)
                state.queued.isNotEmpty() -> queuedStr
                else -> stringResource(R.string.sync_downloads_header_default)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.selectedTab == DownloadManagementTab.QUEUE,
                onClick = { onSelectTab(DownloadManagementTab.QUEUE) },
                label = { Text(stringResource(R.string.sync_downloads_tab_queue, state.active.size)) },
            )
            FilterChip(
                selected = state.selectedTab == DownloadManagementTab.HISTORY,
                onClick = { onSelectTab(DownloadManagementTab.HISTORY) },
                label = { Text(stringResource(R.string.sync_downloads_tab_history, state.history.size)) },
            )
        }
    }
}

@Composable
private fun QueueContent(
    state: DownloadManagementUiState,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onClearQueue: () -> Unit,
) {
    if (!state.isLoading && state.active.isEmpty()) {
        EmptyDownloads(
            title = stringResource(R.string.sync_downloads_empty_queue_title),
            subtitle = stringResource(R.string.sync_downloads_empty_queue_subtitle),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "clear_queue") {
            ClearButton(
                label = stringResource(R.string.sync_downloads_clear_queue),
                onClick = onClearQueue,
                confirm = stringResource(R.string.sync_downloads_clear_queue_confirm),
            )
        }
        if (state.downloading.isNotEmpty()) {
            item(key = "downloading_label") { SectionLabel(stringResource(R.string.sync_downloads_section_downloading)) }
            items(state.downloading, key = { "active_${it.queueId}" }) { item ->
                DownloadRowCard(item, onCancel = onCancel, onRetry = onRetry)
            }
        }
        if (state.queued.isNotEmpty()) {
            item(key = "queued_label") { SectionLabel(stringResource(R.string.sync_downloads_section_up_next)) }
            itemsIndexed(state.queued, key = { _, item -> "queued_${item.queueId}" }) { index, item ->
                DownloadRowCard(
                    item = item,
                    queuePosition = index + 1,
                    onCancel = onCancel,
                    onRetry = onRetry,
                )
            }
        }
        if (state.waiting.isNotEmpty()) {
            item(key = "waiting_label") { SectionLabel(stringResource(state.waitingLabelRes())) }
            items(state.waiting, key = { "waiting_${it.queueId}" }) { item ->
                DownloadRowCard(item, onCancel = onCancel, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun HistoryContent(
    state: DownloadManagementUiState,
    onRetry: (Long) -> Unit,
    onClearHistory: () -> Unit,
) {
    if (!state.isLoading && state.history.isEmpty()) {
        EmptyDownloads(
            title = stringResource(R.string.sync_downloads_empty_history_title),
            subtitle = stringResource(R.string.sync_downloads_empty_history_subtitle),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "clear_history") {
            ClearButton(label = stringResource(R.string.sync_downloads_clear_history), onClick = onClearHistory)
        }
        item(key = "recent_label") { SectionLabel(stringResource(R.string.sync_downloads_section_recent)) }
        items(state.history, key = { "history_${it.queueId}" }) { item ->
            DownloadRowCard(item, onCancel = {}, onRetry = onRetry)
        }
    }
}

@Composable
private fun DownloadRowCard(
    item: DownloadManagementItem,
    queuePosition: Int? = null,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
) {
    val extendedColors = StashTheme.extendedColors
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = extendedColors.glassBackground,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, extendedColors.glassBorder),
    ) {
        DownloadManagementRow(
            item = item,
            queuePosition = queuePosition,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

/**
 * A quiet text button at the end of the list's first line. With [confirm] it asks
 * first: clearing can't be undone.
 */
@Composable
private fun ClearButton(label: String, onClick: () -> Unit, confirm: String? = null) {
    var asking by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        androidx.compose.material3.TextButton(onClick = { if (confirm == null) onClick() else asking = true }) { Text(label) }
    }
    if (asking && confirm != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.sync_downloads_clear_confirm_title, label)) },
            text = { Text(confirm) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { asking = false; onClick() }) { Text(label) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { asking = false }) { Text(stringResource(R.string.sync_downloads_keep)) }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyDownloads(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
