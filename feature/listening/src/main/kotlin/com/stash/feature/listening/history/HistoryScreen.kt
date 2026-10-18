package com.stash.feature.listening.history

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.db.dao.HistoryRow
import com.stash.core.data.mapper.toDomain
import com.stash.core.model.Track
import com.stash.core.ui.components.PlaylistInfo
import com.stash.core.ui.components.SaveToPlaylistSheet
import com.stash.core.ui.components.SearchFilterBar
import com.stash.core.ui.components.ShareTrackSheet
import com.stash.core.ui.components.SheetOptionRow
import com.stash.core.ui.components.TrackListItem
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.components.TrackRowFitScope
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.listening.ListeningLabels
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay

/** How close to an end of the loaded list the next page starts loading. */
private const val PREFETCH_ROWS = 30

/**
 * History (plan §3.1): every listen, newest first, under a sticky header per day ("Today · 12
 * plays · 47 min"). Tap a song to replay that day from it; long-press for the usual song options
 * plus Remove from history; each day's ⋮ plays or removes the whole day. Search filters on title,
 * artist and album; the calendar jumps to any day with listens.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nowPlayingId by viewModel.nowPlayingTrackId.collectAsStateWithLifecycle()
    val playlists by viewModel.userPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    val use24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    var searchOpen by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<HistoryRow?>(null) }
    var shareTrack by remember { mutableStateOf<Track?>(null) }
    var saveTrack by remember { mutableStateOf<Track?>(null) }
    var confirmRemoveDay by rememberSaveable { mutableStateOf<Long?>(null) }

    val closeSearch = {
        searchOpen = false
        viewModel.onQueryChange("")
    }
    BackHandler(enabled = searchOpen, onBack = closeSearch)

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(viewModel) {
        viewModel.scrollToDay.collect { day ->
            val s = viewModel.state.value
            listState.scrollToItem(indexOfDay(s.days, day, leadingKeys(s).size))
        }
    }
    // "Today" moves on at midnight and when the screen comes back after a while.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshToday()
        onPauseOrDispose {}
    }
    LaunchedEffect(viewModel) {
        while (true) {
            val now = java.time.ZonedDateTime.now()
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            delay(java.time.Duration.between(now, midnight).toMillis() + 1_000)
            viewModel.refreshToday()
        }
    }
    // Paging: the next older page near the end, the next newer one near the top after a jump.
    // Keyed on the item count too, so a page that still leaves the list near its end asks again.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            ((info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - PREFETCH_ROWS) to info.totalItemsCount
        }.collect { (nearEnd, _) -> if (nearEnd) viewModel.loadOlder() }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val near = listState.firstVisibleItemIndex < PREFETCH_ROWS && state.hasNewer &&
                listState.layoutInfo.totalItemsCount > 0
            near to listState.layoutInfo.totalItemsCount
        }.collect { (nearTop, _) -> if (nearTop) viewModel.loadNewer() }
    }
    // Keep the row the user is on where it is when rows go in above it (a newer page, a day read
    // again). Compose re-finds the first visible item by key only within ~100 items of where it
    // was, and a page is 200 rows; at the very top it doesn't try at all. So: after the new rows
    // are composed and before they're laid out, the item that was first (by index, the one the
    // offset belongs to; not the pinned sticky header) is asked to stay first. Not at the head
    // of the list, where a listen that just landed should show on top.
    val anchorFrom = remember { arrayOfNulls<HistoryUiState>(1) }
    SideEffect {
        val previous = anchorFrom[0]
        anchorFrom[0] = state
        if (previous == null || previous.days === state.days || previous.listId != state.listId) return@SideEffect
        val oldIndex = listState.firstVisibleItemIndex
        if (oldIndex <= 1 && !previous.hasNewer) return@SideEffect
        val key = keyAt(previous, oldIndex) ?: return@SideEffect
        val newIndex = indexOfKey(state, key) ?: return@SideEffect
        if (newIndex != oldIndex) listState.requestScrollToItem(newIndex, listState.firstVisibleItemScrollOffset)
    }
    // A listen that lands while the newest ones are on screen shows on top instead of above it.
    val newestId = state.days.firstOrNull()?.rows?.firstOrNull()?.event?.id
    LaunchedEffect(newestId) {
        if (newestId != null && !state.hasNewer && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            HistoryTopBar(
                searchOpen = searchOpen,
                calendarEnabled = state.dayCounts.isNotEmpty(),
                onBack = onBack,
                onToggleSearch = { if (searchOpen) closeSearch() else searchOpen = true },
                onOpenCalendar = { calendarOpen = true },
            )
            if (searchOpen) {
                SearchFilterBar(
                    query = state.query,
                    onQueryChanged = viewModel::onQueryChange,
                    onClear = { viewModel.onQueryChange("") },
                    placeholder = "Search your history",
                )
            }
            Box(Modifier.weight(1f)) {
                TrackRowFitScope {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomChromePadding(16.dp)),
                    ) {
                        if (state.paused) {
                            item(key = "paused") { PausedCard(onResume = viewModel::resumeHistory) }
                        }
                        if (state.showColdStart) {
                            item(key = "cold") { ColdStartCard() }
                        }
                        if (state.noMatches) {
                            item(key = "nomatch") { NoMatches(state.query) }
                        }
                        state.days.forEach { group ->
                            stickyHeader(key = group.key, contentType = "header") {
                                val counts = state.dayCounts[group.day]
                                DayHeader(
                                    title = ListeningLabels.dayTitle(group.day, state.today),
                                    // While searching, the day's totals would read as the matches' count.
                                    summary = if (state.query.isBlank()) {
                                        ListeningLabels.daySummary(counts?.plays, counts?.listenedMs)
                                    } else {
                                        null
                                    },
                                    onPlayDay = { viewModel.playDay(group.day) },
                                    onRemoveDay = { confirmRemoveDay = group.day },
                                )
                            }
                            items(group.rows, key = { "e${it.event.id}" }, contentType = { "row" }) { row ->
                                val track = remember(row.track) { row.track.toDomain() }
                                val time = ListeningLabels.timeOfDay(row.event.localMinute, row.event.startedAt, use24Hour)
                                TrackListItem(
                                    track = track,
                                    isPlaying = track.id == nowPlayingId,
                                    onClick = { viewModel.play(row) },
                                    onLongPress = { optionsFor = row },
                                    trailingText = ListeningLabels.rowTrailing(time, ListeningLabels.origin(row.event.originDevice)),
                                )
                            }
                        }
                        if (state.hasOlder) {
                            item(key = "more", contentType = "more") {
                                if (state.olderFailed) LoadMoreFailed(onRetry = viewModel::retryOlder) else LoadingMore()
                            }
                        }
                    }
                }
                if (state.hasNewer) {
                    BackToToday(
                        onClick = viewModel::backToLatest,
                        // Below the sticky day header, never over it.
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomChromePadding(8.dp)),
        ) { Snackbar(snackbarData = it) }
    }

    if (calendarOpen) {
        ListeningCalendarSheet(
            dayCounts = state.dayCounts,
            firstDay = state.dayCounts.keys.minOrNull() ?: state.today,
            today = state.today,
            onPick = { day ->
                calendarOpen = false
                viewModel.jumpTo(day)
            },
            onDismiss = { calendarOpen = false },
        )
    }

    optionsFor?.let { row ->
        val sheetState = rememberModalBottomSheetState()
        val track = remember(row.track) { row.track.toDomain() }
        ModalBottomSheet(
            onDismissRequest = { optionsFor = null },
            sheetState = sheetState,
            containerColor = StashTheme.extendedColors.elevatedSurface,
        ) {
            TrackOptionsSheet(
                track = track,
                onPlayNext = { viewModel.playNext(it); optionsFor = null },
                onAddToQueue = { viewModel.addToQueue(it); optionsFor = null },
                onSaveToPlaylist = { saveTrack = it; optionsFor = null },
                onShare = { shareTrack = it; optionsFor = null },
                onDismiss = { optionsFor = null },
                extraOptions = {
                    SheetOptionRow(
                        icon = Icons.Outlined.RemoveCircleOutline,
                        label = "Remove from history",
                        onClick = {
                            viewModel.removeListen(row)
                            optionsFor = null
                        },
                    )
                },
            )
        }
    }

    shareTrack?.let { ShareTrackSheet(track = it, onDismiss = { shareTrack = null }) }

    saveTrack?.let { track ->
        SaveToPlaylistSheet(
            playlists = playlists.map { PlaylistInfo(it.id, it.name, it.trackCount) },
            onSaveToPlaylist = { id -> viewModel.saveToPlaylist(track.id, id); saveTrack = null },
            onCreatePlaylist = { name -> viewModel.createPlaylistAndAdd(name, track.id); saveTrack = null },
            onDismiss = { saveTrack = null },
        )
    }

    confirmRemoveDay?.let { day ->
        val plays = state.dayCounts[day]?.plays ?: 0
        val title = ListeningLabels.dayTitle(day, state.today)
        AlertDialog(
            onDismissRequest = { confirmRemoveDay = null },
            title = { Text("Remove $title from your history?") },
            text = {
                Text(
                    (if (plays > 0) "Its ${ListeningLabels.plays(plays)} leave this phone's listening history. " else "") +
                        "This doesn't remove anything already sent to Last.fm, ListenBrainz or YouTube Music.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDay(day)
                    confirmRemoveDay = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveDay = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The list index of the item with [key] in [state]'s list (the same items, in the same order, as
 * the LazyColumn below), or null when it's gone.
 */
