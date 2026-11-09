package com.stash.core.data.sync

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The download_queue row a runner claimed for the download running in this coroutine. The
 * downloader reads it to defer exactly that row: a song can have two rows claimed by two runners
 * (a sync's and a tap's), and deferring every IN_PROGRESS row of the song left the other runner's
 * finished download recorded on a row that said it was waiting.
 */
class ClaimedQueueRow(val queueId: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ClaimedQueueRow>
}
