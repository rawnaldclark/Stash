package com.stash.data.download.shared

import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.model.Track
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.lossless.AudioFormat
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for [TrackFinalizer]. v0.9.35 introduces art-resolution
 * through [AlbumArtCache] — these tests pin that the resolved file
 * (or null) is forwarded to [MetadataEmbedder.embedMetadata].
 */
class TrackFinalizerTest {

    private val metadataEmbedder: MetadataEmbedder = mockk()
    private val fileOrganizer: FileOrganizer = mockk()
    private val audioExtractor: AudioDurationExtractor = mockk()
    private val albumArtCache: AlbumArtCache = mockk()

    private val subject = TrackFinalizer(
        metadataEmbedder = metadataEmbedder,
        fileOrganizer = fileOrganizer,
        audioExtractor = audioExtractor,
        albumArtCache = albumArtCache,
    )

    @Test fun `finalizeFile resolves art and passes it to the embedder`() = runTest {
        val art = File.createTempFile("art", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        }
        var passedArt: File? = SENTINEL
        coEvery { albumArtCache.resolveArt(any()) } returns art
        coEvery { metadataEmbedder.embedMetadata(any(), any(), any()) } answers {
            // Capture the third arg out of band — MockK matchers on
            // nullable types are awkward; recording the call's args is
            // less brittle.
            passedArt = thirdArg()
            firstArg()
        }
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any())
        } returns FileOrganizer.CommittedTrack("/library/Drake/x.flac", 100)
        coEvery { audioExtractor.extract(any()) } returns null

        val result = subject.finalizeFile(
            sourceFile = File.createTempFile("src", ".flac"),
            track = stubTrack(),
            format = AudioFormat(codec = "flac", bitrateKbps = 0, bitsPerSample = 16, sampleRateHz = 44100),
        )

        coVerify { metadataEmbedder.embedMetadata(any(), any(), any()) }
        assertSame(art, passedArt)
        assertTrue(result is TrackFinalizer.FinalizeResult.Success)
        art.delete()
    }

    @Test fun `finalizeFile proceeds when art resolve returns null`() = runTest {
        var passedArt: File? = SENTINEL
        var embedderCalled = false
        coEvery { albumArtCache.resolveArt(any()) } returns null
        coEvery { metadataEmbedder.embedMetadata(any(), any(), any()) } answers {
            embedderCalled = true
            passedArt = thirdArg()
            firstArg()
        }
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any())
        } returns FileOrganizer.CommittedTrack("/library/Drake/x.flac", 100)
        coEvery { audioExtractor.extract(any()) } returns null

        val result = subject.finalizeFile(
            sourceFile = File.createTempFile("src", ".flac"),
            track = stubTrack(),
            format = AudioFormat(codec = "flac", bitrateKbps = 0, bitsPerSample = 16, sampleRateHz = 44100),
        )

        assertTrue("embedder must be invoked even when art is null", embedderCalled)
        assertNull(passedArt)
        assertTrue(result is TrackFinalizer.FinalizeResult.Success)
    }

    @Test fun `finalizeFile propagates cancellation from commit`() = runTest {
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any())
        } throws CancellationException("cancelled")

        var cancellationPropagated = false
        try {
            subject.finalizeFile(
                sourceFile = File.createTempFile("src", ".opus"),
                track = stubTrack(),
                format = AudioFormat(codec = "opus", bitrateKbps = 128),
                embedMetadata = false,
            )
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue("cancellation must propagate", cancellationPropagated)
    }
    private fun stubTrack(): Track = Track(
        id = 1L,
        title = "Title",
        artist = "Drake",
        album = "Album",
        albumArtUrl = "https://i.scdn.co/image/abc",
    )

    private companion object {
        // Sentinel so we can distinguish "third arg captured as null" from
        // "test never updated the variable" — the test fails fast either way.
        private val SENTINEL: File = File("__never_captured__")
    }
}