internal fun indexOfKey(state: HistoryUiState, key: Any): Int? {
    var index = 0
    for (lead in leadingKeys(state)) {
        if (lead == key) return index
        index++
    }
    for (group in state.days) {
        if (group.key == key) return index
        index++
        for (row in group.rows) {
            if ("e${row.event.id}" == key) return index
            index++
        }
    }
    return null
}

/** The key of the item at [index] in [state]'s list, or null past its end. */
internal fun keyAt(state: HistoryUiState, index: Int): Any? {
    val lead = leadingKeys(state)
    if (index < lead.size) return lead[index]
    var i = lead.size
    for (group in state.days) {
        if (i == index) return group.key
        i++
        if (index < i + group.rows.size) return "e${group.rows[index - i].event.id}"
        i += group.rows.size
    }
    return null
}

/** The keys of the cards above the first day header, in list order. */
internal fun leadingKeys(state: HistoryUiState): List<String> = listOfNotNull(
    "paused".takeIf { state.paused },
    "cold".takeIf { state.showColdStart },
    "nomatch".takeIf { state.noMatches },
)

/** Index of the header of the first listed day on or before [day] ([Long.MAX_VALUE] = the top). */
internal fun indexOfDay(days: List<HistoryDay>, day: Long, leading: Int): Int {
    if (day == Long.MAX_VALUE) return 0
    var index = leading
    for (group in days) {
        if (group.day <= day) return index
        index += 1 + group.rows.size
    }
    return (index - 1).coerceAtLeast(0)
}

