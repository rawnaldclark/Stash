package com.stash.core.data.weblink

import android.content.Context
import android.os.Build
import androidx.room.Room
import com.stash.core.data.weblink.handoff.HandoffChannel
import com.stash.core.data.weblink.handoff.HandoffSync
import com.stash.core.data.weblink.store.AndroidWebLinkStore
import com.stash.core.data.weblink.store.SyncDatabase
import com.stash.core.data.weblink.store.WebLinkStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Link Stash on the web: the sync client, the local link state (its own `stash_sync.db`) and pairing sessions. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WebLinkModule {
    @Binds abstract fun bindSyncApi(impl: SyncApiClient): SyncApi

    @Binds abstract fun bindWebLinkStore(impl: AndroidWebLinkStore): WebLinkStore

    @Binds abstract fun bindHandoffChannel(impl: HandoffSync): HandoffChannel

    @Binds abstract fun bindMirrorStore(impl: com.stash.core.data.weblink.mirror.FileMirrorStore): com.stash.core.data.weblink.mirror.MirrorStore

    @Binds abstract fun bindMirrorLibrary(impl: com.stash.core.data.weblink.mirror.RoomMirrorLibrary): com.stash.core.data.weblink.mirror.MirrorLibrary

    companion object {
        @Provides
        @Singleton
        fun provideSyncDatabase(@ApplicationContext context: Context): SyncDatabase =
            Room.databaseBuilder(context, SyncDatabase::class.java, SyncDatabase.NAME).build()
    }
}

/** A new [PairingSession] per pairing screen; the phone's label name is its model ("Pixel 6") the first time it links. */
class PairingSessionFactory @Inject constructor(
    private val api: SyncApi,
    private val store: WebLinkStore,
    private val repo: WebLinkRepository,
) {
    fun create(): PairingSession = PairingSession(api, store, repo, phoneName = { Build.MODEL ?: "Phone" })
}
