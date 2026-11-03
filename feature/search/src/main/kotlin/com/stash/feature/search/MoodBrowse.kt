package com.stash.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.stash.core.ui.components.SectionHeader
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.theme.StashTheme
import com.stash.data.ytmusic.model.MoodTile
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/** A tile's height (Mochy Music's 76 dp): two lines of title fit, and the covers stand their full height in it. */
private val TileHeight = 76.dp
private val TileShape = RoundedCornerShape(14.dp)

/** The two fanned covers' box at the tile's right edge, and each cover's corners. */
private val CoversWidth = 84.dp
private val CoverShape = RoundedCornerShape(8.dp)

/** How long the visible tiles must stay put before their covers are asked for: a fling past costs nothing. */
private const val VISIBLE_SETTLE_MS = 250L

/**
 * Search before anything is typed (Mochy Music's Search tab): YouTube Music's "Moods & moments" and "Genres" as two
 * titled sections, each a two-column grid of colour tiles with two covers fanned at their right edge. A tap opens
 * the tile's page ([onOpen]). Which tiles are on screen is reported to [onTilesVisible] once a scroll settles: only
 * those have their covers read.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun MoodBrowseGrid(
    moods: List<MoodTile>,
    genres: List<MoodTile>,
    covers: Map<String, List<String>>,
    onOpen: (MoodTile) -> Unit,
    onTilesVisible: (List<MoodTile>) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    // Each row of two by its key, so the rows on screen name their tiles
    val rows = remember(moods, genres) {
        moods.chunked(2).withIndex().associate { (i, row) -> rowKey(MOODS, i) to row } +
            genres.chunked(2).withIndex().associate { (i, row) -> rowKey(GENRES, i) to row }
    }
    val report by rememberUpdatedState(onTilesVisible)
    LaunchedEffect(listState, rows) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.flatMap { (it.key as? String)?.let(rows::get).orEmpty() } }
            .debounce(VISIBLE_SETTLE_MS)
            .distinctUntilChanged()
            .collect { report(it) }
    }
    val moodsTitle = stringResource(R.string.search_moods)
    val genresTitle = stringResource(R.string.search_genres)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomChromePadding(16.dp)),
    ) {
        tileSection(MOODS, moodsTitle, moods, covers, onOpen)
        tileSection(GENRES, genresTitle, genres, covers, onOpen)
    }
}

private const val MOODS = "moods"
private const val GENRES = "genres"

private fun rowKey(section: String, index: Int) = "${section}_row_$index"

/** A titled section: two tiles a row, an odd last tile half the width like the rest. Nothing when it has no tiles. */
private fun LazyListScope.tileSection(
    section: String,
    title: String,
    tiles: List<MoodTile>,
    covers: Map<String, List<String>>,
    onOpen: (MoodTile) -> Unit,
) {
    if (tiles.isEmpty()) return
    item(key = "${section}_header", contentType = "header") { SectionHeader(title = title) }
    itemsIndexed(
        tiles.chunked(2),
        key = { i, _ -> rowKey(section, i) },
        contentType = { _, _ -> "tiles" },
    ) { _, row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.forEach { tile ->
                MoodTileCard(
                    tile = tile,
                    covers = covers[tile.params].orEmpty(),
                    onClick = { onOpen(tile) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    // With the header's own 12 dp, Mochy's 22 dp between a section's last tiles and the next title
    item(key = "${section}_gap", contentType = "gap") { Spacer(Modifier.height(10.dp)) }
}

/**
 * One tile, Mochy Music's: YouTube's colour as a wash from the left over the raised surface (so it reads on the light
 * theme too), the title on the left in up to two lines, and the tile's two covers fanned at its right edge. With no
 * covers yet (still reading, or Data saver on mobile data) the wash alone shows. A title that wraps fades the covers'
 * left side so the two never fight.
 *
 * Mochy keeps the title clear of the covers (70 dp from the right), which on a 393 dp phone is too narrow for some
 * words: "Songwriters" came out "Songwriter / s", and at the largest font "Christma / s" and "Indie & / alterna…". A
 * title that would cut a word, or lose its end, runs on over the covers instead (14 dp from the right, like its
 * left), and they fade under it. It's measured before it's drawn, so a tile scrolling in never shows the cut first.
 */
@Composable
internal fun MoodTileCard(
    tile: MoodTile,
    covers: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = StashTheme.extendedColors.elevatedSurface
    val wash = remember(tile.color, surface) {
        val tint = moodTint(tile.color)
        Brush.linearGradient(listOf(blendOver(tint, 0.55f, surface), blendOver(tint, 0.16f, surface)))
    }
    val interaction = remember { MutableInteractionSource() }
    val style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
    BoxWithConstraints(
        modifier = modifier
            .height(TileHeight)
            .pressScale(interaction)
            .clip(TileShape)
            .background(wash)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        val measurer = rememberTextMeasurer(cacheSize = 4)
        val density = LocalDensity.current
        val tileWidth = constraints.maxWidth
        val fit = remember(tile.title, style, tileWidth, density) {
            fun layout(endPadding: Dp) = measurer.measure(
                text = tile.title,
                style = style,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                constraints = Constraints(maxWidth = (tileWidth - with(density) { TitleStart.roundToPx() + endPadding.roundToPx() }).coerceAtLeast(0)),
            )
            val clear = layout(TitleEndClear)
            val cut = splitsAWord(tile.title, (0 until clear.lineCount - 1).map { clear.getLineEnd(it) }) ||
                clear.isLineEllipsized(clear.lineCount - 1)
            if (cut) TitleFit(runsOn = true, wraps = layout(TitleStart).lineCount > 1) else TitleFit(runsOn = false, wraps = clear.lineCount > 1)
        }
        FannedCovers(
            covers = covers,
            fadeLeft = fit.wraps || fit.runsOn,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).size(width = CoversWidth, height = TileHeight),
        )
        Text(
            text = tile.title,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = TitleStart, end = if (fit.runsOn) TitleStart else TitleEndClear),
        )
    }
}

/** The title's left padding, and its right one clear of the covers (Mochy's). */
private val TitleStart = 14.dp
private val TitleEndClear = 70.dp

/** How a tile's title sits: [runsOn] over the covers so no word is cut, and whether it [wraps] to a second line. */
private data class TitleFit(val runsOn: Boolean, val wraps: Boolean)

/**
 * Whether a title laid out in lines ending at [lineEnds] (each line's end but the last, as offsets into [title]) cuts
 * a word in two: a line that ends between two letters. A break at a space, or after a hyphen ("Hip-|hop"), is not.
 */
internal fun splitsAWord(title: String, lineEnds: List<Int>): Boolean =
    lineEnds.any { end -> end in 1 until title.length && title[end - 1].isLetterOrDigit() && title[end].isLetterOrDigit() }

/**
 * Mochy Music's two layered covers: the second tilted back -24° behind, the first -6° in front, lower and further
 * right. Each shows only once its picture's address is known.
 */
@Composable
private fun FannedCovers(covers: List<String>, fadeLeft: Boolean, modifier: Modifier) {
    if (covers.isEmpty()) return
    Box(
        modifier = modifier
            // The fade needs its own layer to cut the covers' pixels, not the tile's under them
            .then(if (fadeLeft) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else Modifier)
            .drawWithCache {
                val fade = Brush.horizontalGradient(
                    0.00f to Color.Transparent,
                    0.42f to Color.Black.copy(alpha = 0.55f),
                    0.70f to Color.Black,
                )
                onDrawWithContent {
                    drawContent()
                    if (fadeLeft) drawRect(brush = fade, blendMode = BlendMode.DstIn)
                }
            },
    ) {
        covers.getOrNull(1)?.let { LayeredCover(it, rotation = -24f, offsetX = (-2).dp, offsetY = 2.dp, size = 44.dp) }
        LayeredCover(covers[0], rotation = -6f, offsetX = 28.dp, offsetY = 13.dp, size = 48.dp)
    }
}

@Composable
private fun BoxScope.LayeredCover(url: String, rotation: Float, offsetX: Dp, offsetY: Dp, size: Dp) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .size(size)
            .graphicsLayer {
                rotationZ = rotation
                translationX = with(density) { offsetX.toPx() }
                translationY = with(density) { offsetY.toPx() }
                shape = CoverShape
                clip = true
            }
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * YouTube's stripe colour lifted toward a mid grey (Mochy Music's tint), so a deep red or a near-black still makes a
 * wash the title reads on. ARGB without alpha is taken as opaque; no colour, or black, is a neutral grey.
 */
internal fun moodTint(color: Long?): Color {
    val argb = color?.toInt() ?: 0
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    if (r == 0 && g == 0 && b == 0) return Color(0xFF3A3A44)
    fun lift(c: Int) = (c + (170 - c) * 0.45f).toInt().coerceIn(40, 220)
    return Color(lift(r), lift(g), lift(b))
}

/** [top] at [alpha] over [bottom], as one opaque colour. */
private fun blendOver(top: Color, alpha: Float, bottom: Color): Color = Color(
    red = top.red * alpha + bottom.red * (1f - alpha),
    green = top.green * alpha + bottom.green * (1f - alpha),
    blue = top.blue * alpha + bottom.blue * (1f - alpha),
    alpha = 1f,
)
