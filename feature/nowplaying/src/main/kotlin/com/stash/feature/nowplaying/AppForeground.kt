package com.stash.feature.nowplaying

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Stash is in the foreground (any activity started). Now Playing prefetches lyrics only
 * then, so music playing with the screen off doesn't look anything up.
 */
interface AppForeground {
    val foreground: Flow<Boolean>

    /** Never in the foreground: the ViewModel's default, so unit tests opt in to prefetching. */
    object Never : AppForeground {
        override val foreground: Flow<Boolean> = flowOf(false)
    }
}

/** The process lifecycle's STARTED state. */
@Singleton
class ProcessForeground @Inject constructor() : AppForeground {
    override val foreground: Flow<Boolean> = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.STARTED) }
        .distinctUntilChanged()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppForegroundModule {
    @Binds
    abstract fun bindAppForeground(impl: ProcessForeground): AppForeground
}
