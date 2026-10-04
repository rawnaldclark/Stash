@file:OptIn(ExperimentalComposeApi::class, ExperimentalLayoutApi::class)

package com.stash.feature.nowplaying.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.stash.data.lyrics.parser.TtmlGroup
import com.stash.data.lyrics.parser.TtmlLine
import com.stash.data.lyrics.parser.TtmlLyrics
import com.stash.data.lyrics.parser.TtmlSyllable
import com.stash.feature.nowplaying.ui.LyricsMotion.PHASE_ACTIVE
import com.stash.feature.nowplaying.ui.LyricsMotion.PHASE_SUNG
import com.stash.feature.nowplaying.ui.LyricsMotion.phaseOf
import com.stash.feature.nowplaying.ui.LyricsMotion.progressOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Word-synced lyrics, modelled on Spicy Lyrics:
 *  - per-syllable gradient sweep (20% soft edge) with scale / lift / glow springs
 *  - syllables held >= 1s split into letters that roll a wave through the word
 *  - adlibs on their own smaller row under the lead, duets aligned to the opposite side
 *  - interlude dots for gaps >= 3s (and long intros)
 *  - lines dim by state (1.0 / 0.51 / 0.497) and blur with distance from the active line (API 31+)
 *
 * Nothing tracks "the current word": every syllable derives its own phase from the clock, so
 * overlapping words / lines / adlibs are all animated independently. All per-frame values live in
 * state read in the DRAW phase; composition only changes on phase flips.
 */
@Composable
fun LyricsSyllableRenderer(
    lyrics: TtmlLyrics,
    currentPositionMs: Long,
    onLineTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    playbackSpeed: Float = 1f,
) {
    val items = remember(lyrics) { buildItems(lyrics) }
    val clock = rememberSmoothPositionMs(currentPositionMs, isPlaying, playbackSpeed)
    val activeIndex = remember(items) { derivedStateOf { activeItemIndex(items, clock.value) } }
    val listState = rememberLazyListState()

    // Grace window after a REAL finger drag (isScrollInProgress is also true during our own scroll).
    var lastUserScrollAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start || interaction is DragInteraction.Stop) {
                lastUserScrollAtMs = System.currentTimeMillis()
            }
        }
    }
    LaunchedEffect(items) {
        snapshotFlow { activeIndex.value }.collect { index ->
            if (items.isEmpty()) return@collect
            if (System.currentTimeMillis() - lastUserScrollAtMs > SCROLL_GRACE_MS) {
                runCatching {
                    listState.animateScrollToItem(index.coerceIn(0, items.lastIndex), SCROLL_OFFSET_PX)
                }
            }
        }
    }

    val measurer = rememberTextMeasurer(cacheSize = 256)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val leadBase = MaterialTheme.typography.headlineSmall
    val ctx = remember(measurer, clock, onSurface, leadBase) {
        val lead = leadBase.copy(fontWeight = FontWeight.Bold, lineHeight = TextUnit.Unspecified)
        LyricsCtx(
            measurer = measurer,
            clock = clock,
            leadStyle = lead,
            bgStyle = lead.copy(fontSize = lead.fontSize * 0.72f, fontWeight = FontWeight.SemiBold),
            bright = onSurface.copy(alpha = 0.85f),
            dim = onSurface.copy(alpha = 0.35f),
            bgBright = onSurface.copy(alpha = 0.70f),
            bgDim = onSurface.copy(alpha = 0.28f),
            ink = onSurface,
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 200.dp),
    ) {
        itemsIndexed(items, key = { index, _ -> index }) { index, item ->
            when (item) {
                is LyricsItem.Line -> LineItem(
                    item = item,
                    index = index,
                    activeIndex = activeIndex,
                    ctx = ctx,
                    onTap = { onLineTap(item.startMs) },
                )
                is LyricsItem.Interlude -> InterludeItem(item, ctx)
            }
        }
    }
}

// ─── items ──────────────────────────────────────────────────────────────────────────────────────

private sealed interface LyricsItem {
    val startMs: Long
    val endMs: Long

    data class Line(val line: TtmlLine) : LyricsItem {
        override val startMs: Long get() = line.lead.startMs
        override val endMs: Long get() = line.lead.endMs
    }

