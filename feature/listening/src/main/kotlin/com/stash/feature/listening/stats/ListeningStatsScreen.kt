package com.stash.feature.listening.stats

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.db.dao.TopAlbumRow
import com.stash.core.data.db.dao.TopTrackRow
import com.stash.core.data.genre.GenreShare
import com.stash.core.data.listening.ListeningStatsCalculator.Bucket
import com.stash.core.data.listening.ListeningStatsCalculator.Granularity
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.listening.ListeningLabels
import com.stash.feature.listening.recap.PastRecap
import com.stash.feature.listening.recap.lastfm.LastFmPastRecapsViewModel
import com.stash.feature.listening.recap.RecapDoorCard
import com.stash.feature.listening.recap.RecapPeriod
import com.stash.feature.listening.ui.ArtImage
import com.stash.feature.listening.ui.ArtistGlyph
import com.stash.feature.listening.ui.CircleArt
import com.stash.feature.listening.ui.EstimatedFootnote
import com.stash.feature.listening.ui.genreCredit
import com.stash.feature.listening.ui.GenreBar
import com.stash.feature.listening.ui.genreFractions
import com.stash.feature.listening.ui.Gutter
import com.stash.feature.listening.ui.ListeningTopBar
import com.stash.feature.listening.ui.RangeChips
import com.stash.feature.listening.ui.RankedRow
import com.stash.feature.listening.ui.RefreshTodayEffect
import com.stash.feature.listening.ui.SquareArt
import com.stash.feature.listening.ui.StatsCard
import com.stash.feature.listening.ui.StatsSectionTitle
import com.stash.feature.listening.ui.charts.ListeningBars
import com.stash.feature.listening.ui.charts.ListeningClock
import com.stash.feature.listening.ui.charts.ListeningHeatmap

/** Top songs listed on this screen; See all opens the full list. */
private const val SONGS_SHOWN = 5

/**
 * "Your listening" (DESIGN.md Screen 1): for the chosen range, the time listened with its chart,
 * the counts, top artists, songs and albums, when you listen, your days and a few highlights.
 * Under ten listens it shows only the total and one line.
 *
 * Navigation goes out through the callbacks; the app wires them to History, Top, the artist and
 * album pages.
 */
