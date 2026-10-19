package com.stash.feature.listening.recap

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader
import com.stash.core.data.db.dao.FirstListen
import com.stash.core.data.listening.DayRange
import com.stash.core.ui.theme.Inter
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.feature.listening.ListeningLabels
import com.stash.feature.listening.ui.charts.ListeningClock
import com.stash.feature.listening.ui.genrePercent
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.ceil

// ── Look ────────────────────────────────────────────────────────────────────

/** The story's ink: always on a dark, cover-tinted ground (the story is always dark). */
internal object RecapInk {
    val primary = Color(0xFFF5F3FB)
    val secondary = Color(0xFFF5F3FB).copy(alpha = 0.74f)
    val tertiary = Color(0xFFF5F3FB).copy(alpha = 0.52f)
    val faint = Color.White.copy(alpha = 0.14f)
    val glass = Color.White.copy(alpha = 0.09f)
    val glassBorder = Color.White.copy(alpha = 0.16f)
}

/** The colours one card is dressed in, from its cover (see RecapTint in RecapScreen). */
internal data class CardColors(val accent: Color, val glow: Color, val deep: Color)

/** The story's type: Space Grotesk numbers and names, Inter words (core/ui Type.kt). */
internal object RecapType {
    val eyebrow = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 25.sp)
    val small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp)
    val statement = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp)
    val name = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp)
    val big = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 72.sp, lineHeight = 72.sp, letterSpacing = (-2).sp)
    val huge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 120.sp, lineHeight = 116.sp, letterSpacing = (-5).sp)
    val rank = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 28.sp)
}

/**
 * How a card is drawn: on screen ([active] = it's the card showing; [animate] = count-ups and
 * reveals play) or for a share image ([share]: everything at its final state).
 */
internal data class CardMode(val active: Boolean, val animate: Boolean, val share: Boolean = false) {
    val moving: Boolean get() = active && animate && !share

    companion object {
        val Share = CardMode(active = true, animate = false, share = true)
    }
}

/** Preloaded software bitmaps by [ArtModel.key] while a share image is drawn; null on screen. */
internal val LocalShareImages = compositionLocalOf<Map<String, ImageBitmap>?> { null }

// ── Motion helpers ──────────────────────────────────────────────────────────

/** [target] counted up from 0 over ~900 ms when the card shows (at once when not animating). */
@Composable
internal fun countUp(target: Int, mode: CardMode, delayMs: Long = 150): Int {
    val anim = remember(target) { Animatable(if (mode.moving) 0f else target.toFloat()) }
    LaunchedEffect(target, mode.moving) {
        if (mode.moving) {
            anim.snapTo(0f)
            delay(delayMs)
            anim.animateTo(target.toFloat(), tween(COUNT_UP_MS, easing = FastOutSlowInEasing))
        } else {
            anim.snapTo(target.toFloat())
        }
    }
    return anim.value.toInt()
}

/** 0 → 1 after [delayMs] when the card shows (1 at once when not animating): a reveal's progress. */
@Composable
internal fun appear(mode: CardMode, delayMs: Long, durationMs: Int = 520): Float {
    val anim = remember { Animatable(if (mode.moving) 0f else 1f) }
    LaunchedEffect(mode.moving) {
        if (mode.moving) {
            anim.snapTo(0f)
            delay(delayMs)
            anim.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
        } else {
            anim.snapTo(1f)
        }
    }
    return anim.value
}

/** Fades in and rises 16 dp as [p] goes 0 → 1. */
internal fun Modifier.rise(p: Float): Modifier = graphicsLayer {
    alpha = p
    translationY = (1f - p) * 16.dp.toPx()
}

private const val COUNT_UP_MS = 900

// ── Art ─────────────────────────────────────────────────────────────────────

