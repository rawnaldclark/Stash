package com.stash.core.media.diagnostics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.AutoplayRadioPreference
import com.stash.core.data.prefs.CrossfadePreference
import com.stash.core.media.equalizer.EqState
import com.stash.core.media.equalizer.EqStore
import com.stash.core.media.equalizer.LoudnessState
import com.stash.core.media.equalizer.LoudnessStore
import com.stash.core.media.equalizer.NamedPreset
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * The diagnostics bundle's "Playback" section: the audio settings, where media plays now,
 * and the last resolves and playback errors this app run. Track ids only: no title, URL or
 * user-typed name ever reaches it. Robolectric, so the output line runs the real AudioManager
 * query rather than a stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaybackDiagnosticsContributorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val log = PlaybackDiagnosticsLog().also { it.clock = { NOW } }
    private val crossfade: CrossfadePreference = mockk {
        every { enabled } returns flowOf(true)
        every { durationMs } returns flowOf(6_000L)
    }
    private val autoplayOn = object : AutoplayRadioPreference {
        override val enabled = flowOf(true)
        override suspend fun setEnabled(value: Boolean) = Unit
    }
    private val eqStore: EqStore = mockk { coEvery { read() } returns EqState(enabled = true, presetId = "bass") }
    private val loudnessStore: LoudnessStore = mockk { coEvery { read() } returns LoudnessState(enabled = false) }

    private fun contributor() = PlaybackDiagnosticsContributor(context, crossfade, autoplayOn, eqStore, loudnessStore, log)

    @Test fun `reports the audio settings in plain words, and says when nothing has been played yet`() = runTest {
        val s = contributor().section()
        assertThat(s).contains("Crossfade:              on · 6 s")
        assertThat(s).contains("Equalizer:              on · Bass Boost")
        assertThat(s).contains("Loudness normalization: off")
        assertThat(s).contains("Autoplay radio:         on")
        assertThat(s).contains("Recent resolves: none this app run")
        assertThat(s).contains("Recent playback errors: none this app run")
    }

    @Test fun `names the outputs Android routes media to, by type`() = runTest {
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        shadowOf(context.getSystemService(AudioManager::class.java)).setAudioDevicesForAttributes(
            media,
            ImmutableList.of(
                device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
                device(AudioDeviceInfo.TYPE_IP), // not in the name list: printed as its number
            ),
        )
        assertThat(contributor().section()).contains("Output now:             bluetooth a2dp, speaker, type 20")
    }

    @Test @Config(sdk = [26])
    fun `below Android 13 the output line says it cannot be asked`() = runTest {
        assertThat(contributor().section()).contains("Output now:             n/a (Android <13)")
    }

    @Test fun `recent resolves read newest first, with the source, the time taken and the gates`() = runTest {
        log.clock = { NOW - 40_000 }
        log.recordResolve(7L, "none (all missed)", 3_120, lossless = false)
        log.clock = { NOW - 12_000 }
        log.recordResolve(4711L, "qbdlx", 812, lossless = true)
        log.clock = { NOW }

        assertThat(contributor().section()).contains(
            "Recent resolves, this app run (newest first):\n" +
                "  12 s ago · track 4711 · qbdlx · 812 ms · lossless on\n" +
                "  40 s ago · track 7 · none (all missed) · 3120 ms · lossless off",
        )
    }

    @Test fun `recent playback errors say what failed, what the player did, and where the stream came from`() = runTest {
        log.clock = { NOW - 125_000 }
        log.recordError(null, PlaybackException("decode", null, PlaybackException.ERROR_CODE_DECODING_FAILED), "LOCAL_SKIP", null, "file")
        log.clock = { NOW - 5_000 }
        log.recordError(4711L, forbidden(), "STREAMING_CASCADE → RetrySameItem", "youtube", "https")
        log.clock = { NOW }

        assertThat(contributor().section()).contains(
            "Recent playback errors, this app run (newest first):\n" +
                "  5 s ago · track 4711 · ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 403) · STREAMING_CASCADE → RetrySameItem · origin=youtube · scheme=https\n" +
                "  2 min ago · track ? · ERROR_CODE_DECODING_FAILED · LOCAL_SKIP · origin=none · scheme=file",
        )
    }

    @Test fun `no url or title an error carries ever reaches the section`() = runTest {
        log.recordError(4711L, forbidden(), "STREAMING_CASCADE → Recover", "youtube", "https")
        // A placeholder item's URI carries the title and artist as query params.
        val placeholder = IOException("stash-resolve URI without a track id: stash-resolve://track/?t=Secret+Song&a=Some+Artist")
        log.recordError(4712L, PlaybackException("Source error", placeholder, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), "STREAMING_CASCADE → Recover", null, "stash-resolve")

        val s = contributor().section()

        assertThat(s).contains("track 4711")
        assertThat(s).contains("track 4712")
        assertThat(s).doesNotContain("googlevideo")
        assertThat(s).doesNotContain("ip=")
        assertThat(s).doesNotContain("Secret")
        assertThat(s).doesNotContain("stash-resolve://")
    }

    @Test fun `a saved equalizer preset's name is the user's own words and stays out`() = runTest {
        coEvery { eqStore.read() } returns EqState(
            enabled = true,
            presetId = "u_1",
            customPresets = listOf(NamedPreset("u_1", "Sam's car", FloatArray(5), 0f)),
        )
        val s = contributor().section()
        assertThat(s).contains("Equalizer:              on · a saved preset")
        assertThat(s).doesNotContain("Sam's car")
    }

    @Test fun `the buffers keep only the last 10 resolves and 5 errors, newest first`() {
        repeat(12) { log.recordResolve(it.toLong(), "youtube", 100, lossless = true) }
        repeat(7) { log.recordError(it.toLong(), PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), "LOCAL_SKIP", null, "file") }

        assertThat(log.recentResolves().map { it.trackId }).containsExactly(11L, 10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L).inOrder()
        assertThat(log.recentErrors().map { it.trackId }).containsExactly(6L, 5L, 4L, 3L, 2L).inOrder()
    }

    @Test fun `a failing block costs one line, not the section`() = runTest {
        coEvery { eqStore.read() } throws IllegalStateException("datastore closed")
        log.recordResolve(4711L, "qbdlx", 812, lossless = true)

        val s = contributor().section()

        assertThat(s).contains("[settings unavailable: datastore closed]")
        assertThat(s).contains("track 4711 · qbdlx")
    }

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    /** What ExoPlayer raises for a 403: the cause carries the stream URL (and with it the user's IP). */
    private fun forbidden() = PlaybackException(
        "Source error: $STREAM_URL",
        HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), DataSpec(Uri.parse(STREAM_URL)), ByteArray(0)),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    private companion object {
        const val NOW = 1_758_000_000_000L
        const val STREAM_URL = "https://rr3---sn-4g5e6nsz.googlevideo.com/videoplayback?ip=203.0.113.7&sig=SECRET"
    }
}
