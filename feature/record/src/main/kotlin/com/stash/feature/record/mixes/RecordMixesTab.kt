package com.stash.feature.record.mixes

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.stash.core.ui.theme.SpaceGrotesk
import com.stash.core.ui.theme.StashTextPrimary
import com.stash.core.ui.theme.StashTextSecondary
import com.stash.feature.record.R
import com.stash.feature.record.art.RecordArt
import com.stash.feature.record.bridge.RecordPlayback
import com.stash.feature.record.imports.RecordImports
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Your record's Mixes tab (stash-player `src/routes/spotify/+page.svelte`): "Mixes from your years" (this week years ago, the eras
 * in the order they came, then the songs that stayed, the lost favourites, what you had on repeat and how it started) and "Your
 * years" (each year's most played, oldest first), each a card in the web's words ([HistoryMixes.card]) over its albums' covers:
 * the card opens its mix ([onOpenMix]), its round button plays it here. With no import: what they need, and the way to bring it.
 * On the record's dark ground in both themes, as the record is.
 */
@Composable
internal fun RecordMixesTab(
    viewModel: RecordMixesTabViewModel,
    onOpenMix: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The words in the screen's language (the app's own language setting reaches the Activity, not the Application)
    val res = LocalContext.current.resources
    val locales = LocalConfiguration.current.locales
    LaunchedEffect(locales) { viewModel.useWords(locales.toLanguageTags(), ResourceMixWords(res)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    when (val s = state) {
        null -> Box(modifier)
        RecordMixesTabViewModel.State.None -> Column(modifier.windowInsetsPadding(bottom).padding(horizontal = 16.dp, vertical = 32.dp)) {
            SectionTitle(stringResource(R.string.record_mixes_from_years), Modifier)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.record_mixes_none), style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = StashTextSecondary)
            Spacer(Modifier.height(16.dp))
            Button(onClick = viewModel::bring) { Text(stringResource(R.string.record_mixes_none_action)) }
        }
        is RecordMixesTabViewModel.State.Mixes -> LazyColumn(
            modifier,
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            if (s.mixes.isNotEmpty()) item(key = "mixes") { CardRow(stringResource(R.string.record_mixes_from_years), s.mixes, onOpenMix, viewModel::play) }
            if (s.years.isNotEmpty()) item(key = "years") { CardRow(stringResource(R.string.record_mixes_your_years), s.years, onOpenMix, viewModel::play) }
            item(key = "foot") { Spacer(Modifier.windowInsetsPadding(bottom)) }
        }
    }
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
 * The Mixes tab's state: the two lists of cards, worked out the first time the tab shows (the record doesn't need them) and kept
 * while the record is shown again; made again when the taste, the library, the day or the language changes (off the main thread).
 * Each card's covers, one card after another, top first. A read that fails keeps what the tab shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordMixesTabViewModel @Inject constructor(
    private val mixes: RecordMixes,
    private val art: RecordArt,
    private val playback: RecordPlayback,
    private val imports: RecordImports,
) : ViewModel() {
    /** A card: the mix, its words (mix.ts `card`) and the covers of its first albums. */
    data class Card(val id: String, val title: String, val card: HistoryMixes.MixCard, val covers: List<String>)

    sealed interface State {
        /** No import: mixes need years. */
        data object None : State

        data class Mixes(val mixes: List<Card>, val years: List<Card>) : State
    }

    /** The mixes as made, for Play (their songs and the covers found so far). */
    private var made: Map<String, Pair<HistoryMix, List<String?>>> = emptyMap()

    /** The words, in the screen's language (by its tags): set by [RecordMixesTab]. */
    private val wordsIn = MutableStateFlow<Pair<String, MixWords>?>(null)

    fun useWords(tags: String, w: MixWords) {
        if (wordsIn.value?.first != tags) wordsIn.value = tags to w
    }

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
                    send(State.None)
                    return@channelFlow
                }
                val (tab, years) = now.tab
                val all = tab + years
                val covers = all.map { m -> m.items.map { it.lib?.art } }.toMutableList()
                // Every card's words (How it started's walks every song of the taste): off the main thread
                val words = withContext(Dispatchers.Default) {
                    val stats = now.taste.songs.associateBy { it.key }
                    all.map { HistoryMixes.card(it, now.taste, w, stats) }
                }
                fun state(): State {
                    made = all.indices.associate { all[it].id to (all[it] to covers[it]) }
                    val cards = all.indices.map { Card(all[it].id, all[it].title, words[it], cardCovers(covers[it])) }
                    return State.Mixes(cards.take(tab.size), cards.drop(tab.size))
                }
                send(state())
                for ((i, m) in all.withIndex()) {
                    try {
                        covers[i] = mixCovers(art, m.items, albums = 4, retry = this) { sofar ->
                            covers[i] = sofar
                            trySend(state())
                        }
                        send(state())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "a mix's covers failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** A card's round Play: the mix from its first song, "Playing from" its title. */
    fun play(id: String) {
        val (m, covers) = made[id] ?: return
        playback.play(songsOf(m.items, covers), 0, m.title, shuffle = false)
    }

    /** "Bring your listening": the import sheet. */
    fun bring() = imports.openSheet()

    private companion object {
        const val TAG = "YourRecord"
    }
}
