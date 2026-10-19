package com.stash.feature.listening.recap

import com.stash.core.media.recap.RecapAudio
import com.stash.core.model.Track
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The story's music as the Recap screen uses it: [RecapAudio]'s contract (begin on open, a song per
 * card, hold-to-pause, end on close AND on dispose) behind an interface the ViewModel tests fake.
 *
 * Every call but [endAndWait] returns at once: the work runs on an app-lifetime main-thread scope,
 * not the ViewModel's, so an [end] from the screen's onDispose still runs while the ViewModel is
 * being cleared, and all calls run strictly in the order they were made.
 */
interface RecapStoryAudio {
    fun begin()
    fun play(track: Track)
    fun pause()
    fun resume()

    /** Closes the story's audio; [restoreMain] plays the main player again if it was playing. Idempotent. */
    fun end(restoreMain: Boolean = true)

    /** [end], returning once it's done (before "Play your top songs" sets its own queue). */
    suspend fun endAndWait(restoreMain: Boolean)
}

@Singleton
internal class RecapStoryAudioImpl @Inject constructor(private val audio: RecapAudio) : RecapStoryAudio {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Every call runs through this one lock, in the order made: a play() right behind a begin()
     * (a quick reopen, unmuting) waits for the story to exist instead of being ignored as "no
     * story open", which left the first card silent. The lock is fair (FIFO).
     */
    private val order = Mutex()

    private fun inOrder(block: suspend () -> Unit) {
        scope.launch { order.withLock { block() } }
    }

    override fun begin() = inOrder { audio.begin() }

    override fun play(track: Track) = inOrder { audio.play(track) }

    override fun pause() = inOrder { audio.pause() }

    override fun resume() = inOrder { audio.resume() }

    override fun end(restoreMain: Boolean) = inOrder { audio.end(restoreMain) }

    override suspend fun endAndWait(restoreMain: Boolean) = order.withLock { audio.end(restoreMain) }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecapStoryAudioModule {
    @Binds
    abstract fun bind(impl: RecapStoryAudioImpl): RecapStoryAudio
}
