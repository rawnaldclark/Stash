package com.stash.core.data.social

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Who made a like on a service, for [LikeDestinationDispatcher]'s rule "never take away a like Stash didn't make".
 *
 *  - The songs a service already had liked (or loved) when Stash went to like them there, or that Stash couldn't tell about: the
 *    service's like, not Stash's, so a later un-heart in Stash never takes it away. Written before the row's `*_saved_at` stamp,
 *    so a stamped row is never left without it; cleared when Stash itself makes the like later (the listener had removed it there).
 *  - The stamp epoch ([stampEpoch]): builds before this one stamped every like they sent, also on songs the listener had liked
 *    there for years, and kept no record of whose it was. A stamp older than the epoch is never un-liked.
 */
@Singleton
class LikeOwnership internal constructor(private val store: DataStore<Preferences>) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.likeMirrorDataStore)

    suspend fun markLikedByService(destination: Destination, trackId: Long) {
        store.edit { it[KEY] = it[KEY].orEmpty() + entry(destination, trackId) }
    }

    suspend fun clear(destination: Destination, trackId: Long) {
        val entry = entry(destination, trackId)
        if (entry !in store.data.first()[KEY].orEmpty()) return
        store.edit { it[KEY] = it[KEY].orEmpty() - entry }
    }

    suspend fun isLikedByService(destination: Destination, trackId: Long): Boolean =
        entry(destination, trackId) in store.data.first()[KEY].orEmpty()

    /**
     * When this build first stamped a like: written once, before the first stamp (so every stamp this build writes is at or after
     * it), and the same ever after. Returns the epoch in force.
     */
    suspend fun stampEpoch(now: Long): Long {
        store.data.first()[EPOCH]?.let { return it }
        var epoch = now
        store.edit { p -> p[EPOCH]?.let { epoch = it } ?: run { p[EPOCH] = now } }
        return epoch
    }

    /** The stamp epoch, or null when this build has stamped nothing yet (every stamp there is came from an older build). */
    suspend fun epoch(): Long? = store.data.first()[EPOCH]

    private fun entry(destination: Destination, trackId: Long) = "${destination.name}:$trackId"

    private companion object {
        val KEY = stringSetPreferencesKey("liked_by_service_v1")
        val EPOCH = longPreferencesKey("stamp_epoch_v1")
    }
}