@Composable
fun ListeningStatsScreen(
    onBack: () -> Unit,
    onOpenHistory: (day: Long?) -> Unit,
    onOpenTop: (kind: String, range: String) -> Unit,
    onOpenArtist: (name: String) -> Unit,
    onOpenAlbum: (album: String, artist: String) -> Unit,
    onOpenRecap: (kind: String, year: Int, month: Int) -> Unit = { _, _, _ -> },
    onOpenLastFmRecap: (kind: String, year: Int, month: Int) -> Unit = { _, _, _ -> },
    viewModel: ListeningStatsViewModel = hiltViewModel(),
    lastFmViewModel: LastFmPastRecapsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recap by viewModel.recapOffer.collectAsStateWithLifecycle()
    val pastRecaps by viewModel.pastRecaps.collectAsStateWithLifecycle()
    // The connected Last.fm account's recaps: their own group, apart from Stash's own listening.
    val lastFmRecaps by lastFmViewModel.recaps.collectAsStateWithLifecycle()
    val selected by viewModel.range.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val use24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    RefreshTodayEffect(viewModel, viewModel::refreshToday)
    val s = state
    val rangeKey = s.range.key

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ListeningTopBar(
            title = "Your listening",
            onBack = onBack,
            actions = {
                IconButton(onClick = { onOpenHistory(null) }) {
                    Icon(Icons.Rounded.History, contentDescription = "History")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp)),
        ) {
            item(key = "range") {
                RangeChips(selected = selected, onSelect = viewModel::selectRange)
                Text(
                    text = s.days?.let { ListeningLabels.rangeCaption(it.fromDay, it.toDay, s.today) }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = StashTheme.extendedColors.textTertiary,
                    modifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp),
                )
            }
            recap?.let { offer ->
                item(key = "recap") {
                    RecapDoorCard(
                        offer = offer,
                        eyebrow = "Watch your recap",
                        onClick = { onOpenRecap(offer.period.kind, offer.period.year, offer.period.monthArg) },
                        modifier = Modifier.padding(start = Gutter, end = Gutter, top = 16.dp),
                    )
                }
            }
            item(key = "hero") {
                HeroCard(s, onOpenDay = { onOpenHistory(it) })
            }
            if (s.loading) return@LazyColumn
            if (s.coldStart) {
                // Little Stash history yet, but the Last.fm account may have years of it.
                if (lastFmRecaps.isNotEmpty()) {
                    item(key = "past_recaps_title") { StatsSectionTitle("Past recaps") }
                    item(key = "lastfm_recaps") {
                        LastFmRecapsGroup(lastFmRecaps, s.today, onOpen = { p -> onOpenLastFmRecap(p.kind, p.year, p.monthArg) })
                    }
                }
                return@LazyColumn
            }

            item(key = "tiles") { Tiles(s) }

            if (s.topArtists.isNotEmpty()) {
                item(key = "artists_title") {
                    StatsSectionTitle("Top artists", action = "See all", onAction = { onOpenTop("artists", rangeKey) })
                }
                // Keyed on the range: a new range's row starts at its #1, not where the last one was scrolled.
                item(key = "artists") { key(s.range) { TopArtistsRow(s.topArtists, onOpenArtist) } }
            }
            if (s.topSongs.isNotEmpty()) {
                item(key = "songs_title") {
                    TopSongsTitle(onPlay = viewModel::playTopSongs, onSeeAll = { onOpenTop("songs", rangeKey) })
                }
                itemsIndexed(s.topSongs.take(SONGS_SHOWN), key = { _, r -> "song${r.track.id}" }) { i, row ->
                    TopSongRow(rank = i + 1, row = row, onClick = { viewModel.playTopSong(row.track.id) })
                }
            }
            if (s.topAlbums.isNotEmpty()) {
                item(key = "albums_title") {
                    StatsSectionTitle("Top albums", action = "See all", onAction = { onOpenTop("albums", rangeKey) })
                }
                item(key = "albums") { key(s.range) { TopAlbumsRow(s.topAlbums, onOpenAlbum) } }
            }
            // Hidden until enough of the range's listening has a genre (a top genre from a sliver
            // of it would be a guess shown as a fact).
            if (s.topGenres.isShowable) {
                item(key = "genres_title") {
                    StatsSectionTitle("Top genres", action = "See all", onAction = { onOpenTop("genres", rangeKey) })
                }
                item(key = "genres") { GenresCard(s.topGenres.items, s.topGenres.coverage) }
            }
            item(key = "clock_title") { StatsSectionTitle("When you listen") }
            item(key = "clock") { ClockCard(s, use24Hour) }
            item(key = "days_title") { StatsSectionTitle("Your days") }
            item(key = "days") { DaysCard(s, onOpenDay = { onOpenHistory(it) }) }
            if (s.obsession != null || s.newSongs > 0) {
                item(key = "highlights_title") { StatsSectionTitle("Highlights") }
                item(key = "highlights") { HighlightsCard(s, onOpenDay = { onOpenHistory(it) }) }
            }
            if (pastRecaps.isNotEmpty() || lastFmRecaps.isNotEmpty()) {
                item(key = "past_recaps_title") { StatsSectionTitle("Past recaps") }
            }
            if (pastRecaps.isNotEmpty()) {
                item(key = "past_recaps") {
                    PastRecapsRow(pastRecaps, s.today, onOpen = { p -> onOpenRecap(p.kind, p.year, p.monthArg) })
                }
            }
            if (lastFmRecaps.isNotEmpty()) {
                item(key = "lastfm_recaps") {
                    LastFmRecapsGroup(lastFmRecaps, s.today, onOpen = { p -> onOpenLastFmRecap(p.kind, p.year, p.monthArg) })
                }
            }
            if (s.estimated) item(key = "footnote") { EstimatedFootnote() }
        }
    }
}