/** A cover or photo: a preloaded bitmap for a share image, else loaded by Coil; a glyph when missing. */
@Composable
internal fun RecapArt(model: ArtModel?, size: Dp, shape: Shape, modifier: Modifier = Modifier, glyph: ImageVector = Icons.Default.MusicNote) {
    Box(
        modifier = modifier.size(size).clip(shape).background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        val shared = LocalShareImages.current
        val bitmap = model?.let { shared?.get(it.key) }
        when {
            bitmap != null -> Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            model != null && shared == null -> AsyncImage(
                model = artData(model),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Icon(glyph, contentDescription = null, tint = RecapInk.tertiary, modifier = Modifier.size(size * 0.4f))
        }
    }
}

/** What Coil loads for [model]: a file path as is, a URL at its upgraded size. */
internal fun artData(model: ArtModel): String = if (model.key.startsWith("http")) ArtUrlUpgrader.upgrade(model.key) ?: model.key else model.key

// ── Building blocks ─────────────────────────────────────────────────────────

@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = RecapInk.secondary) {
    Text(text, style = RecapType.eyebrow, color = color, modifier = modifier)
}

/** A ranked row: big numeral, art, name and a quiet number on the right. */
@Composable
private fun RankRow(
    rank: Int,
    title: String,
    subtitle: String?,
    trailing: String,
    art: @Composable () -> Unit,
    colors: CardColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            style = RecapType.rank,
            color = if (rank == 1) colors.accent else RecapInk.tertiary,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(34.dp),
        )
        // A fixed slot, so every name starts at the same x though #1's art is bigger.
        Box(Modifier.width(60.dp), contentAlignment = Alignment.CenterStart) { art() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (rank == 1) RecapType.name.copy(fontSize = 22.sp) else RecapType.name.copy(fontSize = 18.sp),
                color = RecapInk.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = RecapType.small, color = RecapInk.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(trailing, style = RecapType.small, color = RecapInk.secondary, maxLines = 1)
    }
}

/** A thin rounded bar [fraction] long in [color]. */
@Composable
private fun ThinBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(RecapInk.faint, size = size, cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0.015f, 1f), size.height), cornerRadius = r)
    }
}

private fun plays(n: Int) = ListeningLabels.plays(n)

/** "7 plays · about 10 min" when the time is partly estimated (the Minutes card's rule), else exact. */
internal fun playsAndTime(plays: Int, listenedMs: Long, estimated: Boolean): String =
    if (listenedMs <= 0) ListeningLabels.plays(plays)
    else "${ListeningLabels.plays(plays)} · ${if (estimated) "about " else ""}${ListeningLabels.listenTime(listenedMs)}"

/** "28 plays · about 1 h 36 min" (the same rule). */
internal fun daySummary(plays: Int, listenedMs: Long, estimated: Boolean): String = playsAndTime(plays, listenedMs, estimated)

// ── The cards ───────────────────────────────────────────────────────────────

/**
 * One card's content, filling the space it's given (the story's middle on screen, the safe area of
 * a 1080 x 1920 share image). Layout stays inside ~316 x 440 dp so the share image's safe zones hold.
 */
@Composable
internal fun RecapCardContent(
    card: RecapCard,
    period: RecapPeriod,
    colors: CardColors,
    mode: CardMode,
    quizAnswer: Int?,
    onQuizAnswer: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        when (card) {
            is RecapCard.Intro -> IntroCard(card, period, colors, mode)
            is RecapCard.Minutes -> MinutesCard(card, colors, mode)
            is RecapCard.Genres -> GenresCard(card, colors, mode)
            is RecapCard.TopArtist -> TopArtistCard(card, colors, mode)
            is RecapCard.TopArtists -> TopArtistsCard(card, colors, mode)
            is RecapCard.Quiz -> QuizCard(card, colors, mode, quizAnswer, onQuizAnswer)
            is RecapCard.TopSong -> TopSongCard(card, colors, mode)
            is RecapCard.TopSongs -> TopSongsCard(card, colors, mode)
            is RecapCard.OnRepeat -> OnRepeatCard(card, colors, mode)
            is RecapCard.Clock -> ClockCard(card, colors, mode)
            is RecapCard.Streak -> StreakCard(card, colors, mode)
            is RecapCard.Discoveries -> DiscoveriesCard(card, colors, mode)
            is RecapCard.Summary -> SummaryPoster(card, period, colors, mode)
        }
    }
}

