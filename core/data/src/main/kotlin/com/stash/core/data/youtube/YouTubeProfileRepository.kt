package com.stash.core.data.youtube

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.YouTubeIdentity
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncScheduler
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.YouTubeProfile
import com.stash.data.ytmusic.YouTubeProfiles
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * YouTube Music profiles (#664). A Google account can own Brand Account profiles, each with its own
 * library; Stash used to read only the account's own, so someone whose music lives on a Brand profile
 * synced a near-empty library. This lists the account's profiles and records the one to use, which
 * every signed-in YouTube Music request then acts as (InnerTubeClient), and syncs its library.
 */
@Singleton
class YouTubeProfileRepository @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val tokenManager: TokenManager,
    private val syncScheduler: SyncScheduler,
    private val syncPreferences: SyncPreferencesManager,
) {
    /** The account's profiles, or null when they couldn't be read (not signed in, offline, a changed response). */
    suspend fun list(): List<YouTubeProfile>? =
        runCatching { innerTube.accountsList()?.let(YouTubeProfiles::parse) }.getOrNull()

    /** The profile in use: the account's own unless a Brand profile was picked. */
    suspend fun current(): YouTubeIdentity = tokenManager.getYouTubeIdentity()

    /** Use [profile] from now on, and sync its library. */
    suspend fun choose(profile: YouTubeProfile) {
        use(YouTubeIdentity(profile.pageId, profile.authUser), profile.name, profile.photoUrl)
    }

    /**
     * A Brand Account by the id in its myaccount.google.com/b/<id> address: the way in when the
     * profiles can't be listed. Null or blank goes back to the account's own profile.
     */
    suspend fun chooseById(pageId: String?, name: String) {
        use(YouTubeIdentity(pageId?.trim()?.takeIf { it.isNotEmpty() }), name, null)
    }

    private suspend fun use(identity: YouTubeIdentity, name: String, photo: String?) {
        tokenManager.setYouTubeProfile(identity, name, photo)
        runCatching { syncScheduler.triggerManualSync(syncPreferences.preferences.first().wifiOnly) }
    }

    companion object {
        /** A Brand Account id as myaccount.google.com/b/<id> shows it: 21 digits (some are 20). */
        val BRAND_ID = Regex("""\d{15,25}""")
    }
}
