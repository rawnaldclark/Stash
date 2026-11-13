package com.stash.core.addons

import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamInfo

/** Which contract an add-on's manifest was written to (SPEC.md 10). The host reads all three as v1. */
enum class AddonDialect {
    /** Stash Add-ons v1 (`stash: 1`). */
    V1,

    /** Stash Sources v0 (`stash: 0`), read through [AddonV0]. */
    V0,

    /** Eclipse, BitChord and LastWave add-ons (no `stash` field), read through [AddonEclipse]. */
    ECLIPSE,
}

/**
 * What the Eclipse-family reader learned that a v1 [Manifest] has no field for (add-ons plan
 * phase 4 §2.3). Null for v1 and v0 add-ons.
 */
data class EclipseTraits(
    /** `isrc` without `resolve`: resolve through `/resolve-isrc?isrc=`, asked only for songs with an ISRC. */
    val resolveViaIsrc: Boolean = false,
    /** False for `allowDownloads: 0` (BitChord's switch): never a download source. */
    val allowDownloads: Boolean = true,
    /** The first option of a `quality` select the add-on declares, sent as `quality` on its streams. */
    val qualityOption: String? = null,
)

/** A manifest as the host reads it: the v1 [manifest], the [dialect] it came in, and an Eclipse add-on's [eclipse] traits. */
data class AddonManifest(
    val manifest: Manifest,
    val dialect: AddonDialect = AddonDialect.V1,
    val eclipse: EclipseTraits? = null,
)

/**
 * An answer outside the asked range, kept so a lower range in the same play can use it without
 * asking the add-on again (add-ons plan phase 4 §2.3). Its [toString] never names the URL: a
 * [AddonFailure] reaches logs.
 */
class HeldStream(val info: StreamInfo) {
    override fun toString(): String = "HeldStream(${info.tier.wire})"
    override fun equals(other: Any?): Boolean = other is HeldStream && other.info == info
    override fun hashCode(): Int = info.hashCode()
}

/**
 * Thrown by a parse step for an answer that is not broken but is a refusal all the same: a miss
 * (Eclipse's `200 {error}`), a stream outside the asked range, or one Stash can't play. The client
 * turns it into [AddonFailure.Refused] with [code], never [AddonFailure.InvalidResponse], so it
 * never counts toward the add-on's breaker.
 */
internal class AddonRefusalException(val code: ErrorCode, val held: StreamInfo? = null) : Exception(code.wire)
