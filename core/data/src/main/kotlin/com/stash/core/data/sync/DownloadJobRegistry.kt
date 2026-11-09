package com.stash.core.data.sync

import com.stash.core.data.db.dao.DownloadQueueDao
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** In-process handles for independently cancellable queue-item coroutines. */
@Singleton
class DownloadJobRegistry @Inject constructor() {
    private val jobs = ConcurrentHashMap<Long, Job>()

    /**
     * Makes "claim the row, register its job" one step as far as a tap can see.
     * A worker claims (the row goes IN_PROGRESS) and registers under it; a tap
     * asks [isLive] and resets the row under it ([withClaimsHeld]). Without it
     * a tap landing between a claim and its registration saw an IN_PROGRESS row
     * with no live job, took it for one stuck since a process death, and reset
     * it under the download that had just started.
     */
    private val claimLock = Mutex()

    fun register(queueId: Long, job: Job) {
        jobs.put(queueId, job)?.cancel()
    }

    fun unregister(queueId: Long, job: Job) {
        jobs.remove(queueId, job)
    }

    fun cancel(queueId: Long) {
        jobs[queueId]?.cancel()
    }

    /**
     * Runs [claim] (the row's PENDING/FAILED → IN_PROGRESS write) and, when it
     * took the row, registers [job] for [queueId] until the job completes, all
     * under the claim lock. Returns whether the claim took the row.
     */
    suspend fun claimAndRegister(queueId: Long, job: Job, claim: suspend () -> Boolean): Boolean =
        claimLock.withLock {
            if (!claim()) return@withLock false
            register(queueId, job)
            job.invokeOnCompletion { unregister(queueId, job) }
            true
        }

    /** True while a job in this process is downloading [queueId]'s row. */
    fun isLive(queueId: Long): Boolean = jobs[queueId]?.isActive == true

    /**
     * Runs [block] with no claim able to land meanwhile, so what it reads of a
     * row's IN_PROGRESS state and [isLive] still holds when it writes.
     */
    suspend fun <T> withClaimsHeld(block: suspend () -> T): T = claimLock.withLock { block() }

    /**
     * Rows marked downloading with no job in this process behind them (the process died
     * mid-download, a worker stopped before it handed its claim back) go back to waiting.
     * A row a live job holds is left alone, whichever runner holds it: resetting it let a
     * second runner claim the same song and download it twice. Held against claims, so a
     * download starting now can't be caught between its claim and its registration.
     * Returns how many rows went back.
     */
    suspend fun releaseDeadClaims(dao: DownloadQueueDao): Int = withClaimsHeld {
        dao.inProgressIds().filterNot(::isLive).sumOf { dao.releaseClaim(it) }
    }
}
