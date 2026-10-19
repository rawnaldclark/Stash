package com.stash.feature.listening.recap

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size as CoilSize
import coil3.toBitmap
import com.stash.core.ui.ambient.Ground
import com.stash.core.ui.ambient.PaletteCache
import com.stash.core.ui.ambient.PaletteKind
import com.stash.core.ui.ambient.roles
import com.stash.core.ui.share.ShareImage
import com.stash.core.ui.theme.LocalStashColors
import com.stash.core.ui.theme.StashDarkColorScheme
import com.stash.core.ui.theme.StashExtendedColorsDark
import com.stash.core.ui.theme.StashPurple
import com.stash.core.ui.theme.StashPurpleDark
import com.stash.core.ui.theme.StashPurpleLight
import com.stash.core.ui.theme.StashTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.reflect.KProperty

private const val TAG = "RecapScreen"

/** The Recap story's base colour, under the cover glow. */
private val RecapBase = Color(0xFF07070D)

/** Stash purple, for a card without a cover. */
private val BrandColors = CardColors(accent = StashPurpleLight, glow = StashPurple, deep = StashPurpleDark)

/** A press held this long pauses the story. */
private const val HOLD_MS = 220L

/** The quiz moves on this long after it's answered. */
private const val QUIZ_AFTER_ANSWER_MS = 2_600L

/**
 * The Recap story (DESIGN.md Screen 3, PHASE3-STORY-SPEC): full screen, one card at a time with
 * segmented progress bars; tap the right two thirds for the next card, the left third to go back,
 * press and hold to pause (the music too), swipe down or Back to close. Cards move on by
 * themselves (longer on reveals; the quiz waits for an answer) unless TalkBack or "remove
 * animations" is on. Every card can be shared as a 1080 x 1920 image.
 */
@Composable
fun RecapScreen(
    onClose: () -> Unit,
    /** Settings › Accounts, where Last.fm is connected (a Last.fm recap with no account). */
    onOpenLastFmSettings: () -> Unit = {},
    viewModel: RecapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val close by rememberUpdatedState {
        viewModel.closeAudio()
        onClose()
    }
    LaunchedEffect(viewModel) {
        launch { viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
        viewModel.closeRequests.collect { onClose() }
    }
    // end() from the close action AND from leaving composition (RecapAudio's contract), but not
    // when the activity is only being recreated (rotation, dark mode): the ViewModel and the story
    // live on, and closing there would leave it silent over the user's music.
    val activity = LocalContext.current.findActivity()
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.closeAudio() }
    }
    BackHandler { close() }
    LightStatusBarIcons()

    CompositionLocalProvider(LocalStashColors provides StashExtendedColorsDark) {
        MaterialTheme(colorScheme = StashDarkColorScheme, typography = StashTypography) {
            Box(Modifier.fillMaxSize().background(RecapBase)) {
                when (val s = state) {
                    RecapUiState.Loading -> CircularProgressIndicator(
                        color = RecapInk.tertiary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp).align(Alignment.Center),
                    )
                    is RecapUiState.Fetching -> FetchingRecap(s, onClose = close)
                    is RecapUiState.Empty -> EmptyRecap(s, onClose = close, onRetry = viewModel::retry, onOpenSettings = onOpenLastFmSettings)
                    // The cards (and the share images drawn from them) speak in the story's voice.
                    is RecapUiState.Ready -> CompositionLocalProvider(LocalRecapVoice provides s.voice) {
                        Story(s, viewModel, onClose = close)
                    }
                }
            }
        }
    }
}

