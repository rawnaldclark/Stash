package com.stash.core.common.notification

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The tap target every Stash notification without a screen of its own shares. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OpenStashTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun installLauncher(): ComponentName {
        val component = ComponentName(context.packageName, "com.stash.app.MainActivity")
        val pm = shadowOf(context.packageManager)
        pm.addOrUpdateActivity(ActivityInfo().apply { packageName = component.packageName; name = component.className })
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        return component
    }

    @Test fun `a tap opens the launcher activity`() {
        val component = installLauncher()
        val pending = openStashPendingIntent(context, OpenStashRequest.SLEEP_TIMER)

        assertThat(pending).isNotNull()
        val saved = shadowOf(pending!!)
        assertThat(saved.isActivity).isTrue()
        assertThat(saved.savedIntents.single().component).isEqualTo(component)
        // Immutable: Android 12+ refuses a mutable one, and nothing needs to fill it in.
        assertThat(saved.flags and PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
    }

    @Test fun `without a launcher activity there is no tap, not a crash`() {
        assertThat(openStashPendingIntent(context, OpenStashRequest.SLEEP_TIMER)).isNull()
    }

    @Test fun `every notification has its own request code, apart from the Downloads shortcut`() {
        val codes = listOf(
            OpenStashRequest.SLEEP_TIMER, OpenStashRequest.SYNC_COMPLETE, OpenStashRequest.FLAC_UPGRADE_SUMMARY,
            OpenStashRequest.CLOUD_UPLOAD, OpenStashRequest.LYRICS_PROGRESS, OpenStashRequest.LYRICS_DONE,
        )
        assertThat(codes.toSet()).hasSize(codes.size)
        // SyncNotificationManager.REQUEST_OPEN_DOWNLOADS: the same launch Intent plus an extra, which
        // PendingIntent matching ignores, so sharing the code would overwrite the Downloads shortcut.
        assertThat(codes).doesNotContain(9101)
    }
}
