package com.stash.core.data.prefs

import com.stash.core.model.QualityTier
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for reading and writing the user's preferred audio quality tier.
 *
 * Lives in `:core:data` so feature modules can inject it without depending on
 * `:data:download`. The concrete implementation backed by DataStore is provided
 * by [com.stash.data.download.prefs.QualityPreferencesManager] and bound via Hilt.
 */
interface QualityPreference {

    /** Emits the current [QualityTier], defaulting to [QualityTier.BEST]. */
    val qualityTier: Flow<QualityTier>

    /** Persists the selected [tier] for future downloads. */
    suspend fun setQualityTier(tier: QualityTier)
}