/** The story is always dark: light status bar icons while it's open, the app's own after. */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) controller.isAppearanceLightStatusBars = before }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun EmptyRecap(s: RecapUiState.Empty, onClose: () -> Unit, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(28.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = RecapInk.primary) }
        }
        Spacer(Modifier.weight(1f))
        Text(s.title, style = RecapType.statement, color = RecapInk.primary)
        Spacer(Modifier.height(10.dp))
        Text(
            s.message ?: "Not enough listening here for a recap yet. Recaps show up once a month has ${RecapAvailability.MIN_PLAYS} listens.",
            style = RecapType.body,
            color = RecapInk.secondary,
        )
        if (s.canRetry) {
            Spacer(Modifier.height(20.dp))
            GlassPill("Try again", null, onClick = onRetry)
        }
        if (s.openSettings) {
            Spacer(Modifier.height(20.dp))
            GlassPill("Open Settings", null, onClick = onOpenSettings)
        }
        Spacer(Modifier.weight(1.4f))
    }
}

/** A Last.fm recap reading its scrobbles: the title, a quiet spinner and how far it has got. */
@Composable
private fun FetchingRecap(s: RecapUiState.Fetching, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(28.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = RecapInk.primary) }
        }
        Spacer(Modifier.weight(1f))
        Text(s.title, style = RecapType.statement, color = RecapInk.primary)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = RecapInk.tertiary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            // Announced as it changes (politely): TalkBack users hear the read progress.
            Text(
                s.line,
                style = RecapType.body,
                color = RecapInk.secondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.weight(1.4f))
    }
}

/** A card index kept within 0..[last] on every read and write ([index] in [Story]). */
private class CoercedIndex(private val state: MutableState<Int>, private val last: Int) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = state.value.coerceIn(0, last)
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        state.value = value.coerceIn(0, last)
    }
}

/** A share in progress: the card, its colours and its images, decoded in software. */
private data class ShareJob(val index: Int, val colors: CardColors, val images: Map<String, ImageBitmap>, val quizAnswer: Int?)

