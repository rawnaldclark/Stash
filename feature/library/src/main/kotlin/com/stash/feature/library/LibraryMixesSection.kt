package com.stash.feature.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.stash.core.data.mix.MixBuildState
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.ui.components.SourceIndicator
import com.stash.core.ui.theme.StashTheme

/**
 * The "Mixes" group in Library — Stash mixes, per-source daily mixes, a
 * Create-mix card, and the Liked Songs card. Ported from Home's mix section
 * (visual identity preserved; the Premium Crisp reskin of Library is a later
 * sub-project). Long-pressing a custom mix opens an action sheet
 * (Refresh / Edit / Delete). The builtin Daily Discover is excluded upstream
 * (it becomes the Home hero), so it never appears here.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryMixesSection(
    stashMixes: List<Playlist>,
    spotifyMixes: List<Playlist>,
    youtubeMixes: List<Playlist>,
    likedPlaylists: List<Playlist>,
    customMixPlaylistIds: Set<Long>,
    buildingMixIds: Set<Long>,
    emptyMixIds: Set<Long>,
    onOpenPlaylist: (Long) -> Unit,
    onOpenLikedSongs: (String?) -> Unit,
    onPlayAllMixes: (MusicSource?) -> Unit,
    onRefreshMix: (Long) -> Unit,
    onEditMix: (Long) -> Unit,
    onDeleteMix: (Playlist) -> Unit,
    onCreateMix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The long-pressed custom mix whose action sheet is open (null = closed).
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }

    val allMixes = stashMixes + spotifyMixes + youtubeMixes
    if (allMixes.isEmpty() && likedPlaylists.isEmpty()) return

    fun buildStateFor(id: Long): MixBuildState = when {
        buildingMixIds.contains(id) -> MixBuildState.BUILDING
        emptyMixIds.contains(id) -> MixBuildState.EMPTY
        else -> MixBuildState.READY
    }

    Column(modifier = modifier.fillMaxWidth()) {
        MixesSectionHeader(
            showPlayBoth = spotifyMixes.isNotEmpty() && youtubeMixes.isNotEmpty(),
            onPlayBoth = { onPlayAllMixes(null) },
        )

        if (allMixes.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                allMixes.forEach { mix ->
                    DailyMixCard(
                        playlist = mix,
                        onClick = { onOpenPlaylist(mix.id) },
                        onLongPress = {
                            // Only custom mixes have an action sheet (refresh/edit/delete).
                            if (customMixPlaylistIds.contains(mix.id) ||
                                mix.type == PlaylistType.STASH_MIX
                            ) {
                                selectedPlaylist = mix
                            }
                        },
                        buildState = buildStateFor(mix.id),
                    )
                }
                CreateMixCard(onClick = onCreateMix)
            }
        }

        if (likedPlaylists.isNotEmpty()) {
            val spotifyLiked = likedPlaylists.filter { it.source == MusicSource.SPOTIFY }
            val youtubeLiked = likedPlaylists.filter { it.source == MusicSource.YOUTUBE }
            val spotifyCount = spotifyLiked.sumOf { it.trackCount }
            val youtubeCount = youtubeLiked.sumOf { it.trackCount }
            // Sum ALL liked playlists' real track counts — a local STASH_LIKED has
            // no Spotify/YouTube source, so the split-based sum would read 0.
            val totalLiked = likedPlaylists.sumOf { it.trackCount }
            // Source chips only make sense when both EXTERNAL sources contribute;
            // a single local collection shows a plain count, no chips.
            val bothSources = spotifyLiked.isNotEmpty() && youtubeLiked.isNotEmpty()
            val single = when {
                spotifyLiked.isNotEmpty() && youtubeLiked.isEmpty() -> MusicSource.SPOTIFY
                youtubeLiked.isNotEmpty() && spotifyLiked.isEmpty() -> MusicSource.YOUTUBE
                else -> null
            }
            Spacer(Modifier.height(4.dp))
            LikedSongsCard(
                totalCount = totalLiked,
                spotifyCount = spotifyCount,
                youtubeCount = youtubeCount,
                showSourceChips = bothSources,
                singleSource = single,
                onPlayAll = {},
                onPlaySpotify = {},
                onPlayYouTube = {},
                onClick = { onOpenLikedSongs(null) },
                onClickSpotify = { onOpenLikedSongs(MusicSource.SPOTIFY.name) },
                onClickYouTube = { onOpenLikedSongs(MusicSource.YOUTUBE.name) },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }

    // Action sheet for a long-pressed mix. Refresh (Stash mixes), Edit/Delete
    // (custom only), and Open — the mix-specific actions; playlist-level
    // play/queue/download live on the opened playlist screen.
    selectedPlaylist?.let { playlist ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedPlaylist = null },
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
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${playlist.trackCount} tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (playlist.type == PlaylistType.STASH_MIX) {
                HomeBottomSheetActionRow(
                    icon = Icons.Default.Refresh,
                    label = "Refresh this mix",
                    onClick = {
                        onRefreshMix(playlist.id)
                        selectedPlaylist = null
                    },
                )
            }
            if (customMixPlaylistIds.contains(playlist.id)) {
                HomeBottomSheetActionRow(
                    icon = Icons.Default.Edit,
                    label = "Edit mix",
                    onClick = {
                        onEditMix(playlist.id)
                        selectedPlaylist = null
                    },
                )
                HomeBottomSheetActionRow(
                    icon = Icons.Default.Delete,
                    label = "Delete mix",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        onDeleteMix(playlist)
                        selectedPlaylist = null
                    },
                )
            }
            HomeBottomSheetActionRow(
                icon = Icons.Default.PlayArrow,
                label = "Open",
                onClick = {
                    onOpenPlaylist(playlist.id)
                    selectedPlaylist = null
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Below: card composables ported verbatim from Home (feature/home/HomeScreen.kt).
// Visual identity preserved. Appended byte-for-byte by the port; do not restyle
// here — Library's Premium Crisp reskin is a separate sub-project.
// ─────────────────────────────────────────────────────────────────────────
// ── Daily mix card ───────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DailyMixCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    buildState: MixBuildState = MixBuildState.READY,
) {
    val extendedColors = StashTheme.extendedColors
    val gradientColors = if (playlist.source == MusicSource.SPOTIFY) {
        listOf(
            extendedColors.spotifyGreen.copy(alpha = 0.4f),
            Color.Transparent,
        )
    } else {
        listOf(
            extendedColors.youtubeRed.copy(alpha = 0.4f),
            Color.Transparent,
        )
    }

    Surface(
        modifier = modifier
            .width(180.dp)
            .height(120.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Album art background. For daily mixes with 2 tile URLs
            // (first 2 unique album covers from the current tracklist) we
            // render them side-by-side — the cover updates visibly every
            // sync that rotates tracks. Single-URL playlists render as a
            // single background as before.
            DailyMixCoverBackground(
                tileUrls = playlist.artTileUrls,
                fallback = playlist.artUrl,
                modifier = Modifier.fillMaxSize(),
            )
            // Gradient overlay for text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(gradientColors))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                        )
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                SourceIndicator(source = playlist.source, size = 8.dp)
                Column {
                    // Text always renders white because the card always has a
                    // dark bottom gradient overlay (Black alpha 0.6) by design.
                    // Using theme-aware onSurface would make the text disappear
                    // on light theme where onSurface is near-black.
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when (buildState) {
                        MixBuildState.BUILDING -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                color = Color.White.copy(alpha = 0.85f),
                                strokeWidth = 1.5.dp,
                            )
                            Text(
                                text = "Building…",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                        MixBuildState.EMPTY -> Text(
                            text = "No tracks found",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                        MixBuildState.READY -> Text(
                            text = "${playlist.trackCount} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

// ── Mixes section header with optional Play Both button ─────────────────

/**
 * Custom header for the "Your Mixes" section. Shows the title on the left
 * and an optional "Play Both" pill button on the right that plays every
 * mix from both Spotify and YouTube Music combined.
 *
 * The Play Both pill only renders when [showPlayBoth] is true, so users
 * connected to only one service see a plain header instead.
 *
 * @param showPlayBoth Whether to render the Play Both pill. True when the
 *   user has mixes from both sources.
 * @param onPlayBoth Callback invoked when the Play Both pill is tapped.
 */
