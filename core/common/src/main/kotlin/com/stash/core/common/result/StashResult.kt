package com.stash.core.common.result

sealed interface StashResult<out T> {
    data class Success<T>(val data: T) : StashResult<T>
    data class Error(val exception: Throwable, val message: String? = null) : StashResult<Nothing>
    data object Loading : StashResult<Nothing>
}

inline fun <T, R> StashResult<T>.map(transform: (T) -> R): StashResult<R> = when (this) {
    is StashResult.Success -> StashResult.Success(transform(data))
    is StashResult.Error -> this
    is StashResult.Loading -> this
}

inline fun <T> StashResult<T>.onSuccess(action: (T) -> Unit): StashResult<T> {
    if (this is StashResult.Success) action(data)
    return this
}

inline fun <T> StashResult<T>.onError(action: (Throwable, String?) -> Unit): StashResult<T> {
    if (this is StashResult.Error) action(exception, message)
    return this
}
