package com.stash.core.data.social

/**
 * What the line under a "Like on … too" switch says, from what [LikeOutbox] holds for that service. Settings turns it into words
 * (feature/settings `LikeMirrorStatusText`).
 *
 * @property waiting likes and unlikes not yet on the service.
 * @property error why the last try failed: set for [State.RETRYING], [State.OFFLINE] and [State.NEEDS_YOU].
 */
data class LikeMirrorStatus(val state: State, val waiting: Int = 0, val error: LikeMirrorError? = null) {

    enum class State {
        /** Nothing waiting. */
        UP_TO_DATE,

        /** Waiting for its first try (it goes in a moment). */
        WAITING,

        /** Waiting for a connection. */
        OFFLINE,

        /** The service said no or asked to slow down; tried again on its own. */
        RETRYING,

        /** The listener has to act: the sign-in expired, or the tries ran out. "Tap to retry". */
        NEEDS_YOU,
    }

    companion object {
        val UP_TO_DATE = LikeMirrorStatus(State.UP_TO_DATE)

        /** [destination]'s line from everything waiting in the outbox. */
        fun of(ops: List<LikeOutboxOp>, destination: Destination): LikeMirrorStatus {
            val mine = ops.filter { it.destination == destination }
            if (mine.isEmpty()) return UP_TO_DATE
            val parked = mine.filter { it.parked }
            val latest = mine.filter { it.lastError != null }.maxByOrNull { it.lastAttemptAt ?: 0L }?.lastError
            return when {
                parked.isNotEmpty() -> LikeMirrorStatus(
                    State.NEEDS_YOU,
                    mine.size,
                    parked.maxByOrNull { it.lastAttemptAt ?: 0L }?.lastError ?: LikeMirrorError.REFUSED,
                )
                latest == LikeMirrorError.SIGNED_OUT -> LikeMirrorStatus(State.NEEDS_YOU, mine.size, latest)
                latest == LikeMirrorError.OFFLINE -> LikeMirrorStatus(State.OFFLINE, mine.size, latest)
                latest != null -> LikeMirrorStatus(State.RETRYING, mine.size, latest)
                else -> LikeMirrorStatus(State.WAITING, mine.size)
            }
        }
    }
}
