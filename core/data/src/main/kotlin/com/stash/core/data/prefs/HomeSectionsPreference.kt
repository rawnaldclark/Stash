package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reorderable/hideable content sections of the Home tab, in default
 * order. The Discover hero pager is NOT one of these — it's Home's front
 * door and stays pinned on top.
 */
enum class HomeSection(val key: String) {
    NEW_RELEASES("new_releases"),
    QOBUZ_PLAYLISTS("qobuz_playlists"),
    TOP_ALBUMS("top_albums"),
    MADE_FOR_YOU("made_for_you"),
    RADIOS("radios"),
    MOOD_DECADES("mood_decades");

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

/** Dedicated DataStore for Home section order + visibility. */
private val Context.homeSectionsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "home_sections_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * User-arranged Home layout: [order] is a full permutation of
 * [HomeSection]; [hidden] removes sections from Home without forgetting
 * their position. Defaults preserve today's layout with nothing hidden.
 */
@Singleton
class HomeSectionsPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val orderKey = stringPreferencesKey("home_sections_order")
    private val hiddenKey = stringPreferencesKey("home_sections_hidden")

    val order: Flow<List<HomeSection>> = context.homeSectionsDataStore.data.map { prefs ->
        resolveHomeSectionOrder(prefs[orderKey].toKeys())
    }

    val hidden: Flow<Set<HomeSection>> = context.homeSectionsDataStore.data.map { prefs ->
        prefs[hiddenKey].toKeys().mapNotNull(HomeSection::fromKey).toSet()
    }

    /** What Home actually renders: [order] minus [hidden]. */
    val visibleSections: Flow<List<HomeSection>> = context.homeSectionsDataStore.data.map { prefs ->
        val hiddenSet = prefs[hiddenKey].toKeys().mapNotNull(HomeSection::fromKey).toSet()
        resolveHomeSectionOrder(prefs[orderKey].toKeys()).filter { it !in hiddenSet }
    }

    suspend fun setOrder(order: List<HomeSection>) {
        context.homeSectionsDataStore.edit { prefs ->
            prefs[orderKey] = order.joinToString(",") { it.key }
        }
    }

    /** Swap [section] one slot up or down in the full order. */
    suspend fun move(section: HomeSection, up: Boolean) {
        val current = order.first().toMutableList()
        val idx = current.indexOf(section)
        val target = if (up) idx - 1 else idx + 1
        if (idx < 0 || target !in current.indices) return
        current[idx] = current[target].also { current[target] = current[idx] }
        setOrder(current)
    }

    suspend fun setHidden(section: HomeSection, hide: Boolean) {
        context.homeSectionsDataStore.edit { prefs ->
            val current = prefs[hiddenKey].toKeys().mapNotNull(HomeSection::fromKey).toMutableSet()
            if (hide) current.add(section) else current.remove(section)
            prefs[hiddenKey] = current.joinToString(",") { it.key }
        }
    }

    private fun String?.toKeys(): List<String> =
        this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
