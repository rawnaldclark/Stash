package com.stash.core.data.update

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.stash.core.data.R
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.workers.UpdateCheckWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where an update is. */
sealed interface UpdateState {
    data object Idle : UpdateState

    /** [fraction] 0..1, or null when the size isn't known. */
    data class Downloading(val fraction: Float?) : UpdateState

    /** Android needs "Install unknown apps" allowed for Stash first (once). */
    data object NeedsPermission : UpdateState

    /** Handed to Android: the APK is being staged, or Android's confirm is up. */
    data object Installing : UpdateState

    /**
     * Downloaded, checked and staged, waiting for the listener: Android's confirm couldn't open because Stash wasn't in
     * front (Android 10+ blocks it silently). Install now opens it.
     */
    data object ReadyToInstall : UpdateState

    data class Failed(val reason: Reason) : UpdateState {
        enum class Reason {
            /** The download isn't this app signed with this app's key: nothing installed. */
            NOT_AN_UPDATE,

            /** This version, or a newer one, is installed already. */
            ALREADY_NEWER,
            DOWNLOAD,
            INSTALL,
        }
    }
}

/**
 * In-app updates (release plan: "prompt + one tap"). [UpdateCheckWorker] finds a newer release on GitHub and [store]s it;
 * the update sheet offers it; Update downloads the release's APK, checks it is this app, newer, and signed with the same key,
 * and installs it with [PackageInstaller]. Android asks the listener to confirm every update: the session asks for no
 * confirm (Android 12+), but Android grants that only to an installer that declares UPDATE_PACKAGES_WITHOUT_USER_ACTION,
 * and Stash's manifest doesn't. Results are matched to the session they belong to: an earlier session, abandoned when the
 * APK is staged again, answers too.
 */
