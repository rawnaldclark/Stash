package com.stash.core.ui.ambient

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * A read that may fail transiently (a cover fetched offline or mid-tunnel), retried politely:
 * emits [load]'s result, or on a failure (null) [fallback] and then, for each entry of
 * [backoffMs], waits for the next of [signals] (the network coming back, a play/pause), lets
 * that entry's time pass, and reads again, stopping at the first success. Never retries without
 * a signal, never more than [backoffMs] times, and a burst of signals during a wait counts once.
 */
fun <T : Any> retryingLoad(
    load: suspend () -> T?,
    fallback: T,
    signals: Flow<Unit>,
    backoffMs: List<Long>,
): Flow<T> = flow {
    load()?.let {
        emit(it)
        return@flow
    }
    emit(fallback)
    for (wait in backoffMs) {
        signals.first()
        delay(wait)
        load()?.let {
            emit(it)
            return@flow
        }
    }
}
