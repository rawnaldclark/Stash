package com.stash.core.data.backcover

import com.stash.core.data.prefs.NowPlayingPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** Where back covers and inside pages may come from, beyond the Cover Art Archive. */
interface BackCoverSources {
    /**
     * Whether Discogs fills in what the archive lacks (Settings > Appearance > Discogs art). Its
     * images are its users' photos and scans, some of them poor, so it can be turned off.
     */
    val discogs: Flow<Boolean>

    /** When Discogs was last turned on: an answer looked up before then, without it, is asked again. */
    suspend fun discogsOnSince(): Long

    /** Every source, always: tests and previews. */
    object All : BackCoverSources {
        override val discogs: Flow<Boolean> = flowOf(true)
        override suspend fun discogsOnSince(): Long = 0L
    }
}

/** [BackCoverSources] as the user set them, in Now Playing's preferences. */
@Singleton
class PreferenceBackCoverSources @Inject constructor(
    private val preference: NowPlayingPreference,
) : BackCoverSources {
    override val discogs: Flow<Boolean> = preference.discogsArt
    override suspend fun discogsOnSince(): Long = preference.discogsArtOnSince.first()
}
