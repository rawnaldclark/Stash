package com.stash.core.data.update

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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
        /** Sessions staged: the nth is session 100 + n. */
        val installs = AtomicInteger()
        val commits = AtomicInteger()
        val confirms = AtomicInteger()
        val verifies = AtomicInteger()

        /** Whether Android's confirm can open now (Stash in front). */
        @Volatile var confirmOpens: Boolean = true

        /** What a check of the APK says. */
        @Volatile var verdict: UpdateState.Failed.Reason? = null

        /** Sessions Android has dropped. */
        val gone: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        /** The session committed last. */
        @Volatile var session: Int = 0

        override fun verify(apk: File): UpdateState.Failed.Reason? {
            verifies.incrementAndGet()
            return verdict
        }
        override fun canInstall() = allowed
        override fun stage(apk: File): Int {
            val n = installs.incrementAndGet()
            onInstall(apk)
            return 100 + n
        }
        override fun commit(sessionId: Int) {
            session = sessionId
            commits.incrementAndGet()
        }
        override fun isStaged(sessionId: Int) = sessionId !in gone
        override fun confirm(confirm: Intent): Boolean {
            if (!confirmOpens) return false
            confirms.incrementAndGet()
            return true
        }
    }

    private fun confirmIntent() = Intent("android.content.pm.action.CONFIRM_INSTALL")

    /** Until the [n]th session is committed: from then on its answers count. */
    private fun awaitCommits(steps: FakeSteps, n: Int) {
        val until = System.currentTimeMillis() + 5_000
        while (steps.commits.get() < n && System.currentTimeMillis() < until) Thread.sleep(10)
        assertThat(steps.commits.get()).isAtLeast(n)
    }

    /** Update now, then Android asks for the confirm while Stash is in the background: ready to install. */
    private fun readyToInstall(steps: FakeSteps): AppUpdater {
        val updater = AppUpdater(context, OkHttpClient(), steps)
        updater.update()
        awaitCommits(steps, 1)
        steps.confirmOpens = false
        updater.onConfirmNeeded(steps.session, confirmIntent())
        assertThat(updater.state.value).isEqualTo(UpdateState.ReadyToInstall)
        steps.confirmOpens = true
        return updater
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
        awaitCommits(steps, 1)
        assertThat(steps.installs.get()).isEqualTo(1)
        assertThat(updater.state.value).isEqualTo(UpdateState.Installing)
    }

    @Test
    fun `a confirm Android won't show in the background waits as ready to install, and Install now opens it`() {
        val steps = FakeSteps()
        val updater = AppUpdater(context, OkHttpClient(), steps)
        updater.update()
        awaitCommits(steps, 1)

        // The installer's answer arrives while Stash is in the background: Android would drop the confirm silently.
        steps.confirmOpens = false
        updater.onConfirmNeeded(steps.session, confirmIntent())

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
        awaitCommits(steps, 1)

        updater.onConfirmNeeded(steps.session, confirmIntent())

        assertThat(steps.confirms.get()).isEqualTo(1)
        assertThat(updater.state.value).isEqualTo(UpdateState.Installing)
        assertThat(notifications).isEmpty()
    }

    @Test
    fun `Install now checks and stages the download again when Android dropped the session, and no confirm at all fails`() {
        val steps = FakeSteps()
        val updater = readyToInstall(steps)
        assertThat(steps.verifies.get()).isEqualTo(1)

        // The held confirm would still open, and Android's installer would close with no answer: it stays shut.
        steps.gone += steps.session
        updater.installNow()
        awaitCommits(steps, 2)
        assertThat(steps.confirms.get()).isEqualTo(0)
        assertThat(steps.verifies.get()).isEqualTo(2)
        assertThat(steps.installs.get()).isEqualTo(2)
        assertThat(updater.state.value).isEqualTo(UpdateState.Installing)

        updater.onConfirmNeeded(steps.session, null)
        assertThat(updater.state.value).isEqualTo(UpdateState.Failed(UpdateState.Failed.Reason.INSTALL))
    }

    @Test
    fun `Install now fails when Android dropped the session and the download is gone`() {
        val steps = FakeSteps()
        val updater = readyToInstall(steps)
        steps.gone += steps.session
        File(context.cacheDir, "updates").deleteRecursively()

        updater.installNow()

        assertThat(updater.state.value).isEqualTo(UpdateState.Failed(UpdateState.Failed.Reason.INSTALL))
        assertThat(steps.confirms.get()).isEqualTo(0)
        assertThat(steps.installs.get()).isEqualTo(1)
        assertThat(notifications).isEmpty()
    }

    @Test
    fun `Install now won't stage a kept download that no longer checks out`() {
        val steps = FakeSteps()
        val updater = readyToInstall(steps)
        steps.gone += steps.session
        steps.verdict = UpdateState.Failed.Reason.NOT_AN_UPDATE

        updater.installNow()

        assertThat(updater.awaitState { it is UpdateState.Failed })
            .isEqualTo(UpdateState.Failed(UpdateState.Failed.Reason.NOT_AN_UPDATE))
        assertThat(steps.installs.get()).isEqualTo(1)
        assertThat(File(File(context.cacheDir, "updates"), "Stash-0.9.1.apk").exists()).isFalse()
    }

    @Test
    fun `a decline at Android's confirm after Install now starts over`() {
        val steps = FakeSteps()
        val updater = readyToInstall(steps)
        updater.installNow()
        assertThat(steps.confirms.get()).isEqualTo(1)

        updater.onInstallResult(steps.session, PackageInstaller.STATUS_FAILURE_ABORTED, null)

        assertThat(updater.state.value).isEqualTo(UpdateState.Idle)
        assertThat(notifications).isEmpty()
        updater.installNow() // nothing held any more
        assertThat(steps.confirms.get()).isEqualTo(1)
        // Update now stages the kept download again.
        updater.update()
        awaitCommits(steps, 2)
        assertThat(updater.state.value).isEqualTo(UpdateState.Installing)
    }

    @Test
    fun `the ready to install notification goes when its session ends`() {
        val steps = FakeSteps()
        val updater = readyToInstall(steps)
        assertThat(notifications).hasSize(1)

        // Android dropped the session (or the listener declined) while it waited.
        updater.onInstallResult(steps.session, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertThat(updater.state.value).isEqualTo(UpdateState.Idle)
        assertThat(notifications).isEmpty()
        updater.installNow()
        assertThat(steps.confirms.get()).isEqualTo(0)

        // Installed.
        updater.update()
        awaitCommits(steps, 2)
        steps.confirmOpens = false
        updater.onConfirmNeeded(steps.session, confirmIntent())
        assertThat(notifications).hasSize(1)
        updater.onInstallResult(steps.session, PackageInstaller.STATUS_SUCCESS, null)
        assertThat(updater.state.value).isEqualTo(UpdateState.Idle)
        assertThat(notifications).isEmpty()
    }

    @Test
    fun `a newer release while ready to install drops the held confirm, edited notes don't`() {
        val steps = FakeSteps()
        val updater = readyToInstall(steps)

        AppUpdater.store(context, AvailableUpdate("v0.9.1", "0.9.1", apk.toURI().toString(), apk.length(), "Edited notes."))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(updater.state.value).isEqualTo(UpdateState.ReadyToInstall)

        val newer = File(context.filesDir, "newer.apk").apply { writeBytes(ByteArray(10)) }
        AppUpdater.store(context, AvailableUpdate("v0.9.2", "0.9.2", newer.toURI().toString(), newer.length(), "notes"))
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(updater.available.value!!.tag).isEqualTo("v0.9.2")
        assertThat(updater.state.value).isEqualTo(UpdateState.Idle)
        assertThat(notifications).isEmpty()
        updater.installNow() // the sheet now names 0.9.2: 0.9.1's confirm mustn't open from it
        assertThat(steps.confirms.get()).isEqualTo(0)
        assertThat(steps.installs.get()).isEqualTo(1)
    }

    @Test
    fun `an earlier session's answers, sent when it is abandoned, are ignored`() {
        lateinit var updater: AppUpdater
        var first = 0
        // Staging again abandons the first session, and Android answers "aborted" for it while the second one stages.
        val steps = FakeSteps(onInstall = {
            if (first != 0) updater.onInstallResult(first, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        })
        updater = AppUpdater(context, OkHttpClient(), steps)
        updater.update()
        awaitCommits(steps, 1)
        first = steps.session
        updater.onInstallResult(first, PackageInstaller.STATUS_FAILURE, "failed")
        updater.reset()

        updater.update()
        awaitCommits(steps, 2)
        assertThat(updater.state.value).isEqualTo(UpdateState.Installing)

        // And late: after the second one is committed.
        updater.onInstallResult(first, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        updater.onConfirmNeeded(first, confirmIntent())
        assertThat(updater.state.value).isEqualTo(UpdateState.Installing)
        assertThat(steps.confirms.get()).isEqualTo(0)

        // The current session's answers count.
        updater.onConfirmNeeded(steps.session, confirmIntent())
        assertThat(steps.confirms.get()).isEqualTo(1)
        updater.onInstallResult(steps.session, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertThat(updater.state.value).isEqualTo(UpdateState.Idle)
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
        shadowOf(Looper.getMainLooper()).idle()

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
