package com.stash.feature.home

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.stash.core.ui.theme.SpaceGrotesk
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.mix.MixBuildState
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.ui.components.AlbumSquareCard
import com.stash.core.ui.components.CardRail
import com.stash.core.ui.components.CrispChipRow
import com.stash.core.ui.components.DiscoverHeroCard
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.RankedAlbumList
import com.stash.core.ui.components.RankedAlbumUi
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.components.SourceIndicator
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.PlaylistSummary
import com.stash.core.ui.theme.LocalIsDarkTheme
import com.stash.core.ui.components.streaming.StreamingModeChip
import com.stash.core.ui.components.streaming.StreamingModeSheet
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.stash.core.ui.theme.StashTheme

/**
 * Home screen composable displaying a premium dark dashboard with sync
 * status, daily mixes, recently added tracks, liked songs, and playlists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToPlaylist: (Long) -> Unit = {},
    onNavigateToAlbum: (AlbumSummary) -> Unit = {},
    onSeeAllPlaylists: (String) -> Unit = {},
    onNavigateToMixBuilder: (Long?) -> Unit = {},
    // Task 7 wires the actual mix-browse destination; today a no-op from the host.
    onSeeAllMixes: (MixRail) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    // Long-pressed Stash mix whose action sheet is open (null = closed).
    var actionSheetMixId by remember { mutableStateOf<Long?>(null) }
    // Long-pressed Daily Discover hero → its own two-row sheet (refresh / minimize).
    var heroActionSheet by remember { mutableStateOf(false) }
    // Opening a mix = open its materialized playlist + freshen it if stale.
    // A HomeMix's id IS its playlist id, so this reuses the existing playlist nav.
    val openMix: (Long) -> Unit = { id ->
        viewModel.refreshMixIfStale(id)
        onNavigateToPlaylist(id)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val heroMinimized by viewModel.heroMinimized.collectAsStateWithLifecycle()
    // Master streaming-mode flag. Both the top-bar StreamingModeChip and
    // the sheet (StreamingModeSheet) render from this single source of
    // truth; the chip itself early-returns to nothing while the build-
    // time kill-switch (StashConstants.STREAMING_ENGINE_ENABLED) is off.
    val streamingEnabled by viewModel.streamingEnabled.collectAsStateWithLifecycle()

    // Bottom-sheet state for the playback-mode picker triggered by the
    // top-bar chip. The sheet is the chip's tap target — keeps the chip
    // a single thumb-friendly icon-and-label while still routing a flip
    // through the OnlineOfflinePicker so the user explicitly chooses a
    // tile rather than accidentally toggling mid-scroll.
    var showStreamingSheet by remember { mutableStateOf(false) }
    val streamingSheetState = rememberModalBottomSheetState()

    // One-time privacy disclosure dialog for streaming. Shown the first
    // time the user enables streaming; the ViewModel persists the
    // "seen" flag and emits a Unit signal on the SharedFlow. The toggle
    // itself flips instantly — this dialog is informational, not a
    // confirmation gate.
    var showStreamingDisclosure by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.showStreamingDisclosure.collect {
            showStreamingDisclosure = true
        }
    }

    val toastContext = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { msg ->
            android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        // ── App title row: wordmark + social icons ────────────────────
        // v0.9.13: empty space to the right of the wordmark holds quick
        // links to the project (GitHub, X). Supporter pill moves back
        // to its own full-width row below.
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isDark = LocalIsDarkTheme.current
                Image(
                    painter = painterResource(
                        id = if (isDark) R.drawable.wordmark_stash_dark
                        else R.drawable.wordmark_stash_light,
                    ),
                    contentDescription = "Stash",
                    modifier = Modifier.height(48.dp),
                )
                Spacer(modifier = Modifier.weight(1f))

                // Streaming-mode quick-access chip. Tap → opens the
                // StreamingModeSheet for the picker. Gated on
                // STREAMING_ENGINE_ENABLED inside the composable so it
                // renders nothing when the flag is off.
                StreamingModeChip(
                    streamingEnabled = streamingEnabled,
                    onClick = { showStreamingSheet = true },
                )

                Spacer(modifier = Modifier.width(8.dp))

                val socialUriHandler = LocalUriHandler.current
                androidx.compose.material3.IconButton(
                    onClick = { socialUriHandler.openUri(STASH_DISCORD_URL) },
                    modifier = Modifier.size(40.dp),
                ) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            id = R.drawable.ic_discord,
                        ),
                        contentDescription = "Join the Stash Discord",
                        tint = androidx.compose.ui.graphics.Color(0xFF5865F2),
                        modifier = Modifier.size(20.dp),
                    )
                }

                androidx.compose.material3.IconButton(
                    onClick = { socialUriHandler.openUri(STASH_ISSUE_URL) },
                    modifier = Modifier.size(40.dp),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = "Report an issue on GitHub",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // ── Supporter pill (full row) ─────────────────────────────────
        // v0.9.13: live data from TipJarRepository. Tap → ko-fi.
        item {
            val tipJar = uiState.tipJar
            val pillSupporters = remember(tipJar) {
                val live = tipJar.supporters.map {
                    Supporter(name = it.name, amount = "$${it.amountUsd}", message = it.message)
                }
                // Legacy donors pre-date the Ko-fi webhook and exist nowhere
                // in the Worker's KV — they always ride the tape (deduped in
                // case they ever re-donate through the live pipeline).
                val liveNames = live.map { it.name.lowercase() }.toSet()
                live + LEGACY_SUPPORTERS.filter { it.name.lowercase() !in liveNames }
            }
            // Edge-to-edge: the ticker runs the full screen width, no card
            // chrome — maximum runway for the scrolling messages.
            SupporterTicker(
                supporters = pillSupporters,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            )
        }

        // ── Powered-by-ARCOD strip: removed 2026-07-01 while ARCOD is parked
        // (host down for us). PartnerStrip + ArcodPartner kept for re-enabling.

        // ── Lossless connect nudge ───────────────────────────────────
        // Shown when the user has lossless toggled OFF and hasn't
        // dismissed. Tap routes to Settings; X dismisses forever.
        // Stacks below the Last.fm banner if both apply.
        uiState.losslessPrompt?.let {
            item {
                Spacer(Modifier.height(6.dp))
                LosslessConnectBanner(
                    onSetUp = {
                        viewModel.requestSettingsLosslessFocus()
                        onNavigateToSettings()
                    },
                    onDismiss = viewModel::dismissLosslessBanner,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // ── Re-tagging library (metadata backfill progress) ──────────
        // v0.9.35: surfaces MetadataBackfillWorker progress on upgrade
        // so users know why disk IO / yt-dlp activity is happening. The
        // banner renders Hidden in the steady state (post-backfill); the
        // 2-second "Done" pulse self-acks via LaunchedEffect inside the
        // composable.
        if (uiState.metadataBackfillBanner !is com.stash.feature.home.banner.MetadataBackfillBannerState.Hidden) {
            item {
                Spacer(Modifier.height(6.dp))
                com.stash.feature.home.banner.MetadataBackfillBanner(
                    state = uiState.metadataBackfillBanner,
                    onFinishedAcknowledged = viewModel::onMetadataBackfillFinishedAcknowledged,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // ── Discover hero pager (Daily Discover + your Stash mixes) ──
        // A mix created from the hero's ＋ ring lands right here as an
        // identical sibling card — swipe between Daily Discover and your
        // own mixes (dots below signal the pages). Long-press a Your-mix
        // page for the action sheet (refresh / edit / delete / hide).
        // Replaces the old "Your mixes" rail.
        item {
            Spacer(Modifier.height(6.dp))
            // Minimized (long-press → "Minimize for now") drops the hero page
            // for the session; the mix pages keep the pager alive. Distinct
            // from hero == null (not connected), which shows PersonalizeCard.
            val hero = if (heroMinimized) null else uiState.hero
            val mixPages = uiState.yourMixes
            when {
                uiState.isLoading -> DiscoverHeroCard(
                    label = "Daily discovery",
                    title = "Discover",
                    subtitle = "",
                    artUrl = null,
                    onPlay = {},
                    onOpen = {},
                    loading = true,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                uiState.hero == null && mixPages.isEmpty() -> PersonalizeCard(
                    onConnect = onNavigateToSettings,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                hero == null && mixPages.isEmpty() -> Unit // minimized, nothing to page
                else -> {
                    val heroPages = if (hero != null) 1 else 0
                    val pageCount = heroPages + mixPages.size
                    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                        pageCount = { pageCount },
                    )
                    Column {
                        androidx.compose.foundation.pager.HorizontalPager(
                            state = pagerState,
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            pageSpacing = 10.dp,
                        ) { page ->
                            if (page < heroPages && hero != null) {
                                DiscoverHeroCard(
                                    label = "Daily discovery",
                                    title = hero.title,
                                    subtitle = hero.subtitle,
                                    artUrl = hero.artUrl,
                                    onPlay = viewModel::playHero,
                                    onOpen = { onNavigateToPlaylist(hero.playlistId) },
                                    onCreateMix = { onNavigateToMixBuilder(null) },
                                    onLongPress = { heroActionSheet = true },
                                )
                            } else {
                                val m = mixPages[page - heroPages]
                                DiscoverHeroCard(
                                    label = "Your mix",
                                    title = m.title,
                                    subtitle = when (m.buildState) {
                                        MixBuildState.BUILDING -> "building…"
                                        MixBuildState.EMPTY -> "empty — edit to fill"
                                        else -> "${m.trackCount} tracks"
                                    },
                                    artUrl = m.artUrl,
                                    onPlay = { viewModel.playMix(m.id) },
                                    onOpen = { openMix(m.id) },
                                    onLongPress = { actionSheetMixId = m.id },
                                )
                            }
                        }
                        if (pageCount > 1) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                repeat(pageCount) { i ->
                                    val active = pagerState.currentPage == i
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 3.dp)
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (active) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Genre filter chips ───────────────────────────────────────
        // Steer ONLY the Qobuz discovery rows below, so they sit with
        // them — directly under the hero, above New Releases — instead of
        // topping the whole page (they never affected the hero).
        item {
            Spacer(Modifier.height(14.dp))
            CrispChipRow(
                chips = uiState.genres.map { it.label },
                selected = uiState.selectedGenre,
                onSelect = viewModel::onSelectGenre,
            )
        }

        // ── Qobuz discovery rows (genre-filtered) ─────────────────────
        // These are what the genre chips at the top actually control, so
        // they sit directly under the hero; the imported/auto mix rails
        // follow. Each row renders only when it has content — a failed or
        // empty row (fail-soft repository) is simply omitted.
        if (uiState.newReleases.isNotEmpty()) {
            item {
                DiscoveryAlbumRow(
                    title = "New Releases",
                    albums = uiState.newReleases,
                    onOpen = onNavigateToAlbum,
                )
            }
        }
        if (uiState.playlists.isNotEmpty()) {
            item {
                DiscoveryPlaylistRow(
                    title = "Qobuz Playlists",
                    playlists = uiState.playlists,
                    onOpen = onNavigateToAlbum,
                    onSeeAll = { onSeeAllPlaylists(uiState.selectedGenre) },
                )
            }
        }
        if (uiState.topAlbums.isNotEmpty()) {
            item {
                // Collapsed to the top 5 by default; "Show all N" expands the
                // full best-sellers chart in place. Saveable so the choice
                // survives the item scrolling out of composition.
                var topAlbumsExpanded by rememberSaveable { mutableStateOf(false) }
                val visibleTop =
                    if (topAlbumsExpanded) uiState.topAlbums else uiState.topAlbums.take(5)
                Column {
                    Spacer(Modifier.height(16.dp))
                    SectionHeader(
                        title = "Top Albums",
                        actionText = when {
                            uiState.topAlbums.size <= 5 -> null
                            topAlbumsExpanded -> "Show less"
                            else -> "Show all ${uiState.topAlbums.size}"
                        },
                        onActionClick = { topAlbumsExpanded = !topAlbumsExpanded },
                    )
                    RankedAlbumList(
                        items = visibleTop.mapIndexed { i, a ->
                            RankedAlbumUi(
                                rank = i + 1,
                                title = a.title,
                                artist = a.artist,
                                artUrl = a.thumbnailUrl,
                                movement = null,   // Qobuz best-sellers carries no chart delta
                            )
                        },
                        onClick = { ranked -> onNavigateToAlbum(uiState.topAlbums[ranked.rank - 1]) },
                    )
                }
            }
        }

        // ── Mix rails (Made for you · Radios · Mood & decades · Your mixes) ──
        // Derived from the user's playlists + recipes (HomeViewModel.mixRail).
        // Each rail renders only when non-empty. "Your mixes" (Stash mixes)
        // additionally long-press → the action sheet below.
        if (uiState.madeForYou.isNotEmpty()) item {
            CardRail(
                title = "Made for you",
                actionText = "See all",
                onActionClick = { onSeeAllMixes(MixRail.MADE_FOR_YOU) },
            ) {
                items(uiState.madeForYou, key = { it.id }) { m ->
                    MixRailCard(
                        title = m.title, artUrl = m.artUrl, source = m.source,
                        buildState = m.buildState, onClick = { openMix(m.id) },
                        onLongPress = { actionSheetMixId = m.id },
                    )
                }
            }
        }
        if (uiState.radios.isNotEmpty()) item {
            CardRail(
                title = "Radios",
                actionText = "See all",
                onActionClick = { onSeeAllMixes(MixRail.RADIOS) },
            ) {
                items(uiState.radios, key = { it.id }) { m ->
                    MixRailCard(
                        title = m.title, artUrl = m.artUrl, source = m.source,
                        buildState = m.buildState, onClick = { openMix(m.id) },
                        onLongPress = { actionSheetMixId = m.id },
                    )
                }
            }
        }
        if (uiState.moodDecades.isNotEmpty()) item {
            CardRail(
                title = "Mood & decades",
                actionText = "See all",
                onActionClick = { onSeeAllMixes(MixRail.MOOD_DECADES) },
            ) {
                items(uiState.moodDecades, key = { it.id }) { m ->
                    MixRailCard(
                        title = m.title, artUrl = m.artUrl, source = m.source,
                        buildState = m.buildState, onClick = { openMix(m.id) },
                        onLongPress = { actionSheetMixId = m.id },
                    )
                }
            }
        }

    }

    // ── Streaming privacy disclosure (first-use only) ────────────────────
    if (showStreamingDisclosure) {
        com.stash.feature.home.streaming.StreamingDisclosureDialog(
            onDismiss = { showStreamingDisclosure = false },
        )
    }

    // ── Streaming-mode picker sheet (chip → bottom sheet) ──────────────
    // Opens from the top-bar chip. The picker writes through the same
    // applyStreamingMode path as Settings; here we also run the
    // first-time disclosure handshake (HomeViewModel.onStreamingToggle).
    // Sheet auto-dismisses on tile selection so a flip is exactly two
    // taps: chip → tile.
    if (showStreamingSheet) {
        StreamingModeSheet(
            streamingEnabled = streamingEnabled,
            onSelect = { requested ->
                viewModel.onStreamingToggle(requested)
                showStreamingSheet = false
            },
            onDismiss = { showStreamingSheet = false },
            sheetState = streamingSheetState,
        )
    }

    // ── Stash-mix action sheet (long-press a "Your mixes" card) ──────────
    // Moved from LibraryMixesSection (Library keeps its own copy until Task 8).
    // Gating mirrors Library: Refresh for every Stash mix, Edit/Delete for
    // custom mixes only, Open always. "Your mixes" == the STASH_MIX rail, so
    // membership there stands in for the type == STASH_MIX check.
    actionSheetMixId?.let { id ->
        // Guard render only — a stale id (mix vanished from every rail after a
        // delete/refresh re-emit) simply renders nothing; the next long-press
        // overwrites it. No state write during composition.
        val mix = (uiState.madeForYou + uiState.radios + uiState.moodDecades + uiState.yourMixes)
            .firstOrNull { it.id == id }
        if (mix != null) {
            val sheetState = rememberModalBottomSheetState()
            val isStashMix = uiState.yourMixes.any { it.id == id }
            val isCustom = id in uiState.customMixPlaylistIds
            ModalBottomSheet(
                onDismissRequest = { actionSheetMixId = null },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Text(
                        text = mix.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (isStashMix) {
                    MixActionRow(
                        icon = Icons.Default.Refresh,
                        label = "Refresh this mix",
                        onClick = {
                            viewModel.refreshMix(id)
                            actionSheetMixId = null
                        },
                    )
                }
                if (isCustom) {
                    MixActionRow(
                        icon = Icons.Default.Edit,
                        label = "Edit mix",
                        onClick = {
                            viewModel.editRecipeId(id) { recipeId -> onNavigateToMixBuilder(recipeId) }
                            actionSheetMixId = null
                        },
                    )
                    MixActionRow(
                        icon = Icons.Default.Delete,
                        label = "Delete mix",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            viewModel.deleteCustomMix(id)
                            actionSheetMixId = null
                        },
                    )
                }
                MixActionRow(
                    icon = Icons.Filled.RemoveCircleOutline,
                    label = "Hide from Home",
                    onClick = {
                        viewModel.setHideFromHome(id, true)
                        actionSheetMixId = null
                    },
                )
                MixActionRow(
                    icon = Icons.Default.PlayArrow,
                    label = "Open",
                    onClick = {
                        openMix(id)
                        actionSheetMixId = null
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // ── Daily Discover hero action sheet (long-press the hero page) ──────
    if (heroActionSheet) {
        val hero = uiState.hero
        if (hero != null) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { heroActionSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Text(
                        text = hero.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                MixActionRow(
                    icon = Icons.Default.Refresh,
                    label = "Refresh",
                    onClick = {
                        viewModel.refreshMix(hero.playlistId)
                        heroActionSheet = false
                    },
                )
                MixActionRow(
                    icon = Icons.Default.VisibilityOff,
                    label = "Minimize for now",
                    onClick = {
                        viewModel.setHeroMinimized(true)
                        heroActionSheet = false
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ── Mix action-sheet row ──────────────────────────────────────────────────

/** A single action row inside the Stash-mix action sheet. */
@Composable
private fun MixActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

