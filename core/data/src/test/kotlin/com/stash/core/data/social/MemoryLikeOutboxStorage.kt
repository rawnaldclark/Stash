package com.stash.core.data.social

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** The outbox in memory: the coordinator's tests run on virtual time, which a DataStore's real IO thread would not follow. */
internal class MemoryLikeOutboxStorage(initial: LikeOutboxState = LikeOutboxState()) : LikeOutboxStorage {
    private val flow = MutableStateFlow(initial)
    override val state: Flow<LikeOutboxState> = flow
    override suspend fun update(transform: (LikeOutboxState) -> LikeOutboxState): LikeOutboxState = flow.updateAndGet(transform)
    val now: LikeOutboxState get() = flow.value
}

/** A scheduler that records what was asked of WorkManager and is as online as the test says. */
internal class FakeLikeMirrorScheduler(var isOnline: Boolean = true) : LikeMirrorScheduler {
    val scheduled = mutableListOf<Long>()
    private var onBack: (() -> Unit)? = null
    override fun schedule(delayMs: Long) {
        scheduled += delayMs
    }
    override fun online(): Boolean = isOnline
    override fun watchConnectivity(onBack: () -> Unit) {
        this.onBack = onBack
    }

    /** The phone's connection comes back. */
    fun backOnline() {
        isOnline = true
        onBack?.invoke()
    }
}
