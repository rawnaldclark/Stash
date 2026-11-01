package com.stash.core.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.radio.RadioSeed
import com.stash.core.model.RadioStartResult
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Library's Start radio actions ([PlayerRadioStarter]): the seeds they start and the lines they show. */
@RunWith(RobolectricTestRunner::class)
class RadioStarterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val player = mockk<PlayerRepository>()
    private val starter = PlayerRadioStarter(player, context)

    private val song = Track(id = 7L, title = "Starting Over", artist = "LSD and the Search for God", youtubeId = "yt123")

    @Test
    fun `a song's menu starts a song radio seeded from that song`() = runTest {
        coEvery { player.startRadio(any(), any()) } returns RadioStartResult.Started

        assertThat(starter.fromSong(song)).isNull() // started: the music is the feedback

        coVerify(exactly = 1) {
            player.startRadio(RadioSeed.Song(title = "Starting Over", artist = "LSD and the Search for God", ytVideoId = "yt123"), false)
        }
    }

    @Test
    fun `the artist page starts an artist radio by name`() = runTest {
        coEvery { player.startRadio(any(), any()) } returns RadioStartResult.Started

        assertThat(starter.fromArtist("  Slowdive ")).isNull()

        coVerify(exactly = 1) { player.startRadio(RadioSeed.Artist("Slowdive"), false) }
    }

    @Test
    fun `a blank artist starts nothing`() = runTest {
        assertThat(starter.fromArtist("  ")).isNull()
        coVerify(exactly = 0) { player.startRadio(any(), any()) }
    }

    @Test
    fun `each failure explains itself`() = runTest {
        val expected = mapOf(
            RadioStartResult.PlayerNotReady to R.string.radio_failed_player_not_ready,
            RadioStartResult.NoStation to R.string.radio_failed_no_station,
            RadioStartResult.DownloadMode to R.string.radio_failed_download_mode,
        )
        for ((result, message) in expected) {
            coEvery { player.startRadio(any(), any()) } returns result
            assertThat(starter.fromSong(song)).isEqualTo(context.getString(message))
            assertThat(starter.fromArtist("Slowdive")).isEqualTo(context.getString(message))
        }
    }

    @Test
    fun `every start result is mapped`() {
        val all = listOf(RadioStartResult.Started, RadioStartResult.PlayerNotReady, RadioStartResult.NoStation, RadioStartResult.DownloadMode)
        assertThat(all.map { RadioStarter.failureMessage(it) == null }).containsExactly(true, false, false, false).inOrder()
    }
}