// ── Lossless connect banner ──────────────────────────────────────────────

/**
 * "Try lossless audio" Home banner. Shows when the user has
 * lossless turned off (explicit save, since v0.9.8 fresh installs
 * default to ON) and hasn't dismissed. Tapping routes to Settings,
 * where the existing Audio Quality card hosts the toggle + captcha
 * setup flow.
 */
@Composable
private fun LosslessConnectBanner(
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSetUp)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Try lossless audio",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Studio-quality FLAC downloads via Qobuz. Tap to set up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Set up →",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss lossless banner",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ── Supporter pill ───────────────────────────────────────────────────────

private data class Supporter(
    val name: String,
    val amount: String,
    val message: String,
)

// v0.9.13: report-an-issue link shown as a wrench icon next to the
// wordmark on Home. Tap → GitHub new-issue form so users can file
// bugs without leaving the project. Edit when the repo URL changes.
private const val STASH_ISSUE_URL = "https://github.com/rawnaldclark/Stash/issues/new"

// v0.9.38+: Discord invite shown as a chat-bubble icon (blurple-tinted)
// to the left of the wrench. Tap → opens the invite in the default
// browser. Edit when the invite rotates.
private const val STASH_DISCORD_URL = "https://discord.gg/vcbjEby5PC"

private val LEGACY_SUPPORTERS = listOf(
    Supporter(
        name = "Cedric",
        amount = "$10",
        message = "Just downloaded Stash to replace Spotify. This is amazing bro. Thanks for your work.",
    ),
    Supporter(
        name = "Slowcab",
        amount = "$5",
        message = "Amazing work! Keep sticking it to the man!",
    ),
    Supporter(
        name = "RucaNebas",
        amount = "$5",
        message = "Awesome application! I hope continuous improvement and support",
    ),
)