@Composable
private fun MixesSectionHeader(
    showPlayBoth: Boolean,
    onPlayBoth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Your Mixes",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (showPlayBoth) {
            val accent = MaterialTheme.colorScheme.primary
            Surface(
                modifier = Modifier
                    .height(32.dp)
                    .clickable(onClick = onPlayBoth),
                color = accent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play mixes from both services",
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Play Both",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
            }
        }
    }
}


// ── Liked songs card (with source chips + smart collapse) ───────────────

/**
 * Featured card showing liked-songs across Spotify and YouTube Music.
 *
 * Layout:
 * - Tappable main row plays the combined pool (both sources).
 * - When [showSourceChips] is true (both sources have liked songs), a pair
 *   of tappable chips below the main row plays only one source at a time.
 * - When only one source contributes, chips are hidden and a small source
 *   indicator appears next to the title to identify which service the
 *   count represents.
 *
 * @param totalCount Combined liked-song count across both sources.
 * @param spotifyCount Spotify liked-song count (0 if none).
 * @param youtubeCount YouTube liked-song count (0 if none).
 * @param showSourceChips Whether to render per-source chips.
 * @param singleSource The sole contributing source when [showSourceChips] is
 *   false, used to label the card; null when both or neither source contributes.
 * @param onPlayAll Invoked when the main card body is tapped.
 * @param onPlaySpotify Invoked when the Spotify chip is tapped.
 * @param onPlayYouTube Invoked when the YouTube chip is tapped.
 */
