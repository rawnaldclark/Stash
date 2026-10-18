package com.stash.feature.nowplaying.sheet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.ui.ambient.rememberReducedMotion
import com.stash.feature.nowplaying.NowPlayingScreen
import com.stash.feature.nowplaying.NowPlayingViewModel
import com.stash.feature.nowplaying.ui.LocalNowPlayingShown
import com.stash.feature.nowplaying.ui.rememberNpArtRequest
import kotlin.math.roundToInt

/**
 * The player sheet's layer over the whole app: the scrim over the page, the card that grows from
 * the mini player into Now Playing, and the cover that travels from the mini player's art to Now
 * Playing's. Hosted by the app scaffold above everything (outside the NavHost), so the player is
 * one surface whatever screen is behind it.
 *
 * Now Playing is laid out once at the full screen size and never re-measured: the card reveals it
 * through a clip that grows from the mini player's rect, and every per-frame change (clip, alphas,
 * the cover's flight) is a graphicsLayer / draw / placement read of [PlayerSheetState.progress].
 * At MINI the card isn't placed at all (nothing drawn, nothing hit-tested), so the app below takes
 * every touch.
 *
 * Shares the activity-scoped [NowPlayingViewModel] with the mini player.
 */
@Composable
fun PlayerSheetOverlay(
    state: PlayerSheetState,
    onNavigateToArtist: (id: String, name: String, avatarUrl: String?, focusAlbum: String?) -> Unit,
    onNavigateToAlbum: (albumId: String, name: String, artUrl: String?, artistName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val density = LocalDensity.current
    val reducedMotion = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current
    val navBarBottom = WindowInsets.navigationBars.getBottom(density)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    SideEffect {
        state.density = density.density
        state.isRtl = rtl
        state.reducedMotion = reducedMotion
        state.navBarBottomPx = navBarBottom.toFloat()
        state.onSnapHaptic = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }

    // Lyrics mode belongs to one visit to the player: it resets when the player settles at MINI
    // (it also lets LyricsPane leave, and with it its keep-screen-on).
    LaunchedEffect(state, viewModel) {
        snapshotFlow { state.settledAnchor }.collect { if (it == PlayerSheetAnchor.MINI) viewModel.onDismissLyrics() }
    }
    // A keyboard left up on the page behind has no business over the player.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state) {
        snapshotFlow { state.settledAnchor }.collect { if (it == PlayerSheetAnchor.FULL) keyboard?.hide() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { state.screenSize = it }
            // The page and its chrome dim as the player opens.
            .drawBehind {
                // Under the full card the scrim is never seen: not drawn.
                val p = state.progress
                val a = PlayerSheetMath.scrimAlpha(p)
                if (a > 0f && p < 1f) drawRect(Color.Black, alpha = a)
            },
    ) {
        if (state.isWarm) {
            SheetCard(state) {
                NowPlayingScreen(
                    onDismiss = state::collapse,
                    onNavigateToArtist = onNavigateToArtist,
                    onNavigateToAlbum = onNavigateToAlbum,
                    viewModel = viewModel,
                    sheet = state,
                )
            }
            CoverTraveller(state, viewModel)
        }
    }
}

/**
 * The card: full screen, clipped to the rect between the mini player and the screen, with the
 * theme's ground fading in behind Now Playing over the first 10 %.
 */
@Composable
private fun SheetCard(state: PlayerSheetState, content: @Composable () -> Unit) {
    val ground = MaterialTheme.colorScheme.background
    // Recomposes only when the player leaves or reaches MINI.
    val parked by remember(state) { derivedStateOf { state.progress <= 0f } }
    val a11y = if (parked) {
        // Parked under the mini player: not there for TalkBack either.
        Modifier.clearAndSetSemantics { }
    } else {
        Modifier.semantics {
            paneTitle = PANE_TITLE
            customActions = listOf(CustomAccessibilityAction(COLLAPSE_ACTION) { state.collapse(); true })
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    // Placed while the player is open, and once after it warms (or the screen size
                    // changed) so the big cover can report where it sits before the first drag; that
                    // placement is clipped to nothing below.
                    val needsHero = state.heroArt == null || state.heroMeasuredFor != state.screenSize
                    if (state.progress > 0f || needsHero) placeable.place(0, 0)
                }
            }
            .graphicsLayer {
                val p = state.progress
                clip = true
                shape = if (p > 0f) {
                    val rect = PlayerSheetMath.cardRect(state.effectiveMiniCardRect(), state.screenRect(), p)
                    SheetCardShape(rect, PlayerSheetMath.cardCornerDp(p) * density)
                } else {
                    SheetCardShape(Rect.Zero, 0f)
                }
            }
            .then(a11y)
            .playerSheetDrag(state)
            .drawBehind {
                val a = PlayerSheetMath.cardGroundAlpha(state.progress)
                if (a > 0f) drawRect(ground, alpha = a)
            },
    ) {
        // Past FULL (the rubber band) the content rises a little with the finger, over the ground.
        // A placement offset, so the big cover reports where it really is. Clear of a side system
        // bar or cutout (landscape 3-button navigation), as the NavHost's screens are; the top and
        // bottom insets Now Playing pads itself, so its ambient runs under the bars.
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, -PlayerSheetMath.overFullRisePx(state.progress, state.travelPx()).roundToInt()) }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                // Until the player rests open, a touch is the sheet's, never a control's: below
                // 75 % the controls are invisible, and a tap mid-move must not press one. Only the
                // down is taken (before the controls see it), so the sheet's own drag, which
                // listens after them, still gets the whole gesture.
                .pointerInput(state) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (!state.isAtRest(PlayerSheetAnchor.FULL)) {
                                event.changes.forEach { if (it.changedToDownIgnoreConsumed()) it.consume() }
                            }
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalNowPlayingShown provides !parked, content = content)
        }
    }
}