@Composable
private fun Story(s: RecapUiState.Ready, viewModel: RecapViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val muted by viewModel.muted.collectAsStateWithLifecycle()
    val quizAnswer by viewModel.quizAnswer.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val cards = s.cards
    val last = cards.lastIndex

    // Restored after process death the deck may be shorter (a listen deleted): never past its end.
    val savedIndex = rememberSaveable { mutableStateOf(0) }
    val coercedIndex: CoercedIndex = remember(last) { CoercedIndex(savedIndex, last) }
    var index by coercedIndex
    // Bumped when the card showing restarts (back on the first card), so its timer starts over.
    var restartTick by remember { mutableStateOf(0) }
    var held by remember { mutableStateOf(false) }
    var shareJob by remember { mutableStateOf<ShareJob?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    val progress = remember { Animatable(0f) }
    val (autoAdvance, animate) = rememberMotionPrefs()

    // Colours per cover, read once each (PaletteCache), Stash purple until (or unless) they load.
    val palettes = remember { mutableStateMapOf<String, CardColors>() }
    LaunchedEffect(cards) {
        cards.mapNotNull { it.tint }.distinct().forEach { model ->
            if (model.key in palettes) return@forEach
            val palette = PaletteCache.load(context, artData(model)) ?: return@forEach
            // A colourless cover (black, white, grey) would dress the card in grey: Stash purple instead.
            if (palette.kind == PaletteKind.NEUTRAL) {
                palettes[model.key] = BrandColors
                return@forEach
            }
            val roles = palette.roles(Ground.DARK)
            palettes[model.key] = CardColors(accent = roles.accent, glow = roles.vib, deep = roles.dom)
        }
    }
    fun colorsOf(card: RecapCard) = card.tint?.let { palettes[it.key] } ?: BrandColors
    val current = cards[index]
    val target = colorsOf(current)
    val glow by animateColorAsState(target.glow, tween(900), label = "glow")
    val deep by animateColorAsState(target.deep, tween(900), label = "deep")
    val accent by animateColorAsState(target.accent, tween(700), label = "accent")
    val colors = CardColors(accent, glow, deep)

    // Audio: start once the story shows; each card's song; hold pauses it.
    LaunchedEffect(Unit) { viewModel.startAudio() }
    LaunchedEffect(index) { viewModel.onCard(index) }
    // Off screen (the share sheet, another app) the story waits where it was.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val onScreen = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val paused = held || shareJob != null || !onScreen
    LaunchedEffect(paused) { viewModel.setHeld(paused) }

    fun go(to: Int) {
        val next = to.coerceIn(0, last)
        // Same card (back on the first one): restart its timer through the effect's key, not a
        // snapTo that would cancel the running animation and leave it stalled.
        if (next == index) restartTick++ else index = next
    }

    val quizBlocking = current is RecapCard.Quiz && quizAnswer == null
    // Read through State inside the loop below (a plain value would be the one from when it started).
    val pausedNow = rememberUpdatedState(paused)
    val blockingNow = rememberUpdatedState(quizBlocking)
    // The progress bar of the card showing: runs while not held, moves on at the end.
    LaunchedEffect(index, restartTick) {
        progress.snapTo(0f)
        snapshotFlow { Triple(pausedNow.value, autoAdvance, blockingNow.value) }.collectLatest { (p, auto, blocking) ->
            if (p || !auto || blocking) return@collectLatest
            val card = cards[index]
            val duration = if (card is RecapCard.Quiz) QUIZ_AFTER_ANSWER_MS else RecapDeck.durationMs(card)
            val remaining = ((1f - progress.value) * duration).toLong().coerceAtLeast(1)
            progress.animateTo(1f, tween(remaining.toInt(), easing = LinearEasing))
            if (index < last) index++
        }
    }

    fun share(i: Int) {
        if (shareJob != null) return
        val card = cards[i]
        scope.launch {
            val images = loadShareImages(context, card.images + listOfNotNull(card.tint), hero = card.hero)
            shareJob = ShareJob(i, colorsOf(card), images, quizAnswer)
        }
    }

    val dismissPx = with(density) { 140.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dragY
                val shrink = (dragY / (size.height.coerceAtLeast(1f))).coerceIn(0f, 0.2f)
                scaleX = 1f - shrink * 0.3f
                scaleY = 1f - shrink * 0.3f
            },
    ) {
        StoryBackground(colors)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            ProgressSegments(count = cards.size, index = index, progress = { progress.value })
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.title, style = RecapType.small.copy(fontSize = 14.sp), color = RecapInk.secondary, modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::toggleMute) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = if (muted) "Turn story music on" else "Mute story music",
                        tint = RecapInk.primary,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = RecapInk.primary)
                }
            }
            // The cards: taps, holds and the swipe down are read here (buttons inside take theirs first).
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Next card") { go(index + 1); true },
                            CustomAccessibilityAction("Previous card") { go(index - 1); true },
                        )
                    }
                    .pointerInput(cards) {
                        storyGestures(
                            slop = viewConfiguration.touchSlop,
                            onTap = { x -> if (x < size.width / 3f) go(index - 1) else go(index + 1) },
                            onHold = { held = it },
                            onDrag = { dragY = it },
                            onDragEnd = { dy ->
                                if (dy > dismissPx) onClose() else dragY = 0f
                            },
                        )
                    },
            ) {
                Crossfade(targetState = index, animationSpec = tween(380), label = "card") { i ->
                    val card = cards[i]
                    RecapCardContent(
                        card = card,
                        period = s.period,
                        colors = if (i == index) colors else colorsOf(card),
                        mode = CardMode(active = i == index, animate = animate),
                        quizAnswer = quizAnswer,
                        onQuizAnswer = { choice ->
                            viewModel.answerQuiz(choice)
                            val q = card as? RecapCard.Quiz
                            haptics.performHapticFeedback(
                                if (q != null && choice == q.correctIndex) HapticFeedbackType.Confirm else HapticFeedbackType.Reject,
                            )
                        },
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }
            }
            BottomActions(
                card = current,
                endActions = s.voice.endActions,
                quizAnswer = quizAnswer,
                saved = saved,
                sharing = shareJob != null,
                onShare = { share(index) },
                onSkipQuiz = viewModel::skipQuiz,
                onPlayTop = viewModel::playTopSongs,
                onSave = viewModel::saveAsPlaylist,
            )
        }
    }

    shareJob?.let { job ->
        ShareCapture(job = job, card = cards[job.index], period = s.period, footer = s.footer) { bitmap ->
            scope.launch {
                try {
                    if (bitmap != null) {
                        ShareImage.share(context, bitmap, "${s.voice.filePrefix} ${s.title} ${shareStem(cards[job.index])}", "Share your recap")
                    } else {
                        Toast.makeText(context, "Couldn't make the image. Try again.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "share failed", e)
                    Toast.makeText(context, "Couldn't share the image.", Toast.LENGTH_SHORT).show()
                } finally {
                    // Written to the PNG (or failed): the 1080x1920 bitmap isn't needed any more.
                    bitmap?.recycle()
                    shareJob = null
                }
            }
        }
    }
}

/** The file name's tail for a card's share image. */
private fun shareStem(card: RecapCard): String = when (card) {
    is RecapCard.Intro -> "intro"
    is RecapCard.Minutes -> "time"
    is RecapCard.Genres -> "genres"
    is RecapCard.TopArtist -> "top artist"
    is RecapCard.TopArtists -> "top artists"
    is RecapCard.Quiz -> "quiz"
    is RecapCard.TopSong -> "top song"
    is RecapCard.TopSongs -> "top songs"
    is RecapCard.OnRepeat -> "on repeat"
    is RecapCard.Clock -> "clock"
    is RecapCard.Streak -> "streak"
    is RecapCard.Discoveries -> "new to you"
    is RecapCard.Summary -> "summary"
}

/** Auto-advance and count-ups, off with TalkBack (touch exploration) or "remove animations". */
@Composable
private fun rememberMotionPrefs(): Pair<Boolean, Boolean> {
    val context = LocalContext.current
    return remember(context) {
        val talkBack = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        val animationsOff = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        val on = !talkBack && !animationsOff
        on to on
    }
}

/**
 * The story's gestures, in one pointer loop so they never fight: a quick tap ([onTap] with its x),
 * a press held [HOLD_MS] or longer ([onHold] true, then false on release) and a downward drag
 * ([onDrag] with the distance, [onDragEnd] on release). A press a button inside took is ignored.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.storyGestures(
    slop: Float,
    onTap: (Float) -> Unit,
    onHold: (Boolean) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        var holding = false
        var dragging = false
        var total = Offset.Zero
        var lastTime = down.uptimeMillis
        while (true) {
            // A finger held still still sends small moves: the hold is timed from the press, not
            // from the last event.
            val wait = (HOLD_MS - (lastTime - down.uptimeMillis)).coerceAtLeast(1)
            val event = if (holding || dragging) awaitPointerEvent() else withTimeoutOrNull(wait) { awaitPointerEvent() }
            if (event == null) {
                holding = true
                onHold(true)
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            lastTime = change.uptimeMillis
            if (!holding && !dragging && change.pressed && lastTime - down.uptimeMillis >= HOLD_MS &&
                abs(total.x) < slop && abs(total.y) < slop
            ) {
                holding = true
                onHold(true)
            }
            if (change.isConsumed && !dragging) {
                if (holding) onHold(false)
                break
            }
            total += change.positionChange()
            if (!dragging && total.y > slop && abs(total.y) > abs(total.x)) {
                dragging = true
                // The story (and its song) waits while it's being dragged down.
                if (!holding) {
                    holding = true
                    onHold(true)
                }
            }
            if (dragging) {
                onDrag(total.y.coerceAtLeast(0f))
                change.consume()
            }
            if (!change.pressed) {
                when {
                    dragging -> {
                        onHold(false)
                        onDragEnd(total.y)
                    }
                    holding -> onHold(false)
                    abs(total.x) < slop && abs(total.y) < slop -> onTap(change.position.x)
                }
                break
            }
        }
    }
}

/** The ground: near-black with the card's cover colours glowing from the top and bottom corners. */
@Composable
private fun StoryBackground(colors: CardColors) {
    Canvas(Modifier.fillMaxSize()) { drawStoryBackground(colors) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStoryBackground(colors: CardColors) {
    drawRect(RecapBase)
    drawRect(
        Brush.radialGradient(
            colors = listOf(colors.glow.copy(alpha = 0.62f), colors.glow.copy(alpha = 0.18f), Color.Transparent),
            center = Offset(size.width * 0.15f, size.height * 0.02f),
            radius = size.height * 0.62f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(colors.deep.copy(alpha = 0.50f), Color.Transparent),
            center = Offset(size.width * 0.95f, size.height * 0.98f),
            radius = size.height * 0.55f,
        ),
    )
    // A soft veil so white text holds on the brightest covers.
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.32f))))
}

