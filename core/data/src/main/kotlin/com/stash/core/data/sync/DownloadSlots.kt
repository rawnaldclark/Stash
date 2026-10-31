package com.stash.core.data.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/**
 * The songs that download at once, shared by every way a song downloads (review on #732): a sync, a single retry, the
 * discovery drain and DownloadManager itself take from the same [COUNT]. A queue row is claimed (shown as downloading)
 * only while its caller holds a slot, so a song waiting for one stays Pending whichever path is busy.
 *
 * A caller that claims a row holds its slot with [withSlot] and calls the downloader inside [holding]; DownloadManager's
 * own [withSlot] then sees the slot is already held and doesn't take a second one (which would deadlock at [COUNT]).
 */
@Singleton
class DownloadSlots @Inject constructor() {

    /** Public only so the inline [withSlot] can reach it. */
    val permits = Semaphore(COUNT)

    /** Runs [block] holding one slot, or straight away when this coroutine already holds one ([holding]). */
    suspend inline fun <T> withSlot(block: () -> T): T =
        if (currentCoroutineContext()[Held] != null) block() else permits.withPermit(block)

    /** Runs [block] marked as holding the slot the caller took with [withSlot]. */
    suspend fun <T> holding(block: suspend () -> T): T = withContext(Held) { block() }

    /** Marks a coroutine that already holds a slot. */
    object Held : CoroutineContext.Element, CoroutineContext.Key<Held> {
        override val key: CoroutineContext.Key<*> get() = this
    }

    companion object {
        /** With native opus downloads are almost all network: eight at once. */
        const val COUNT = 8
    }
}
