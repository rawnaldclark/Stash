// EqStoreTest.kt
package com.stash.core.media.equalizer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EqStoreTest {
  private lateinit var store: EqStore
  private lateinit var file: File

  @Before fun setUp() {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    file = ctx.preferencesDataStoreFile("eq_state_test")
    val ds = PreferenceDataStoreFactory.create { file }
    store = EqStore(ds)
  }

  @After fun tearDown() { file.delete() }

  @Test fun `read on missing key returns default with enabled false`() = runBlocking {
    val s = store.read()
    assertThat(s.enabled).isFalse()
    assertThat(s).isEqualTo(EqState())
  }

  @Test fun `write then read round-trip`() = runBlocking {
    val original = EqState(enabled = true, presetId = "rock",
      gainsDb = floatArrayOf(4f, 2f, -1f, 2f, 3f), preampDb = -2f, bassBoostDb = 5f)
    store.write(original)
    val restored = store.read()
    assertThat(restored).isEqualTo(original)
  }

  @Test fun `corrupted JSON falls back to default`() = runBlocking {
    store.writeRaw("{ this is not valid json }")
    val s = store.read()
    assertThat(s).isEqualTo(EqState())
  }
}