/** One thin bar per card: past cards full, the current one filling, the rest empty. */
@Composable
private fun ProgressSegments(count: Int, index: Int, progress: () -> Float) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)
            .height(3.dp),
    ) {
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (count - 1)) / count
        val r = CornerRadius(size.height / 2, size.height / 2)
        val p = progress()
        for (i in 0 until count) {
            val x = i * (w + gap)
            drawRoundRect(Color.White.copy(alpha = 0.24f), topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = r)
            val fill = when {
                i < index -> 1f
                i == index -> p
                else -> 0f
            }
            if (fill > 0f) drawRoundRect(Color.White, topLeft = Offset(x, 0f), size = Size(w * fill, size.height), cornerRadius = r)
        }
    }
}

/** Under the card: Share (Skip on an unanswered quiz); under the summary, the end actions. */
@Composable
private fun BottomActions(
    card: RecapCard,
    endActions: Boolean,
    quizAnswer: Int?,
    saved: Boolean,
    sharing: Boolean,
    onShare: () -> Unit,
    onSkipQuiz: () -> Unit,
    onPlayTop: () -> Unit,
    onSave: () -> Unit,
) {
    val summaryActions = card is RecapCard.Summary && endActions
    Box(Modifier.fillMaxWidth().height(if (summaryActions) 132.dp else 76.dp).padding(horizontal = 24.dp, vertical = 12.dp)) {
        when {
            card is RecapCard.Quiz && quizAnswer == null ->
                GlassPill("Skip", null, onClick = onSkipQuiz, modifier = Modifier.align(Alignment.Center))
            // A Last.fm recap's summary has Share only (its songs may not be in the library).
            summaryActions -> Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = onPlayTop,
                    shape = RoundedCornerShape(24.dp),
                    color = RecapInk.primary,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = RecapBase, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play your top songs", style = RecapType.name.copy(fontSize = 16.sp), color = RecapBase)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPill(
                        if (saved) "Saved" else "Save as playlist",
                        if (saved) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.PlaylistAdd,
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                    )
                    GlassPill("Share", Icons.Rounded.IosShare, onClick = onShare, enabled = !sharing, modifier = Modifier.weight(1f))
                }
            }
            else -> GlassPill("Share", Icons.Rounded.IosShare, onClick = onShare, enabled = !sharing, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun GlassPill(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        color = RecapInk.glass,
        border = BorderStroke(1.dp, RecapInk.glassBorder),
        modifier = modifier.height(44.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = RecapInk.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = RecapType.name.copy(fontSize = 14.sp), color = RecapInk.primary, maxLines = 1, softWrap = false)
        }
    }
}

