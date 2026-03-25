package com.stash.feature.nowplaying

import android.content.Context
import android.net.Uri
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Development-only helper that wires up a small queue of synthetic [Track] objects
 * pointing to the bundled [R.raw.test_silence] WAV resource and immediately begins
 * playback via [PlayerRepository.setQueue].
 *
 * This allows developers and QA to exercise the Now Playing UI, MediaController
 * lifecycle, and palette color extraction without requiring a fully integrated
 * music source (Spotify, YouTube, etc.) or a real downloaded file on device.
 *
 * Usage (e.g. from a dev-menu composable or debug Activity):
 * ```
 * @Inject lateinit var testPlaybackHelper: TestPlaybackHelper
 * // inside a coroutine scope:
 * testPlaybackHelper.startTestQueue()
 * ```
 *
 * NOTE: This class should NOT be referenced from production code paths.
 * It is intentionally placed in the `feature/nowplaying` module so it is
 * excluded from the `:core:*` public API surface.
 */
@Singleton
class TestPlaybackHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerRepository: PlayerRepository,
) {

    /**
     * Builds a three-track test queue using [R.raw.test_silence] as the audio
     * source for every entry, then calls [PlayerRepository.setQueue] to start
     * playback from the first track.
     *
     * The resource URI follows the `android.resource://<package>/<res-id>` scheme
     * understood by ExoPlayer's [androidx.media3.exoplayer.source.DefaultMediaSourceFactory].
     *
     * Suspend because [PlayerRepository.setQueue] is a suspending function that
     * connects to the [androidx.media3.session.MediaController] before issuing
     * commands.
     */
    suspend fun startTestQueue() {
        val packageName = context.packageName
        val audioUri = Uri.parse("android.resource://$packageName/${R.raw.test_silence}")
            .toString()

        val testTracks = buildTestTracks(audioUri)
        playerRepository.setQueue(tracks = testTracks, startIndex = 0)
    }

    /**
     * Constructs a list of three [Track] objects suitable for UI exercising.
     *
     * Each track carries:
     * - A distinct [Track.id] so the queue/adapter treats them as separate items.
     * - Realistic-looking metadata (title, artist, album) for testing the Now
     *   Playing display.
     * - [Track.filePath] pointing at [audioUri] so ExoPlayer can open the file.
     * - [Track.isDownloaded] set to `true` to bypass any download-check guards.
     * - [Track.source] set to [MusicSource.SPOTIFY] as the nominal origin — no
     *   actual Spotify API calls are made for a local resource URI.
     *
     * @param audioUri  `android.resource://…` URI string for the raw WAV asset.
     */
    private fun buildTestTracks(audioUri: String): List<Track> = listOf(
        Track(
            id = TEST_TRACK_ID_1,
            title = "Test Track — Silence I",
            artist = "Stash Dev",
            album = "Test Album",
            durationMs = TEST_DURATION_MS,
            filePath = audioUri,
            fileFormat = "wav",
            source = MusicSource.SPOTIFY,
            isDownloaded = true,
            albumArtUrl = null,
        ),
        Track(
            id = TEST_TRACK_ID_2,
            title = "Test Track — Silence II",
            artist = "Stash Dev",
            album = "Test Album",
            durationMs = TEST_DURATION_MS,
            filePath = audioUri,
            fileFormat = "wav",
            source = MusicSource.SPOTIFY,
            isDownloaded = true,
            albumArtUrl = null,
        ),
        Track(
            id = TEST_TRACK_ID_3,
            title = "Test Track — Silence III",
            artist = "Stash Dev",
            album = "Test Album",
            durationMs = TEST_DURATION_MS,
            filePath = audioUri,
            fileFormat = "wav",
            source = MusicSource.SPOTIFY,
            isDownloaded = true,
            albumArtUrl = null,
        ),
    )

    companion object {
        // Stable IDs well outside the range of real Room-generated IDs.
        private const val TEST_TRACK_ID_1 = -1001L
        private const val TEST_TRACK_ID_2 = -1002L
        private const val TEST_TRACK_ID_3 = -1003L

        // Duration of the bundled silent WAV (~46 ms), expressed in ms.
        // Using a round number so the progress bar is exercisable.
        private const val TEST_DURATION_MS = 5_000L
    }
}
