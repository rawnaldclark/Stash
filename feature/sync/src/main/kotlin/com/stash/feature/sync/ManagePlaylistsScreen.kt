package com.stash.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.mix.LastFmRecommendationState
import com.stash.core.model.PlaylistType
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.sync.components.CoverTile
import com.stash.feature.sync.components.InLibraryCheck
import com.stash.feature.sync.components.SaveCircle
import com.stash.feature.sync.components.SyncAmbientBackdrop

/** Which of an account's lists is showing. */
private enum class ManageTab { PLAYLISTS, MIXES, LIKED }

/**
 * One account's playlists, mixes and liked songs, every one with its cover and
 * a Save circle. In Stream only all of it is already in the library, and Save
 * keeps a playlist on the phone too; in Download mode Save is how anything gets
 * there. Save is the playlist page's Download switch ([SyncViewModel.onSavePlaylist]),
 * so the two always agree. The Sync tab overhaul, 2026-10-29: this used to be
 * a list of on/off switches whose meaning changed with the mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagePlaylistsScreen(
    source: SyncSource,
    onBack: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Issue #255: the Last.fm manage surface is driven by recommendation state.
    val lastFmState by viewModel.lastFmState.collectAsStateWithLifecycle()
    val streamOnly by viewModel.streamingEnabled.collectAsStateWithLifecycle()

    if (source == SyncSource.LASTFM) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Last.fm") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.sync_back))
                        }
                    },
                )
            },
        ) { innerPadding ->
            LastFmManageContent(
                state = lastFmState,
                onToggle = viewModel::onLastFmRecommendationsToggled,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        }
        return
    }

    val rows = uiState.playlistRows().filter { it.source == source }
    val liked = rows.filter { it.type == PlaylistType.LIKED_SONGS }
    val mixes = rows.filter { it.type == PlaylistType.DAILY_MIX }
    val playlists = rows.filter { it.type != PlaylistType.DAILY_MIX && it.type != PlaylistType.LIKED_SONGS }
    val name = if (source == SyncSource.SPOTIFY) "Spotify" else "YouTube Music"
    val brand = if (source == SyncSource.SPOTIFY) StashTheme.extendedColors.spotifyGreen else StashTheme.extendedColors.youtubeRed

    var tab by rememberSaveable { mutableStateOf(ManageTab.PLAYLISTS) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    // The query reaches every tab, so search never looks broken behind a tab (#310).
    fun matches(row: SyncPlaylistRow) = q.isBlank() || row.name.contains(q, ignoreCase = true)
    val shown = when (tab) {
        ManageTab.PLAYLISTS -> playlists
        ManageTab.MIXES -> mixes
        ManageTab.LIKED -> liked
    }.filter(::matches)

    // Land at the top once the list loads: rows arrive a frame after the screen.
    val listState = rememberLazyListState()
    LaunchedEffect(rows.isEmpty()) {
        if (rows.isNotEmpty()) listState.scrollToItem(0)
    }

    val arts = (playlists + mixes).mapNotNull { it.artUrl }.distinct()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SyncAmbientBackdrop(arts = arts.take(2), modifier = Modifier.height(400.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
        ) {
            item(key = "header") {
                Column {
                    IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sync_back),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(brand))
                                Text(
                                    text = stringResource(
                                        if (streamOnly) R.string.sync_manage_all_in_library else R.string.sync_manage_pick_to_save,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = name,
                                fontFamily = SpaceGrotesk,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 30.sp,
                                letterSpacing = (-0.5).sp,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        // Saves what's in the library; one taken out stays out.
                        val unsaved = shown.filter { !it.userHidden && !it.saved(streamOnly) }
                        if (tab == ManageTab.PLAYLISTS && unsaved.isNotEmpty()) {
                            TextButton(onClick = {
                                unsaved.forEach { viewModel.onSavePlaylist(it.id, true) }
                            }) { Text(stringResource(R.string.sync_manage_save_all)) }
                        }
                    }
                }
            }

            item(key = "search") {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            item(key = "tabs") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TabPill(
                        label = stringResource(R.string.sync_manage_tab_playlists, playlists.size),
                        selected = tab == ManageTab.PLAYLISTS,
                        onClick = { tab = ManageTab.PLAYLISTS },
                    )
                    TabPill(
                        label = stringResource(R.string.sync_manage_tab_mixes, mixes.size),
                        selected = tab == ManageTab.MIXES,
                        onClick = { tab = ManageTab.MIXES },
                    )
                    if (liked.isNotEmpty()) {
                        TabPill(
                            label = stringResource(R.string.sync_manage_tab_liked),
                            selected = tab == ManageTab.LIKED,
                            onClick = { tab = ManageTab.LIKED },
                        )
                    }
                }
            }

            // How much of this tab is in the library, and the bulk way in or out: an account
            // can bring 30 mixes nobody asked for.
            if (shown.isNotEmpty() && q.isBlank()) {
                item(key = "in-library-count") {
                    val inLibrary = shown.count { !it.userHidden }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.sync_manage_in_library_count, inLibrary, shown.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (inLibrary < shown.size) {
                            TextButton(onClick = {
                                shown.filter { it.userHidden }.forEach { viewModel.onSetInLibrary(it.id, true) }
                            }) { Text(stringResource(R.string.sync_manage_add_all)) }
                        }
                        if (inLibrary > 0) {
                            TextButton(onClick = {
                                shown.filterNot { it.userHidden }.forEach { viewModel.onSetInLibrary(it.id, false) }
                            }) { Text(stringResource(R.string.sync_manage_remove_all)) }
                        }
                    }
                }
            }

            // The discovery switch shows even with no mixes yet, so they can be
            // stopped before the first sync pulls any in (#335, #344).
            if (tab == ManageTab.MIXES) {
                item(key = "mixes-discovery") {
                    DiscoverMixesRow(
                        enabled = uiState.discoverAutoMixes,
                        onChange = viewModel::onDiscoverAutoMixesChanged,
                    )
                }
            }
            if (tab == ManageTab.LIKED && source == SyncSource.YOUTUBE) {
                item(key = "liked-studio") {
                    StudioOnlyToggleRow(
                        enabled = uiState.youtubeLikedStudioOnly,
                        onChange = viewModel::onYoutubeLikedStudioOnlyChanged,
                    )
                }
            }

            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (q.isNotBlank()) {
                            stringResource(R.string.sync_manage_no_match, q)
                        } else {
                            stringResource(R.string.sync_manage_empty)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(shown, key = { it.id }) { row ->
                    PlaylistSaveRow(
                        row = row,
                        saved = row.saved(streamOnly),
                        onToggle = { viewModel.onSavePlaylist(row.id, it) },
                        onSetInLibrary = { viewModel.onSetInLibrary(row.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistSaveRow(
    row: SyncPlaylistRow,
    saved: Boolean,
    onToggle: (Boolean) -> Unit,
    onSetInLibrary: (Boolean) -> Unit,
) {
    val inLibrary = !row.userHidden
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InLibraryCheck(
            inLibrary = inLibrary,
            playlistName = row.name,
            label = stringResource(R.string.sync_in_library),
            onState = stringResource(R.string.sync_in_library_on),
            offState = stringResource(R.string.sync_in_library_off),
            onToggle = onSetInLibrary,
        )
        CoverTile(url = row.artUrl, modifier = Modifier.size(56.dp).alpha(if (inLibrary) 1f else 0.4f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val songs = pluralStringResource(R.plurals.sync_songs_count, row.trackCount, row.trackCount)
            Text(
                text = when {
                    !inLibrary -> stringResource(R.string.sync_manage_not_in_library)
                    saved -> stringResource(R.string.sync_manage_on_phone, songs)
                    else -> songs
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (saved && inLibrary) StashTheme.extendedColors.cyanLight else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SaveCircle(
            saved = saved,
            playlistName = row.name,
            saveLabel = stringResource(R.string.sync_save),
            savedState = stringResource(R.string.sync_saved_state),
            notSavedState = stringResource(R.string.sync_not_saved_state),
            onToggle = onToggle,
        )
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) MaterialTheme.colorScheme.onBackground else StashTheme.extendedColors.glassBackgroundHover)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(StashTheme.extendedColors.glassBackgroundHover)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.sync_manage_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Clear, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Master switch for discovering the service's own algorithmic mixes.
 *
 * Distinct from [MixHideRow] below, and the difference is the whole point:
 * hiding a mix removes it from Home but sync still fetches it every run. This
 * stops them being fetched at all, which is what shortens the sync people were
 * complaining about (#344), and stops new ones appearing (#335).
 */
