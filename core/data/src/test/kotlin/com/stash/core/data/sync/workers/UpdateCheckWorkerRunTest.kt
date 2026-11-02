package com.stash.core.data.sync.workers

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.R
import com.stash.core.data.update.AppUpdater
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The update check run whole, on a debug build's test release (`files/update_test.json`): the notification it posts, and
 * none for a version the listener skipped (review on #814).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpdateCheckWorkerRunTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Before
    fun setUp() {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).versionName = "0.9.122"
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        // The launcher activity a tap opens the sheet through.
        val main = ComponentName(context.packageName, "com.stash.app.MainActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(main)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            main,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        val apk = File(context.filesDir, "test-update.apk").apply { writeBytes(ByteArray(10)) }
        // The tag as GitHub might send it, with a stray space: it is trimmed like the stored release's.
        File(context.filesDir, "update_test.json").writeText(
            """
            {"tag_name":" v0.9.123 ","name":"Stash v0.9.123","draft":false,"prerelease":false,
             "body":"Paste links from SoundCloud. Sync tab OVERHAUL!\r\n\r\n## What's new\r\n- **SoundCloud.** Sync it.\r\n\r\n## What's Changed\r\n* Things by @someone in https://github.com/rawnaldclark/Stash/pull/1",
             "assets":[{"name":"Stash.apk","size":10,"browser_download_url":"${apk.toURI()}"}]}
            """.trimIndent(),
        )
    }

    private fun run(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<UpdateCheckWorker>(context).build().doWork() }

    @Test
    fun `a newer release posts Stash 0_9_123 is ready over the notes' first line, with Stash's own icon`() {
        assertThat(run()).isInstanceOf(ListenableWorker.Result.Success::class.java)

        val posted = notifications.single()
        assertThat(posted.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Stash 0.9.123 is ready")
        assertThat(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("Paste links from SoundCloud. Sync tab OVERHAUL!")
        assertThat(posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
            .isEqualTo("Paste links from SoundCloud. Sync tab OVERHAUL!\nTap to see what's new.")
        assertThat(posted.smallIcon.resId).isEqualTo(R.drawable.ic_stat_stash)
        // Stored trimmed for the sheet, and remembered as notified under the same tag.
        val prefs = context.getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE)
        assertThat(prefs.getString("available_tag", null)).isEqualTo("v0.9.123")
        assertThat(prefs.getString("last_notified_version", null)).isEqualTo("v0.9.123")
    }

    @Test
    fun `a skipped version posts nothing, and is still stored for Check for updates`() {
        context.getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE).edit().putString("skipped_tag", "v0.9.123").commit()

        val result = run()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat((result as ListenableWorker.Result.Success).outputData.getString(UpdateCheckWorker.KEY_OUTCOME))
            .isEqualTo(UpdateCheckWorker.OUTCOME_UPDATE_AVAILABLE)
        assertThat(notifications).isEmpty()
        assertThat(context.getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE).getString("available_tag", null))
            .isEqualTo("v0.9.123")
    }

    @Test
    fun `the next version after a skipped one is announced`() {
        context.getSharedPreferences(AppUpdater.PREFS, Context.MODE_PRIVATE).edit().putString("skipped_tag", "v0.9.122").commit()

        run()

        assertThat(notifications).hasSize(1)
    }
}
