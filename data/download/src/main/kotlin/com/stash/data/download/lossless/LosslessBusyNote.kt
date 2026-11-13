package com.stash.data.download.lossless

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What a streaming chain walk learns when a lossless source serves nothing because it is BUSY
 * (Tidal's relay answered 503 / 429 with a Retry-After, or its busy circuit is still open), as
 * opposed to having no lossless version of the song.
 *
 * The walk (`StreamSourceRegistry`) puts one on its coroutine context; the lossless source that
 * hears "busy" writes when it may be asked again ([noteLosslessBusy]). A lossy fallback that walk
 * then serves is marked as being for this play only, so a later play asks the lossless sources
 * again instead of replaying the fallback for hours. Downloads never carry one, so nothing they do
 * changes.
 *
 * It travels on the context rather than through every resolver's signature: each rung keeps its
 * plain "a stream or null" answer, and a coroutine that carries no note pays nothing.
 */
class LosslessBusyNote : AbstractCoroutineContextElement(Key) {

    /** Epoch-ms when the busiest source said it may be asked again; 0 while none said busy. */
    @Volatile
    var untilMs: Long = 0L
        private set

    /** True once any lossless source of this walk answered busy. */
    val heard: Boolean get() = untilMs > 0L

    /** Keeps the LATEST time any source gave: asking again sooner than that would only hear "busy" again. */
    @Synchronized
    fun note(untilMs: Long) {
        if (untilMs > this.untilMs) this.untilMs = untilMs
    }

    companion object Key : CoroutineContext.Key<LosslessBusyNote>
}

/**
 * Tells the walk this coroutine runs in (if any) that a lossless source was busy until [untilMs]
 * (epoch-ms). A no-op outside a streaming chain walk.
 */
suspend fun noteLosslessBusy(untilMs: Long) {
    currentCoroutineContext()[LosslessBusyNote]?.note(untilMs)
}