// ── Hero ────────────────────────────────────────────────────────────────────

@Composable
private fun HeroCard(s: ListeningStatsState, onOpenDay: (Long) -> Unit) {
    StatsCard(Modifier.padding(start = Gutter, end = Gutter, top = 16.dp)) {
        Text(
            text = "Time listened",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (s.estimated) {
                Text(
                    text = "about ",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                text = if (s.loading) " " else ListeningLabels.listenTime(s.totals.listenedMs),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        if (!s.loading && s.deltaPercent != null && !s.coldStart) {
            Spacer(Modifier.height(6.dp))
            DeltaPill(s.deltaPercent, ListeningInsights.previousPhrase(s.range))
        }
        when {
            s.loading -> Spacer(Modifier.height(132.dp))
            s.coldStart -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (s.coversWholeHistory) {
                        "Your top songs, artists and listening habits show up here after ${ListeningInsights.COLD_START_PLAYS} listens."
                    } else {
                        "Not many listens in this period yet. Try a longer range."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Spacer(Modifier.height(16.dp))
                HeroBars(s, onOpenDay)
            }
        }
    }
}

@Composable
private fun DeltaPill(percent: Int, caption: String) {
    val colors = StashTheme.extendedColors
    val (icon, tint) = when {
        percent > 0 -> Icons.AutoMirrored.Rounded.TrendingUp to colors.success
        percent < 0 -> Icons.AutoMirrored.Rounded.TrendingDown to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.AutoMirrored.Rounded.TrendingFlat to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val number = when {
        percent > 0 -> "+$percent%"
        percent < 0 -> "−${-percent}%"
        else -> "Same"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$number $caption" },
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(number, style = MaterialTheme.typography.labelMedium, color = tint)
        }
        Spacer(Modifier.width(8.dp))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HeroBars(s: ListeningStatsState, onOpenDay: (Long) -> Unit) {
    val buckets = s.buckets
    if (buckets.isEmpty()) return
    val values = remember(buckets) { buckets.map { it.listenedMs } }
    val peak = remember(values) { values.indices.maxByOrNull { values[it] }?.takeIf { values[it] > 0 } }
    val unit = when (s.granularity) {
        Granularity.DAY -> "day"
        Granularity.WEEK -> "week"
        Granularity.MONTH -> "month"
    }
    val summary = buildString {
        append("Listening time per $unit")
        s.days?.let { append(", ${ListeningLabels.rangeCaption(it.fromDay, it.toDay, s.today)}") }
        if (peak != null) {
            append(". Most: ${bucketLabel(buckets[peak], s.granularity, s.today)}, ${ListeningLabels.listenTime(values[peak])}.")
        }
    }
    // A time axis reads oldest to newest left to right in every language: the bars and their
    // labels stay left-to-right in a right-to-left layout too.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Column {
    ListeningBars(
        values = values,
        summary = summary,
        highlight = peak,
        onBarClick = { i -> onOpenDay(minOf(buckets[i].toDay, s.today)) },
        // TalkBack can't tap a bar: each bar with listening is an action in its actions menu.
        barActionLabel = { i -> "Open ${bucketLabel(buckets[i], s.granularity, s.today)} in History" },
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(
            bucketLabel(buckets.first(), s.granularity, s.today),
            style = MaterialTheme.typography.labelSmall,
            color = StashTheme.extendedColors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (buckets.last().toDay == s.today && s.granularity == Granularity.DAY) "Today" else bucketLabel(buckets.last(), s.granularity, s.today),
            style = MaterialTheme.typography.labelSmall,
            color = StashTheme.extendedColors.textTertiary,
        )
    }
    }
    }
}

/** A bar's name: "Oct 11" for a day or the week's first day, "Oct" (+ year) for a month. */
private fun bucketLabel(b: Bucket, g: Granularity, today: Long): String = when (g) {
    Granularity.MONTH -> java.time.LocalDate.ofEpochDay(b.fromDay).let { d ->
        val pattern = if (d.year == java.time.LocalDate.ofEpochDay(today).year) "MMM" else "MMM yyyy"
        d.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    }
    else -> ListeningLabels.shortDay(b.fromDay, today)
}

// ── Tiles ───────────────────────────────────────────────────────────────────

/**
 * Plays · Songs · Artists · streak as one glass strip of four columns with hairlines between
 * them: each column gets the full quarter (a row of four separate cards left too little room
 * for "days streak"). Artists counts main acts, the same as the Top artists list.
 */
@Composable
private fun Tiles(s: ListeningStatsState) {
    StatsCard(Modifier.padding(start = Gutter, end = Gutter, top = 12.dp), contentPadding = 0.dp) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Tile("%,d".format(s.totals.plays), if (s.totals.plays == 1) "play" else "plays", Modifier.weight(1f))
            TileDivider()
            Tile("%,d".format(s.totals.songs), if (s.totals.songs == 1) "song" else "songs", Modifier.weight(1f))
            TileDivider()
            Tile("%,d".format(s.artistCount), if (s.artistCount == 1) "artist" else "artists", Modifier.weight(1f))
            TileDivider()
            Tile(
                "${s.currentStreak}", ListeningLabels.streakUnit(s.currentStreak), Modifier.weight(1f),
                spoken = ListeningLabels.streak(s.currentStreak),
            )
        }
    }
}

@Composable
private fun TileDivider() {
    Box(
        Modifier
            .padding(vertical = 14.dp)
            .width(1.dp)
            .fillMaxHeight()
            .background(StashTheme.extendedColors.glassBorder),
    )
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier = Modifier, spoken: String = "$value $label") {
    Column(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Top artists, songs, albums ──────────────────────────────────────────────

@Composable
private fun TopArtistsRow(artists: List<TopArtistUi>, onOpenArtist: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        // Bottom: the names line up even though #1's circle is bigger.
        verticalAlignment = Alignment.Bottom,
    ) {
        itemsIndexed(artists, key = { _, a -> a.key }) { i, artist ->
            val size: Dp = if (i == 0) 112.dp else 84.dp
            Column(
                modifier = Modifier
                    .width(size)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenArtist(artist.name) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Number ${i + 1}, ${artist.name}, ${ListeningLabels.plays(artist.plays)}"
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ArtImage(
                    path = if (artist.photoUrl != null) null else artist.artPath,
                    url = artist.photoUrl ?: artist.artUrl,
                    size = size,
                    shape = CircleArt,
                    placeholder = ArtistGlyph,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    artist.name,
                    style = if (i == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    ListeningLabels.plays(artist.plays),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TopSongsTitle(onPlay: () -> Unit, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter - 4.dp, top = 28.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsTitleText("Top songs", Modifier.weight(1f))
        PlayPill(onPlay)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "See all",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSeeAll).padding(4.dp),
        )
    }
}

@Composable
private fun StatsTitleText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = modifier)
}

/** A small filled "Play" pill. */
@Composable
internal fun PlayPill(onClick: () -> Unit, label: String = "Play") {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun TopSongRow(rank: Int, row: TopTrackRow, onClick: () -> Unit) {
    RankedRow(
        rank = rank,
        title = row.track.title,
        subtitle = row.track.artist,
        plays = ListeningLabels.plays(row.plays),
        time = row.listenedMs.takeIf { it > 0 }?.let(ListeningLabels::listenTime),
        onClick = onClick,
        art = { ArtImage(row.track.albumArtPath, row.track.albumArtUrl, 48.dp, SquareArt) },
    )
}

@Composable
private fun TopAlbumsRow(albums: List<TopAlbumRow>, onOpenAlbum: (String, String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(albums, key = { _, a -> ListeningInsights.albumKey(a.album, a.artist) }) { i, album ->
            Column(
                modifier = Modifier
                    .width(AlbumSize)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenAlbum(album.album, album.artist) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Number ${i + 1}, ${album.album} by ${album.artist}, ${ListeningLabels.plays(album.plays)}"
                    },
            ) {
                Box {
                    ArtImage(album.albumArtPath, album.albumArtUrl, AlbumSize, RoundedCornerShape(10.dp))
                    RankBadge(i + 1, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(album.album, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A rank on a cover: a small dark disc with the number, legible on any art. */
@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$rank", style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.White)
    }
}

private val AlbumSize = 132.dp

// ── Past recaps ─────────────────────────────────────────────────────────────

/**
 * The Last.fm account's recaps (LASTFM-RECAP.md §5): a small "On Last.fm" label over the same
 * tiles, counted in scrobbles, each opening the Last.fm story. Kept visibly apart from Stash's own.
 */
@Composable
private fun LastFmRecapsGroup(recaps: List<PastRecap>, today: Long, onOpen: (RecapPeriod) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            text = "On Last.fm",
            style = MaterialTheme.typography.labelLarge,
            color = StashTheme.extendedColors.textTertiary,
            modifier = Modifier
                .padding(start = Gutter, end = Gutter, bottom = 8.dp)
                .semantics { heading() },
        )
        PastRecapsRow(
            recaps = recaps,
            today = today,
            onOpen = onOpen,
            count = { n -> if (n == 1) "1 scrobble" else "%,d scrobbles".format(java.util.Locale.US, n) },
            describe = { name, line -> "Your $name on Last.fm, $line" },
        )
    }
}

/** Every recap the history holds, as small glass tiles: "October" / "so far · 82 plays". */
@Composable
private fun PastRecapsRow(
    recaps: List<PastRecap>,
    today: Long,
    onOpen: (RecapPeriod) -> Unit,
    count: (Int) -> String = ListeningLabels::plays,
    describe: (name: String, line: String) -> String = { name, line -> "Your $name recap, $line" },
) {
    val colors = StashTheme.extendedColors
    val thisYear = java.time.LocalDate.ofEpochDay(today).year
    LazyRow(
        contentPadding = PaddingValues(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(recaps, key = { _, r -> r.period.kind + r.period.year + "-" + r.period.monthArg }) { _, r ->
            val name = when (val p = r.period) {
                is RecapPeriod.YearOf -> "${p.year}"
                is RecapPeriod.MonthOf -> if (p.year == thisYear) p.name() else p.longName()
            }
            val line = buildString {
                if (r.period.inProgress(today)) append("so far · ")
                append(count(r.plays))
            }
            Surface(
                onClick = { onOpen(r.period) },
                shape = MaterialTheme.shapes.medium,
                color = colors.glassBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassBorder),
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = describe(name, line) },
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

// ── Top genres ──────────────────────────────────────────────────────────────

/** Up to five genres as bars, each "% of your listening" time, with Last.fm's credit under them. */
@Composable
private fun GenresCard(genres: List<GenreShare>, coverage: Double) {
    val fractions = remember(genres) { genreFractions(genres) }
    StatsCard(Modifier.padding(horizontal = Gutter)) {
        genres.forEachIndexed { i, genre ->
            if (i > 0) Spacer(Modifier.height(14.dp))
            GenreBar(
                genre = genre,
                fraction = fractions[i],
                color = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
        }
    }
    Text(
        text = genreCredit(coverage),
        style = MaterialTheme.typography.bodySmall,
        color = StashTheme.extendedColors.textTertiary,
        modifier = Modifier.padding(start = Gutter, end = Gutter, top = 8.dp),
    )
}

// ── When you listen ─────────────────────────────────────────────────────────

@Composable
private fun ClockCard(s: ListeningStatsState, use24Hour: Boolean) {
    val peak = s.peakHour
    val split = s.daySplit
    val labels = remember(use24Hour) { listOf(0, 6, 12, 18).map { ListeningLabels.hour(it, use24Hour) } }
    val peakText = peak?.let { ListeningLabels.hour(it, use24Hour) } ?: "—"
    val summary = buildString {
        append("Listening clock.")
        if (peak != null) append(" You listen most around $peakText.")
        if (split != null) {
            append(" Morning ${split.morning}%, afternoon ${split.afternoon}%, evening ${split.evening}%, night ${split.night}%.")
        }
    }
    StatsCard(Modifier.padding(horizontal = Gutter)) {
        ListeningClock(
            hourly = s.hourly,
            peakHour = peak,
            hourLabels = labels,
            centerTop = "Peak",
            centerValue = peakText,
            summary = summary,
            modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
        )
        if (split != null) {
            Spacer(Modifier.height(12.dp))
            val parts = listOf("Morning" to split.morning, "Afternoon" to split.afternoon, "Evening" to split.evening, "Night" to split.night)
            val top = parts.maxOf { it.second }
            Row(Modifier.fillMaxWidth()) {
                parts.forEach { (name, pct) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "$pct%",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (pct == top) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── Your days ───────────────────────────────────────────────────────────────

@Composable
private fun DaysCard(s: ListeningStatsState, onOpenDay: (Long) -> Unit) {
    StatsCard(Modifier.padding(horizontal = Gutter)) {
        Row(Modifier.fillMaxWidth()) {
            DayFact("Current streak", ListeningLabels.days(s.currentStreak), Modifier.weight(1f))
            DayFact("Longest streak", ListeningLabels.days(s.longestStreak?.days ?: 0), Modifier.weight(1f))
            DayFact("Days listened", "${s.totals.days}", Modifier.weight(1f))
        }
        s.busiestDay?.let { busiest ->
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(StashTheme.extendedColors.glassBackground)
                    .clickable { onOpenDay(busiest.day) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Busiest day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        ListeningLabels.absoluteDay(busiest.day, s.today),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    ListeningLabels.daySummary(busiest.plays, busiest.listenedMs).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (s.heatmap.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            ListeningHeatmap(
                weeks = s.heatmap,
                summary = "Calendar of listening days, ${s.totals.days} days with listens.",
                dayActionLabel = { day -> "Open ${ListeningLabels.absoluteDay(day, s.today)} in History" },
                onDayClick = onOpenDay,
            )
        }
    }
}

@Composable
private fun DayFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

// ── Highlights ──────────────────────────────────────────────────────────────

@Composable
private fun HighlightsCard(s: ListeningStatsState, onOpenDay: (Long) -> Unit) {
    StatsCard(Modifier.padding(horizontal = Gutter), contentPadding = 4.dp) {
        s.obsession?.let { o ->
            Highlight(
                label = "On repeat",
                title = o.track.title,
                line = "${ListeningLabels.times(o.plays)} on ${ListeningLabels.shortDay(o.day, s.today)}",
                art = { ArtImage(o.track.albumArtPath, o.track.albumArtUrl, 52.dp, SquareArt) },
                onClick = { onOpenDay(o.day) },
            )
        }
        if (s.newSongs > 0) {
            val best = s.bestNewFind
            Highlight(
                label = "New to you",
                title = if (s.newSongs == 1) "1 new song" else "${s.newSongs} new songs",
                line = best?.let { "Best find: ${it.track.title} · ${it.track.artist}" } ?: "First heard in this period",
                art = { ArtImage(best?.track?.albumArtPath, best?.track?.albumArtUrl, 52.dp, SquareArt) },
                onClick = null,
            )
        }
    }
}

@Composable
private fun Highlight(
    label: String,
    title: String,
    line: String,
    art: @Composable () -> Unit,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
