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
 * order. The Discover hero pager is NOT one of these — it's Home's front
 * door and stays pinned on top.
 */
enum class HomeSection(val key: String) {
    YOUR_PLAYLISTS("your_playlists"),
    NEW_RELEASES("new_releases"),
    QOBUZ_PLAYLISTS("qobuz_playlists"),
    TOP_ALBUMS("top_albums"),
    MADE_FOR_YOU("made_for_you"),
    RADIOS("radios"),
    MOOD_DECADES("mood_decades"),

    /** Stash Community (spec 2026-09-26 §3). Has its own switch, off by default: see [HomeSectionsPreference.communityOn]. */
    COMMUNITY("community");

    companion object {
        fun fromKey(key: String): HomeSection? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Merge a saved order permutation with the app's known sections: unknown
 * saved keys are dropped (removed in an update), known sections missing
 * from the saved order are appended in default order (added in an
 * update) — so a stale preference can never hide a new section.
 */
fun resolveHomeSectionOrder(savedKeys: List<String>): List<HomeSection> {
    val known = savedKeys.mapNotNull(HomeSection::fromKey).distinct()
    return known + HomeSection.entries.filter { it !in known }
}

/** Home before the preference loads, and when it can't be read: every section but Community, which is opt-in. */
val DEFAULT_HOME_SECTIONS: List<HomeSection> = HomeSection.entries - HomeSection.COMMUNITY

/**
 * What Home renders: [order] minus [hidden], and minus COMMUNITY unless [communityOn]. Community has its own
 * switch, off by default (spec 2026-09-26 §3), because a section new in an update is otherwise shown.
 */
fun visibleHomeSections(order: List<HomeSection>, hidden: Set<HomeSection>, communityOn: Boolean): List<HomeSection> =
    order.filter { it !in hidden && (communityOn || it != HomeSection.COMMUNITY) }

/** [order] with COMMUNITY first: where it lands the first time it's turned on, under the Discover hero. */
fun withCommunityFirst(order: List<HomeSection>): List<HomeSection> =
    listOf(HomeSection.COMMUNITY) + (order - HomeSection.COMMUNITY)

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
 * nothing hidden and no Liked card.
 */
@Singleton
class HomeSectionsPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val orderKey = stringPreferencesKey("home_sections_order")
    private val hiddenKey = stringPreferencesKey("home_sections_hidden")
    private val showLikedKey = booleanPreferencesKey("show_liked_on_home")
    private val communityOnKey = booleanPreferencesKey("community_on")

    /**
     * Show one Liked Songs card — the STASH_LIKED + LIKED_SONGS playlists
     * merged and de-duped — leading the "Your playlists" rail.
     */
    val showLikedOnHome: Flow<Boolean> = context.homeSectionsDataStore.data.map { prefs ->
        prefs[showLikedKey] ?: false
    }.distinctUntilChanged().catch { emit(false) }

    val order: Flow<List<HomeSection>> = context.homeSectionsDataStore.data.map { prefs ->
        resolveHomeSectionOrder(prefs[orderKey].toKeys())
    }.distinctUntilChanged().catch { emit(resolveHomeSectionOrder(emptyList())) }

    val hidden: Flow<Set<HomeSection>> = context.homeSectionsDataStore.data.map { prefs ->
        prefs[hiddenKey].toKeys().mapNotNull(HomeSection::fromKey).toSet()
    }.distinctUntilChanged().catch { emit(emptySet()) }

    /** Community's own switch (spec 2026-09-26 §3): off until turned on in Settings ▸ Home layout. */
    val communityOn: Flow<Boolean> = context.homeSectionsDataStore.data.map { prefs ->
        prefs[communityOnKey] ?: false
    }.distinctUntilChanged().catch { emit(false) }

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
     * All five flows here are deduped (the store re-emits on every unrelated
     * write) and catch to the same default the `map` already uses: they feed
     * `HomeViewModel`'s and `SettingsViewModel`'s `combine` directly, and a
     * DataStore IOException that terminated the chain would leave those screens
     * stuck in `isLoading` forever. Note `catch {}` COMPLETES the flow: after an
     * error each emits its default once and never re-emits for the process
     * lifetime.
     */
    val visibleSections: Flow<List<HomeSection>> = context.homeSectionsDataStore.data.map { prefs ->
        val hiddenSet = prefs[hiddenKey].toKeys().mapNotNull(HomeSection::fromKey).toSet()
        visibleHomeSections(resolveHomeSectionOrder(prefs[orderKey].toKeys()), hiddenSet, prefs[communityOnKey] ?: false)
    }.distinctUntilChanged().catch { emit(DEFAULT_HOME_SECTIONS) }

    /** Swap [section] one slot up or down in the full order. */
    suspend fun move(section: HomeSection, up: Boolean) {
        context.homeSectionsDataStore.edit { prefs ->
            val current = resolveHomeSectionOrder(prefs[orderKey].toKeys()).toMutableList()
            val idx = current.indexOf(section)
            val target = if (up) idx - 1 else idx + 1
            if (idx < 0 || target !in current.indices) return@edit
            current[idx] = current[target].also { current[target] = current[idx] }
            prefs[orderKey] = current.joinToString(",") { it.key }
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
                prefs[orderKey] = withCommunityFirst(resolveHomeSectionOrder(prefs[orderKey].toKeys())).joinToString(",") { it.key }
            }
            prefs[communityOnKey] = on
        }
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
