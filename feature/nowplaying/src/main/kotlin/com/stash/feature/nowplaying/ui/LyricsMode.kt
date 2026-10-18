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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
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
    /** Read inside the renderers only, so the 250ms tick doesn't recompose this pane. */
    currentPositionMs: () -> Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    ink: Color,
    /** The album's text colour, read only where it's drawn ("Try again"), so a crossfade never recomposes the pane. */
    accent: () -> Color,
    light: Boolean,
    modifier: Modifier = Modifier,
) {
    // Hold the screen on while lyrics are on screen. Restore the previous value rather than
    // forcing false, so we never clear a keep-screen-on set by something else.
    // Only while playing: paused with the phone set down, the screen never timed out (battery,
    // and static lyrics burning into an AMOLED panel).
    val view = LocalView.current
    DisposableEffect(view, isPlaying) {
        if (!isPlaying) return@DisposableEffect onDispose { }
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
                    currentPositionMs = currentPositionMs(),
                    onLineTap = onSeek,
                    look = look,
                    isPlaying = isPlaying,
                    playbackSpeed = playbackSpeed,
                )
                is LyricsBody.Lines -> LyricsLineRenderer(
                    b.lines, currentPositionMs(), onSeek, look,
                    isPlaying = isPlaying,
                    playbackSpeed = playbackSpeed,
                )
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
    accent: () -> Color = { Color.Unspecified },
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
                Text("Try again", style = MaterialTheme.typography.labelLarge, color = accent())
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
            // A long title or artist scrolls rather than ending in "…"; text that fits stays still.
            NpMarqueeText(text = title, style = HeaderTitleStyle, color = ink)
            Spacer(Modifier.height(2.dp))
            NpMarqueeText(text = artist, style = HeaderArtistStyle, color = ink.copy(alpha = 0.7f))
        }
        Row(modifier = actionsModifier, verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

private val HeaderTitleStyle = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 20.sp)
private val HeaderArtistStyle = TextStyle(fontSize = 14.sp)

/** The header's cover: the web's 64px, radius 8. A tap goes back to the big art. */
internal val CompactCoverSize = 64.dp

/**
 * Both Now Playing covers (the big art and the compact one) decode at one size: one cache
 * entry, one Bitmap, so opening or closing lyrics never decodes the cover again. (The album
 * colours no longer come from these bitmaps: Now Playing wears the app-wide palette, read once
 * per cover by PaletteCache.) INEXACT precision caps the decode at that size without upscaling smaller art; the request,
 * and so the cache key, is still identical for both.
 *
 * The size is the screen's short side: the big cover spans the width less its margins, so this
 * always covers it (Pixel 5: 1080 for a 948px cover; Pixel 6 Pro: 1440 for a ~1270px cover,
 * which a fixed 1024 decode stretched 1.24x). The short side, so rotating doesn't change the
 * request. Capped, so a tablet or an unfolded foldable doesn't hold a
 * 2000px+ bitmap: 1440x1440 ARGB is 8 MB, one shared Bitmap. The app's ArtSizeInterceptor reads
 * this size to pick the cover URL variant, so it also decides whether Qobuz `_max`, Spotify's
 * original etc. are fetched.
 */
private const val NP_ART_MAX_DECODE_PX = 1440

/**
 * The cover request both covers use. Remembered: an inline ImageRequest.Builder is a new object
 * every recomposition, which makes Coil re-evaluate the request each time (and Now Playing
 * recomposes on every 250ms position tick).
 *
 * Placeholder: the bigger cover variant has its own cache key, so a track's first view fetches it
 * from the network (a Qobuz `_max` can be 2 MB). Until it lands, draw the bitmap the mini player
 * already has in memory for the same song: Coil keys that as `MemoryCache.Key(<stored URL>)` (a
 * String model maps to a Uri keyed by its toString, and the mini player sets no transformations
 * or key extras), which is exactly what a String key here builds. A miss (local-file art, or a
 * cover the mini player sized up because it is under ~110px) just means no placeholder, as today.
 * The swap is instant (crossfade is off).
 */
@Composable
internal fun rememberNpArtRequest(artModel: Any?): ImageRequest {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val decodePx = with(LocalDensity.current) {
        minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx()
    }.coerceIn(1, NP_ART_MAX_DECODE_PX)
    return remember(context, artModel, decodePx) {
        ImageRequest.Builder(context)
            .data(artModel)
            .placeholderMemoryCacheKey(artModel as? String)
            .size(decodePx)
            .precision(Precision.INEXACT)
            // Software, as when Now Playing sampled its palette from this bitmap. Nothing reads
            // its pixels any more, so a hardware bitmap would do: a separate, measured change.
            .allowHardware(false)
            .build()
    }
}

/** The compact cover in the lyrics header (the same decode as the big art); a tap goes back to it. */
@Composable
internal fun CompactCover(
    artModel: Any?,
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
        modifier = modifier
            .size(CompactCoverSize)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    )
}

/**
 * The lyrics mode's small menu (the lyrics sheet's controls, moved): Sync offset (synced
 * lyrics with a row or a known output to store it on), the Live line switch for the cover view's
 * lyrics bar, and Save with song file (a downloaded track with lyrics showing).
 */
