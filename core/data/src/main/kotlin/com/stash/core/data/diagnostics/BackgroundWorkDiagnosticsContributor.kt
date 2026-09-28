package com.stash.core.data.diagnostics

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first

/**
 * The diagnostics bundle's "Background work" section — what a "the download or
 * sync never happened" report needs, so nobody has to ask the reporter whether
 * the work was queued, is waiting on a constraint, keeps retrying, or is being
 * held back by Android's battery rules:
 *
 *  - Android's rules for this app: battery optimization, background restriction,
 *    standby bucket, battery saver, doze, Data Saver, notifications
 *  - every [WorkInfo] WorkManager still holds (it keeps finished work about a
 *    day), one line each: worker, state, attempts, constraints, period, when it
 *    is due and why its last run was stopped; at most [MAX_ITEMS] lines, then a
 *    count per state.
 *
 * PRIVACY: a work item prints only its worker's simple class name — never its
 * other tags, output data or progress, which can carry track titles, file paths
 * or ids. Every read is fault-isolated: a failing one costs its line, never the
 * section.
 */
@Singleton
class BackgroundWorkDiagnosticsContributor @Inject constructor(
    @ApplicationContext private val context: Context,
) : DiagnosticsContributor {

    override val title: String = "Background work"

    /** Test seam for the clock. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    override suspend fun section(): String = buildString {
        appendLine("Android's rules for Stash:")
        rule("Battery optimization") {
            if (service<PowerManager>().isIgnoringBatteryOptimizations(context.packageName)) "unrestricted" else "optimized"
        }
        // The SDK checks stay inline in each lambda so lint's NewApi check can see them.
        rule("Background restricted") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) BELOW_9 else yesNo(service<ActivityManager>().isBackgroundRestricted)
        }
        rule("App standby bucket") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) BELOW_9 else bucketName(service<UsageStatsManager>().appStandbyBucket)
        }
        rule("Battery saver") { if (service<PowerManager>().isPowerSaveMode) "on" else "off" }
        rule("Dozing now") { yesNo(service<PowerManager>().isDeviceIdleMode) }
        rule("Data Saver") {
            when (val status = service<ConnectivityManager>().restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "off"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "on (Stash allowed)"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "on"
                else -> "unknown ($status)"
            }
        }
        rule("Notifications allowed") { yesNo(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
        append(runCatching { queueBlock() }.getOrElse { "Work queue: " + unavailable(it) })
    }

    /** One of Android's rules, label-aligned; a throwing read costs this line only. */
    private inline fun StringBuilder.rule(label: String, read: () -> String) {
        appendLine("  " + "$label:".padEnd(LABEL_WIDTH) + runCatching(read).getOrElse { unavailable(it) })
    }

    private suspend fun queueBlock(): String {
        val work = WorkManager.getInstance(context)
            .getWorkInfosFlow(WorkQuery.fromStates(WorkInfo.State.entries)).first()
            .sortedWith(compareBy({ STATE_ORDER.indexOf(it.state) }, { workerName(it) }))
        val now = nowMs()
        return buildString {
            appendLine("Work queue (everything WorkManager holds; it keeps finished work about a day):")
            work.take(MAX_ITEMS).forEach { appendLine("  " + describe(it, now)) }
            if (work.size > MAX_ITEMS) appendLine("  … ${work.size - MAX_ITEMS} more")
            append("  Totals: " + STATE_ORDER.joinToString(" · ") { state -> "$state ${work.count { it.state == state }}" })
        }
    }

    /** One work item's line. Reads WorkManager's own bookkeeping and the worker class name, nothing the worker wrote. */
    // WorkInfo.stopReason is @RequiresApi(31) for lint, but it is a field WorkManager stores, not
    // a platform call, so reading it is safe on every API level.
    @SuppressLint("NewApi")
    internal fun describe(w: WorkInfo, now: Long): String = buildList {
        add(workerName(w))
        add(w.state.name)
        if (w.runAttemptCount > 0) add("attempts ${w.runAttemptCount}")
        val c = w.constraints
        val needs = listOfNotNull(
            "network ${c.requiredNetworkType}".takeIf { c.requiredNetworkType != NetworkType.NOT_REQUIRED },
            "charging".takeIf { c.requiresCharging() },
            "battery not low".takeIf { c.requiresBatteryNotLow() },
            "storage not low".takeIf { c.requiresStorageNotLow() },
            "idle".takeIf { c.requiresDeviceIdle() },
        )
        if (needs.isNotEmpty()) add("needs " + needs.joinToString(", "))
        w.periodicityInfo?.let { add("periodic every ${it.repeatIntervalMillis.milliseconds}") }
        // Only ENQUEUED work has a next run; everything else reports Long.MAX_VALUE.
        if (w.state == WorkInfo.State.ENQUEUED && w.nextScheduleTimeMillis != Long.MAX_VALUE) {
            add(due(w.nextScheduleTimeMillis - now))
        }
        if (w.stopReason != WorkInfo.STOP_REASON_NOT_STOPPED) add("stopped: " + stopReasonName(w.stopReason))
    }.joinToString(" · ")

    /** "due in 12m" / "overdue 2h 48m", in whole minutes: the order of magnitude is what matters. */
    private fun due(deltaMs: Long): String {
        val span = deltaMs.absoluteValue.milliseconds.inWholeMinutes.minutes
        return when {
            span == Duration.ZERO -> "due now"
            deltaMs > 0 -> "due in $span"
            else -> "overdue $span"
        }
    }

    /**
     * WorkManager tags every request with its worker's fully qualified class name.
     * Print that tag alone, as the simple name: the app's own tags may carry user data.
     */
    private fun workerName(w: WorkInfo): String =
        w.tags.firstOrNull { CLASS_NAME.matches(it) }?.substringAfterLast('.')?.substringAfterLast('$') ?: "?"

    private fun bucketName(bucket: Int): String = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
        else -> "unknown ($bucket)"
    }

    /** Names for the stop reasons a "why didn't it run" report turns on; the rest print as their number. */
    private fun stopReasonName(reason: Int): String = when (reason) {
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "CANCELLED_BY_APP"
        WorkInfo.STOP_REASON_TIMEOUT -> "TIMEOUT"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "DEVICE_STATE"
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "CONSTRAINT_BATTERY_NOT_LOW"
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "CONSTRAINT_CHARGING"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "CONSTRAINT_CONNECTIVITY"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "CONSTRAINT_STORAGE_NOT_LOW"
        WorkInfo.STOP_REASON_QUOTA -> "QUOTA"
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "BACKGROUND_RESTRICTION"
        WorkInfo.STOP_REASON_APP_STANDBY -> "APP_STANDBY"
        WorkInfo.STOP_REASON_USER -> "USER"
        else -> "$reason"
    }

    private inline fun <reified T : Any> service(): T = checkNotNull(context.getSystemService(T::class.java))
    private fun yesNo(v: Boolean) = if (v) "yes" else "no"
    private fun unavailable(t: Throwable) = "unavailable (${t.javaClass.simpleName})"

    private companion object {
        const val MAX_ITEMS = 40
        const val LABEL_WIDTH = 23
        const val BELOW_9 = "n/a (Android <9)"

        /** The order a triager reads in: what is running, what is waiting, what went wrong, then history. */
        val STATE_ORDER = listOf(
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED,
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED,
        )

        /** `com.pkg.Worker` or `com.pkg.Outer$Worker`: lowercase package segments, then a capitalised class. */
        val CLASS_NAME = Regex("""([a-z_][a-z0-9_]*\.)+[A-Z][\w$]*""")
    }
}