@Composable
private fun LikedSongsCard(
    totalCount: Int,
    spotifyCount: Int,
    youtubeCount: Int,
    showSourceChips: Boolean,
    singleSource: MusicSource?,
    onPlayAll: () -> Unit,
    onPlaySpotify: () -> Unit,
    onPlayYouTube: () -> Unit,
    onClick: () -> Unit,
    onClickSpotify: () -> Unit,
    onClickYouTube: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val infiniteTransition = rememberInfiniteTransition(label = "livingHeart")

    // Shifting gradient — cycles through purple hues
    val gradientColor1 by infiniteTransition.animateColor(
        initialValue = extendedColors.purpleLight,
        targetValue = extendedColors.purpleDark,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gradientColor1",
    )
    val gradientColor2 by infiniteTransition.animateColor(
        initialValue = extendedColors.purpleDark,
        targetValue = extendedColors.purpleLight,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gradientColor2",
    )

    // Breathing glow — shadow radius pulses
    val glowRadius by infiniteTransition.animateFloat(
        initialValue = 8f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowRadius",
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            Color.Transparent,
                        )
                    )
                ),
        ) {
            // Main row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Text content on the left
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YOUR COLLECTION",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.5.sp,
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Liked Songs",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (singleSource != null) {
                            SourceIndicator(source = singleSource, size = 6.dp)
                        }
                    }
                    Text(
                        text = when {
                            singleSource == MusicSource.SPOTIFY -> "$totalCount tracks on Spotify"
                            singleSource == MusicSource.YOUTUBE -> "$totalCount tracks on YouTube Music"
                            showSourceChips -> "$totalCount tracks \u00B7 2 sources"
                            else -> "$totalCount tracks"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Living heart icon on the right
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .drawBehind {
                            drawCircle(
                                color = gradientColor1.copy(alpha = glowAlpha),
                                radius = glowRadius.dp.toPx(),
                            )
                        }
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(gradientColor1, gradientColor2)
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Source chips — compact pills, dot + count only
            if (showSourceChips) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SourceLikedChip(
                        source = MusicSource.SPOTIFY,
                        count = spotifyCount,
                        onClick = onClickSpotify,
                    )
                    SourceLikedChip(
                        source = MusicSource.YOUTUBE,
                        count = youtubeCount,
                        onClick = onClickYouTube,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceLikedChip(
    source: MusicSource,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val accent = when (source) {
        MusicSource.SPOTIFY -> extendedColors.spotifyGreen
        MusicSource.YOUTUBE -> extendedColors.youtubeRed
        MusicSource.LOCAL, MusicSource.BOTH -> MaterialTheme.colorScheme.primary
    }
    val sourceName = when (source) {
        MusicSource.SPOTIFY -> "Spotify"
        MusicSource.YOUTUBE -> "YouTube"
        MusicSource.LOCAL -> "Local"
        MusicSource.BOTH -> ""
    }

    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(20.dp),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .semantics {
                        contentDescription = "$count $sourceName liked songs"
                    },
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}


// ── Daily mix cover background ───────────────────────────────────────────

/**
 * Renders the cover art for a daily-mix card. When 2 tile URLs are supplied,
 * draws them side-by-side as a 50/50 horizontal mosaic so users see visible
 * proof that the mix refreshed. With fewer URLs, falls back to a single
 * [AsyncImage] using [fallback]. Draws nothing when neither is available.
 */
@Composable
private fun DailyMixCoverBackground(
    tileUrls: List<String>,
    fallback: String?,
    modifier: Modifier = Modifier,
) {
    when {
        tileUrls.size >= 2 -> Row(modifier = modifier) {
            AsyncImage(
                model = tileUrls[0],
                contentDescription = null,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentScale = ContentScale.Crop,
            )
            AsyncImage(
                model = tileUrls[1],
                contentDescription = null,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentScale = ContentScale.Crop,
            )
        }
        fallback != null -> AsyncImage(
            model = fallback,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
        else -> Unit
    }
}


// ── Create mix card ──────────────────────────────────────────────────────

/**
 * Leading tile in the Stash Mixes row. Tapping it opens the Mix Builder
 * to create a brand-new custom mix (recipeId = null). Compact (104×120 — a
 * narrow "add" affordance, not a full 180-wide mix card) with a dashed glass
 * border, mirroring the Playlists grid's [CreatePlaylistCard] affordance.
 */
@Composable
private fun CreateMixCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .width(104.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(extendedColors.glassBackground)
            .drawBehind {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                        0f,
                    ),
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.5f),
                    style = stroke,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = "Create\nmix",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}


// ── Bottom sheet action row ──────────────────────────────────────────────

/**
 * A single action row inside a playlist context-menu bottom sheet.
 *
 * @param icon  Leading icon for the action.
 * @param label Human-readable label.
 * @param tint  Icon and label color. Defaults to [MaterialTheme.colorScheme.onSurface].
 * @param onClick Callback when the row is tapped.
 */
@Composable
private fun HomeBottomSheetActionRow(
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

