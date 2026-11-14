package com.stash.core.data.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Tapping the "all good" sync summary or the FLAC-upgrade summary opens Stash, not nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncNotificationTapTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val launcher = ComponentName(context.packageName, "com.stash.app.MainActivity")

    @Before fun installLauncher() {
        val pm = shadowOf(context.packageManager)
        pm.addOrUpdateActivity(ActivityInfo().apply { packageName = launcher.packageName; name = launcher.className })
        pm.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun opensStash(n: Notification?) {
        assertThat(n).isNotNull()
        assertThat(n!!.contentIntent).isNotNull()
        assertThat(shadowOf(n.contentIntent).savedIntents.single().component).isEqualTo(launcher)
    }

    @Test fun `the all-good sync summary opens Stash`() {
        SyncNotificationManager(context).showSummary(newTracks = 3, playlistsChecked = 2, failed = 0)
        opensStash(shadowOf(nm).getNotification(SyncNotificationManager.NOTIFICATION_ID_SUMMARY))
    }

    @Test fun `the FLAC upgrade summary opens Stash`() {
        SyncNotificationManager(context).showFlacUpgradeSummary(upgraded = 4, noMatch = 1, failed = 0, skipped = 0)
        opensStash(shadowOf(nm).getNotification(SyncNotificationManager.NOTIFICATION_ID_FLAC_SUMMARY))
    }
}
