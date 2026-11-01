package com.stash.core.data.social

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * When the listener's Spotify Liked Songs were last fetched in full: the only copy that can say a song is NOT liked there
 * ([ServiceLikedThereCheck]). The playlist's own last-synced time moves on every sync, a partial fetch included (a page that
 * errored, a cap), so it can't say that. Written by DiffWorker for each Spotify Liked Songs it reads: the time when the fetch
 * was complete, nothing (cleared) when it wasn't.
 */
@Singleton
class SpotifyLikedSongsFetch internal constructor(private val store: DataStore<Preferences>) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.likeMirrorDataStore)

    /** A fetch of Spotify Liked Songs landed at [at]; [complete] when every song came (not partial, not an empty failure). */
    suspend fun fetched(complete: Boolean, at: Long) {
        store.edit { if (complete) it[COMPLETE_AT] = at else it.remove(COMPLETE_AT) }
    }

    /** When the last fetch landed, if it was complete; null when it wasn't (or none ever was). */
    suspend fun completeAt(): Long? = store.data.first()[COMPLETE_AT]

    private companion object {
        val COMPLETE_AT = longPreferencesKey("spotify_liked_complete_at_v1")
    }
}
