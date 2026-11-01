package com.stash.core.media

import android.content.Context
import androidx.annotation.StringRes
import com.stash.core.data.radio.RadioSeed
import com.stash.core.model.RadioStartResult
import com.stash.core.model.Track
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Start radio from the Library: the song menus (Songs, playlists, Liked, albums, an artist's songs,
 * History) and Library's artist page. The same seeds the rest of the app uses: a song's menu starts
 * a song radio (RadioSeed.Song, as Search, Now Playing's queue and Your record do) and an artist
 * page starts an artist radio (RadioSeed.Artist, as Search's artist page does; without its browse
 * id, which Library doesn't have, the generator looks the artist up by name).
 *
 * Each call returns the line to show when the station didn't start, or null when it did (the music
 * starting is the feedback).
 */
interface RadioStarter {

    /** A song radio seeded from [track]. */
    suspend fun fromSong(track: Track): String?

    /** An artist radio seeded from [artistName]; nothing (null) for a blank name. */
    suspend fun fromArtist(artistName: String): String?

    companion object {
        /** The hint for a station that didn't start; null for [RadioStartResult.Started]. */
        @StringRes
        fun failureMessage(result: RadioStartResult): Int? = when (result) {
            RadioStartResult.Started -> null
            RadioStartResult.PlayerNotReady -> R.string.radio_failed_player_not_ready
            RadioStartResult.NoStation -> R.string.radio_failed_no_station
            RadioStartResult.DownloadMode -> R.string.radio_failed_download_mode
        }
    }
}

/** [RadioStarter] on the player. See RadioStarterTest. */
@Singleton
class PlayerRadioStarter @Inject constructor(
    private val playerRepository: PlayerRepository,
    @ApplicationContext private val context: Context,
) : RadioStarter {

    override suspend fun fromSong(track: Track): String? = messageFor(
        playerRepository.startRadio(
            RadioSeed.Song(title = track.title, artist = track.artist, ytVideoId = track.youtubeId),
        ),
    )

    override suspend fun fromArtist(artistName: String): String? {
        val name = artistName.trim()
        if (name.isEmpty()) return null
        return messageFor(playerRepository.startRadio(RadioSeed.Artist(name)))
    }

    private fun messageFor(result: RadioStartResult): String? =
        RadioStarter.failureMessage(result)?.let(context::getString)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RadioStarterModule {
    @Binds
    abstract fun bindRadioStarter(impl: PlayerRadioStarter): RadioStarter
}
