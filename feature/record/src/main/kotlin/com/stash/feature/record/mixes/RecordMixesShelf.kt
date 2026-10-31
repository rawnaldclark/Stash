package com.stash.feature.record.mixes

import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stash.core.ui.components.CardRail
import com.stash.core.ui.components.HomeRailCardSize
import com.stash.core.ui.components.HomeRailCoverShape
import com.stash.core.ui.components.motion.pressScale
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.record.R
import com.stash.feature.record.art.RecordArt
import com.stash.feature.record.taste.RecordTaste
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * Home's "From your record" (stash-player `src/lib/ui/spotify/HomeShelf.svelte`): three mixes only years of history make ("This
 * week, years ago" when there is one, then a different few each day: [HistoryMixes.homePicks]), each a card that opens it
 * ([onOpenMix]), with Show all into the record's Mixes tab ([onShowAll]). Passed into Home as a slot, like Your record's card;
 * Settings › Home layout moves and hides it. Nothing at all without picks (no import, or too little history for a mix).
 */
@Composable
fun RecordMixesSection(
    onOpenMix: (String) -> Unit,
    onShowAll: () -> Unit,
    viewModel: RecordMixesShelfViewModel = hiltViewModel(),
) {
    // The words in the screen's language (the app's own language setting reaches the Activity, not the Application)
    val context = LocalContext.current
    val locales = LocalConfiguration.current.locales
    LaunchedEffect(locales) { viewModel.useWords(locales.toLanguageTags(), ResourceMixWords(context)) }
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    if (cards.isEmpty()) return
    CardRail(
        title = stringResource(R.string.record_mixes_shelf),
        actionText = stringResource(R.string.record_mixes_show_all),
        onActionClick = onShowAll,
    ) {
        items(cards, key = { it.id }) { c -> ShelfCard(c, onClick = { onOpenMix(c.id) }) }
    }
}

/** Card.svelte with a mix's tile: its covers, its title, and its line (or how many songs). */
@Composable
private fun ShelfCard(card: RecordMixesShelfViewModel.Card, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.record_mixes_open, card.title)
    val sub = card.sub.ifEmpty { pluralStringResource(R.plurals.record_mix_songs, card.songs, card.songs) }
    Column(
        Modifier
            .width(HomeRailCardSize)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            },
    ) {
        MixTile(
            card.tiles,
            seed = card.title,
            modifier = Modifier
                .size(HomeRailCardSize)
                .clip(HomeRailCoverShape)
                .border(1.dp, StashTheme.extendedColors.glassBorder, HomeRailCoverShape),
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.clearAndSetSemantics { }) {
            Text(card.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The local day and the UTC day, again each time either turns (checked every minute): Home's few change at midnight. */
internal fun mixDays(now: () -> Long = System::currentTimeMillis, zone: () -> ZoneId = ZoneId::systemDefault): Flow<Pair<Long, Long>> = flow {
    while (true) {
        val t = now()
        emit(Instant.ofEpochMilli(t).atZone(zone()).toLocalDate().toEpochDay() to Math.floorDiv(t, 86_400_000L))
        delay(60_000)
    }
}.distinctUntilChanged()

/**
 * [RecordMixesSection]'s cards: today's picks, made again when the taste changes (an import, a removal, the day's work-out, a
 * song put back), when the library does (a song liked since plays from your copy), when the day turns and when the language
 * does; then each card's covers, one card after another (cached by album: at once after the first time; a card made again keeps
 * the covers it had, so nothing flickers). A read that fails keeps what Home shows. Opening Home works the taste out again at
 * most once a local day ([RecordTaste.daily]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecordMixesShelfViewModel @Inject constructor(
    private val mixes: RecordMixes,
    private val art: RecordArt,
    taste: RecordTaste,
) : ViewModel() {
    init {
        taste.daily()
    }

    /** A mix's card: its id, words, how many songs (the line when it has none) and the squares of its tile. */
    data class Card(val id: String, val title: String, val sub: String, val songs: Int, val tiles: List<TileCover>)

    /** Each mix's covers as last found, with the songs they were found for (by mix id): a card made again keeps them. */
    private val lastCovers = java.util.concurrent.ConcurrentHashMap<String, Pair<List<String>, List<String?>>>()

    /** The words, in the screen's language (by its tags): set by [RecordMixesSection]. */
    private val words = MutableStateFlow<Pair<String, MixWords>?>(null)

    fun useWords(tags: String, w: MixWords) {
        if (words.value?.first != tags) words.value = tags to w
    }

    val cards: StateFlow<List<Card>> = combine(words.filterNotNull(), mixes.changes, mixes.libraryChanges, mixDays()) { w, _, _, _ -> w.second }
        .flatMapLatest { w ->
            channelFlow {
                val now = try {
                    mixes.now(w)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Home keeps what it shows
                    Log.w(TAG, "the record's mixes couldn't be made: ${e.javaClass.simpleName}")
                    return@channelFlow
                }
                val picks = now?.picks.orEmpty()
                // A card made again keeps the covers it had (the same songs), until its own come
                val covers = picks.map { m ->
                    val had = lastCovers[m.id]?.takeIf { it.first == m.items.map { s -> s.key } }?.second
                    m.items.mapIndexed { j, s -> s.lib?.art ?: had?.getOrNull(j) }
                }.toMutableList()
                fun cards() = picks.mapIndexed { i, m -> Card(m.id, m.title, m.sub, m.items.size, tileCovers(m.items, covers[i])) }
                send(cards())
                for ((i, m) in picks.withIndex()) {
                    try {
                        val found = mixCovers(art, m.items, albums = 4, retry = this) { sofar ->
                            covers[i] = sofar.mapIndexed { j, u -> u ?: covers[i].getOrNull(j) }
                            trySend(cards())
                        }
                        covers[i] = found.mapIndexed { j, u -> u ?: covers[i].getOrNull(j) }
                        lastCovers[m.id] = m.items.map { it.key } to covers[i]
                        send(cards())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The squares keep the songs' colours
                        Log.w(TAG, "a mix's covers failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        .distinctUntilChanged()
        // Home's own: kept while Home is in the back stack (its last cards shown at once on a return), never another Home's
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val TAG = "YourRecord"
    }
}