@Singleton
class AppUpdater internal constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    testSteps: Steps?,
) {
    @Inject constructor(@ApplicationContext context: Context, okHttpClient: OkHttpClient) : this(context, okHttpClient, null)

    /** The platform's part of an update, replaced in tests: checking the APK, the install permission, the install itself. */
    internal interface Steps {
        fun verify(apk: File): UpdateState.Failed.Reason?
        fun canInstall(): Boolean

        /** Drops Stash's earlier sessions and copies [apk] into a new one; its id. */
        fun stage(apk: File): Int

        /** Hands the staged session to Android ([UpdateInstallReceiver] hears the answer). */
        fun commit(sessionId: Int)

        /** Whether the session is still there for its confirm (Android drops one after a while, or at a reboot). */
        fun isStaged(sessionId: Int): Boolean

        /** Opens Android's install [confirm] if Stash is in front; false when it can't be opened (in the background). */
        fun confirm(confirm: Intent): Boolean
    }

    private val steps: Steps = testSteps ?: object : Steps {
        override fun verify(apk: File) = verifyApk(apk)
        override fun canInstall() = context.packageManager.canRequestPackageInstalls()
        override fun stage(apk: File) = stageSession(apk)
        override fun commit(sessionId: Int) = commitSession(sessionId)
        override fun isStaged(sessionId: Int) = context.packageManager.packageInstaller.getSessionInfo(sessionId) != null
        override fun confirm(confirm: Intent): Boolean {
            // Android 10+ drops an activity start from the background without a word: don't try it there. A Stash screen
            // must be started: the process alone counts as foreground while another app in front is bound to its player.
            if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
            val me = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
            if (me.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return false
            return try {
                context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                Log.w(TAG, "can't open the confirm: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * The session of the APK being installed now, set before its commit (its answer can come before the commit returns);
     * null while staging, so an earlier session's answer, sent when it is abandoned, is never taken for this one's.
     */
    @Volatile private var session: Int? = null

    /** Android's install confirm, held while it couldn't open ([UpdateState.ReadyToInstall]). */
    private var heldConfirm: Intent? = null

    /** Whether "ready to install" is up: it replaced the update's notification, and goes when the install ends. */
    private var readyNotified = false

    private val _available = MutableStateFlow(read(prefs, context))
    /** The newer release to offer, or null (none found, or this one installed since). */
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    // Held: SharedPreferences keeps its listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Any part of the stored release, not only its tag: notes edited on GitHub after the release reach an open sheet
        // at the next check, and so would a replaced APK.
        if (key == null || key in RELEASE_KEYS) reload()
    }

    @Synchronized
    private fun reload() {
        val before = _available.value?.tag
        _available.value = read(prefs, context)
        // A newer release (or none) while the older one waits for Install now: the sheet would name the newer one and
        // install the older. Drop the held confirm; Update now gets the newer one.
        if (_available.value?.tag != before && _state.value == UpdateState.ReadyToInstall) toIdle()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Installed (or nothing to install): the downloaded APK, ~170 MB, has done its job.
        if (_available.value == null) scope.launch { File(context.cacheDir, DIR).deleteRecursively() }
    }

    /** Whether the sheet should come up for [update] by itself now: not skipped, and not put off with Later in the last day. */
    fun shouldPrompt(update: AvailableUpdate, now: Long = System.currentTimeMillis()): Boolean = AppUpdates.shouldPrompt(
        update.tag,
        prefs.getString(KEY_LATER_TAG, null),
        prefs.getLong(KEY_LATER_AT, 0L),
        now,
        prefs.getString(KEY_SKIPPED_TAG, null),
    )

    /** The version installed now ("0.9.122"), for "You have 0.9.122"; null if Android won't say. */
    val installedVersion: String? by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    private val _asks = MutableStateFlow(0)
    /** Counts the times the listener asked to see the update (the notification, Settings): the sheet opens on each. */
    val asks: StateFlow<Int> = _asks.asStateFlow()

    /** Open the update sheet now, whatever Later said. */
    fun ask() {
        if (_available.value != null) _asks.value++
    }

    /** Later: ask again tomorrow (a newer release asks at once). */
    fun later() {
        val tag = _available.value?.tag ?: return
        prefs.edit().putString(KEY_LATER_TAG, tag).putLong(KEY_LATER_AT, System.currentTimeMillis()).apply()
    }

    /**
     * Skip this version: the sheet and the notification never come up for it by themselves again. The next version asks as
     * usual, and Settings › Check for updates (or the notification, if one is up) still opens this one.
     */
    fun skip() {
        val tag = _available.value?.tag ?: return
        prefs.edit().putString(KEY_SKIPPED_TAG, tag).apply()
    }

    /** Update: download, check, install. A second tap while it runs does nothing. */
    @Synchronized
    fun update() {
        val update = _available.value ?: return
        if (busy()) return
        heldConfirm = null // from ready to install: staged again below
        cancelReadyNotification()
        _state.value = UpdateState.Downloading(0f) // busy from this call on, before the job starts
        job = scope.launch {
            val apk = try {
                download(update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed: ${e.javaClass.simpleName}: ${e.message}")
                _state.value = UpdateState.Failed(UpdateState.Failed.Reason.DOWNLOAD)
                return@launch
            }
            steps.verify(apk)?.let { reason ->
                apk.delete()
                _state.value = UpdateState.Failed(reason)
                return@launch
            }
            installOrFail(apk)
        }
    }

    /**
     * Back from Android's "Install unknown apps" screen: install what was downloaded, now that it's allowed. The same one job
     * as [update], so two resumes in a row install once.
     */
    @Synchronized
    fun onReturnFromPermission() {
        if (_state.value != UpdateState.NeedsPermission || job?.isActive == true) return
        val update = _available.value ?: return
        val apk = apkFile(update)
        if (!apk.exists() || !steps.canInstall()) return
        _state.value = UpdateState.Installing
        job = scope.launch { installOrFail(apk) }
    }

    /**
     * [UpdateInstallReceiver]: Android wants the listener to confirm the install. Its screen opens now when Stash is in
     * front. From the background Android 10+ blocks it without a word, which used to leave "Installing…" for good: the
     * confirm is held instead, the update is [UpdateState.ReadyToInstall] (the sheet comes back for it), and a notification
     * says so. A missing confirm is an install that failed. An earlier session's [sessionId] is ignored.
     */
    @Synchronized
    fun onConfirmNeeded(sessionId: Int, confirm: Intent?) {
        if (!isCurrent(sessionId, PackageInstaller.STATUS_PENDING_USER_ACTION)) return
        if (confirm == null) {
            _state.value = UpdateState.Failed(UpdateState.Failed.Reason.INSTALL)
            return
        }
        if (steps.confirm(confirm)) return
        heldConfirm = confirm
        _state.value = UpdateState.ReadyToInstall
        _available.value?.let(::notifyReadyToInstall)
    }

    /**
     * Install now, from [UpdateState.ReadyToInstall]: Android's held confirm opens. If its session is gone (Android
     * dropped it), or the confirm won't open, the downloaded APK is checked and staged again.
     */
    @Synchronized
    fun installNow() {
        if (_state.value != UpdateState.ReadyToInstall) return
        cancelReadyNotification()
        val confirm = heldConfirm
        heldConfirm = null
        _state.value = UpdateState.Installing
        // A confirm whose session is gone still opens, and Android's installer then closes with no answer at all.
        val staged = session?.let(steps::isStaged) == true
        if (confirm != null && staged && steps.confirm(confirm)) return
        val apk = _available.value?.let(::apkFile)?.takeIf { it.exists() }
        if (apk == null) {
            _state.value = UpdateState.Failed(UpdateState.Failed.Reason.INSTALL)
            return
        }
        job = scope.launch {
            // Checked again: the file has sat in the cache since it was checked.
            steps.verify(apk)?.let { reason ->
                apk.delete()
                _state.value = UpdateState.Failed(reason)
                return@launch
            }
            installOrFail(apk)
        }
    }

    /** Back to the start: no held confirm, and no "ready to install" left up. */
    private fun toIdle() {
        heldConfirm = null
        cancelReadyNotification()
        _state.value = UpdateState.Idle
    }

    private fun cancelReadyNotification() {
        if (!readyNotified) return
        readyNotified = false
        NotificationManagerCompat.from(context).cancel(SyncNotificationManager.NOTIFICATION_ID_UPDATE)
    }

    /** Whether [sessionId] is the session being installed now; an earlier one's answer is logged and dropped. */
    private fun isCurrent(sessionId: Int, status: Int): Boolean {
        if (sessionId == session) return true
        Log.i(TAG, "status $status for session $sessionId, not the current one ($session): ignored")
        return false
    }

    /** "Stash 0.9.123 is ready to install", in place of the update's notification; a tap opens the sheet. */
    private fun notifyReadyToInstall(update: AvailableUpdate) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(UpdateCheckWorker.EXTRA_OPEN_UPDATE, true)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return
        val tap = PendingIntent.getActivity(
            context,
            REQUEST_READY_TO_INSTALL,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, SyncNotificationManager.CHANNEL_UPDATE)
            .setContentTitle(context.getString(R.string.update_ready_notification_title, update.version))
            .setContentText(context.getString(R.string.update_ready_notification_text))
            .setSmallIcon(R.drawable.ic_stat_stash)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            manager.notify(SyncNotificationManager.NOTIFICATION_ID_UPDATE, notification)
            readyNotified = true
        } catch (e: SecurityException) {
            Log.w(TAG, "can't post the ready-to-install notification: no permission")
        }
    }

    /** A download or install under way (a confirm Android shows counts too). */
    private fun busy() = job?.isActive == true || _state.value == UpdateState.Installing

    /** The install (or Android's permission step first), a failure of it (no space to stage the APK, the installer refusing) shown, never thrown. */
    private fun installOrFail(apk: File) {
        if (!steps.canInstall()) {
            _state.value = UpdateState.NeedsPermission
            return
        }
        _state.value = UpdateState.Installing
        // Staging abandons the earlier sessions, and each answers "aborted": none of them is this install.
        session = null
        try {
            val id = steps.stage(apk)
            session = id
            steps.commit(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session = null // abandoned: its "aborted" mustn't hide the failure
            Log.w(TAG, "install failed: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = UpdateState.Failed(UpdateState.Failed.Reason.INSTALL)
        }
    }

    /** Android's "Install unknown apps" screen for Stash. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The sheet was closed after a failure or at Android's permission step (Later): start over next time. A downloaded APK
     * stays, so that next Update now installs it without downloading again.
     */
    @Synchronized
    fun reset() {
        val s = _state.value
        if (s is UpdateState.Failed || s == UpdateState.NeedsPermission) toIdle()
    }

    /**
     * [UpdateInstallReceiver]: the installer's answer for [sessionId]; only the current session's counts. Public: called
     * from another file (see the incremental-build note in #741).
     */
    @Synchronized
    fun onInstallResult(sessionId: Int, status: Int, message: String?) {
        if (!isCurrent(sessionId, status)) return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit // the receiver hands that to onConfirmNeeded
            PackageInstaller.STATUS_SUCCESS -> toIdle() // this process is replaced right after
            PackageInstaller.STATUS_FAILURE_ABORTED -> toIdle() // the listener said no (or Android dropped the session)
            else -> {
                heldConfirm = null
                cancelReadyNotification()
                Log.w(TAG, "install failed: $status $message")
                _state.value = UpdateState.Failed(UpdateState.Failed.Reason.INSTALL)
            }
        }
    }

    private fun apkFile(update: AvailableUpdate) = File(File(context.cacheDir, DIR), "Stash-${update.version}.apk")

    /** The release's APK in the cache (kept whole from an earlier try; a part never counts). */
    private fun download(update: AvailableUpdate): File {
        val apk = apkFile(update)
        if (apk.exists() && (update.sizeBytes <= 0 || apk.length() == update.sizeBytes)) return apk
        apk.parentFile?.listFiles()?.forEach { it.delete() } // an older release's APK, a broken part
        apk.parentFile?.mkdirs()
        val part = File(apk.path + ".part")
        _state.value = UpdateState.Downloading(0f)
        try {
            fetch(update, part)
            if (!part.renameTo(apk)) error("rename failed")
        } finally {
            // A broken or interrupted download (no space, a dropped connection, cancelled) leaves nothing behind.
            part.delete()
        }
        return apk
    }

    private fun fetch(update: AvailableUpdate, part: File) {
        if (update.apkUrl.startsWith("file:") && isDebuggable(context)) {
            // A debug build's test release (see [UpdateCheckWorker]): an APK already on the phone.
            File(java.net.URI(update.apkUrl)).copyTo(part, overwrite = true)
        } else {
            val request = Request.Builder().url(update.apkUrl).header("User-Agent", "Stash-Android").build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("no body")
                val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastShown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastShown) {
                                    lastShown = pct
                                    _state.value = UpdateState.Downloading((done.toFloat() / total).coerceAtMost(1f))
                                }
                            } else {
                                _state.value = UpdateState.Downloading(null)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Why [apk] mustn't be installed, or null: it's this app, newer than this one, and signed with this one's key. */
    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File): UpdateState.Failed.Reason? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return UpdateState.Failed.Reason.NOT_AN_UPDATE
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) {
            Log.w(TAG, "update is ${archive.packageName}, this is ${context.packageName}")
            return UpdateState.Failed.Reason.NOT_AN_UPDATE
        }
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) return UpdateState.Failed.Reason.ALREADY_NEWER
        if (signers(archive).isEmpty() || signers(archive) != signers(installed)) {
            Log.w(TAG, "update is signed with another key")
            return UpdateState.Failed.Reason.NOT_AN_UPDATE
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo ?: return emptySet()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        val sha = MessageDigest.getInstance("SHA-256")
        // Every key in the rotation history: an update signed with a rotated key still names the old one.
        return sigs.mapTo(HashSet()) { sig -> sha.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }
    }

    /** Copies [apk] into a new [PackageInstaller] session, after dropping Stash's earlier ones; the session's id. */
    private fun stageSession(apk: File): Int {
        val installer = context.packageManager.packageInstaller
        // An earlier try's session (staged, its confirm never answered) would keep its copy of the APK: drop it. Each
        // dropped session that was committed answers "aborted" to its own callback.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Asks Android 12+ for no confirm. Android grants it only to an installer that declares
            // UPDATE_PACKAGES_WITHOUT_USER_ACTION (Stash doesn't), so each update still has its confirm.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("stash.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
        } catch (e: Exception) {
            // Staging failed: the session's copy of the APK mustn't stay on the phone.
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
        return sessionId
    }

    /** Commits the staged session ([UpdateInstallReceiver] hears the answer, with its id). */
    private fun commitSession(sessionId: Int) {
        val installer = context.packageManager.packageInstaller
        try {
            installer.openSession(sessionId).use { session ->
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                    // Mutable: the installer adds its status to it.
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            // The commit failed: the session's copy of the APK mustn't stay on the phone.
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val DIR = "updates"
        private const val REQUEST_READY_TO_INSTALL = 9004

        /** Shared with [UpdateCheckWorker], which writes the release it found. */
        const val PREFS = "update_check_prefs"
        private const val KEY_TAG = "available_tag"
        private const val KEY_URL = "available_url"
        private const val KEY_SIZE = "available_size"
        /** The notes as plain text, before 0.9.123: read only when the Markdown below isn't stored yet. */
        private const val KEY_NOTES_PLAIN = "available_notes"
        private const val KEY_NOTES = "available_notes_md"
        private const val KEY_LATER_TAG = "later_tag"
        private const val KEY_LATER_AT = "later_at"
        private const val KEY_SKIPPED_TAG = "skipped_tag"
        private val RELEASE_KEYS = setOf(KEY_TAG, KEY_URL, KEY_SIZE, KEY_NOTES)

        fun isDebuggable(context: Context) = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        /** [UpdateCheckWorker]: the newer release it found ([update]), or null when this app is up to date. */
        fun store(context: Context, update: AvailableUpdate?) {
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            edit.remove(KEY_NOTES_PLAIN)
            if (update == null) {
                edit.remove(KEY_TAG).remove(KEY_URL).remove(KEY_SIZE).remove(KEY_NOTES)
            } else {
                // The tag last: the updater reloads on its change.
                edit.putString(KEY_URL, update.apkUrl).putLong(KEY_SIZE, update.sizeBytes).putString(KEY_NOTES, update.notes)
                    .putString(KEY_TAG, update.tag)
            }
            edit.apply()
        }

        /** Whether the listener skipped [tag] ("Skip this version"): [UpdateCheckWorker] then posts no notification for it. */
        fun isSkipped(context: Context, tag: String): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SKIPPED_TAG, null) == tag

        /** The stored release, if it is still newer than what is installed (it isn't once it has been installed). */
        private fun read(prefs: SharedPreferences, context: Context): AvailableUpdate? {
            val tag = prefs.getString(KEY_TAG, null) ?: return null
            val url = prefs.getString(KEY_URL, null) ?: return null
            val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                ?: return null
            if (!UpdateCheckWorker.isNewerVersion(tag, installed)) return null
            val notes = prefs.getString(KEY_NOTES, null) ?: prefs.getString(KEY_NOTES_PLAIN, null).orEmpty()
            return AvailableUpdate(tag, tag.removePrefix("v").removePrefix("V"), url, prefs.getLong(KEY_SIZE, 0L), notes)
        }
    }
}