    data class Interlude(
        override val startMs: Long,
        override val endMs: Long,
        val oppositeAligned: Boolean,
    ) : LyricsItem
}

/** Lines, plus dot interludes for a long intro and any gap >= 3s (Spicy's `getLyricsBetweenShow`). */
private fun buildItems(lyrics: TtmlLyrics): List<LyricsItem> {
    val lines = lyrics.lines
    val out = ArrayList<LyricsItem>(lines.size + 4)
    lines.firstOrNull()?.let { first ->
        if (first.lead.startMs >= LyricsMotion.INTERLUDE_MIN_GAP_MS) {
            out += LyricsItem.Interlude(0L, first.lead.startMs, first.oppositeAligned)
        }
    }
    lines.forEachIndexed { i, line ->
        out += LyricsItem.Line(line)
        val next = lines.getOrNull(i + 1)
        if (next != null && next.lead.startMs - line.lead.endMs >= LyricsMotion.INTERLUDE_MIN_GAP_MS) {
            out += LyricsItem.Interlude(line.lead.endMs, next.lead.startMs, next.oppositeAligned)
        }
    }
    return out
}

/** Auto-scroll target: first item running now (if several overlap), else the latest that started. */
private fun activeItemIndex(items: List<LyricsItem>, pos: Long): Int {
    val running = items.indexOfFirst { pos >= it.startMs && pos < it.endMs }
    if (running >= 0) return running
    return items.indexOfLast { it.startMs <= pos }.coerceAtLeast(0)
}

/** Same rule for the live bar, which only deals in lines. */
internal fun activeLineIndex(lines: List<TtmlLine>, posMs: Long): Int {
    val running = lines.indexOfFirst { posMs >= it.lead.startMs && posMs < it.lead.endMs }
    if (running >= 0) return running
    return lines.indexOfLast { it.lead.startMs <= posMs }.coerceAtLeast(0)
}

@Immutable
private class LyricsCtx(
    val measurer: TextMeasurer,
    val clock: State<Long>,
    val leadStyle: TextStyle,
    val bgStyle: TextStyle,
    val bright: Color,
    val dim: Color,
    val bgBright: Color,
    val bgDim: Color,
    val ink: Color,
)

// ─── lines ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LineItem(
    item: LyricsItem.Line,
    index: Int,
    activeIndex: State<Int>,
    ctx: LyricsCtx,
    onTap: () -> Unit,
) {
    val clock = ctx.clock
    val state by remember(item) { derivedStateOf { phaseOf(clock.value, item.startMs, item.endMs) } }
    val alphaAnim = animateFloatAsState(
        targetValue = when (state) { PHASE_ACTIVE -> 1f; PHASE_SUNG -> 0.497f; else -> 0.51f },
        animationSpec = tween(300),
        label = "line-alpha",
    )
    val blurTarget by remember(item, index) {
        derivedStateOf {
            val distance = abs(index - activeIndex.value)
            // The line right next to the active one stays crisp (just dimmer via alpha);
            // blur only kicks in from two lines away, and ramps up more gently than before.
            if (state == PHASE_ACTIVE || distance <= 1) 0f
            else min(BLUR_PER_LINE * (distance - 1), BLUR_MAX)
        }
    }
    val blur by animateFloatAsState(blurTarget, tween(300), label = "line-blur")

    val line = item.line
    val description = remember(line) { line.lead.text }
    // Direction comes from the line's own text, not the phone's locale.
    val lineDirection = remember(line) {
        val firstStrong = line.lead.text.firstOrNull { Character.isLetter(it) }
        if (firstStrong != null && isRtlChar(firstStrong)) LayoutDirection.Rtl else LayoutDirection.Ltr
    }

    CompositionLocalProvider(LocalLayoutDirection provides lineDirection) {
        Column(
            horizontalAlignment = if (line.oppositeAligned) Alignment.End else Alignment.Start,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = alphaAnim.value }
                .then(if (blur > 0.25f) Modifier.blur(blur.dp, BlurredEdgeTreatment.Rectangle) else Modifier)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)
                .padding(horizontal = 24.dp, vertical = 10.dp)
                .clearAndSetSemantics { contentDescription = description },
        ) {
            GroupRow(line.lead, ctx, ctx.leadStyle, ctx.bright, ctx.dim, line.oppositeAligned)
            line.background.forEach { adlib ->
                GroupRow(adlib, ctx, ctx.bgStyle, ctx.bgBright, ctx.bgDim, line.oppositeAligned, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: TtmlGroup,
    ctx: LyricsCtx,
    style: TextStyle,
    bright: Color,
    dim: Color,
    opposite: Boolean,
    modifier: Modifier = Modifier,
) {
    val words = remember(group) { group.words() }
    val density = LocalDensity.current
    val gap = with(density) { (style.fontSize.toPx() * 0.28f).toDp() }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap, if (opposite) Alignment.End else Alignment.Start),
    ) {
        words.forEach { word ->
            // A word = consecutive syllables glued by partOfWord; FlowRow only wraps between words.
            Row {
                word.forEach { syl ->
                    if (isEmphasis(syl)) EmphasisSyllable(syl, ctx, style, bright, dim)
                    else SimpleSyllable(syl, ctx, style, bright, dim)
                }
            }
        }
    }
}

