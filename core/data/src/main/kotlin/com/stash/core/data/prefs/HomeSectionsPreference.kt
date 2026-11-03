package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reorderable/hideable content sections of the Home tab, in default
 * order.
 */
enum class HomeSection(val key: String) {
    /** Daily Discover and your mixes (the hero pager). Pinned on top until 2026-10-19; now movable and hideable like the rest. First, so it stays on top by default. */
    DISCOVER("discover"),
    YOUR_PLAYLISTS("your_playlists"),
    NEW_RELEASES("new_releases"),
    QOBUZ_PLAYLISTS("qobuz_playlists"),
    TOP_ALBUMS("top_albums"),
    MADE_FOR_YOU("made_for_you"),
    RADIOS("radios"),
    MOOD_DECADES("mood_decades"),

    /** Stash Community (spec 2026-09-26 §3). Has its own switch, off by default: see [HomeSectionsPreference.communityOn]. */
    COMMUNITY("community"),

    /**
     * "Your listening" (Pick 3, owner decision 2026-10-18: listening lives on Home only): Recently
     * played and the doors into History and, later, Top songs, Stats and Your Year. Last in the enum, so
     * a stale saved order appends it; Home shows it first until the user reorders Home
     * ([effectiveHomeSectionOrder]). Home renders it only once there's a listen.
     */
    YOUR_LISTENING("your_listening"),

    /**
     * Your record (PLAN record-in-app 2026-10-26: "Home card, like the web"): the way into the record. Like the web, directly
     * under Daily Discover: a saved order without it gets it there ([resolveHomeSectionOrder]), so it shows for everyone who
     * already has one. Last in the enum only so the enum's order stays the old default's.
     */
    YOUR_RECORD("your_record"),

    /**
     * "From your record" (record-in-app phase 5c): mixes only years of history make, a different few each day, with Show all
     * into the record's Mixes tab. Only with an import (Home renders nothing without picks). Always directly under
     * [YOUR_RECORD], wherever the card is (owner 2026-11-01: the mixes belong with the record): it has no place of its own
     * ([withRecordMixesUnderRecord], [movedHomeSectionOrder]), only its own switch, so the card can be hidden and the mixes
     * kept. Last in the enum so the enum's order stays the old default's.
     */
    RECORD_MIXES("record_mixes"),

    /**
     * "New from your artists": this year's and last year's releases from the artists you listen to most
     * ([com.stash.core.data.home.NewFromArtists]). Streaming content, so Home hides it in Offline mode, and shows
     * nothing without listening history. Above the catalogs, your artists' releases before everyone's: a saved
     * order without it gets it there ([ADDED_SECTION_ANCHORS]).
     */
    NEW_FROM_ARTISTS("new_from_artists"),

    /**
     * "Because you like ‹song›": two or three rows, each YouTube Music's related songs for a song you play a lot
     * lately or liked lately ([com.stash.core.data.home.BecauseYouLike]). Streaming content, hidden in Offline mode,
     * and nothing until there's listening to seed from. Home's most personal discovery after Daily Discover, so it
     * leads the new shelves: above New from your artists, above the catalogs.
     *
     * (Moods & moments, key "moods", was a Home section in unreleased builds; it moved to the Search tab before it
     * shipped. A saved order or hidden set still naming it drops it through [fromKey], and the next write leaves it out.)
     */
    BECAUSE_YOU_LIKE("because_you_like");

