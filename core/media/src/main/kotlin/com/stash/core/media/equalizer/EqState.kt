package com.stash.core.media.equalizer

import kotlinx.serialization.Serializable

/**
 * Single source of truth for equalizer state.
 *
 * Held by [EqController]; serialised by [EqStore]. Mutations flow through the
 * controller's `update {}` only — UI emits events, processors only read.
 *
 * Defaults are intentionally "everything off" so a missing or corrupted
 * persisted state cannot accidentally enable EQ — the root of the legacy
 * "EQ off but audio still EQ'd" bug.
 */
@Serializable
data class EqState(
  val schemaVersion: Int = 1,
  val enabled: Boolean = false,
  val presetId: String = "flat",
  val gainsDb: FloatArray = floatArrayOf(0f, 0f, 0f, 0f, 0f),
  val preampDb: Float = 0f,
  val bassBoostDb: Float = 0f,
  val customPresets: List<NamedPreset> = emptyList(),
) {
  // FloatArray needs explicit equals/hashCode for data-class semantics.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EqState) return false
    return schemaVersion == other.schemaVersion &&
      enabled == other.enabled &&
      presetId == other.presetId &&
      gainsDb.contentEquals(other.gainsDb) &&
      preampDb == other.preampDb &&
      bassBoostDb == other.bassBoostDb &&
      customPresets == other.customPresets
  }
  override fun hashCode(): Int {
    var r = schemaVersion
    r = 31 * r + enabled.hashCode()
    r = 31 * r + presetId.hashCode()
    r = 31 * r + gainsDb.contentHashCode()
    r = 31 * r + preampDb.hashCode()
    r = 31 * r + bassBoostDb.hashCode()
    r = 31 * r + customPresets.hashCode()
    return r
  }
}

@Serializable
data class NamedPreset(
  val id: String,
  val name: String,
  val gainsDb: FloatArray,
  val preampDb: Float,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is NamedPreset) return false
    return id == other.id && name == other.name &&
      gainsDb.contentEquals(other.gainsDb) && preampDb == other.preampDb
  }
  override fun hashCode(): Int {
    var r = id.hashCode()
    r = 31 * r + name.hashCode()
    r = 31 * r + gainsDb.contentHashCode()
    r = 31 * r + preampDb.hashCode()
    return r
  }
}
