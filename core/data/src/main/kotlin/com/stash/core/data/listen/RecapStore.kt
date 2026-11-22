package com.stash.core.data.listen

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.model.listen.SessionRecap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recapDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "session_recaps",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The rule for which recaps are kept (Sessions UX spec §6): a week, and at most [MAX], newest first. */
object RecapShelf {
    const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX = 20

    /** [existing] with [recap] added (replacing one with the same id), as it should be kept at [now]. */
    fun add(existing: List<SessionRecap>, recap: SessionRecap, now: Long): List<SessionRecap> =
        kept(listOf(recap) + existing.filter { it.id != recap.id }, now)

    fun kept(recaps: List<SessionRecap>, now: Long): List<SessionRecap> =
        recaps.filter { now - it.endedAt < KEEP_MS }.sortedByDescending { it.endedAt }.take(MAX)
}

/**
 * Session recaps, on this phone only: nothing about a session is kept on the server, and nothing here is sent
 * anywhere. Not a table: the Room database stays as it is, and 20 small records fit a preference.
 */
@Singleton
class RecapStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val key = stringPreferencesKey("recaps")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val list = ListSerializer(SessionRecap.serializer())

    private fun decode(text: String?): List<SessionRecap> =
        text?.let { runCatching { json.decodeFromString(list, it) }.getOrNull() }.orEmpty()

    /** The recaps still kept, newest first. One too old is left out here, and dropped at the next [add]. */
    fun recaps(now: () -> Long = System::currentTimeMillis): Flow<List<SessionRecap>> =
        context.recapDataStore.data.map { RecapShelf.kept(decode(it[key]), now()) }.catch { emit(emptyList()) }

    suspend fun add(recap: SessionRecap, now: Long = System.currentTimeMillis()) {
        runCatching {
            context.recapDataStore.edit { it[key] = json.encodeToString(list, RecapShelf.add(decode(it[key]), recap, now)) }
        }
    }
}
