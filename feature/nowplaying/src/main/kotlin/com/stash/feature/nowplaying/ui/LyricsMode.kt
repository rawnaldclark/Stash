package com.stash.feature.nowplaying.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.stash.core.ui.theme.SpaceGrotesk
import java.util.Locale
import kotlin.math.roundToLong

/*
 * Now Playing's lyrics mode (direction A, 2026-10-11): the lyrics take the art's place on the
 * live ambient, as on the web player's phone layout: a compact header (64dp cover, title,
 * artist, actions), the lyrics filling the middle under soft edge fades, and the progress bar
 * and transport at the bottom. The screen owns the layout and the switch; this file holds the
 * pieces. Replaces the opaque LyricsBottomSheet.
 */

/**
 * The lyrics area. Picks the renderer for [state] ([lyricsBodyFor]), sizes the type to the
 * width ([lyricFontSizeSp]), fades the top 24dp and bottom 120dp into the ambient, and holds
 * the screen on while it's showing (issue #382).
 *
 * A song change crossfades from the old body through "Looking for lyrics" to the new one, and
 * the renderer starts fresh (its own list state, placed at the live line).
 */
@Composable
internal fun LyricsPane(
    state: LyricsViewState,
    currentPositionMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    ink: Color,
    accent: Color,
    light: Boolean,
    modifier: Modifier = Modifier,
) {
    // Hold the screen on while lyrics are on screen. Restore the previous value rather than
    // forcing false, so we never clear a keep-screen-on set by something else.
    val view = LocalView.current
    DisposableEffect(view) {
        val wasKeepingScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = wasKeepingScreenOn }
    }

    BoxWithConstraints(modifier = modifier.lyricsEdgeFade()) {
        val density = LocalDensity.current
        val fontSp = lyricFontSizeSp(maxWidth.value)
        val viewportHeight = maxHeight
        val look = remember(fontSp, viewportHeight, ink, light, density) {
            with(density) {
                val anchor = lyricAnchorPx(fontSp.sp.toPx(), viewportHeight.toPx()).toDp()
                LyricsLook(
                    fontSize = fontSp.sp,
                    // The web's inks: lit .85 on the dark ambient, full plum on the light wash; dim .35.
                    bright = if (light) ink else ink.copy(alpha = 0.85f),
                    dim = ink.copy(alpha = if (light) 0.38f else 0.35f),
                    ink = ink,
                    sidePadding = LYRICS_SIDE_PAD_DP.dp,
                    lineGap = (fontSp.sp.toPx() * 0.2f).toDp(),
                    anchor = anchor,
                    bottomRoom = (viewportHeight - anchor).coerceAtLeast(0.dp),
                )
            }
        }
        val body = remember(state) { lyricsBodyFor(state) }
        Crossfade(targetState = body, animationSpec = tween(250), label = "lyrics-body") { b ->
            when (b) {
                LyricsBody.Loading -> LyricsMessage("Looking for lyrics", look, quiet = true)
                is LyricsBody.Words -> LyricsSyllableRenderer(
                    lyrics = b.lyrics,
                    currentPositionMs = currentPositionMs,
                    onLineTap = onSeek,
                    look = look,
                    isPlaying = isPlaying,
                    playbackSpeed = playbackSpeed,
                )
                is LyricsBody.Lines -> LyricsLineRenderer(b.lines, currentPositionMs, onSeek, look)
                is LyricsBody.Plain -> LyricsPlainRenderer(b.text, look)
                is LyricsBody.Message -> LyricsMessage(
                    text = b.text,
                    look = look,
                    accent = accent,
                    onRetry = if (b.canRetry) onRetry else null,
                )
            }
        }
    }
}

/**
 * A calm one-liner where the live line would sit (the web's `.lyr-msg`): "No lyrics for this
 * song", "Looking for lyrics" (quieter), with a text "Try again" where a retry can help.
 */
