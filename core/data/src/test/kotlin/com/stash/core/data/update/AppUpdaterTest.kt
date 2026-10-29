package com.stash.core.data.update

import android.content.Context
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
    }

    private fun store(size: Long, file: File = apk) =
        AppUpdater.store(context, AvailableUpdate("v0.9.1", "0.9.1", file.toURI().toString(), size, "notes"))

    private class FakeSteps(
        @Volatile var allowed: Boolean = true,
        val onInstall: (File) -> Unit = {},
    ) : AppUpdater.Steps {
        val installs = AtomicInteger()
        override fun verify(apk: File): UpdateState.Failed.Reason? = null
        override fun canInstall() = allowed
        override fun install(apk: File) {
            installs.incrementAndGet()
            onInstall(apk)
        }
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