private fun TtmlGroup.words(): List<List<TtmlSyllable>> {
    val out = ArrayList<List<TtmlSyllable>>()
    var cur = ArrayList<TtmlSyllable>()
    for (s in syllables) {
        cur += s
        if (!s.partOfWord) { out += cur; cur = ArrayList() }
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

private fun isEmphasis(s: TtmlSyllable): Boolean =
    s.endMs - s.startMs >= LyricsMotion.EMPHASIS_MIN_MS &&
        s.text.none { Character.isSurrogate(it) || isRtlChar(it) }

private fun isRtlChar(c: Char): Boolean {
    val d = Character.getDirectionality(c)
    return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
}

// ─── syllables ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SimpleSyllable(syl: TtmlSyllable, ctx: LyricsCtx, style: TextStyle, bright: Color, dim: Color) {
    val clock = ctx.clock
    val phase by remember(syl) { derivedStateOf { phaseOf(clock.value, syl.startMs, syl.endMs) } }
    val motion = remember(syl) {
        Snapshot.withoutReadObservation {                       // don't subscribe composition to the clock
            val pos = clock.value
            val ph = phaseOf(pos, syl.startMs, syl.endMs)
            val p = when (ph) { PHASE_ACTIVE -> progressOf(pos, syl.startMs, syl.endMs); PHASE_SUNG -> 1f; else -> 0f }
            GlyphMotion(LyricsMotion.WordScale, LyricsMotion.WordY, p, LyricsMotion.gradientFor(ph, p))
        }
    }
    // Runs frames only while ACTIVE, or until the springs settle after a phase flip.
    LaunchedEffect(syl, phase) {
        val ph = phase
        frameLoop { dt ->
            val p = when (ph) { PHASE_ACTIVE -> progressOf(clock.value, syl.startMs, syl.endMs); PHASE_SUNG -> 1f; else -> 0f }
            motion.setGoals(
                LyricsMotion.WordScale.at(p), LyricsMotion.WordY.at(p), LyricsMotion.Glow.at(p),
                LyricsMotion.gradientFor(ph, p),
            )
            motion.step(dt)
            ph != PHASE_ACTIVE && motion.asleep()
        }
    }
    GlyphBox(syl.text, style, motion, ctx, bright, dim, yScale = 1f, blurBase = 4f, blurGain = 2f, alphaGain = 0.35f)
}

@Composable
private fun EmphasisSyllable(syl: TtmlSyllable, ctx: LyricsCtx, style: TextStyle, bright: Color, dim: Color) {
    val clock = ctx.clock
    val letters = remember(syl) { syl.text.map { it.toString() } }
    val phase by remember(syl) { derivedStateOf { phaseOf(clock.value, syl.startMs, syl.endMs) } }
    val motion = remember(syl) {
        Snapshot.withoutReadObservation {
            val ph = phaseOf(clock.value, syl.startMs, syl.endMs)
            EmphasisMotion(letters.size, if (ph == PHASE_SUNG) 1f else 0f)
        }
    }
    LaunchedEffect(syl, phase) {
        frameLoop { dt -> motion.update(clock.value, syl.startMs, syl.endMs, dt) }
    }
    val fontPx = with(LocalDensity.current) { style.fontSize.toPx() }
    Row(
        Modifier.graphicsLayer {
            scaleX = motion.word.scale
            scaleY = motion.word.scale
            translationY = motion.word.yOffset * fontPx
        },
    ) {
        letters.forEachIndexed { i, ch ->
            GlyphBox(ch, style, motion.letters[i], ctx, bright, dim, yScale = 2f, blurBase = 4f, blurGain = 12f, alphaGain = 1.85f)
        }
    }
}

/**
 * Draws one glyph run manually so the sweep, scale, lift and glow are pure draw-phase reads (no
 * recomposition per frame). The gradient is Spicy's: [bright] up to the sweep position, fading to
 * [dim] over the next 20% of the width.
 */
@Composable
private fun GlyphBox(
    text: String,
    style: TextStyle,
    motion: GlyphMotion,
    ctx: LyricsCtx,
    bright: Color,
    dim: Color,
    yScale: Float,
    blurBase: Float,
    blurGain: Float,
    alphaGain: Float,
) {
    val density = LocalDensity.current
    val layout = remember(text, style) { ctx.measurer.measure(text, style, softWrap = false, maxLines = 1) }
    val fontPx = with(density) { style.fontSize.toPx() }
    Box(
        Modifier
            .size(with(density) { layout.size.width.toDp() }, with(density) { layout.size.height.toDp() })
            .graphicsLayer {
                scaleX = motion.scale
                scaleY = motion.scale
                translationY = motion.yOffset * fontPx * yScale
            }
            .drawBehind {
                val w = size.width
                val startX = motion.gradient * w
                val brush = Brush.horizontalGradient(
                    0f to bright, 1f to dim,
                    startX = startX,
                    endX = startX + w * LyricsMotion.GRADIENT_EDGE,
                )
                val glowAlpha = (motion.glow * alphaGain).coerceIn(0f, 1f)
                val shadow = if (glowAlpha > 0.01f) {
                    Shadow(bright.copy(alpha = glowAlpha), Offset.Zero, (blurBase + blurGain * motion.glow) * density.density)
                } else null
                drawText(layout, brush = brush, shadow = shadow)
            },
    )
}

// ─── interludes ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun InterludeItem(item: LyricsItem.Interlude, ctx: LyricsCtx) {
    val clock = ctx.clock
    val visible by remember(item) {
        derivedStateOf {
            clock.value >= item.startMs && clock.value < item.endMs - LyricsMotion.INTERLUDE_PRE_HIDDEN_MS
        }
    }
    val expand = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f),   // small overshoot = the bounce
        label = "interlude-expand",
    )
    val windows = remember(item) { dotWindows(item.startMs, item.endMs) }
    val density = LocalDensity.current
    val fontPx = with(density) { ctx.leadStyle.fontSize.toPx() }
    val dotBox = with(density) { (fontPx * 0.62f).toDp() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->            // collapses to 0 height when hidden, like Spicy
                val placeable = measurable.measure(constraints)
                val h = (placeable.height * expand.value.coerceIn(0f, 1f)).roundToInt()
                layout(placeable.width, h) { placeable.placeRelative(0, 0) }
            }
            .graphicsLayer {
                val e = expand.value.coerceAtLeast(0f)
                scaleX = e
                scaleY = e
                alpha = e.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(if (item.oppositeAligned) 1f else 0f, 0f)
            }
            .padding(horizontal = 24.dp),
        contentAlignment = if (item.oppositeAligned) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(with(density) { (fontPx * 0.12f).toDp() }),
        ) {
            windows.forEach { (s, e) -> InterludeDot(s, e, ctx, dotBox, fontPx) }
        }
    }
}