@Composable
private fun LyricsMessage(
    text: String,
    look: LyricsLook,
    quiet: Boolean = false,
    accent: Color = Color.Unspecified,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = look.sidePadding, end = look.sidePadding, top = look.anchor),
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 1.4.em),
            color = look.ink.copy(alpha = if (quiet) 0.5f else 0.72f),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text("Try again", style = MaterialTheme.typography.labelLarge, color = accent)
            }
        }
    }
}

/** Fades the content's top and bottom edges into whatever is behind (the ambient). */
private fun Modifier.lyricsEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        val top = LYRICS_FADE_TOP.toPx()
        val bottom = LYRICS_FADE_BOTTOM.toPx()
        if (h <= top + bottom) return@drawWithContent
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                top / h to Color.Black,
                (h - bottom) / h to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * The lyrics mode header: [cover], then title and artist, then [actions] (the like heart and
 * the lyrics menu). The text and actions take [textModifier] / [actionsModifier] so the screen
 * can stagger their entrance.
 */
@Composable
internal fun LyricsModeHeader(
    cover: @Composable () -> Unit,
    title: String,
    artist: String,
    ink: Color,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    actionsModifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        cover()
        Spacer(Modifier.width(14.dp))
        Column(modifier = textModifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = artist,
                fontSize = 14.sp,
                color = ink.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(modifier = actionsModifier, verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** The header's cover: the web's 64px, radius 8. A tap goes back to the big art. */
internal val CompactCoverSize = 64.dp

/**
 * Both Now Playing covers (the big art and the compact one) decode at this one size. The
 * ambient palette samples whichever cover loads, and AndroidX Palette's swatches depend on the
 * pixels it gets: a 64dp decode of In Rainbows found a red "vibrant" where the full-size one
 * found yellow, so the accent flipped each time lyrics opened or closed. One size means one
 * cache entry, one Bitmap, one palette (the ViewModel also skips a Bitmap it has sampled).
 */
private const val NP_ART_DECODE_PX = 1024

/**
 * The cover request both covers use. Remembered: an inline ImageRequest.Builder is a new object
 * every recomposition, which makes Coil re-evaluate the request each time (and Now Playing
 * recomposes on every 250ms position tick).
 */
@Composable
internal fun rememberNpArtRequest(artModel: Any?): ImageRequest {
    val context = LocalContext.current
    return remember(context, artModel) {
        ImageRequest.Builder(context)
            .data(artModel)
            .size(NP_ART_DECODE_PX)
            .allowHardware(false) // Required for Palette bitmap extraction.
            .build()
    }
}

/**
 * The compact cover. It also feeds the ambient palette when the song changes while lyrics are
 * up (the big art, the palette's usual source, isn't composed then): [onBitmapLoaded] is the
 * screen's guarded hook, which skips a cover it has already sampled.
 */
@Composable
internal fun CompactCover(
    artModel: Any?,
    onBitmapLoaded: (android.graphics.Bitmap?) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val missingArtColor = MaterialTheme.colorScheme.surfaceVariant
    val missingArt = remember(missingArtColor) { ColorPainter(missingArtColor) }
    val request = rememberNpArtRequest(artModel)
    AsyncImage(
        model = request,
        contentDescription = "Album art, back to the cover",
        contentScale = ContentScale.Crop,
        error = missingArt,
        fallback = missingArt,
        onSuccess = { s: AsyncImagePainter.State.Success ->
            onBitmapLoaded(runCatching { s.result.image.toBitmap() }.getOrNull())
        },
        modifier = modifier
            .size(CompactCoverSize)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    )
}

/**
 * The lyrics mode's small menu (the lyrics sheet's controls, moved): Sync offset (synced
 * lyrics with a row to store it on), the Live line switch for the cover view's lyrics bar, and
 * Save with song file (a downloaded track with lyrics showing).
 */
@Composable
internal fun LyricsMenuButton(
    items: LyricsMenuItems,
    liveEnabled: Boolean,
    onLiveToggle: (Boolean) -> Unit,
    offsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    savingToFile: Boolean,
    onSaveToFile: () -> Unit,
    ink: Color,
) {
    var open by remember { mutableStateOf(false) }
    var showOffsetDialog by remember { mutableStateOf(false) }
    if (showOffsetDialog) {
        OffsetAdjustDialog(
            currentOffsetMs = offsetMs,
            onOffsetChange = onOffsetChange,
            onDismiss = { showOffsetDialog = false },
        )
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreHoriz, contentDescription = "Lyrics options", tint = ink, modifier = Modifier.size(24.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = com.stash.core.ui.theme.StashTheme.extendedColors.elevatedSurface,
        ) {
            if (items.offset) {
                DropdownMenuItem(
                    text = { Text("Sync offset") },
                    leadingIcon = { Icon(Icons.Default.SyncAlt, contentDescription = null) },
                    trailingIcon = {
                        if (offsetMs != 0L) {
                            Text(
                                text = "%+.1fs".format(Locale.ROOT, offsetMs / 1000f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { open = false; showOffsetDialog = true },
                )
            }
            // The cover view's lyrics bar: the live line, or a quiet "View lyrics" when off.
            DropdownMenuItem(
                text = { Text("Live line on cover") },
                leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                trailingIcon = {
                    com.stash.core.ui.components.StashSwitch(checked = liveEnabled, onCheckedChange = null)
                },
                onClick = { onLiveToggle(!liveEnabled) },
            )
            if (items.saveToFile) {
                DropdownMenuItem(
                    text = { Text(if (savingToFile) "Saving…" else "Save with song file") },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                    enabled = !savingToFile,
                    onClick = { open = false; onSaveToFile() },
                )
            }
        }
    }
}

/**
 * Sync-adjustment popup: ±5s slider in 0.1s steps, plus a text field for anything further out
 * (hard-capped ±60s).
 *
 * The slider only PERSISTS on release ([Slider.onValueChangeFinished]) — writing to Room on every
 * tick meant the just-written value flowed back down through [currentOffsetMs] mid-drag and
 * visibly snapped the slider backward. Dragging still moves the displayed number instantly via
 * local state; only the DB write is deferred to release.
 */
@Composable
private fun OffsetAdjustDialog(
    currentOffsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var sliderSeconds by remember(currentOffsetMs) {
        mutableStateOf((currentOffsetMs / 1000f).coerceIn(-OFFSET_SLIDER_RANGE_S, OFFSET_SLIDER_RANGE_S))
    }
    var customText by remember(currentOffsetMs) {
        mutableStateOf("%.1f".format(Locale.ROOT, currentOffsetMs / 1000f))
    }

    fun apply(seconds: Float) {
        onOffsetChange((seconds.coerceIn(-OFFSET_HARD_CAP_S, OFFSET_HARD_CAP_S) * 1000f).roundToLong())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust sync") },
        text = {
            Column {
                Text(
                    text = "Positive delays lyrics, negative shows them earlier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "%+.1f s".format(Locale.ROOT, sliderSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Slider(
                    value = sliderSeconds,
                    onValueChange = { v ->
                        sliderSeconds = v
                        customText = "%.1f".format(Locale.ROOT, v)
                    },
                    onValueChangeFinished = { apply(sliderSeconds) },
                    valueRange = -OFFSET_SLIDER_RANGE_S..OFFSET_SLIDER_RANGE_S,
                    steps = OFFSET_SLIDER_STEPS,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Further out:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Seconds") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        // Accept a comma decimal separator (Germany, France, ...) as well as a
                        // period — toFloatOrNull() only understands the latter regardless of the
                        // device's own locale, so a comma-locale device typing "1,5" and tapping
                        // Set silently did nothing.
                        val v = customText.replace(',', '.').toFloatOrNull() ?: return@TextButton
                        sliderSeconds = v.coerceIn(-OFFSET_SLIDER_RANGE_S, OFFSET_SLIDER_RANGE_S)
                        apply(v)
                    }) { Text("Set") }
                }
                TextButton(onClick = {
                    sliderSeconds = 0f
                    customText = "0.0"
                    apply(0f)
                }) { Text("Reset to 0") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private const val OFFSET_SLIDER_RANGE_S = 5f
private const val OFFSET_SLIDER_STEPS = 99
private const val OFFSET_HARD_CAP_S = 60f
