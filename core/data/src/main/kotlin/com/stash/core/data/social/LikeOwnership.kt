package com.stash.core.data.social

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The songs a service already had liked (or loved) when Stash went to like them there, or that Stash couldn't tell about: the
 * service's like, not Stash's, so a later un-heart in Stash never takes it away.
 *
 * The rule's first line of defence is that such a like never gets its `*_saved_at` stamp ([LikeDestinationDispatcher]), and an
 * unlike only fires on a stamped row; this record is the second, checked by the unlike too, and the explicit answer to "who made
 * this like". Cleared when Stash itself makes the like later (the listener had removed it there in between).
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

    private fun entry(destination: Destination, trackId: Long) = "${destination.name}:$trackId"

    private companion object {
        val KEY = stringSetPreferencesKey("liked_by_service_v1")
    }
}