/** TalkBack's name for the open player, and its action to close it (the app's strings are in code). */
private const val PANE_TITLE = "Now Playing"
private const val COLLAPSE_ACTION = "Close the player"

/**
 * The card's clip: [rect] (layer coordinates, which are the root's: the card fills the screen at
 * 0,0) with [radius] px corners. A data class, so a new rect each frame is a new outline.
 */
private data class SheetCardShape(val rect: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        if (radius <= 0f) Outline.Rectangle(rect) else Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * The cover in flight between the mini player's art and Now Playing's: laid out once at the big
 * cover's size and moved by its layer (translation, scale from the top-left, corners from 6 to the
 * big cover's radius), so a frame of the flight is a layer update, never a layout. It decodes the
 * same request as the big cover (one bitmap), and shows only strictly between the anchors: at MINI
 * the mini player's own art shows, at FULL Now Playing's.
 */
@Composable
private fun CoverTraveller(state: PlayerSheetState, viewModel: NowPlayingViewModel) {
    // Composed only while it flies: at rest it would decode with every song change for nothing.
    // By then Now Playing's big cover has the same request in the memory cache.
    val flying by remember(state) { derivedStateOf { PlayerSheetMath.travellerVisible(state.progress) } }
    if (!flying) return
    DisposableEffect(state) { onDispose { state.travellerReady = false } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val track = uiState.currentTrack
    val artModel: Any? = track?.albumArtPath ?: track?.albumArtUrl
    val request = rememberNpArtRequest(artModel)
    val missingArtColor = MaterialTheme.colorScheme.surfaceVariant
    val missingArt = remember(missingArtColor) { ColorPainter(missingArtColor) }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        error = missingArt,
        fallback = missingArt,
        // Something to draw (the cover, the mini player's bitmap as its placeholder, or the
        // missing-art tile): until then the mini player keeps its own cover showing.
        onLoading = { loading -> state.travellerReady = loading.painter != null },
        onSuccess = { state.travellerReady = true },
        onError = { state.travellerReady = true },
        modifier = Modifier
            .layout { measurable, _ ->
                val hero = state.effectiveHeroArt().rect
                val w = hero.width.roundToInt().coerceAtLeast(1)
                val h = hero.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val p = state.progress
                if (!PlayerSheetMath.travellerVisible(p) || !state.travellerReady || size.width <= 0f) {
                    alpha = 0f
                    return@graphicsLayer
                }
                alpha = 1f
                val hero = state.effectiveHeroArt()
                val r = PlayerSheetMath.lerpRect(state.miniArtRect(), hero.rect, p)
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = r.left
                translationY = r.top
                scaleX = r.width / size.width
                scaleY = r.height / size.height
                val cornerOnScreen = PlayerSheetMath.travellerCornerDp(p, hero.cornerPx / density) * density
                // The layer is scaled after the clip: divide, so the corner reads right on screen.
                shape = RoundedCornerShape(cornerOnScreen / scaleX.coerceAtLeast(0.01f))
                clip = true
            },
    )
}