@Composable
internal fun LyricsMenuButton(
    items: LyricsMenuItems,
    liveEnabled: Boolean,
    onLiveToggle: (Boolean) -> Unit,
    /** The song's own offset; null when it has no lyrics row to keep one on (a stream). */
    offsetMs: Long?,
    onOffsetChange: (Long) -> Unit,
    savingToFile: Boolean,
    onSaveToFile: () -> Unit,
    ink: Color,
    /** The output playing now and its offset, or null while the output isn't known. */
    outputOffset: LyricsOutputOffset? = null,
    onOutputOffsetChange: (Long) -> Unit = {},
    gettingWordSynced: Boolean = false,
    onGetWordSynced: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    var showOffsetDialog by remember { mutableStateOf(false) }
    if (showOffsetDialog) {
        OffsetAdjustDialog(
            currentOffsetMs = offsetMs,
            onOffsetChange = onOffsetChange,
            output = outputOffset,
            onOutputOffsetChange = onOutputOffsetChange,
            onDismiss = { showOffsetDialog = false },
        )
    }
    // What the lyrics actually run on: the song's offset plus the output's.
    val heardOffsetMs = effectiveLyricsOffsetMs(offsetMs, outputOffset?.offsetMs)
    Box {
        IconButton(onClick = { open = true }) {
            // Tune, not a second "more": this is the lyrics' own settings, beside the row's ⋮.
            Icon(Icons.Default.Tune, contentDescription = "Lyrics options", tint = ink, modifier = Modifier.size(24.dp))
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
                        if (heardOffsetMs != 0L) {
                            Text(
                                text = "%+.1fs".format(Locale.ROOT, heardOffsetMs / 1000f),
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
                // The switch inside is display-only; the row carries its on/off for TalkBack.
                modifier = Modifier.semantics {
                    role = Role.Switch
                    toggleableState = ToggleableState(liveEnabled)
                },
            )
            if (items.wordSynced) {
                DropdownMenuItem(
                    text = { Text(if (gettingWordSynced) "Looking\u2026" else "Get word-synced lyrics") },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                    enabled = !gettingWordSynced,
                    onClick = { open = false; onGetWordSynced() },
                )
            }
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
 * With the output playing now known ([output]), "Apply to all songs on <device>" switches what
 * the slider edits from this song's offset to that output's (Bluetooth latency: set once, right
 * for every song on those headphones, and left alone on wired or the speaker). The switch only
 * picks the target, it never moves a value, and a line under the slider names the other offset
 * whenever it's also shifting these lyrics. A stream has no row for a song offset
 * ([currentOffsetMs] null), so there it edits the output's only.
 *
 * The slider only PERSISTS on release ([Slider.onValueChangeFinished]) — writing to Room on every
 * tick meant the just-written value flowed back down through [currentOffsetMs] mid-drag and
 * visibly snapped the slider backward. Dragging still moves the displayed number instantly via
 * local state; only the DB write is deferred to release.
 */
@Composable
private fun OffsetAdjustDialog(
    currentOffsetMs: Long?,
    onOffsetChange: (Long) -> Unit,
    output: LyricsOutputOffset?,
    onOutputOffsetChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val songScope = currentOffsetMs != null
    var applyToOutput by remember { mutableStateOf(false) }
    val toOutput = output != null && (applyToOutput || !songScope)
    val editedMs = if (toOutput && output != null) output.offsetMs else currentOffsetMs ?: 0L
    var sliderSeconds by remember(editedMs, toOutput) {
        mutableStateOf((editedMs / 1000f).coerceIn(-OFFSET_SLIDER_RANGE_S, OFFSET_SLIDER_RANGE_S))
    }
    var customText by remember(editedMs, toOutput) {
        mutableStateOf("%.1f".format(Locale.ROOT, editedMs / 1000f))
    }

    fun apply(seconds: Float) {
        val ms = (seconds.coerceIn(-OFFSET_HARD_CAP_S, OFFSET_HARD_CAP_S) * 1000f).roundToLong()
        when {
            toOutput -> onOutputOffsetChange(clampOutputOffsetMs(ms))
            songScope -> onOffsetChange(ms)
        }
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
                if (output != null) {
                    Spacer(Modifier.height(4.dp))
                    if (songScope) {
                        // The whole row toggles; the switch inside is display-only.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = applyToOutput, role = Role.Switch, onValueChange = { applyToOutput = it })
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = "Apply to all songs on ${output.output.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            com.stash.core.ui.components.StashSwitch(checked = applyToOutput, onCheckedChange = null)
                        }
                    } else {
                        Text(
                            text = "For all songs on ${output.output.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
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
                // The other offset, when it shifts these lyrics too, so the total never surprises.
                // (The device name is never a format string: it can hold a "%".)
                val otherNote = when {
                    toOutput -> currentOffsetMs?.takeIf { it != 0L }
                        ?.let { "This song adds ${signedSeconds(it)} of its own." }
                    output != null && output.offsetMs != 0L ->
                        "${output.output.name} adds ${signedSeconds(output.offsetMs)} to every song."
                    else -> null
                }
                if (otherNote != null) {
                    Text(
                        text = otherNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/** An offset as the dialog writes it: "+0.3 s". */
private fun signedSeconds(ms: Long): String = "%+.1f s".format(Locale.ROOT, ms / 1000f)

private const val OFFSET_SLIDER_RANGE_S = 5f
private const val OFFSET_SLIDER_STEPS = 99
private const val OFFSET_HARD_CAP_S = 60f