// ── Share images ────────────────────────────────────────────────────────────

/** Share image size and safe zones, in pixels (DESIGN.md: top 250, bottom 340, sides 65). */
internal object ShareSpec {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val SAFE_TOP = 250
    const val SAFE_BOTTOM = 340
    const val SAFE_SIDES = 65

    /**
     * Drawn at 2.5 px per dp, so the card's dp layout is 432 x 768 whatever the phone: its safe
     * area (about 380 x 530 dp) holds every card at the sizes it has on screen.
     */
    const val DENSITY = 2.5f
}

/**
 * Draws [card] off screen at exactly 1080 x 1920 (no chrome; safe zones kept; [footer]
 * "Stash · October 2026" or "Stash · Last.fm · October 2026"), records it into a graphics layer and hands back a software bitmap
 * (null on failure). Images come preloaded in [job] (Coil hardware bitmaps can't be read back).
 */
@Composable
private fun ShareCapture(job: ShareJob, card: RecapCard, period: RecapPeriod, footer: String, onDone: (Bitmap?) -> Unit) {
    val layer = rememberGraphicsLayer()
    val done by rememberUpdatedState(onDone)
    Box(
        Modifier
            .layout { measurable, _ ->
                val p = measurable.measure(Constraints.fixed(ShareSpec.WIDTH, ShareSpec.HEIGHT))
                layout(p.width, p.height) { p.place(0, 0) }
            }
            .drawWithContent {
                layer.record(size = IntSize(ShareSpec.WIDTH, ShareSpec.HEIGHT)) { this@drawWithContent.drawContent() }
                // Not drawn on screen: only recorded.
            },
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(ShareSpec.DENSITY, 1f),
            LocalShareImages provides job.images,
        ) {
            ShareCard(card, period, footer, job.colors, job.quizAnswer)
        }
    }
    LaunchedEffect(job) {
        val bitmap = try {
            // Two frames: composed and laid out, then drawn (recorded).
            withFrameNanos { }
            withFrameNanos { }
            val image = layer.toImageBitmap().asAndroidBitmap()
            if (image.config == Bitmap.Config.HARDWARE) {
                image.copy(Bitmap.Config.ARGB_8888, false).also { image.recycle() }
            } else {
                image
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "share capture failed", e)
            null
        }
        done(bitmap)
    }
}

