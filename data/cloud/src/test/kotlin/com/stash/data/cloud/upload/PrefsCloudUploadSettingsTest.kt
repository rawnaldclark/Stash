package com.stash.data.cloud.upload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What survives an app restart: the choices, and each account's own "Drive full". */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PrefsCloudUploadSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A second instance reads what the first one wrote, as after an app restart. */
    private fun fresh() = PrefsCloudUploadSettings(context)

    @Test fun `drive full is per account and comes back after a restart`() = runTest {
        val settings = fresh()
        settings.setDriveFull(1, true)
        settings.setDriveFull(2, true)
        settings.setDriveFull(1, false)

        assertThat(settings.fullAccounts.value).containsExactly(2L)
        assertThat(fresh().fullAccounts.value).containsExactly(2L)
    }

    @Test fun `the old one-for-all flag is dropped, it can't say which account it meant`() {
        val prefs = context.getSharedPreferences("cloud_gdrive_uploads", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("drive_full", true).commit()

        assertThat(fresh().fullAccounts.value).isEmpty()
        assertThat(prefs.contains("drive_full")).isFalse()
    }

    @Test fun `the save account and backup account survive a restart`() = runTest {
        val settings = fresh()
        assertThat(settings.saveAccountId.value).isNull()
        settings.setSaveAccount(7)
        settings.setBackupAccount(7)

        assertThat(fresh().saveAccountId.value).isEqualTo(7L)
        assertThat(fresh().backupAccountId.value).isEqualTo(7L)

        settings.setSaveAccount(null)
        assertThat(fresh().saveAccountId.value).isNull()
    }

    @Test fun `forgetting one account's folders leaves the other's and its full flag`() = runTest {
        val settings = fresh()
        settings.put(1, "Stash", "folder-1")
        settings.put(2, "Stash", "folder-2")
        settings.setDriveFull(2, true)

        settings.clear(1)

        assertThat(settings.get(1, "Stash")).isNull()
        assertThat(settings.get(2, "Stash")).isEqualTo("folder-2")
        assertThat(fresh().fullAccounts.value).containsExactly(2L)
    }
}