/**
 * Tip-jar ticker — dublab-style community marquee.
 *
 * One slim strip, ALL supporters flowing through it as a continuous
 * horizontal scroll (name · amount, then their message, ✦-separated) —
 * grassroots radio-station energy instead of a rotating card. Tap
 * anywhere opens ko-fi; goal/progress tracking lives there.
 */
@Composable
private fun SupporterTicker(
    supporters: List<Supporter>,
    modifier: Modifier = Modifier,
) {
    if (supporters.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    val extendedColors = StashTheme.extendedColors

    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    // Supporters woven with station announcements (mission line, dev and
    // contributor calls) so the wire carries variety, not just gratitude.
    // Separator: a dimmed interpunct — no color pop, so the
    // strip stays quiet inside the app's palette.
    val line = remember(supporters, ink, dim) {
        val segments = buildTickerSegments(
            supporters.map { TickerSegment.Shoutout(it.name, it.amount, it.message) },
        )
        buildAnnotatedString {
            segments.forEach { seg ->
                when (seg) {
                    is TickerSegment.Shoutout -> {
                        withStyle(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold)) {
                            append("${seg.name} · ${seg.amount}")
                        }
                        if (seg.message.isNotBlank()) {
                            withStyle(SpanStyle(color = dim, fontStyle = FontStyle.Italic)) {
                                append("  “${seg.message}”")
                            }
                        }
                    }
                    is TickerSegment.Announcement -> {
                        // Station voice: quiet by design — muted ink, regular
                        // weight (names are bold, quotes italic; this murmurs).
                        withStyle(SpanStyle(color = dim)) {
                            append(seg.text)
                        }
                    }
                }
                // Entry separator: the app's own interpunct, dimmed — no
                // color pop, just a quiet beat of air between voices.
                withStyle(SpanStyle(color = dim.copy(alpha = 0.5f))) { append("    ·    ") }
            }
        }
    }

    // Wall-clock marquee: offset is a pure function of elapsed-since-epoch
    // (see marqueeOffsetPx), so the tape RESUMES mid-flow after the item
    // leaves and re-enters composition. basicMarquee restarted from zero on
    // every return to the top of Home — which is why announcements deep in
    // the tape were never seen. rememberSaveable pins the epoch across
    // lazy-item disposal.
    val epochMs = rememberSaveable { System.currentTimeMillis() }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { nowMs = System.currentTimeMillis() }
        }
    }
    val velocityPxPerSec = with(LocalDensity.current) { 31.dp.toPx() }
    var tapeWidthPx by remember { mutableIntStateOf(0) }

    // Full-bleed strip: no card, no border, no leading icon — every pixel
    // of width belongs to the tape.
    Surface(
        modifier = modifier.clickable { uriHandler.openUri("https://ko-fi.com/rawnald") },
        // AMOLED: the glass tint would keep the strip's pixels lit — let the
        // pure-black ground show through instead.
        color = if (com.stash.core.ui.theme.LocalIsAmoledTheme.current) {
            Color.Transparent
        } else {
            extendedColors.glassBackground
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .clipToBounds(),
        ) {
            Row(
                modifier = Modifier
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    .graphicsLayer {
                        translationX = -marqueeOffsetPx(nowMs - epochMs, velocityPxPerSec, tapeWidthPx)
                    },
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.onSizeChanged { tapeWidthPx = it.width },
                )
                // Second copy of the tape for a seamless wrap.
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

// \u2500\u2500 Qobuz discovery rows \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

/** Horizontal carousel of Qobuz albums (New Releases). Tap \u2192 album detail. */
@Composable
private fun DiscoveryAlbumRow(
    title: String,
    albums: List<AlbumSummary>,
    onOpen: (AlbumSummary) -> Unit,
) {
    Column {
        Spacer(Modifier.height(16.dp))
        SectionHeader(title = title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums) { album ->
                AlbumSquareCard(
                    title = album.title,
                    artist = album.artist,
                    thumbnailUrl = album.thumbnailUrl,
                    year = album.year,
                    isLossless = true,
                    onClick = { onOpen(album) },
                )
            }
        }
    }
}

/**
 * Horizontal carousel of Qobuz community playlists. Each card carries the
 * curator as its subtitle and opens via a QOBUZ_PLAYLIST [AlbumSummary] so the
 * existing album-detail screen renders + plays it.
 */
@Composable
private fun DiscoveryPlaylistRow(
    title: String,
    playlists: List<PlaylistSummary>,
    onOpen: (AlbumSummary) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column {
        Spacer(Modifier.height(16.dp))
        SectionHeader(title = title, actionText = "See all", onActionClick = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(playlists) { playlist ->
                AlbumSquareCard(
                    title = playlist.title,
                    // Curator is always the regional Qobuz account ("Qobuz France")
                    // — redundant on every card, so show the track count instead.
                    artist = "${playlist.trackCount} tracks",
                    thumbnailUrl = playlist.thumbnailUrl,
                    year = null,
                    isLossless = true,
                    onClick = { onOpen(playlist.toAlbumNav()) },
                )
            }
        }
    }
}