/** The share image's layout: the card in its final state inside the safe zones, the footer at the bottom of them. */
@Composable
private fun ShareCard(card: RecapCard, period: RecapPeriod, footer: String, colors: CardColors, quizAnswer: Int?) {
    val d = LocalDensity.current
    Box(Modifier.fillMaxSize().drawBehind { drawStoryBackground(colors) }) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = with(d) { ShareSpec.SAFE_SIDES.toDp() },
                    end = with(d) { ShareSpec.SAFE_SIDES.toDp() },
                    top = with(d) { ShareSpec.SAFE_TOP.toDp() },
                    bottom = with(d) { ShareSpec.SAFE_BOTTOM.toDp() },
                ),
        ) {
            RecapCardContent(
                card = card,
                period = period,
                colors = colors,
                mode = CardMode.Share,
                quizAnswer = quizAnswer,
                onQuizAnswer = {},
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.height(14.dp))
            Text(footer, style = RecapType.small.copy(fontSize = 14.sp), color = RecapInk.secondary)
        }
    }
}

/** Decodes [models] in software (allowHardware(false)) for a share image; missing ones are left out. */
private suspend fun loadShareImages(context: Context, models: List<ArtModel>, hero: ArtModel?): Map<String, ImageBitmap> = coroutineScope {
    (models + listOfNotNull(hero)).distinct().map { model ->
        async {
            try {
                // Decoded at the size drawn: only the card's one big image is large.
                val px = if (model == hero) SHARE_HERO_PX else SHARE_ART_PX
                val request = ImageRequest.Builder(context)
                    .data(artData(model))
                    .size(CoilSize(px, px))
                    .allowHardware(false)
                    .build()
                val result = SingletonImageLoader.get(context).execute(request)
                (result as? SuccessResult)?.let { model.key to it.image.toBitmap().asImageBitmap() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }.awaitAll().filterNotNull().toMap()
}

/** Decode size of a share image's big image (the #1 cover is ~580 px there). */
private const val SHARE_HERO_PX = 640

/** Decode size of every other image in a share image (covers of 50-80 dp at 2.5 px/dp). */
private const val SHARE_ART_PX = 200
