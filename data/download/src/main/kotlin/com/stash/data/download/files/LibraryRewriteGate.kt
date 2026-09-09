package com.stash.data.download.files

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mutual exclusion between the bulk passes that rewrite `file_path` for
 * every downloaded track: the library move ([MoveLibraryCoordinator]) and
 * the folder-layout reorganize ([ReorganizeLibraryCoordinator], #198/#104).
 *
 * Run at the same time, the two heal the same rows and whichever
 * `healFilePath` lands last wins — the other pass's file is left on disk
 * with nothing pointing at it. The gate lives here rather than in either
 * coordinator because the exclusion has to hold in BOTH start orders, and a
 * coordinator that checks the other one's state can only cover one of them
 * (and would need a dependency cycle to cover both).
 *
 * The loser is refused, not queued: both passes are manual, restartable
 * actions, so telling the user to try again is honest and a queue is a
 * surprise.
 *
 * ponytail: one global claim, not a per-track lock — these are
 * whole-library sweeps, so there is nothing finer to contend over.
 */
@Singleton
class LibraryRewriteGate @Inject constructor() {

    private val holder = AtomicReference<String?>(null)

    /** Human-readable name of the pass holding the gate, or null if free. */
    val heldBy: String? get() = holder.get()

    /** Claims the gate for [owner]; false when someone else already holds it. */
    fun tryAcquire(owner: String): Boolean = holder.compareAndSet(null, owner)

    /** Hands the gate back. A no-op unless [owner] is the current holder. */
    fun release(owner: String) {
        holder.compareAndSet(owner, null)
    }
}