@Composable
private fun ColumnScope.IntroCard(card: RecapCard.Intro, period: RecapPeriod, colors: CardColors, mode: CardMode) {
    val count = countUp(card.plays, mode, delayMs = 500)
    val voice = LocalRecapVoice.current
    Spacer(Modifier.weight(1f))
    Text(
        "${period.name()}.",
        style = RecapType.big.copy(fontSize = 64.sp, lineHeight = 66.sp),
        color = RecapInk.primary,
        modifier = Modifier.rise(appear(mode, 0)),
    )
    Spacer(Modifier.height(28.dp))
    Column(Modifier.rise(appear(mode, 350)).clearAndSetSemantics { contentDescription = voice.introSentence(card.plays) }) {
        Text(voice.introLead, style = RecapType.body.copy(fontSize = 22.sp), color = RecapInk.secondary)
        Text("%,d".format(count), style = RecapType.huge, color = colors.accent, maxLines = 1)
        Text("times.", style = RecapType.body.copy(fontSize = 22.sp), color = RecapInk.secondary)
    }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        card.covers.take(5).forEachIndexed { i, c ->
            RecapArt(c, 54.dp, RoundedCornerShape(10.dp), Modifier.rise(appear(mode, 900L + i * 110)))
        }
    }
    voice.credit?.let { credit ->
        Spacer(Modifier.height(14.dp))
        Text(credit, style = RecapType.small, color = RecapInk.tertiary, modifier = Modifier.rise(appear(mode, 1_400)))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ColumnScope.MinutesCard(card: RecapCard.Minutes, colors: CardColors, mode: CardMode) {
    val value = countUp(card.big.value, mode, delayMs = 300)
    Spacer(Modifier.weight(1f))
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = "You listened for ${if (card.estimated) "about " else ""}${card.big.value} ${card.big.unit}. ${card.translation}"
        },
    ) {
        Eyebrow(if (card.estimated) "You listened for about" else "You listened for", Modifier.rise(appear(mode, 0)))
        Spacer(Modifier.height(4.dp))
        Text("%,d".format(value), style = RecapType.huge.copy(fontSize = 136.sp, lineHeight = 132.sp), color = colors.accent, maxLines = 1)
        Text(card.big.unit, style = RecapType.statement.copy(fontSize = 40.sp), color = RecapInk.primary)
        Spacer(Modifier.height(28.dp))
        Text(card.translation, style = RecapType.body.copy(fontSize = 21.sp, lineHeight = 28.sp), color = RecapInk.secondary, modifier = Modifier.rise(appear(mode, 1_200)))
    }
    Spacer(Modifier.weight(1.3f))
}

@Composable
private fun ColumnScope.GenresCard(card: RecapCard.Genres, colors: CardColors, mode: CardMode) {
    val top = card.genres.maxOfOrNull { it.share } ?: 1.0
    Spacer(Modifier.weight(1f))
    Eyebrow("Your top genres", Modifier.rise(appear(mode, 0)))
    Spacer(Modifier.height(22.dp))
    card.genres.forEachIndexed { i, g ->
        val p = appear(mode, 250L + i * 260)
        Column(Modifier.rise(p).clearAndSetSemantics { contentDescription = "Number ${i + 1}, ${g.name}, ${genrePercent(g.share)} of your listening" }) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    g.name,
                    style = RecapType.statement.copy(fontSize = if (i == 0) 38.sp else 28.sp, lineHeight = if (i == 0) 42.sp else 32.sp),
                    color = if (i == 0) RecapInk.primary else RecapInk.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(genrePercent(g.share), style = RecapType.name, color = if (i == 0) colors.accent else RecapInk.secondary)
            }
            Spacer(Modifier.height(10.dp))
            ThinBar((g.share / top).toFloat() * p, if (i == 0) colors.accent else colors.accent.copy(alpha = 0.55f))
        }
        Spacer(Modifier.height(26.dp))
    }
    Spacer(Modifier.weight(1f))
    Text("Share of your listening time · Genres from Last.fm", style = RecapType.small, color = RecapInk.tertiary)
}

