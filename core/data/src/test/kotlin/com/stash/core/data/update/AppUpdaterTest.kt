package com.stash.core.data.update

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [AppUpdater]'s failure paths (review on #741): an install that throws, a return from the permission screen twice, a
 * download that breaks. The platform's steps are fakes; the APK is a debug build's `file:` test release.
 */
@RunWith(RobolectricTestRunner::class)
class AppUpdaterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var apk: File

    @Before
    fun setUp() {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).versionName = "0.9.0"
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        apk = File(context.filesDir, "test-update.apk").apply { writeBytes(ByteArray(1_000) { it.toByte() }) }
        store(apk.length())
        // The launcher activity the ready-to-install notification opens.
        val main = ComponentName(context.packageName, "com.stash.app.MainActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(main)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            main,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private fun store(size: Long, file: File = apk) =
        AppUpdater.store(context, AvailableUpdate("v0.9.1", "0.9.1", file.toURI().toString(), size, "notes"))

    private class FakeSteps(
        @Volatile var allowed: Boolean = true,
        val onInstall: (File) -> Unit = {},
    ) : AppUpdater.Steps {
        val installs = AtomicInteger()
        val confirms = AtomicInteger()

        /** Whether Android's confirm can open now: Stash in front (and the confirm still good). */
        @Volatile var confirmOpens: Boolean = true
        override fun verify(apk: File): UpdateState.Failed.Reason? = null
        override fun canInstall() = allowed
        override fun install(apk: File) {
            installs.incrementAndGet()
            onInstall(apk)
        }
        override fun confirm(confirm: Intent): Boolean {
            if (!confirmOpens) return false
            confirms.incrementAndGet()
            return true
        }
    }

    private fun awaitInstalls(steps: FakeSteps, n: Int) {
        val until = System.currentTimeMillis() + 5_000
        while (steps.installs.get() < n && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    private fun AppUpdater.awaitState(predicate: (UpdateState) -> Boolean): UpdateState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    @Test
    fun `an install that throws is shown as a failure, not a crash`() {
        val steps = FakeSteps(onInstall = { throw IOException("No space left on device") })
        val updater = AppUpdater(context, OkHttpClient(), steps)

        updater.update()

        assertThat(updater.awaitState { it is UpdateState.Failed }).isEqualTo(UpdateState.Failed(UpdateState.Failed.Reason.INSTALL))
        assertThat(steps.installs.get()).isEqualTo(1)
    }

    @Test
    fun `back from the permission screen, resumes in a row install once and a throwing install fails cleanly`() {
        val release = CountDownLatch(1)
        val steps = FakeSteps(allowed = false, onInstall = {
            release.await(5, TimeUnit.SECONDS)
            throw SecurityException("installer refused")
        })
        val updater = AppUpdater(context, OkHttpClient(), steps)
        updater.update()
        assertThat(updater.awaitState { it == UpdateState.NeedsPermission }).isEqualTo(UpdateState.NeedsPermission)

        steps.allowed = true
        repeat(3) { updater.onReturnFromPermission() }
        release.countDown()

        assertThat(updater.awaitState { it is UpdateState.Failed }).isEqualTo(UpdateState.Failed(UpdateState.Failed.Reason.INSTALL))
        assertThat(steps.installs.get()).isEqualTo(1)
    }

    @Test
    fun `Later at the permission step starts over next time and keeps the download`() {
        val steps = FakeSteps(allowed = false)
        val updater = AppUpdater(context, OkHttpClient(), steps)
        updater.update()
        updater.awaitState { it == UpdateState.NeedsPermission }

        updater.reset()
        assertThat(updater.state.value).isEqualTo(UpdateState.Idle)
        val kept = File(File(context.cacheDir, "updates"), "Stash-0.9.1.apk")
        assertThat(kept.exists()).isTrue()

        // Next time, allowed: Update now installs the kept file (the test release's own file is gone, so no new download).
        apk.delete()
        steps.allowed = true
        updater.update()
        updater.awaitState { it == UpdateState.Installing }
        assertThat(steps.installs.get()).isEqualTo(1)
    }

    @Test
    fun `a confirm Android won't show in the background waits as ready to install, and Install now opens it`() {
        val steps = FakeSteps()
        val updater = AppUpdater(context, OkHttpClient(), steps)
        updater.update()
        updater.awaitState { it == UpdateState.Installing }

        // The installer's answer arrives while Stash is in the background: Android would drop the confirm silently.
        steps.confirmOpens = false
        updater.onConfirmNeeded(Intent("android.content.pm.action.CONFIRM_INSTALL"))

        assertThat(updater.state.value).isEqualTo(UpdateState.ReadyToInstall)
        val posted = notifications.single()
        assertThat(posted.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Stash 0.9.1 is ready to install")
        assertThat(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("Tap to install it.")

        // Back in front: Install now opens the held confirm, once, and the notification goes.
        steps.confirmOpens = true
        updater.installNow()
        updater.installNow()
        assertThat(steps.confirms.get()).isEqualTo(1)
        assertThat(updater.state.value).isEqualTo(UpdateState.Installing)
        assertThat(notifications).isEmpty()
        assertThat(steps.installs.get()).isEqualTo(1)
    }

    @Test
    fun `in front, the confirm opens at once and the update stays installing`() {
        val steps = FakeSteps()
        val updater = AppUpdater(context, OkHttpClient(), steps)
        updater.update()
        updater.awaitState { it == UpdateState.Installing }

        updater.onConfirmNeeded(Intent("android.content.pm.action.CONFIRM_INSTALL"))

        assertThat(steps.confirms.get()).isEqualTo(1)
        assertThat(updater.state.value).isEqualTo(UpdateState.Installing)
        assertThat(notifications).isEmpty()
    }

    @Test
    fun `Install now stages the download again when the held confirm won't open, and no confirm at all fails`() {
        val steps = FakeSteps()
        val updater = AppUpdater(context, OkHttpClient(), steps)
        updater.update()
        updater.awaitState { it == UpdateState.Installing }
        steps.confirmOpens = false
        updater.onConfirmNeeded(Intent("android.content.pm.action.CONFIRM_INSTALL"))

        updater.installNow() // the confirm still won't open (gone stale): install the kept APK again
        awaitInstalls(steps, 2)
        assertThat(steps.installs.get()).isEqualTo(2)
        assertThat(updater.state.value).isEqualTo(UpdateState.Installing)

        updater.onConfirmNeeded(null)
        assertThat(updater.state.value).isEqualTo(UpdateState.Failed(UpdateState.Failed.Reason.INSTALL))
    }

    @Test
    fun `a broken download leaves no part file behind`() {
        val missing = File(context.filesDir, "gone.apk")
        store(1_000, missing)
        val steps = FakeSteps()
        val updater = AppUpdater(context, OkHttpClient(), steps)

        updater.update()

        assertThat(updater.awaitState { it is UpdateState.Failed }).isEqualTo(UpdateState.Failed(UpdateState.Failed.Reason.DOWNLOAD))
        assertThat(File(context.cacheDir, "updates").listFiles().orEmpty().toList()).isEmpty()
        assertThat(steps.installs.get()).isEqualTo(0)
    }

    @Test
    fun `later holds the same release for a day, then it is due again`() {
        val updater = AppUpdater(context, OkHttpClient(), FakeSteps())
        val update = updater.available.value!!
        val before = System.currentTimeMillis()
        updater.later()

        assertThat(updater.shouldPrompt(update, now = before + 60_000)).isFalse()
        assertThat(updater.shouldPrompt(update, now = before + AppUpdates.ASK_AGAIN_MS + 60_000)).isTrue()
    }

    @Test
    fun `skip this version holds that release for good, the next one asks, and the worker hears of it`() {
        val updater = AppUpdater(context, OkHttpClient(), FakeSteps())
        val update = updater.available.value!!
        updater.skip()

        assertThat(updater.shouldPrompt(update, now = System.currentTimeMillis() + 30 * AppUpdates.ASK_AGAIN_MS)).isFalse()
        assertThat(AppUpdater.isSkipped(context, "v0.9.1")).isTrue()
        assertThat(AppUpdater.isSkipped(context, "v0.9.2")).isFalse()
        val next = update.copy(tag = "v0.9.2", version = "0.9.2")
        assertThat(updater.shouldPrompt(next)).isTrue()
    }

    @Test
    fun `the notes are kept as written, and notes stored before them as plain text still read`() {
        val md = "Headline.\n\n## What's new\n- **One.** Two."
        AppUpdater.store(context, AvailableUpdate("v0.9.1", "0.9.1", apk.toURI().toString(), apk.length(), md))
        assertThat(AppUpdater(context, OkHttpClient(), FakeSteps()).available.value!!.notes).isEqualTo(md)

        // An update stored by 0.9.122 (plain notes under the old key), then read by this version.
        val prefs = context.getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().putString("available_tag", "v0.9.1").putString("available_url", apk.toURI().toString())
            .putString("available_notes", "What's new").commit()
        assertThat(AppUpdater(context, OkHttpClient(), FakeSteps()).available.value!!.notes).isEqualTo("What's new")
    }

    @Test
    fun `a check that changes only the notes or the apk reaches the open updater`() {
        val updater = AppUpdater(context, OkHttpClient(), FakeSteps())
        val other = File(context.filesDir, "other.apk").apply { writeBytes(ByteArray(10)) }
        AppUpdater.store(context, AvailableUpdate("v0.9.1", "0.9.1", other.toURI().toString(), other.length(), "Edited notes."))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val now = updater.available.value!!
        assertThat(now.notes).isEqualTo("Edited notes.")
        assertThat(now.apkUrl).isEqualTo(other.toURI().toString())
    }

    @Test
    fun `the installed version is read for the sheet`() {
        assertThat(AppUpdater(context, OkHttpClient(), FakeSteps()).installedVersion).isEqualTo("0.9.0")
    }

    @Test
    fun `a second tap while updating does nothing`() {
        val release = CountDownLatch(1)
        val steps = FakeSteps(onInstall = { release.await(5, TimeUnit.SECONDS) })
        val updater = AppUpdater(context, OkHttpClient(), steps)

        updater.update()
        updater.update()
        updater.awaitState { it == UpdateState.Installing }
        updater.update()
        release.countDown()

        Thread.sleep(200)
        assertThat(steps.installs.get()).isEqualTo(1)
    }
}
