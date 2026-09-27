package com.stash.core.data.share

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SharePreferenceTest {
    // Saved on half an emoji, the name would read back after a restart ending in "?".
    @Test fun `a saved name is cut to 40 without splitting an emoji`() = runBlocking {
        val prefs = SharePreference(ApplicationProvider.getApplicationContext())
        prefs.setDisplayName("n".repeat(39) + "🎧")
        assertThat(prefs.displayName()).isEqualTo("n".repeat(39))
    }
}
