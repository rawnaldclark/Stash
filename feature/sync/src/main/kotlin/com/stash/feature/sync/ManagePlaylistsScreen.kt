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
import com.stash.core.data.mix.LastFmRecommendationState
import com.stash.core.ui.theme.StashTheme
import com.stash.core.common.extensions.pluralize

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
    // Issue #255 — the Last.fm manage surface is driven by recommendation
    // state, not by imported-playlist lists.
    val lastFmState by viewModel.lastFmState.collectAsStateWithLifecycle()

    // Both source playlist types share the same shape but are distinct classes;
    // map into one source-agnostic row so the rest of the screen ignores which.
    val rows: List<ManageRow> = if (source == SyncSource.SPOTIFY) {
        uiState.spotifyPlaylists.map { ManageRow(it.id, it.name, it.trackCount, it.type, it.syncEnabled, it.hideFromHome) }
    } else if (source == SyncSource.YOUTUBE) {
        uiState.youTubePlaylists.map { ManageRow(it.id, it.name, it.trackCount, it.type, it.syncEnabled, it.hideFromHome) }
    } else {
        emptyList()
    }

    val accent = when (source) {
        SyncSource.SPOTIFY -> StashTheme.extendedColors.spotifyGreen
        SyncSource.YOUTUBE -> StashTheme.extendedColors.youtubeRed
        SyncSource.LASTFM -> StashTheme.extendedColors.lastfmRed
    }
    val title = when (source) {
        SyncSource.SPOTIFY -> "Spotify playlists"
        SyncSource.YOUTUBE -> "YouTube Music playlists"
        SyncSource.LASTFM -> "Last.fm"
    }

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

    val bySegment = customAll.filter { matchesSegment(segment, it.syncEnabled) }
    // The query filters every section, not just "Your playlists": accounts can
    // have 100+ auto mixes, and an unfiltered Liked/Mixes block pushes the
    // filtered custom results off-screen - making search look broken (#310).
    //
    // The SEGMENT reaches every section whose switch is sync state, for the same
    // reason: it used to filter the custom list alone, so "Off" left the Liked
    // row and every mix on screen wearing an ON switch and read as broken
    // (#373). Mix rows are excluded rather than filtered — their switch is a
    // different axis (see showMixRows).
    val q = query.trim()
    fun matchesQuery(row: ManageRow) = q.isBlank() || row.name.contains(q, ignoreCase = true)
    val visibleLiked = liked?.takeIf { matchesQuery(it) && matchesSegment(segment, it.syncEnabled) }
    val visibleMixes = if (showMixRows(segment)) mixes.filter { matchesQuery(it) } else emptyList()
    val visibleCustom = bySegment.filter { matchesQuery(it) }

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
        if (source == SyncSource.LASTFM) {
            // Issue #255 — Last.fm has no importable playlists; its manage
            // surface is the single Recommendations toggle + explainer.
            LastFmManageContent(
                state = lastFmState,
                onToggle = viewModel::onLastFmRecommendationsToggled,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            return@Scaffold
        }
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
                if (visibleLiked != null) {
                    item(key = "liked-label") { ManageSectionLabel("Liked") }
                    item(key = "liked-row") {
                        SpotifySyncToggleRow(
                            name = visibleLiked.name,
                            trackCount = visibleLiked.trackCount,
                            enabled = visibleLiked.syncEnabled,
                            onToggle = { viewModel.onTogglePlaylistSync(visibleLiked.id, it) },
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
                // The section header always renders, even with no mixes yet, so
                // the discovery switch is reachable BEFORE the first sync pulls
                // any in — otherwise the only way to stop mixes appearing is to
                // let them appear first (#335, #344).
                item(key = "mixes-label") { ManageSectionLabel("Mixes (auto)") }
                item(key = "mixes-discovery") {
                    DiscoverMixesRow(
                        enabled = uiState.discoverAutoMixes,
                        onChange = viewModel::onDiscoverAutoMixesChanged,
                    )
                }
                if (visibleMixes.isNotEmpty()) {
                    item(key = "mixes-summary") {
                        Text(
                            text = "${pluralize(visibleMixes.size, "mix", "mixes")} · surfaced on Home",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(visibleMixes, key = { "mix-${it.id}" }) { mix ->
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
internal enum class ManageSegment(val label: String) {
    ALL("All"),
    SYNCED("Synced"),
    OFF("Off"),
}

/**
 * Whether a row belongs in [segment]. Only meaningful for rows whose switch IS
 * the sync state — the Liked row and the user's own playlists.
 *
 * One predicate, both call sites: the segment used to be applied inline to the
 * custom list only, so the Liked row silently fell through and an enabled
 * playlist survived the "Off" chip (#373).
 */
internal fun matchesSegment(segment: ManageSegment, syncEnabled: Boolean): Boolean =
    when (segment) {
        ManageSegment.ALL -> true
        ManageSegment.SYNCED -> syncEnabled
        ManageSegment.OFF -> !syncEnabled
    }

/**
 * Whether mix ROWS belong under [segment].
 *
 * A mix row's switch is `hideFromHome` ("shown on Home"), not sync state, so a
 * sync-state chip has nothing honest to say about it — filtering mixes by
 * `syncEnabled` would land every one of them under "Off" still wearing an
 * ON-looking switch, which is the same confusion #373 reported. They show
 * under "All" only.
 *
 * The section's HEADER and discovery switch are deliberately NOT gated on this
 * — see the mixes-label item for why they must stay reachable (#335/#344).
 */
internal fun showMixRows(segment: ManageSegment): Boolean = segment == ManageSegment.ALL

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

        // The toggle row mirrors SpotifySyncToggleRow's shape so all three
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