/** Three dots share the gap in thirds; total padding of -550ms so they finish before the next line. */
private fun dotWindows(start: Long, end: Long): List<Pair<Long, Long>> {
    val total = (end - start).toDouble()
    val base = total / 3.0
    val pad = LyricsMotion.INTERLUDE_PADDING_MS / 3.0
    val d1 = max(start.toDouble(), start + base + pad)
    val d2 = max(d1, start + base * 2 + pad * 2)
    val d3 = max(d2, start + total + LyricsMotion.INTERLUDE_PADDING_MS)
    return listOf(start to d1.toLong(), d1.toLong() to d2.toLong(), d2.toLong() to d3.toLong())
}

@Composable
private fun InterludeDot(startMs: Long, endMs: Long, ctx: LyricsCtx, box: Dp, fontPx: Float) {
    val clock = ctx.clock
    val phase by remember(startMs, endMs) { derivedStateOf { phaseOf(clock.value, startMs, endMs) } }
    val motion = remember(startMs, endMs) {
        Snapshot.withoutReadObservation {
            val pos = clock.value
            val p = when (phaseOf(pos, startMs, endMs)) { PHASE_ACTIVE -> progressOf(pos, startMs, endMs); PHASE_SUNG -> 1f; else -> 0f }
            DotMotion(p)
        }
    }
    LaunchedEffect(startMs, endMs, phase) {
        val ph = phase
        frameLoop { dt ->
            val p = when (ph) { PHASE_ACTIVE -> progressOf(clock.value, startMs, endMs); PHASE_SUNG -> 1f; else -> 0f }
            motion.setGoals(p)
            motion.step(dt)
            ph != PHASE_ACTIVE && motion.asleep()
        }
    }
    val ink = ctx.ink
    Canvas(Modifier.size(box)) {
        val r = size.minDimension * 0.30f * motion.scale
        val c = center.copy(y = center.y + motion.yOffset * fontPx * 1.3f)
        val g = motion.glow
        if (g > 0.02f) {
            val glowR = r * 2.6f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(ink.copy(alpha = 0.35f * g * motion.opacity), Color.Transparent),
                    center = c,
                    radius = glowR,
                ),
                radius = glowR,
                center = c,
            )
        }
        drawCircle(color = ink.copy(alpha = motion.opacity), radius = r, center = c)
    }
}

