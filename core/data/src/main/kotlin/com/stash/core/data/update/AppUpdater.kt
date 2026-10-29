package com.stash.core.data.update

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
import androidx.core.content.pm.PackageInfoCompat
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

    /** Handed to Android: its confirm is up (or, after the first update, it installs on its own). */
    data object Installing : UpdateState

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
 * and installs it with [PackageInstaller]. Android asks the listener to confirm (an app from outside a store can't update
 * itself silently the first time), and once Stash has installed an update it is the installer of record, so on Android 12+
 * later updates need no confirm.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _available = MutableStateFlow(read(prefs, context))
    /** The newer release to offer, or null (none found, or this one installed since). */
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    // Held: SharedPreferences keeps its listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_TAG || key == null) _available.value = read(prefs, context)
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Whether the sheet should come up for [update] now: not put off with Later in the last day. */
    fun shouldPrompt(update: AvailableUpdate, now: Long = System.currentTimeMillis()): Boolean =
        AppUpdates.shouldPrompt(update.tag, prefs.getString(KEY_LATER_TAG, null), prefs.getLong(KEY_LATER_AT, 0L), now)

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

    /** Update: download, check, install. A second tap while it runs does nothing. */
    fun update() {
        val update = _available.value ?: return
        if (job?.isActive == true || _state.value == UpdateState.Installing) return
        job = scope.launch {
            try {
                val apk = download(update)
                verify(apk)?.let { reason ->
                    apk.delete()
                    _state.value = UpdateState.Failed(reason)
                    return@launch
                }
                install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update failed: ${e.javaClass.simpleName}: ${e.message}")
                _state.value = UpdateState.Failed(UpdateState.Failed.Reason.DOWNLOAD)
            }
        }
    }

    /** Back from Android's "Install unknown apps" screen: install what was downloaded, now that it's allowed. */
    fun onReturnFromPermission() {
        if (_state.value != UpdateState.NeedsPermission) return
        val update = _available.value ?: return
        val apk = apkFile(update)
        if (!apk.exists()) return
        if (context.packageManager.canRequestPackageInstalls()) scope.launch { install(apk) }
    }

    /** Android's "Install unknown apps" screen for Stash. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The sheet was closed after a failure: start over next time. */
    fun reset() {
        if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
    }

    /** [UpdateInstallReceiver]: the installer's answer. */
    internal fun onInstallResult(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit // the receiver opened Android's confirm
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle // this process is replaced right after
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Idle // the listener said no
            else -> {
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
        if (update.apkUrl.startsWith("file:") && isDebuggable(context)) {
            // A debug build's test release (see [UpdateCheckWorker]): an APK already on the phone.
            File(Uri.parse(update.apkUrl).path!!).copyTo(part, overwrite = true)
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
        if (!part.renameTo(apk)) error("rename failed")
        return apk
    }

    /** Why [apk] mustn't be installed, or null: it's this app, newer than this one, and signed with this one's key. */
    @Suppress("DEPRECATION")
    private fun verify(apk: File): UpdateState.Failed.Reason? {
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

    private fun install(apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission
            return
        }
        _state.value = UpdateState.Installing
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Once Stash installed itself, Android 12+ lets its next updates through without a confirm.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("stash.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                // Mutable: the installer adds its status to it.
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            session.commit(callback.intentSender)
        }
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val DIR = "updates"

        /** Shared with [UpdateCheckWorker], which writes the release it found. */
        const val PREFS = "update_check_prefs"
        private const val KEY_TAG = "available_tag"
        private const val KEY_URL = "available_url"
        private const val KEY_SIZE = "available_size"
        private const val KEY_NOTES = "available_notes"
        private const val KEY_LATER_TAG = "later_tag"
        private const val KEY_LATER_AT = "later_at"


        fun isDebuggable(context: Context) = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        /** [UpdateCheckWorker]: the newer release it found ([update]), or null when this app is up to date. */
        fun store(context: Context, update: AvailableUpdate?) {
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (update == null) {
                edit.remove(KEY_TAG).remove(KEY_URL).remove(KEY_SIZE).remove(KEY_NOTES)
            } else {
                // The tag last: the updater reloads on its change.
                edit.putString(KEY_URL, update.apkUrl).putLong(KEY_SIZE, update.sizeBytes).putString(KEY_NOTES, update.notes)
                    .putString(KEY_TAG, update.tag)
            }
            edit.apply()
        }

        /** The stored release, if it is still newer than what is installed (it isn't once it has been installed). */
        private fun read(prefs: SharedPreferences, context: Context): AvailableUpdate? {
            val tag = prefs.getString(KEY_TAG, null) ?: return null
            val url = prefs.getString(KEY_URL, null) ?: return null
            val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                ?: return null
            if (!UpdateCheckWorker.isNewerVersion(tag, installed)) return null
            return AvailableUpdate(tag, tag.removePrefix("v").removePrefix("V"), url, prefs.getLong(KEY_SIZE, 0L), prefs.getString(KEY_NOTES, "").orEmpty())
        }
    }
}
