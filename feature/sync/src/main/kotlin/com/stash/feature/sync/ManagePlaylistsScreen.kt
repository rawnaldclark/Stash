package com.stash.feature.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.PlaylistType
import com.stash.core.ui.theme.StashTheme

/**
 * Full-screen, search-first playlist management for one sync source. Hosts the
 * per-playlist sync toggles that used to live inline on the Sync-tab source
 * card, plus a hide-from-Home toggle for algo mixes — so the Sync landing stays
 * a compact dashboard and the (possibly 100+) playlist list is virtualized here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagePlaylistsScreen(
    source: SyncSource,
    onBack: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Both source playlist types share the same shape but are distinct classes;
    // map into one source-agnostic row so the rest of the screen ignores which.
    val rows: List<ManageRow> = if (source == SyncSource.SPOTIFY) {
        uiState.spotifyPlaylists.map { ManageRow(it.id, it.name, it.trackCount, it.type, it.syncEnabled, it.hideFromHome) }
    } else {
        uiState.youTubePlaylists.map { ManageRow(it.id, it.name, it.trackCount, it.type, it.syncEnabled, it.hideFromHome) }
    }

    val accent = if (source == SyncSource.SPOTIFY) {
        StashTheme.extendedColors.spotifyGreen
    } else {
        StashTheme.extendedColors.youtubeRed
    }
    val title = if (source == SyncSource.SPOTIFY) "Spotify playlists" else "YouTube Music playlists"

    var query by remember { mutableStateOf("") }
    var segment by remember { mutableStateOf(ManageSegment.ALL) }

    val liked = rows.firstOrNull { it.type == PlaylistType.LIKED_SONGS }
    val mixes = rows.filter { it.type == PlaylistType.DAILY_MIX }
    val customAll = rows.filter { it.type == PlaylistType.CUSTOM }
    val customEnabled = customAll.count { it.syncEnabled }

    // Land at the true top once playlists load. The sections fill in async:
    // the first frame has empty data (only the custom section), then Liked +
    // Mixes get prepended when the flow emits. With stable item keys LazyColumn
    // anchors to the already-shown custom section, leaving Liked + Mixes
    // scrolled off above the fold — so snap back to item 0 on the 0→N load.
    val listState = rememberLazyListState()
    LaunchedEffect(rows.isEmpty()) {
        if (rows.isNotEmpty()) listState.scrollToItem(0)
    }

    val bySegment = when (segment) {
        ManageSegment.ALL -> customAll
        ManageSegment.SYNCED -> customAll.filter { it.syncEnabled }
        ManageSegment.OFF -> customAll.filter { !it.syncEnabled }
    }
    val visibleCustom = if (query.isBlank()) {
        bySegment
    } else {
        bySegment.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // -- Pinned search (stays fixed above the scrolling list) ----------
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Filter playlists…") },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = accent,
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear filter")
                        }
                    }
                } else null,
            )

            // -- Segment filter (applies to the custom "Your playlists" list) --
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ManageSegment.values().forEach { seg ->
                    FilterChip(
                        selected = segment == seg,
                        onClick = { segment = seg },
                        label = { Text(seg.label) },
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // -- Liked ------------------------------------------------------
                if (liked != null) {
                    item(key = "liked-label") { ManageSectionLabel("Liked") }
                    item(key = "liked-row") {
                        SpotifySyncToggleRow(
                            name = liked.name,
                            trackCount = liked.trackCount,
                            enabled = liked.syncEnabled,
                            onToggle = { viewModel.onTogglePlaylistSync(liked.id, it) },
                        )
                    }
                    if (source == SyncSource.YOUTUBE) {
                        item(key = "liked-studio") {
                            StudioOnlyToggleRow(
                                enabled = uiState.youtubeLikedStudioOnly,
                                onChange = viewModel::onYoutubeLikedStudioOnlyChanged,
                            )
                        }
                    }
                }

                // -- Mixes (auto) ----------------------------------------------
                if (mixes.isNotEmpty()) {
                    item(key = "mixes-label") { ManageSectionLabel("Mixes (auto)") }
                    item(key = "mixes-summary") {
                        Text(
                            text = "${mixes.size} mixes · surfaced on Home",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(mixes, key = { "mix-${it.id}" }) { mix ->
                        MixHideRow(
                            name = mix.name,
                            hideFromHome = mix.hideFromHome,
                            // Switch is inverted: ON = shown on Home → toggling
                            // OFF hides it (hidden = !shown).
                            onToggleShown = { shown -> viewModel.onToggleHideFromHome(mix.id, !shown) },
                        )
                    }
                }

                // -- Your playlists --------------------------------------------
                item(key = "custom-label") {
                    ManageSectionLabel("Your playlists · $customEnabled/${customAll.size}")
                }
                item(key = "custom-actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            visibleCustom.forEach { viewModel.onTogglePlaylistSync(it.id, true) }
                        }) { Text("Enable all") }
                        TextButton(onClick = {
                            visibleCustom.forEach { viewModel.onTogglePlaylistSync(it.id, false) }
                        }) { Text("Enable none") }
                    }
                }
                if (visibleCustom.isEmpty()) {
                    item(key = "custom-empty") {
                        Text(
                            text = if (query.isNotBlank())
                                "No playlists matching “${query.trim()}”"
                            else
                                "No playlists here",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    items(visibleCustom, key = { "custom-${it.id}" }) { pl ->
                        SpotifySyncToggleRow(
                            name = pl.name,
                            trackCount = pl.trackCount,
                            enabled = pl.syncEnabled,
                            onToggle = { viewModel.onTogglePlaylistSync(pl.id, it) },
                        )
                    }
                }

                item(key = "bottom-spacer") { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

/** Source-agnostic view of one playlist row (both source types map into this). */
private data class ManageRow(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val type: PlaylistType,
    val syncEnabled: Boolean,
    val hideFromHome: Boolean,
)

/** Segment filter over the custom "Your playlists" list. */
private enum class ManageSegment(val label: String) {
    ALL("All"),
    SYNCED("Synced"),
    OFF("Off"),
}

@Composable
private fun ManageSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Hide-from-Home toggle for an auto mix. The switch reads inverted:
 * checked = shown on Home, so switching OFF hides the mix. No sync toggle —
 * mixes auto-sync.
 */
@Composable
private fun MixHideRow(
    name: String,
    hideFromHome: Boolean,
    onToggleShown: (Boolean) -> Unit,
) {
    val shown = !hideFromHome
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleShown(!shown) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (shown) "Shown on Home" else "Hidden from Home",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        com.stash.core.ui.components.StashSwitch(
            checked = shown,
            onCheckedChange = onToggleShown,
        )
    }
}
