// PresetCatalogTest.kt
package com.stash.core.media.equalizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PresetCatalogTest {
  @Test fun `flat has all zero gains`() {
    val flat = PresetCatalog.byId("flat")!!
    assertThat(flat.gainsDb.toList()).containsExactly(0f, 0f, 0f, 0f, 0f).inOrder()
  }

  @Test fun `bass preset boosts low bands`() {
    val bass = PresetCatalog.byId("bass")!!
    assertThat(bass.gainsDb[0]).isGreaterThan(0f) // 60 Hz boosted
    assertThat(bass.gainsDb[1]).isGreaterThan(0f) // 230 Hz boosted
  }

  @Test fun `byId returns null for unknown id`() {
    assertThat(PresetCatalog.byId("nope")).isNull()
  }

  @Test fun `all presets have 5-element gain array`() {
    PresetCatalog.builtIn.forEach { p ->
      assertThat(p.gainsDb).hasLength(5)
    }
  }

  @Test fun `built-in ids are unique`() {
    val ids = PresetCatalog.builtIn.map { it.id }
    assertThat(ids).containsNoDuplicates()
  }

  @Test fun `allFor places built-ins first then custom presets`() {
    val custom = NamedPreset("u1", "My Mix",
      floatArrayOf(2f, 0f, 0f, 0f, 2f), 0f)
    val combined = PresetCatalog.allFor(listOf(custom))
    assertThat(combined).hasSize(PresetCatalog.builtIn.size + 1)
    assertThat(combined.first().id).isEqualTo("flat")
    assertThat(combined.last()).isEqualTo(custom)
  }
}