@Composable
private fun ColumnScope.TopArtistCard(card: RecapCard.TopArtist, colors: CardColors, mode: CardMode) {
    val photo = appear(mode, 0, 700)
    Spacer(Modifier.weight(0.7f))
    Eyebrow("Your top artist", Modifier.rise(appear(mode, 0)))
    Spacer(Modifier.height(18.dp))
    RecapArt(
        card.artist.art(), 156.dp, CircleShape,
        Modifier.graphicsLayer { alpha = photo; scaleX = 0.9f + 0.1f * photo; scaleY = 0.9f + 0.1f * photo },
        glyph = Icons.Rounded.Person,
    )
    Spacer(Modifier.height(18.dp))
    Text(
        card.artist.name,
        style = RecapType.statement.copy(fontSize = 40.sp, lineHeight = 44.sp),
        color = RecapInk.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.rise(appear(mode, 250)),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        buildAnnotatedString {
            append("You played them on ")
            withStyle(SpanStyle(color = RecapInk.primary, fontWeight = FontWeight.SemiBold)) { append("${card.daysPlayed} of ${card.daysInPeriod}") }
            append(" days")
        },
        style = RecapType.body,
        color = RecapInk.secondary,
        modifier = Modifier.rise(appear(mode, 500)),
    )
    Spacer(Modifier.height(16.dp))
    DayDots(card.grid, card.playedDays, card.today, colors.accent, appear(mode, 700, 900))
    Spacer(Modifier.height(16.dp))
    Text(
        playsAndTime(card.artist.plays, card.artist.listenedMs, card.estimated),
        style = RecapType.small,
        color = RecapInk.tertiary,
        modifier = Modifier.rise(appear(mode, 900)),
    )
    Spacer(Modifier.weight(1f))
}

/**
 * The period's days as dots, played days in the accent: a month as a calendar (a column per
 * weekday), a year as 53 week columns. Days still to come are drawn fainter. [reveal] fills the
 * played dots in from the first day to the last.
 */
@Composable
internal fun DayDots(grid: DayRange, played: Set<Long>, today: Long, accent: Color, reveal: Float) {
    val firstDow = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val start = LocalDate.ofEpochDay(grid.fromDay)
    val offset = ((start.dayOfWeek.value - firstDow.value) + 7) % 7
    val cells = offset + grid.length.toInt()
    val isYear = grid.length > 40
    val columns = if (isYear) ceil(cells / 7.0).toInt() else 7
    val rows = if (isYear) 7 else ceil(cells / 7.0).toInt()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val dot = if (isYear) (maxWidth / columns) * 0.72f else 11.dp
        val pitch = if (isYear) maxWidth / columns else 20.dp
        val shown = (grid.length * reveal).toLong()
        Canvas(Modifier.width(pitch * columns).height(pitch * rows)) {
            val d = dot.toPx()
            val step = pitch.toPx()
            for (i in 0 until grid.length.toInt()) {
                val cell = offset + i
                val col = if (isYear) cell / 7 else cell % 7
                val row = if (isYear) cell % 7 else cell / 7
                val day = grid.fromDay + i
                val color = when {
                    day > today -> Color.White.copy(alpha = 0.05f)
                    day in played && i <= shown -> accent
                    else -> RecapInk.faint
                }
                drawCircle(color, radius = d / 2, center = Offset(col * step + step / 2, row * step + step / 2))
            }
        }
    }
}

