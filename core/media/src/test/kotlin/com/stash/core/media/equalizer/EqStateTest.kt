package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class EqStateTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test fun `default state has enabled=false and zero gains`() {
    val s = EqState()
    assertThat(s.enabled).isFalse()
    assertThat(s.gainsDb.toList()).containsExactly(0f, 0f, 0f, 0f, 0f).inOrder()
    assertThat(s.preampDb).isEqualTo(0f)
    assertThat(s.bassBoostDb).isEqualTo(0f)
    assertThat(s.presetId).isEqualTo("flat")
    assertThat(s.customPresets).isEmpty()
    assertThat(s.schemaVersion).isEqualTo(1)
  }

  @Test fun `round-trip JSON preserves all fields`() {
    val original = EqState(
      enabled = true,
      presetId = "rock",
      gainsDb = floatArrayOf(4f, 2f, -1f, 2f, 3f),
      preampDb = -2f,
      bassBoostDb = 5f,
    )
    val encoded = json.encodeToString(EqState.serializer(), original)
    val decoded = json.decodeFromString(EqState.serializer(), encoded)
    assertThat(decoded).isEqualTo(original)
  }

  @Test fun `custom preset is serialisable`() {
    val s = EqState(customPresets = listOf(NamedPreset("u1", "My Mix",
      floatArrayOf(2f, 0f, 0f, 0f, 2f), 0f)))
    val encoded = json.encodeToString(EqState.serializer(), s)
    val decoded = json.decodeFromString(EqState.serializer(), encoded)
    assertThat(decoded.customPresets).hasSize(1)
    assertThat(decoded.customPresets[0].name).isEqualTo("My Mix")
  }
}
