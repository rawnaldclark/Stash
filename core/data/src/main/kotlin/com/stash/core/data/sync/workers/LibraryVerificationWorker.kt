package com.stash.core.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stash.core.data.library.LibraryReconciliationUseCase
import com.stash.core.data.library.LibraryVerificationStateManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Standalone counterpart to the reconciliation step inside the sync chain.
 * Triggered from Library & Storage → Library, independent of a full sync —
 * unique work name keeps it from colliding with [com.stash.core.data.sync.SyncScheduler].
 */
@HiltWorker
class LibraryVerificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reconciliationUseCase: LibraryReconciliationUseCase,
    private val stateManager: LibraryVerificationStateManager,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "stash_library_verification"
    }

    override suspend fun doWork(): Result {
        return try {
            val result = reconciliationUseCase.reconcile { step, total ->
                stateManager.onProgress(step, total)
            }
            stateManager.onDone(result)
            Result.success()
        } catch (e: Exception) {
            stateManager.onFailed(e.message ?: "Verification failed")
            Result.failure()
        }
    }
}