@Composable
private fun HistoryTopBar(
    searchOpen: Boolean,
    calendarEnabled: Boolean,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenCalendar: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(
            text = "History",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleSearch) {
            Icon(
                imageVector = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                contentDescription = if (searchOpen) "Close search" else "Search history",
            )
        }
        IconButton(onClick = onOpenCalendar, enabled = calendarEnabled) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = "Jump to a day")
        }
    }
}

/**
 * A day's sticky header: the page's background under a glass strip (so rows scroll away beneath
 * it), a short brand bar, the day and its totals, and ⋮ for the whole day.
 */
@Composable
private fun DayHeader(
    title: String,
    summary: String?,
    onPlayDay: () -> Unit,
    onRemoveDay: () -> Unit,
) {
    val colors = StashTheme.extendedColors
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .semantics { heading() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.glassBackground)
                .padding(start = 20.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            if (summary != null) {
                Text(
                    text = "  ·  $summary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "$title options",
                        tint = colors.textTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Play this day") },
                        leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                        onClick = { menuOpen = false; onPlayDay() },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove this day") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                        onClick = { menuOpen = false; onRemoveDay() },
                    )
                }
            }
        }
    }
}

/** History paused in Settings: said once, at the top, with the way back. */
@Composable
private fun PausedCard(onResume: () -> Unit) {
    val colors = StashTheme.extendedColors
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = colors.glassBackground,
        border = BorderStroke(1.dp, colors.glassBorder),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.PauseCircleOutline,
                contentDescription = null,
                tint = colors.warning,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("History is paused", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "New listens aren't saved or scrobbled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onResume) { Text("Resume") }
        }
    }
}

/** No listen yet: what will appear here, and when a song counts. */
@Composable
private fun ColdStartCard() {
    val colors = StashTheme.extendedColors
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = colors.glassBackground,
        border = BorderStroke(1.dp, colors.glassBorder),
    ) {
        Column(Modifier.padding(20.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(14.dp))
            Text("Your history starts here", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(
                "Every song you listen to lands here, newest first. A song counts once you've heard half of it: " +
                    "at least 30 seconds, at most 4 minutes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoMatches(query: String) {
    Text(
        text = "No listens match “${query.trim()}”",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
    )
}

/** An older page failed: say so, and offer to try again. */
@Composable
private fun LoadMoreFailed(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Couldn't load older listens",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun LoadingMore() {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
    }
}

/** After a calendar jump: back to the newest listens in one tap. */
@Composable
private fun BackToToday(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            Text("Back to today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