// ─── word-synced live bar ───────────────────────────────────────────────────────────────────────

/**
 * Single-line word-synced text for [LiveLyricsBar]: syllables fade from 40% to full accent as they're
 * sung. (A fade, not the sweep: the bar is one ellipsized line of small text, and a sweep needs the
 * per-glyph layout the sheet uses.)
 */
@Composable
internal fun WordSyncedBarLine(
    lyrics: TtmlLyrics,
    currentPositionMs: Long,
    isPlaying: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    playbackSpeed: Float = 1f,
) {
    val clock = rememberSmoothPositionMs(currentPositionMs, isPlaying, playbackSpeed)
    val lines = lyrics.lines
    val lineIndex by remember(lines) { derivedStateOf { activeLineIndex(lines, clock.value) } }
    val style = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        shadow = Shadow(color = accent.copy(alpha = 0.55f), blurRadius = 18f),
    )
    AnimatedContent(
        targetState = lineIndex,
        transitionSpec = {
            (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 3 })
                .togetherWith(fadeOut(tween(250)) + slideOutVertically(tween(250)) { -it / 3 })
        },
        label = "wordBarLine",
        modifier = modifier,
    ) { idx ->
        val line = lines.getOrNull(idx)
        val pos = clock.value                       // read here on purpose: one small text, per-frame
        val text = buildAnnotatedString {
            val syls = line?.lead?.syllables.orEmpty().ifEmpty { line?.background?.firstOrNull()?.syllables.orEmpty() }
            if (syls.isEmpty()) {
                withStyle(SpanStyle(color = accent)) { append("♪") }
            } else {
                syls.forEachIndexed { i, s ->
                    val a = when {
                        pos >= s.endMs -> 1f
                        pos < s.startMs -> 0.4f
                        else -> 0.4f + 0.6f * progressOf(pos, s.startMs, s.endMs)
                    }
                    withStyle(SpanStyle(color = accent.copy(alpha = a))) { append(s.text) }
                    if (!s.partOfWord && i < syls.lastIndex) append(' ')
                }
            }
        }
        Text(
            text = text,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── helpers ────────────────────────────────────────────────────────────────────────────────────

/** Calls [tick] once per frame with the (clamped) frame delta in seconds until it returns true. */
private suspend fun frameLoop(tick: (Float) -> Boolean) {
    var last = withFrameNanos { it }
    while (true) {
        val now = withFrameNanos { it }
        val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
        last = now
        if (tick(dt)) return
    }
}

private const val BLUR_PER_LINE = 0.7f
private const val BLUR_MAX = 4.2f   
private const val SCROLL_GRACE_MS = 5_000L
private const val SCROLL_OFFSET_PX = -200