@Composable
private fun DiscoverMixesRow(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!enabled) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Discover mixes automatically",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (enabled) {
                    "Daily Mixes and similar are added as they appear"
                } else {
                    "Off — no new mixes are added, and syncs run shorter"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        com.stash.core.ui.components.StashSwitch(
            checked = enabled,
            onCheckedChange = onChange,
        )
    }
}

/**
 * What Home will actually do with the mix, in words. The rails show their 12
 * freshest mixes; an untouched mix surfaces when a sync adds to it, a pinned one
 * always leads, a hidden one never appears.
 */
internal fun homeVisibilityLabel(hidden: Boolean, pinned: Boolean): String = when {
    hidden -> "Hidden from Home"
    pinned -> "Pinned on Home"
    else -> "On Home when it updates"
}

// ── Last.fm (issue #255) ─────────────────────────────────────────────────────

/**
 * Manage surface for the Last.fm source. Last.fm has no public playlist
 * API, so there is nothing to import and nothing to list — the entire
 * source is the app-managed "Recommended by Last.fm" mix. This screen
 * hosts its toggle, an honest explainer of what the playlist is and where
 * it lands, and the connect hint when Last.fm isn't connected.
 */
@Composable
private fun LastFmManageContent(
    state: LastFmRecommendationState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Recommendations",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        // The toggle row mirrors the other manage rows' shape so all three
        // manage screens read as siblings.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = state.connected) { onToggle(!state.enabled) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Recommended by Last.fm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when {
                        !state.connected ->
                            "Connect Last.fm in Settings → Accounts to enable"
                        !state.enabled ->
                            "Off — no recommendations are generated"
                        else ->
                            "On · ${state.trackCount ?: 0} tracks synced"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            com.stash.core.ui.components.StashSwitch(
                checked = state.connected && state.enabled,
                onCheckedChange = onToggle,
                enabled = state.connected,
            )
        }

        Text(
            text = "Builds a rotating playlist of tracks Last.fm recommends, " +
                "seeded from what you play and scrobble most. Each refresh " +
                "swaps in fresh picks; tracks are matched against YouTube " +
                "Music / Spotify and downloaded like any other sync, " +
                "respecting your network settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "Find it as “Recommended by Last.fm” under Library → Mixes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(80.dp))
    }
}
