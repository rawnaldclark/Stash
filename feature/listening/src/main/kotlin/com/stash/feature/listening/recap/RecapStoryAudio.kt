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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The story's music as the Recap screen uses it: [RecapAudio]'s contract (begin on open, a song per
 * card, hold-to-pause, end on close AND on dispose) behind an interface the ViewModel tests fake.
 *
 * Every call but [endAndWait] returns at once: the work runs on an app-lifetime main-thread scope,
 * not the ViewModel's, so an [end] from the screen's onDispose still runs while the ViewModel is
 * being cleared (RecapAudio serialises begin/end itself, so a quick close-and-reopen is safe).
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

    override fun begin() {
        scope.launch { audio.begin() }
    }

    override fun play(track: Track) = audio.play(track)

    override fun pause() = audio.pause()

    override fun resume() = audio.resume()

    override fun end(restoreMain: Boolean) {
        scope.launch { audio.end(restoreMain) }
    }

    override suspend fun endAndWait(restoreMain: Boolean) = audio.end(restoreMain)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RecapStoryAudioModule {
    @Binds
    abstract fun bind(impl: RecapStoryAudioImpl): RecapStoryAudio
}