    companion object {
        fun fromKey(key: String): HomeSection? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Merge a saved order permutation with the app's known sections: unknown
 * saved keys are dropped (removed in an update), known sections missing
 * from the saved order are appended in default order (added in an
 * update) — so a stale preference can never hide a new section.
 *
 * Three exceptions. DISCOVER when it's missing: every order saved before it
 * existed had Discover pinned on top, so a missing DISCOVER goes to the FRONT.
 * YOUR_RECORD when it's missing: the web's card sits directly under Daily
 * Discover, so it goes there when Discover is first, otherwise first.
 * RECORD_MIXES when it's missing: directly under YOUR_RECORD. The sections
 * added since each have a place of their own too ([ADDED_SECTION_ANCHORS]).
 *
 * This is the saved order as it was saved; what Home shows is
 * [effectiveHomeSectionOrder], where RECORD_MIXES follows the card wherever
 * the saved order had put it.
 */
fun resolveHomeSectionOrder(savedKeys: List<String>): List<HomeSection> {
    val known = savedKeys.mapNotNull(HomeSection::fromKey).distinct()
    val missing = HomeSection.entries.filter { it !in known }
    val front = if (HomeSection.DISCOVER in missing) listOf(HomeSection.DISCOVER) else emptyList()
    val order = front + known + (missing - HomeSection.DISCOVER - HomeSection.YOUR_RECORD - HomeSection.RECORD_MIXES)
    val withRecord = when {
        HomeSection.YOUR_RECORD !in missing -> order
        order.firstOrNull() == HomeSection.DISCOVER -> listOf(HomeSection.DISCOVER, HomeSection.YOUR_RECORD) + order.drop(1)
        else -> listOf(HomeSection.YOUR_RECORD) + order
    }
    val withMixes = if (HomeSection.RECORD_MIXES in missing) withRecordMixesUnderRecord(withRecord) else withRecord
    return withAddedSectionsPlaced(withMixes, missing)
}

/**
 * The sections added after "From your record", each with where it goes in an order saved before it existed:
 * directly above the first of its anchors there, or last when none of them is, and never above Discover (an anchor
 * the user moved over Discover puts the new section directly under it, so Discover keeps its lead). Placed in this
 * list's order, so one anchored like an earlier one lands under it. A fresh install's order comes out of the same
 * rule, and a saved order is never rewritten: this is applied each time it's read, until the user's next reorder
 * saves it whole.
 */
internal val ADDED_SECTION_ANCHORS: List<Pair<HomeSection, List<HomeSection>>> = listOf(
    // Your artists' new releases right above everyone's (Qobuz's New Releases, then the other catalogs)
    HomeSection.NEW_FROM_ARTISTS to listOf(HomeSection.NEW_RELEASES, HomeSection.QOBUZ_PLAYLISTS, HomeSection.TOP_ALBUMS),
    // Above the other new shelves wherever they are (a saved order may hold them already), else above the catalogs
    HomeSection.BECAUSE_YOU_LIKE to listOf(
        HomeSection.NEW_FROM_ARTISTS,
        HomeSection.NEW_RELEASES, HomeSection.QOBUZ_PLAYLISTS, HomeSection.TOP_ALBUMS,
    ),
)

/** [order] with each of [ADDED_SECTION_ANCHORS]' sections that the saved order was [missing] moved to its place. */
internal fun withAddedSectionsPlaced(order: List<HomeSection>, missing: Collection<HomeSection>): List<HomeSection> {
    var placed = order
    val placedNow = HashSet<HomeSection>()
    for ((section, anchors) in ADDED_SECTION_ANCHORS) {
        if (section !in missing) continue
        val rest = placed - section
        val anchor = rest.indexOfFirst { it in anchors }
        val discover = rest.indexOf(HomeSection.DISCOVER)
        val at = if (anchor >= 0 && discover >= 0 && anchor <= discover) {
            // Under Discover, and under the new sections already put there unless it's anchored above them
            var under = discover + 1
            while (under < rest.size && rest[under] in placedNow && rest[under] !in anchors) under++
            under
        } else {
            anchor
        }
        placed = if (at < 0) rest + section else rest.take(at) + section + rest.drop(at)
        placedNow += section
    }
    return placed
}

/**
 * [order] with "From your record" directly under "Your record", wherever the card is (owner 2026-11-01: the mixes belong
 * with the record, not way below everything else). The mixes have no position of their own, only their own switch, so
 * [visibleHomeSections] does the rest: the card hidden, the mixes take its place; the mixes hidden, the card alone. Applied
 * when the order is read, never written over a saved one: an order saved before this keeps every choice in it, and the
 * mixes simply show under the card. An order without the card comes back as it is.
 */
fun withRecordMixesUnderRecord(order: List<HomeSection>): List<HomeSection> {
    val rest = order.filter { it != HomeSection.RECORD_MIXES }
    val card = rest.indexOf(HomeSection.YOUR_RECORD)
    if (card < 0) return order
    return rest.take(card + 1) + HomeSection.RECORD_MIXES + rest.drop(card + 1)
}

/**
 * The order Home uses. Until the user reorders Home after this update ([promoted] false), Your listening
 * leads (owner 2026-10-19: show the new feature first, once); any reorder saves the order and ends that.
 * It goes directly under Discover when Discover is first, otherwise first; and under Your record (and its
 * mixes) when that sits right there (Discover, Your record, From your record, Your listening).
 *
 * The record's mixes go under the card last ([withRecordMixesUnderRecord]): placed before the promotion,
 * Your listening would land between the card and its mixes.
 */
fun effectiveHomeSectionOrder(savedKeys: List<String>, promoted: Boolean): List<HomeSection> {
    // The promotion is placed among the sections that have a place of their own; the mixes follow the card.
    val resolved = resolveHomeSectionOrder(savedKeys).filter { it != HomeSection.RECORD_MIXES }
    val order = if (promoted) {
        resolved
    } else {
        val rest = resolved - HomeSection.YOUR_LISTENING
        var lead = if (rest.firstOrNull() == HomeSection.DISCOVER) 1 else 0
        if (rest.getOrNull(lead) == HomeSection.YOUR_RECORD) lead++
        rest.take(lead) + HomeSection.YOUR_LISTENING + rest.drop(lead)
    }
    return withRecordMixesUnderRecord(order)
}

/**
 * [order] with [section] swapped one slot up or down, among the sections that have a place of their own, and the record's
 * mixes under the card again: moving "Your record" moves its mixes with it, and one tap takes the pair past a neighbour
 * (never a dead tap against its own mixes). Null when nothing moves: [section] is already at that end, isn't in [order], or
 * is RECORD_MIXES, which goes wherever the card goes.
 */
fun movedHomeSectionOrder(order: List<HomeSection>, section: HomeSection, up: Boolean): List<HomeSection>? {
    val idx = order.filter { it != HomeSection.RECORD_MIXES }.indexOf(section)
    if (idx < 0) return null
    return movedHomeSectionOrder(order, section, to = if (up) idx - 1 else idx + 1)
}

/**
 * [order] with [section] taken to [to], counted among the sections that have a place of their own (the Home layout
 * editor's rows), the others closing up behind it: a drag's drop. The record's mixes go under the card again, so dropping
 * "Your record" drops its mixes with it. Null when nothing moves: [section] is already at [to], [to] is off the list,
 * [section] isn't in [order], or it's RECORD_MIXES, which goes wherever the card goes.
 */
fun movedHomeSectionOrder(order: List<HomeSection>, section: HomeSection, to: Int): List<HomeSection>? {
    if (section == HomeSection.RECORD_MIXES) return null
    val movable = order.filter { it != HomeSection.RECORD_MIXES }.toMutableList()
    val from = movable.indexOf(section)
    if (from < 0 || to !in movable.indices || to == from) return null
    movable.add(to, movable.removeAt(from))
    return withRecordMixesUnderRecord(movable)
}

/** Home before the preference loads, and when it can't be read: every section but Community, which is opt-in. */
val DEFAULT_HOME_SECTIONS: List<HomeSection> = effectiveHomeSectionOrder(emptyList(), false) - HomeSection.COMMUNITY

/**
 * What Home renders: [order] minus [hidden], and minus COMMUNITY unless [communityOn]. Community has its own
 * switch, off by default (spec 2026-09-26 §3), because a section new in an update is otherwise shown.
 */
fun visibleHomeSections(order: List<HomeSection>, hidden: Set<HomeSection>, communityOn: Boolean): List<HomeSection> =
    order.filter { it !in hidden && (communityOn || it != HomeSection.COMMUNITY) }

/**
 * [order] with COMMUNITY moved up: where it lands the first time it's turned on, directly under the Discover
 * pager when Discover is first, otherwise first.
 */
fun withCommunityFirst(order: List<HomeSection>): List<HomeSection> {
    val rest = order - HomeSection.COMMUNITY
    return if (rest.firstOrNull() == HomeSection.DISCOVER) {
        listOf(HomeSection.DISCOVER, HomeSection.COMMUNITY) + rest.drop(1)
    } else {
        listOf(HomeSection.COMMUNITY) + rest
    }
}

/** The tab a plain cold launch opens on (#428). Missing or unknown stored values read as [HOME]. */
enum class StartTab { HOME, LIBRARY, SEARCH }

/** Dedicated DataStore for Home section order + visibility. Internal so tests can clear it. */
internal val Context.homeSectionsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "home_sections_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * User-arranged Home layout: [order] is a full permutation of
 * [HomeSection]; [hidden] removes sections from Home without forgetting
 * their position; [showLikedOnHome] adds the merged Liked Songs card to
 * the "Your playlists" section. Defaults preserve today's layout with
 * nothing hidden and no Liked card. Also the two other browse choices in
 * Settings > Appearance: the tab Stash opens on ([startTab]) and Search's
 * mood and genre tiles ([searchBrowseOn]).
 */
@Singleton
class HomeSectionsPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val orderKey = stringPreferencesKey("home_sections_order")
    private val hiddenKey = stringPreferencesKey("home_sections_hidden")
    private val showLikedKey = booleanPreferencesKey("show_liked_on_home")
    private val communityOnKey = booleanPreferencesKey("community_on")
    private val searchBrowseKey = booleanPreferencesKey("search_moods_genres")
    /** Set by any write of [orderKey]: a reorder ends the one-time Your listening promotion. */
    private val listeningPromotedKey = booleanPreferencesKey("your_listening_promoted_v1")

    /**
     * Show one Liked Songs card — the STASH_LIKED + LIKED_SONGS playlists
     * merged and de-duped — leading the "Your playlists" rail.
     */
    val showLikedOnHome: Flow<Boolean> = context.homeSectionsDataStore.data.map { prefs ->
        prefs[showLikedKey] ?: false
    }.distinctUntilChanged().catch { emit(false) }

    val order: Flow<List<HomeSection>> = context.homeSectionsDataStore.data.map { prefs ->
        effectiveHomeSectionOrder(prefs[orderKey].toKeys(), prefs[listeningPromotedKey] ?: false)
    }.distinctUntilChanged().catch { emit(effectiveHomeSectionOrder(emptyList(), false)) }

    val hidden: Flow<Set<HomeSection>> = context.homeSectionsDataStore.data.map { prefs ->
        prefs[hiddenKey].toKeys().mapNotNull(HomeSection::fromKey).toSet()
    }.distinctUntilChanged().catch { emit(emptySet()) }

    /** Community's own switch (spec 2026-09-26 §3): off until turned on in Settings ▸ Home layout. */
    val communityOn: Flow<Boolean> = context.homeSectionsDataStore.data.map { prefs ->
        prefs[communityOnKey] ?: false
    }.distinctUntilChanged().catch { emit(false) }

    /**
     * Settings > Appearance > Moods & genres in Search: the Moods & moments and Genres tiles Search shows before
     * anything is typed (#823). On unless turned off (owner 2026-11-03: not everyone wants them); off, Search opens on
     * the recent searches as it did before them, and nothing is read for them.
     */
    val searchBrowseOn: Flow<Boolean> = context.homeSectionsDataStore.data.map { prefs ->
        prefs[searchBrowseKey] ?: true
    }.distinctUntilChanged().catch { emit(true) }

    // Open Stash on (#428) lives in SharedPreferences, not the DataStore: launch reads it
    // synchronously before the first frame. Waiting on a DataStore read there held the
    // splash ~0.4 s longer on every cold start (Pixel 5, debug).
    private val launchPrefs = context.getSharedPreferences(START_TAB_PREFS, Context.MODE_PRIVATE)
    private val startTabState = MutableStateFlow(
        StartTab.entries.firstOrNull { it.name == launchPrefs.getString(START_TAB_KEY, null) } ?: StartTab.HOME,
    )

    /** Settings > Appearance > Open Stash on (#428). */
    val startTab: Flow<StartTab> = startTabState.asStateFlow()

    /** The same, read synchronously: the tab this launch's NavHost starts on. */
    val startTabNow: StartTab get() = startTabState.value

    /**
     * What Home actually renders: [order] minus [hidden], and minus Community while its switch is off.
     *
     * The DataStore flows here are all deduped (the store re-emits on every unrelated
     * write) and catch to the same default the `map` already uses: they feed
     * `HomeViewModel`'s and `SettingsViewModel`'s `combine` directly, and a
     * DataStore IOException that terminated the chain would leave those screens
     * stuck in `isLoading` forever. Note `catch {}` COMPLETES the flow: after an
     * error each emits its default once and never re-emits for the process
     * lifetime.
     */
    val visibleSections: Flow<List<HomeSection>> = context.homeSectionsDataStore.data.map { prefs ->
        val hiddenSet = prefs[hiddenKey].toKeys().mapNotNull(HomeSection::fromKey).toSet()
        visibleHomeSections(
            effectiveHomeSectionOrder(prefs[orderKey].toKeys(), prefs[listeningPromotedKey] ?: false),
            hiddenSet,
            prefs[communityOnKey] ?: false,
        )
    }.distinctUntilChanged().catch { emit(DEFAULT_HOME_SECTIONS) }

    /**
     * Swap [section] one slot up or down ([movedHomeSectionOrder]): "Your record" moves with its mixes, and the
     * mixes alone don't move (nothing is written).
     */
    suspend fun move(section: HomeSection, up: Boolean) = saveMove(section) { current ->
        movedHomeSectionOrder(current, section, up)
    }

    /**
     * Take [section] to [to] among the sections that have a place of their own ([movedHomeSectionOrder]): a drag's drop in
     * Settings › Home layout, saved once, at the drop. "Your record" goes with its mixes; the mixes alone don't move.
     */
    suspend fun move(section: HomeSection, to: Int) = saveMove(section) { current ->
        movedHomeSectionOrder(current, section, to)
    }

    /** Saves the order [move] makes of the current one, whole, as every reorder always has; nothing when it's null. */
    private suspend fun saveMove(section: HomeSection, move: (List<HomeSection>) -> List<HomeSection>?) {
        context.homeSectionsDataStore.edit { prefs ->
            val current = effectiveHomeSectionOrder(prefs[orderKey].toKeys(), prefs[listeningPromotedKey] ?: false)
            val moved = move(current) ?: return@edit
            prefs[orderKey] = moved.joinToString(",") { it.key }
            prefs[listeningPromotedKey] = true
            // Placed by hand before it was ever on: keep that spot when it's turned on.
            if (section == HomeSection.COMMUNITY && prefs[communityOnKey] == null) prefs[communityOnKey] = false
        }
    }

    suspend fun setHidden(section: HomeSection, hide: Boolean) {
        context.homeSectionsDataStore.edit { prefs ->
            val current = prefs[hiddenKey].toKeys().mapNotNull(HomeSection::fromKey).toMutableSet()
            if (hide) current.add(section) else current.remove(section)
            prefs[hiddenKey] = current.joinToString(",") { it.key }
        }
    }

    suspend fun setShowLikedOnHome(shown: Boolean) {
        context.homeSectionsDataStore.edit { prefs -> prefs[showLikedKey] = shown }
    }

    /** Community's switch. The first time it's turned on, it moves to the top of the order. */
    suspend fun setCommunityOn(on: Boolean) {
        context.homeSectionsDataStore.edit { prefs ->
            if (on && prefs[communityOnKey] == null) {
                val effective = effectiveHomeSectionOrder(prefs[orderKey].toKeys(), prefs[listeningPromotedKey] ?: false)
                prefs[orderKey] = withCommunityFirst(effective).joinToString(",") { it.key }
                prefs[listeningPromotedKey] = true
            }
            prefs[communityOnKey] = on
        }
    }

    suspend fun setSearchBrowseOn(on: Boolean) {
        context.homeSectionsDataStore.edit { prefs -> prefs[searchBrowseKey] = on }
    }

    suspend fun setStartTab(tab: StartTab) {
        launchPrefs.edit().putString(START_TAB_KEY, tab.name).apply()
        startTabState.value = tab
    }

    private fun String?.toKeys(): List<String> =
        this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    internal companion object {
        const val START_TAB_PREFS = "start_tab"
        const val START_TAB_KEY = "start_tab"
    }
}
