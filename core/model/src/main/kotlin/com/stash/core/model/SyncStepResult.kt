package com.stash.core.model

import kotlinx.serialization.Serializable

/**
 * Records what happened during a single step of the sync pipeline.
 * A list of these is JSON-serialized into the sync_history diagnostics column.
 */
@Serializable
data class SyncStepResult(
    val service: String,
    val step: String,
    val status: StepStatus,
    val itemCount: Int = 0,
    val errorMessage: String? = null,
    val httpCode: Int? = null,
)

@Serializable
enum class StepStatus { SUCCESS, EMPTY, ERROR }
