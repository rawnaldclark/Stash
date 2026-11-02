package com.stash.feature.record.mixes

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.stash.core.ui.components.LocalBottomChromeInset
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashTextPrimary
import com.stash.core.ui.theme.StashTextSecondary
import com.stash.feature.record.R
import com.stash.feature.record.art.ArtSong
import com.stash.feature.record.art.RecordArt
import com.stash.feature.record.bridge.RecordPlayback
import com.stash.feature.record.card.RecordCard
import com.stash.feature.record.imports.RecordImports
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Your record's Mixes tab (stash-player `src/routes/spotify/+page.svelte`): "Mixes from your years" (this week years ago, the eras
 * in the order they came, then the songs that stayed, the lost favourites, what you had on repeat and how it started) and "Your
 * years" (each year's most played, oldest first), each a card in the web's words ([HistoryMixes.card]) over its albums' covers:
 * the card opens its mix ([onOpenMix]), its round button plays it here. Then Top artists (each opening their mix, or playing
 * their songs when they have too few for one), Top songs, and "Not in your Stash yet" (the favourites you lost that the library
 * doesn't hold, a heart on each: a song liked leaves the list at once). Every song row plays from it, likes in Stash, and opens
 * the song menu on a long press. With no import: what they need, and the way to bring it. On the record's dark ground in both
 * themes, as the record is.
 */
@Composable
internal fun RecordMixesTab(
    viewModel: RecordMixesTabViewModel,
    onOpenMix: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The words in the screen's language (the app's own language setting reaches the Activity, not the Application)
    val context = LocalContext.current
    val locales = LocalConfiguration.current.locales
    LaunchedEffect(locales) { viewModel.useWords(locales.toLanguageTags(), ResourceMixWords(context)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hearts by viewModel.hearts.collectAsStateWithLifecycle(initialValue = null)
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    val snackbar = remember { SnackbarHostState() }
    val resources = context.resources
    LaunchedEffect(viewModel) {
        viewModel.lines.collect { line -> launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(line.text(resources)) } }
    }
    var menuFor by remember { mutableStateOf<MenuSong?>(null) }
    var lostShown by rememberSaveable { mutableIntStateOf(HistoryMixes.LOST_PAGE) }
    // After a Like in "Not in your Stash yet" the row leaves, and focus goes on to the next row's heart, else the list's heading
    // (the web's lostClick): the row it was at, the hearts' focus by song key, the heading's
    var likedAt by remember { mutableStateOf<Int?>(null) }
    val lostHearts = remember { HashMap<String, FocusRequester>() }
    val lostHead = remember { FocusRequester() }
    Box(modifier) {
        when (val s = state) {
            null -> Unit
            RecordMixesTabViewModel.State.None -> Column(Modifier.windowInsetsPadding(bottom).padding(horizontal = 16.dp, vertical = 32.dp)) {
                SectionTitle(stringResource(R.string.record_mixes_from_years), Modifier)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.record_mixes_none), style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = StashTextSecondary)
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::bring) { Text(stringResource(R.string.record_mixes_none_action)) }
            }
            is RecordMixesTabViewModel.State.Mixes -> {
                // A song liked here leaves "Not in your Stash yet" at once (the list is the lost favourites minus your library, live)
                val lost = remember(s.lost, hearts) { s.lost.filterNot { hearts?.liked(it) == true } }
                LaunchedEffect(lost) {
                    val at = likedAt ?: return@LaunchedEffect
                    likedAt = null
                    val next = lost.getOrNull(at)?.let { lostHearts[it.songKey] } ?: lostHead
                    try {
                        next.requestFocus()
                    } catch (e: IllegalStateException) {
                        // That row isn't laid out (scrolled away): the heading, if it is
                        try {
                            lostHead.requestFocus()
                        } catch (e2: IllegalStateException) {
                            Log.d("YourRecord", "no row to move focus to after a like")
                        }
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    // The last row scrolls clear of the mini player
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + LocalBottomChromeInset.current),
                ) {
                    if (s.mixes.isNotEmpty()) item(key = "mixes") { CardRow(stringResource(R.string.record_mixes_from_years), s.mixes, onOpenMix, viewModel::play) }
                    if (s.years.isNotEmpty()) item(key = "years") { CardRow(stringResource(R.string.record_mixes_your_years), s.years, onOpenMix, viewModel::play) }
                    if (s.artists.isNotEmpty()) {
                        item(key = "artists") { SectionTitle(stringResource(R.string.record_mixes_top_artists), Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) }
                        itemsIndexed(s.artists, key = { _, a -> "artist:" + a.key }) { i, a ->
                            ArtistRow(i + 1, a, onOpen = { a.mixId?.let(onOpenMix) }, onPlay = { viewModel.playArtist(a.key) })
                        }
                        item(key = "artists-end") { Spacer(Modifier.height(24.dp)) }
                    }
                    if (s.tops.isNotEmpty()) {
                        item(key = "tops") { SectionTitle(stringResource(R.string.record_mixes_top_songs), Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) }
                        itemsIndexed(s.tops, key = { _, t -> "top:" + t.songKey }) { i, t ->
                            RecordSongRow(
                                song = t, cover = null, number = i + 1, square = RowSquare.NONE, liked = hearts?.liked(t) == true,
                                colors = RowColorsOf.Record, gutter = 16.dp,
                                onPlay = { viewModel.playTops(i) },
                                onLike = { viewModel.toggleLike(t, hearts?.liked(t) == true) },
                                onMenu = { menuFor = menuSong(t, null, i) },
                            )
                        }
                        item(key = "tops-end") { Spacer(Modifier.height(24.dp)) }
                    }
                    if (lost.isNotEmpty()) {
                        item(key = "lost") { LostHead(lost.size, onPlay = { viewModel.playLost(lost, 0) }, headFocus = lostHead) }
                        itemsIndexed(lost.take(lostShown), key = { _, t -> "lost:" + t.songKey }) { i, t ->
                            val heart = lostHearts.getOrPut(t.songKey) { FocusRequester() }
                            RecordSongRow(
                                song = t, cover = null, number = null, square = RowSquare.TINT_PLAY, liked = false,
                                colors = RowColorsOf.Record, gutter = 16.dp,
                                onPlay = { viewModel.playLost(lost, i) },
                                onLike = {
                                    likedAt = i
                                    viewModel.toggleLike(t, false)
                                },
                                onMenu = { menuFor = menuSong(t, null, i) },
                                heartModifier = Modifier.focusRequester(heart),
                            )
                        }
                        if (lost.size > lostShown) {
                            item(key = "lost-more") {
                                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                                    OutlinedButton(onClick = { lostShown += HistoryMixes.LOST_PAGE }) { Text(stringResource(R.string.record_mixes_show_more), color = StashTextPrimary) }
                                }
                            }
                        }
                    }
                    item(key = "foot") { Spacer(Modifier.windowInsetsPadding(bottom)) }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(bottom))
    }
    RecordSongMenu(menuFor, viewModel.actions, onDismiss = { menuFor = null })
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier) {
    Text(
        text,
        style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.01).em),
        color = StashTextPrimary,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * A Top artists row (`sy-art`): their rank, their picture (their initials over their colours until it comes), their name, their
 * hours and plays. With a mix it opens it, a faint chevron saying so (the web's on a phone); with too few songs for a mix it
 * plays them.
 */
@Composable
private fun ArtistRow(rank: Int, a: RecordMixesTabViewModel.ArtistCard, onOpen: () -> Unit, onPlay: () -> Unit) {
    val label = stringResource(if (a.mixId != null) R.string.record_mixes_artist_open else R.string.record_mixes_artist_play, a.name)
    Row(
        Modifier
            .fillMaxWidth()
            // At least 56 dp, taller when a large font needs it: a fixed height cut the name and its hours
            .heightIn(min = 56.dp)
            .clickable(onClick = if (a.mixId != null) onOpen else onPlay)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$rank",
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = StashTextSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(24.dp),
        )
        Box(Modifier.size(40.dp).clip(CircleShape).background(hueBrush(HistoryMixes.hueOf(a.name))), contentAlignment = Alignment.Center) {
            Text(RecordCard.initials(a.name), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
            if (a.picture != null) AsyncImage(a.picture, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(a.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = StashTextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(a.line, style = TextStyle(fontSize = 13.sp), color = StashTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (a.mixId != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = StashTextPrimary.copy(alpha = 0.55f))
        }
    }
}

/** "Not in your Stash yet"'s head: its title, how many and what to do, and a round Play for them all. */
@Composable
private fun LostHead(n: Int, onPlay: () -> Unit, headFocus: FocusRequester) {
    val context = LocalContext.current
    val locales = LocalConfiguration.current.locales
    val w = remember(locales) { ResourceMixWords(context) }
    val songs = w.songs(n)
    val play = stringResource(R.string.record_mixes_lost_play, songs)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            SectionTitle(stringResource(R.string.record_mixes_lost), Modifier.padding(bottom = 2.dp).focusRequester(headFocus).focusable())
            Text(w.lostLine(n), style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = StashTextSecondary)
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onPlay)
                .semantics {
                    contentDescription = play
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
        }
    }
}

/** A section's cards in a row that snaps card by card (the web's on a phone: each card about three quarters of the width). */
@Composable
private fun CardRow(
    title: String,
    cards: List<RecordMixesTabViewModel.Card>,
    onOpenMix: (String) -> Unit,
    onPlay: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SectionTitle(title, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth * 0.76f
            val list = rememberLazyListState()
            LazyRow(
                state = list,
                flingBehavior = rememberSnapFlingBehavior(list),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(cards, key = { it.id }) { c -> MixCard(c, Modifier.width(width), onOpen = { onOpenMix(c.id) }, onPlay = { onPlay(c.id) }) }
            }
        }
    }
}

/** The card's words over the card's wash: white, a little dimmer for the small lines (the web's rgb(255 255 255 / .82–.86)). */
private val CardSmall = Color.White.copy(alpha = 0.84f)

/** routes/spotify/+page.svelte's `mixCard`: the mix's albums behind (a 2x2 once four are found, else the first), its words, its foot and a round Play. */
@Composable
private fun MixCard(c: RecordMixesTabViewModel.Card, modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit) {
    val open = stringResource(R.string.record_mixes_open, c.title)
    val play = stringResource(R.string.record_mix_play_label, c.title)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(hueBrush(c.card.hue))
            .clickable(onClick = onOpen)
            .semantics {
                contentDescription = open
                role = Role.Button
            },
    ) {
        if (c.covers.isNotEmpty()) {
            Box(Modifier.matchParentSize()) {
                if (c.covers.size >= 4) {
                    Column(Modifier.fillMaxSize()) {
                        for (row in 0 until 2) {
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                for (col in 0 until 2) {
                                    AsyncImage(c.covers[row * 2 + col], null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                                }
                            }
                        }
                    }
                } else {
                    AsyncImage(c.covers[0], null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
        // The wash: darker over covers, so the white words keep their contrast on any of them
        Box(
            Modifier.matchParentSize().background(
                if (c.covers.isNotEmpty()) {
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.42f), 0.38f to Color.Black.copy(alpha = 0.25f), 1f to Color.Black.copy(alpha = 0.8f))
                } else {
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.4f)))
                },
            ),
        )
        Column(Modifier.fillMaxWidth().heightIn(min = 208.dp).padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    c.card.`when`.uppercase(),
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em, lineHeight = 15.sp),
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    c.card.who,
                    style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 29.sp, letterSpacing = (-0.025).em),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (c.card.line.isNotEmpty()) {
                    Text(c.card.line, style = TextStyle(fontSize = 14.sp, lineHeight = 19.sp), color = CardSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(c.card.foot, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp), color = CardSmall, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(44.dp)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onPlay)
                        .semantics {
                            contentDescription = play
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF111111), modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

/**
 * The Mixes tab's state: the two lists of cards, then Top artists (with their pictures), Top songs and "Not in your Stash yet",
 * worked out while the tab shows (the record doesn't need them), the last ones kept for a return; made again when the taste, the
 * library, the day or the language changes (off the main thread). Each card's covers, one card after another, top first; the
 * artists' pictures alongside. A read that fails keeps what the tab shows. The hearts ([hearts]) follow Stash's likes at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordMixesTabViewModel @Inject constructor(
    private val mixes: RecordMixes,
    private val art: RecordArt,
    private val playback: RecordPlayback,
    private val imports: RecordImports,
    /** A row's Like and its song menu. */
    val actions: RecordSongActions,
) : ViewModel() {
    /** A card: the mix, its words (mix.ts `card`) and the covers of its first albums. */
    data class Card(val id: String, val title: String, val card: HistoryMixes.MixCard, val covers: List<String>)

    /** A Top artists row: the artist, their line ("412 hours · 9,021 plays"), the mix it opens (null: it plays), their picture. */
    data class ArtistCard(val key: String, val name: String, val line: String, val mixId: String?, val picture: String?)

    sealed interface State {
        /** No import: mixes need years. */
        data object None : State

        data class Mixes(
            val mixes: List<Card>,
            val years: List<Card>,
            val artists: List<ArtistCard> = emptyList(),
            val tops: List<MixSong> = emptyList(),
            /** The lost favourites the library doesn't hold, as last made (the tab leaves out a song liked since, at once). */
            val lost: List<MixSong> = emptyList(),
        ) : State
    }

    /** The mixes as made, for Play (their songs and the covers found so far). */
    private var made: Map<String, Pair<HistoryMix, List<String?>>> = emptyMap()

    /** The mixes now, for Top artists' and the lists' plays. */
    private var now: MixesNow? = null

    /** The words the state was made in (a play's "Playing from"). */
    private var madeIn: MixWords? = null

    /** Each mix's covers as last found, with the songs they were found for (by mix id): a card made again keeps them. */
    private val lastCovers = java.util.concurrent.ConcurrentHashMap<String, Pair<List<String>, List<String?>>>()

    /** The artists' pictures as last found (by artist key): a list made again keeps them. */
    private val lastFaces = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The words, in the screen's language (by its tags): set by [RecordMixesTab]. */
    private val wordsIn = MutableStateFlow<Pair<String, MixWords>?>(null)

    fun useWords(tags: String, w: MixWords) {
        if (wordsIn.value?.first != tags) wordsIn.value = tags to w
    }

    private val likes = RowLikes(viewModelScope, actions, mixes.likes)

    /** Which hearts are filled now (liked in Stash, with a tap shown at once). */
    internal val hearts: Flow<RowLikes.Hearts> = likes.hearts

    /** What a row's Like or menu came to. */
    val lines: SharedFlow<SongLine> = actions.lines

    val state: StateFlow<State?> = combine(wordsIn.filterNotNull(), mixes.changes, mixes.libraryChanges, mixDays()) { w, _, _, _ -> w.second }
        .flatMapLatest { w ->
            channelFlow {
                val now = try {
                    mixes.now(w)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The tab keeps what it shows: a database that can't be read isn't "no import"
                    Log.w(TAG, "the record's mixes couldn't be made: ${e.javaClass.simpleName}")
                    return@channelFlow
                }
                if (now == null) {
                    this@RecordMixesTabViewModel.now = null
                    send(State.None)
                    return@channelFlow
                }
                val (tab, years) = now.tab
                val all = tab + years
                // A card made again (the tab shown again, the library changed) keeps the covers it had for the same songs
                val covers = all.map { m ->
                    val had = lastCovers[m.id]?.takeIf { it.first == m.items.map { s -> s.key } }?.second
                    m.items.mapIndexed { j, s -> s.lib?.art ?: had?.getOrNull(j) }
                }.toMutableList()
                // Every card's words (How it started's walks every song of the taste) and the lists: off the main thread
                val words = withContext(Dispatchers.Default) {
                    val stats = now.taste.songs.associateBy { it.key }
                    now.artists
                    now.tops
                    now.lost
                    all.map { HistoryMixes.card(it, now.taste, w, stats) }
                }
                val artists = now.artists
                val lines = artists.map { w.artistLine(it.artist.listenedMs, it.artist.plays) }
                fun state(): State {
                    made = all.indices.associate { all[it].id to (all[it] to covers[it]) }
                    this@RecordMixesTabViewModel.now = now
                    madeIn = w
                    val cards = all.indices.map { Card(all[it].id, all[it].title, words[it], cardCovers(covers[it])) }
                    val faces = artists.mapIndexed { i, r -> ArtistCard(r.artist.key, r.artist.name, lines[i], r.mixId, lastFaces[r.artist.key]) }
                    return State.Mixes(cards.take(tab.size), cards.drop(tab.size), faces, now.tops, now.lost)
                }
                send(state())
                // The artists' pictures (art.ts, cached by artist), alongside the cards' covers
                if (artists.isNotEmpty()) {
                    launch {
                        try {
                            val asks = artists.map { ArtSong(title = it.artist.name, artist = it.artist.name, isArtist = true) }
                            fun keep(urls: List<String?>) = urls.forEachIndexed { i, u -> if (u != null) lastFaces[artists[i].artist.key] = u }
                            keep(art.covers(asks, retry = this) { sofar -> keep(sofar); trySend(state()) })
                            send(state())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "the artists' pictures failed: ${e.javaClass.simpleName}")
                        }
                    }
                }
                for ((i, m) in all.withIndex()) {
                    try {
                        val found = mixCovers(art, m.items, albums = 4, retry = this) { sofar ->
                            covers[i] = sofar.mapIndexed { j, u -> u ?: covers[i].getOrNull(j) }
                            trySend(state())
                        }
                        covers[i] = found.mapIndexed { j, u -> u ?: covers[i].getOrNull(j) }
                        lastCovers[m.id] = m.items.map { it.key } to covers[i]
                        send(state())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "a mix's covers failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        // Stopped 5 s after the tab is left (the record shown again, the app in the background), its last state kept for a return
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A card's round Play: the mix from its first song, "Playing from" its title. */
    fun play(id: String) {
        val (m, covers) = made[id] ?: return
        playback.play(songsOf(m.items, covers), 0, m.title, shuffle = false)
    }

    /** A Top artists row with too few songs for a mix: their songs you played most (the page's `playArtist`), "Playing from" Your record. */
    fun playArtist(key: String) {
        val n = now ?: return
        playList(n.artistSongs(key), 0)
    }

    /** Top songs from its [index]th. */
    fun playTops(index: Int) {
        val n = now ?: return
        playList(n.tops, index)
    }

    /** "Not in your Stash yet" as it shows ([shown]: a song liked since left out), from its [index]th. */
    fun playLost(shown: List<MixSong>, index: Int) = playList(shown, index)

    private fun playList(songs: List<MixSong>, index: Int) {
        val w = madeIn ?: return
        playback.play(songsOf(songs, emptyList()), index, w.fromRecord, shuffle = false)
    }

    /** A row's heart: likes [song] in Stash, or (liked) takes it out. */
    fun toggleLike(song: MixSong, liked: Boolean) = likes.toggle(song, liked, cover = null)

    /** "Bring your listening": the import sheet. */
    fun bring() = imports.openSheet()

    private companion object {
        const val TAG = "YourRecord"
    }
}
