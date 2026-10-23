package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Top-level Context extension — mirrors LosslessSourcePreferences.kt:18-20.
private val Context.likeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "like_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * User preferences for the v0.9.13 Library & Likes feature:
 *  - Auto-save toggle + threshold slider (default 3 distinct days/30d)
 *  - Heart-button per-destination defaults (Stash on, Spotify on, YT off)
 *
 * Stash on by default for the heart button — local Liked Songs always
 * works, no external account required. Spotify on by default IF/WHEN
 * the user is connected (Settings UI gates visibility). YT off by
 * default — most users haven't connected YT Music.
 *
 * Auto-save off by default. Even with Spotify connected, opt-in
 * required.
 *
 * v0.9.52 like-mirroring: `mirror_likes_*` opt-ins, default false —
 * deliberately NOT reusing `heart_default_*`, whose true-defaults would
 * auto-enable external writes for every existing user on update.
 */
@Singleton
class LikePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val heartDefaultStashKey = booleanPreferencesKey("heart_default_stash")
    private val heartDefaultSpotifyKey = booleanPreferencesKey("heart_default_spotify")
    private val heartDefaultYtMusicKey = booleanPreferencesKey("heart_default_ytmusic")
    private val mirrorLikesSpotifyKey = booleanPreferencesKey("mirror_likes_spotify")
    private val mirrorLikesYtMusicKey = booleanPreferencesKey("mirror_likes_youtube")
    private val mirrorLikesLastFmKey = booleanPreferencesKey("mirror_likes_lastfm")

    val heartDefaultStash: Flow<Boolean> =
        context.likeDataStore.data.map { it[heartDefaultStashKey] ?: true }

    val heartDefaultSpotify: Flow<Boolean> =
        context.likeDataStore.data.map { it[heartDefaultSpotifyKey] ?: true }

    val heartDefaultYtMusic: Flow<Boolean> =
        context.likeDataStore.data.map { it[heartDefaultYtMusicKey] ?: false }

    // NOTE (review pass 3, 2026-07-31): heartDefaultStash / heartDefaultSpotify /
    // heartDefaultYtMusic are SUPERSEDED and decide nothing.
    //
    // They were the original "which services does the heart reach" controls. That job
    // now belongs to mirrorLikesSpotify / mirrorLikesYtMusic below, which LikeCoordinator
    // actually reads. The heartDefault flows are still plumbed into SettingsUiState, but
    // no switch renders them and nothing in core/data consults them — outside tests, they
    // have zero readers.
    //
    // Left in place deliberately rather than deleted: SettingsViewModel assembles UiState
    // with an index-addressed combine (values[27], values[28], ...), so removing a source
    // silently shifts every later index and hands the UI a type-correct wrong value. That
    // is a worse bug than the dead weight. Remove these together with that combine, not
    // before.
    //
    // The trap to avoid meanwhile: adding a Settings switch for heartDefault* would give
    // users a control that does nothing. Wire it to the mirror prefs instead.

    val mirrorLikesSpotify: Flow<Boolean> =
        context.likeDataStore.data.map { it[mirrorLikesSpotifyKey] ?: false }

    val mirrorLikesYtMusic: Flow<Boolean> =
        context.likeDataStore.data.map { it[mirrorLikesYtMusicKey] ?: false }

    /**
     * Mirror hearts to Last.fm loved tracks. Off by default like the others —
     * mirroring writes to an account the user did not ask us to touch until they
     * say so.
     */
    val mirrorLikesLastFm: Flow<Boolean> =
        context.likeDataStore.data.map { it[mirrorLikesLastFmKey] ?: false }

    suspend fun heartDefaultStashNow(): Boolean = heartDefaultStash.first()
    suspend fun heartDefaultSpotifyNow(): Boolean = heartDefaultSpotify.first()
    suspend fun heartDefaultYtMusicNow(): Boolean = heartDefaultYtMusic.first()
    suspend fun mirrorLikesSpotifyNow(): Boolean = mirrorLikesSpotify.first()
    suspend fun mirrorLikesYtMusicNow(): Boolean = mirrorLikesYtMusic.first()
    suspend fun mirrorLikesLastFmNow(): Boolean = mirrorLikesLastFm.first()

    suspend fun setHeartDefaultStash(value: Boolean) {
        context.likeDataStore.edit { it[heartDefaultStashKey] = value }
    }

    suspend fun setHeartDefaultSpotify(value: Boolean) {
        context.likeDataStore.edit { it[heartDefaultSpotifyKey] = value }
    }

    suspend fun setHeartDefaultYtMusic(value: Boolean) {
        context.likeDataStore.edit { it[heartDefaultYtMusicKey] = value }
    }

    suspend fun setMirrorLikesSpotify(value: Boolean) {
        context.likeDataStore.edit { it[mirrorLikesSpotifyKey] = value }
    }

    suspend fun setMirrorLikesYtMusic(value: Boolean) {
        context.likeDataStore.edit { it[mirrorLikesYtMusicKey] = value }
    }

    suspend fun setMirrorLikesLastFm(value: Boolean) {
        context.likeDataStore.edit { it[mirrorLikesLastFmKey] = value }
    }
}
