package com.stash.core.model

/**
 * Result type for sync API calls that distinguishes between
 * successful data, legitimately empty results, and errors.
 *
 * This replaces the pattern of returning [emptyList] on failure,
 * which made it impossible to distinguish "no new data" from "API broken."
 */
sealed class SyncResult<out T> {
    /** The API call succeeded and returned data. */
    data class Success<T>(val data: T) : SyncResult<T>()

    /** The API call succeeded but there was legitimately no data. */
    data class Empty(val reason: String) : SyncResult<Nothing>()

    /** The API call failed. */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val httpCode: Int? = null,
    ) : SyncResult<Nothing>()
}

/** Unwrap a [SyncResult] containing a list, returning empty on non-success. */
fun <T> SyncResult<List<T>>.getOrEmpty(): List<T> = when (this) {
    is SyncResult.Success -> data
    is SyncResult.Empty -> emptyList()
    is SyncResult.Error -> emptyList()
}