@Composable
private fun ColumnScope.TopArtistsCard(card: RecapCard.TopArtists, colors: CardColors, mode: CardMode) {
    Spacer(Modifier.weight(0.8f))
    Eyebrow("Your top artists", Modifier.rise(appear(mode, 0)))
    Spacer(Modifier.height(20.dp))
    val n = card.artists.size
    card.artists.forEachIndexed { i, a ->
        // Revealed 5 → 1: the last row first, #1 last.
        val p = appear(mode, 300L + (n - 1 - i) * 520L, 560)
        RankRow(
            rank = i + 1,
            title = a.name,
            subtitle = null,
            trailing = plays(a.plays),
            art = { RecapArt(a.art(), if (i == 0) 60.dp else 50.dp, CircleShape, glyph = Icons.Rounded.Person) },
            colors = colors,
            modifier = Modifier.rise(p),
        )
        Spacer(Modifier.height(14.dp))
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.TopSongsCard(card: RecapCard.TopSongs, colors: CardColors, mode: CardMode) {
    Spacer(Modifier.weight(0.8f))
    Eyebrow("Your top songs", Modifier.rise(appear(mode, 0)))
    Spacer(Modifier.height(20.dp))
    val n = card.songs.size
    card.songs.forEachIndexed { i, s ->
        val p = appear(mode, 300L + (n - 1 - i) * 520L, 560)
        RankRow(
            rank = i + 1,
            title = s.displayTitle,
            subtitle = s.track.artist,
            trailing = plays(s.plays),
            art = { RecapArt(s.art(), if (i == 0) 60.dp else 50.dp, RoundedCornerShape(8.dp)) },
            colors = colors,
            modifier = Modifier.rise(p),
        )
        Spacer(Modifier.height(14.dp))
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.QuizCard(
    card: RecapCard.Quiz,
    colors: CardColors,
    mode: CardMode,
    answer: Int?,
    onAnswer: (Int) -> Unit,
) {
    val answered = answer != null && answer >= 0
    Spacer(Modifier.weight(0.7f))
    Eyebrow("Quick question", Modifier.rise(appear(mode, 0)))
    Spacer(Modifier.height(8.dp))
    Text("Which song did you play the most?", style = RecapType.statement, color = RecapInk.primary, modifier = Modifier.rise(appear(mode, 120)))
    Spacer(Modifier.height(24.dp))
    card.options.forEachIndexed { i, song ->
        val correct = i == card.correctIndex
        val picked = answer == i
        val revealed = answer != null
        val bg = when {
            revealed && correct -> colors.accent.copy(alpha = 0.22f)
            else -> RecapInk.glass
        }
        val border = when {
            revealed && correct -> colors.accent
            picked -> RecapInk.secondary
            else -> RecapInk.glassBorder
        }
        val dim = revealed && !correct && !picked
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .rise(appear(mode, 260L + i * 90))
                .graphicsLayer { alpha = if (dim) 0.45f else 1f }
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .border(BorderStroke(if (revealed && correct) 2.dp else 1.dp, border), RoundedCornerShape(16.dp))
                .then(if (!revealed && !mode.share) Modifier.clickable { onAnswer(i) } else Modifier)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append("${song.displayTitle} by ${song.track.artist}")
                        if (revealed && correct) append(", your number one")
                        else if (picked) append(", your pick")
                    }
                }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecapArt(song.art(), 46.dp, RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.displayTitle, style = RecapType.name.copy(fontSize = 17.sp), color = RecapInk.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.track.artist, style = RecapType.small, color = RecapInk.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (revealed && (correct || picked)) {
                Icon(
                    if (correct) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = null,
                    tint = if (correct) colors.accent else RecapInk.secondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    Spacer(Modifier.height(10.dp))
    // A fixed line, so the choices don't move when the answer appears.
    Text(
        text = when {
            answer == null -> ""
            answered && answer == card.correctIndex -> "You got it."
            answered -> "Not quite. It was ${card.options[card.correctIndex].displayTitle}."
            else -> "It was ${card.options[card.correctIndex].displayTitle}."
        },
        style = RecapType.name,
        color = RecapInk.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.height(28.dp),
    )
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.TopSongCard(card: RecapCard.TopSong, colors: CardColors, mode: CardMode) {
    val cover = appear(mode, 150, 800)
    Spacer(Modifier.weight(0.6f))
    Eyebrow("Your #1 song", Modifier.rise(appear(mode, 0)))
    Spacer(Modifier.height(18.dp))
    RecapArt(
        card.song.art(), 232.dp, RoundedCornerShape(16.dp),
        Modifier.graphicsLayer { alpha = cover; scaleX = 0.86f + 0.14f * cover; scaleY = 0.86f + 0.14f * cover },
    )
    Spacer(Modifier.height(22.dp))
    Text(
        card.song.displayTitle,
        style = RecapType.statement.copy(fontSize = 36.sp, lineHeight = 40.sp),
        color = RecapInk.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.rise(appear(mode, 700)),
    )
    Text(card.song.track.artist, style = RecapType.body, color = RecapInk.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rise(appear(mode, 800)))
    Spacer(Modifier.height(14.dp))
    Column(Modifier.rise(appear(mode, 1_100))) {
        Text(playsAndTime(card.song.plays, card.song.listenedMs, card.estimated), style = RecapType.name.copy(fontSize = 17.sp), color = colors.accent)
        card.firstListen?.let { f ->
            Spacer(Modifier.height(4.dp))
            Text("First played ${firstPlayed(f)}", style = RecapType.small, color = RecapInk.tertiary)
        }
    }
    Spacer(Modifier.weight(1f))
}

/** "Oct 2 at 8:11 PM" (+ year outside this one), in the phone's 12/24-hour style. */
@Composable
private fun firstPlayed(f: FirstListen): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val use24 = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    val today = remember { LocalDate.now().toEpochDay() }
    val day = f.localDay ?: java.time.Instant.ofEpochMilli(f.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
    return "${ListeningLabels.shortDay(day, today)} at ${ListeningLabels.timeOfDay(f.localMinute, f.startedAt, use24)}"
}

@Composable
private fun ColumnScope.OnRepeatCard(card: RecapCard.OnRepeat, colors: CardColors, mode: CardMode) {
    val o = card.obsession
    val count = countUp(o.plays, mode, delayMs = 700)
    val today = remember { LocalDate.now().toEpochDay() }
    Spacer(Modifier.weight(1f))
    Eyebrow("On repeat", Modifier.rise(appear(mode, 0)))
    Spacer(Modifier.height(10.dp))
    Text("On ${ListeningLabels.shortDay(o.day, today)} you played", style = RecapType.body.copy(fontSize = 22.sp), color = RecapInk.secondary, modifier = Modifier.rise(appear(mode, 150)))
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(appear(mode, 350))) {
        RecapArt((o.track.albumArtPath ?: o.track.albumArtUrl)?.let(::ArtModel), 72.dp, RoundedCornerShape(12.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(RecapTitles.display(o.track.title), style = RecapType.statement.copy(fontSize = 28.sp, lineHeight = 32.sp), color = RecapInk.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(o.track.artist, style = RecapType.small, color = RecapInk.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("$count", style = RecapType.huge.copy(fontSize = 136.sp, lineHeight = 132.sp), color = colors.accent)
    Text("times in one day.", style = RecapType.statement.copy(fontSize = 30.sp), color = RecapInk.primary)
    Spacer(Modifier.weight(1.2f))
}

@Composable
private fun ColumnScope.ClockCard(card: RecapCard.Clock, colors: CardColors, mode: CardMode) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val use24 = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    val labels = remember(use24) { listOf(0, 6, 12, 18).map { ListeningLabels.hour(it, use24) } }
    val peak = ListeningLabels.hour(card.peakHour, use24)
    Spacer(Modifier.weight(0.6f))
    Eyebrow("When you listen", Modifier.rise(appear(mode, 0)))
    Spacer(Modifier.height(6.dp))
    Text(card.label.title, style = RecapType.statement.copy(fontSize = 44.sp, lineHeight = 48.sp), color = RecapInk.primary, modifier = Modifier.rise(appear(mode, 150)))
    Spacer(Modifier.height(6.dp))
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold)) { append("${card.label.percent}%") }
            append(card.label.line.removePrefix("${card.label.percent}%"))
        },
        style = RecapType.body,
        color = RecapInk.secondary,
        modifier = Modifier.rise(appear(mode, 300)),
    )
    Spacer(Modifier.height(18.dp))
    val shown = appear(mode, 500, 900)
    // The clock draws in the theme's primary: the card's accent here.
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = colors.accent, onSurface = RecapInk.primary)) {
    ListeningClock(
        hourly = card.hourly.map { (it * shown).toInt() },
        peakHour = card.peakHour,
        hourLabels = labels,
        centerTop = "Peak",
        centerValue = peak,
        summary = "Listening clock. You listen most around $peak. ${card.label.title}: ${card.label.line}.",
        modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
    )
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.StreakCard(card: RecapCard.Streak, colors: CardColors, mode: CardMode) {
    Spacer(Modifier.weight(1f))
    card.longest?.let { s ->
        val n = countUp(s.days, mode, delayMs = 300)
        Eyebrow("Your longest streak", Modifier.rise(appear(mode, 0)))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$n", style = RecapType.huge, color = colors.accent)
            Spacer(Modifier.width(12.dp))
            Text("days in a row", style = RecapType.statement.copy(fontSize = 28.sp), color = RecapInk.primary, modifier = Modifier.padding(bottom = 18.dp))
        }
        Text(ListeningLabels.rangeCaption(s.fromDay, s.toDay, card.today), style = RecapType.small, color = RecapInk.tertiary)
        if (card.busiest != null) {
            Spacer(Modifier.height(30.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(RecapInk.faint))
            Spacer(Modifier.height(30.dp))
        }
    }
    card.busiest?.let { b ->
        Column(Modifier.rise(appear(mode, if (card.longest != null) 700 else 0))) {
            Eyebrow("Your busiest day")
            Spacer(Modifier.height(6.dp))
            Text(ListeningLabels.absoluteDay(b.day, card.today), style = RecapType.statement, color = RecapInk.primary)
            Spacer(Modifier.height(6.dp))
            Text(daySummary(b.plays, b.listenedMs, card.estimated), style = RecapType.body, color = RecapInk.secondary)
            card.busiestSong?.let { s ->
                Spacer(Modifier.height(18.dp))
                Text("Most played that day", style = RecapType.small, color = RecapInk.tertiary)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RecapArt(s.art(), 48.dp, RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.displayTitle, style = RecapType.name.copy(fontSize = 17.sp), color = RecapInk.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ListeningLabels.times(s.plays), style = RecapType.small, color = RecapInk.tertiary)
                    }
                }
            }
            // The day's song was already an earlier card's hero: the day's top songs as covers.
            if (card.busiestSong == null && card.dayTop.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Most played that day", style = RecapType.small, color = RecapInk.tertiary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    card.dayTop.forEach { s ->
                        Column(Modifier.width(76.dp).semantics(mergeDescendants = true) {
                            contentDescription = "${s.displayTitle}, ${ListeningLabels.times(s.plays)}"
                        }) {
                            RecapArt(s.art(), 76.dp, RoundedCornerShape(10.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(ListeningLabels.times(s.plays), style = RecapType.small, color = RecapInk.secondary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.weight(1.2f))
}

@Composable
private fun ColumnScope.DiscoveriesCard(card: RecapCard.Discoveries, colors: CardColors, mode: CardMode) {
    val n = countUp(card.newSongs, mode, delayMs = 300)
    Spacer(Modifier.weight(1f))
    Eyebrow("New to you", Modifier.rise(appear(mode, 0)))
    Text("$n", style = RecapType.huge, color = colors.accent)
    Text(if (card.newSongs == 1) "new song found" else "new songs found", style = RecapType.statement, color = RecapInk.primary)
    card.best?.let { s ->
        Spacer(Modifier.height(34.dp))
        Column(Modifier.rise(appear(mode, 1_000))) {
            Eyebrow("Best find")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecapArt(s.art(), 60.dp, RoundedCornerShape(10.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.displayTitle, style = RecapType.name, color = RecapInk.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.track.artist, style = RecapType.small, color = RecapInk.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Text(plays(s.plays), style = RecapType.small, color = RecapInk.secondary)
            }
        }
    }
    Spacer(Modifier.weight(1.2f))
}

/**
 * The summary — the screenshot: the period's title and time, top artists and songs side by side,
 * the top genre and the clock label. On screen and in the share image alike.
 */
@Composable
private fun ColumnScope.SummaryPoster(card: RecapCard.Summary, period: RecapPeriod, colors: CardColors, mode: CardMode) {
    val big = RecapDeck.bigTime(card.listenedMs)
    val value = countUp(big.value, mode, delayMs = 200)
    Spacer(Modifier.weight(0.5f))
    Text(card.title, style = RecapType.statement.copy(fontSize = 28.sp, lineHeight = 32.sp), color = RecapInk.primary, maxLines = 2)
    Spacer(Modifier.height(14.dp))
    if (card.estimated) Text("About", style = RecapType.small, color = RecapInk.tertiary)
    Row(verticalAlignment = Alignment.Bottom) {
        Text("%,d".format(value), style = RecapType.big.copy(fontSize = 60.sp, lineHeight = 60.sp), color = colors.accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = 8.dp).weight(1f)) {
            Text("${big.unit} listened", style = RecapType.name.copy(fontSize = 18.sp), color = RecapInk.primary)
        }
        RecapArt(card.songs.firstOrNull()?.art(), 72.dp, RoundedCornerShape(12.dp))
    }
    Spacer(Modifier.height(20.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(RecapInk.faint))
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth()) {
        SummaryList("Top artists", card.artists.map { it.name }, colors, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        SummaryList("Top songs", card.songs.map { it.displayTitle }, colors, Modifier.weight(1f))
    }
    if (card.topGenre != null || card.clockLabel != null) {
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(RecapInk.faint))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            card.topGenre?.let { SummaryFact("Top genre", it, Modifier.weight(1f)) }
            if (card.topGenre != null && card.clockLabel != null) Spacer(Modifier.width(16.dp))
            card.clockLabel?.let { SummaryFact(it.title, "${it.percent}% ${clockShort(it)}", Modifier.weight(1f)) }
        }
    }
    LocalRecapVoice.current.credit?.let { credit ->
        Spacer(Modifier.height(16.dp))
        Text(credit, style = RecapType.small, color = RecapInk.tertiary)
    }
    Spacer(Modifier.weight(1f))
}

/** The clock label's hours in a few words for the summary ("after 10 PM"). */
private fun clockShort(label: ClockLabel): String = label.line.substringAfter("of your listening ").ifBlank { "" }

@Composable
private fun SummaryList(title: String, items: List<String>, colors: CardColors, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = RecapType.small, color = RecapInk.tertiary)
        Spacer(Modifier.height(8.dp))
        items.take(RecapDeck.RANKED).forEachIndexed { i, name ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${i + 1}",
                    style = RecapType.name.copy(fontSize = 15.sp),
                    color = if (i == 0) colors.accent else RecapInk.tertiary,
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    name,
                    style = RecapType.name.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = RecapInk.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SummaryFact(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = RecapType.small, color = RecapInk.tertiary, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, style = RecapType.name.copy(fontSize = 17.sp), color = RecapInk.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
