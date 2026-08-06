package com.stash.core.data.library

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.stash.core.data.sync.SyncScheduler
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.workers.LibraryVerificationWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryMaintenanceScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncStateManager: SyncStateManager,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** No-ops if a full sync is already running — that pass already reconciles. */
    fun triggerVerification(): Boolean {
        if (syncStateManager.isSyncing) return false
        val isActive = workManager.getWorkInfosForUniqueWork(LibraryVerificationWorker.UNIQUE_WORK_NAME).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        if (isActive) return false

        val work = OneTimeWorkRequestBuilder<LibraryVerificationWorker>().build()
        workManager.enqueueUniqueWork(
            LibraryVerificationWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            work,
        )
        return true
    }
}