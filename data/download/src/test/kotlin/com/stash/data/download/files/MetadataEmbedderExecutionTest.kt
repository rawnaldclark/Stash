package com.stash.data.download.files

import android.content.Context
import android.content.pm.ApplicationInfo
import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class MetadataEmbedderExecutionTest {

    private val root = Files.createTempDirectory("metadata-embedder-test").toFile()
    private val nativeDir = File(root, "native").apply { mkdirs() }
    private val noBackupDir = File(root, "no-backup").apply { mkdirs() }
    private val context: Context = mockk()
    private val applicationInfo = ApplicationInfo().apply {
        nativeLibraryDir = nativeDir.absolutePath
    }
    private val versionProvider = object : AppVersionProvider {
        override val versionName: String = "test"
        override val versionCode: Int = 1
    }
    private val track = Track(id = 1, title = "Title", artist = "Artist")

    init {
        every { context.applicationInfo } returns applicationInfo
        every { context.noBackupFilesDir } returns noBackupDir
    }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `missing ffmpeg binary fails and preserves original`() = runTest {
        val audio = newAudio()

        assertFailurePreservesOriginal(audio)
    }

    @Test
    fun `non-zero ffmpeg exit fails, removes partial output, and preserves original`() = runTest {
        installFfmpeg(
            """#!/bin/sh
                |for arg do output="${'$'}arg"; done
                |printf partial > "${'$'}output"
                |exit 7
                |""".trimMargin(),
        )
        val audio = newAudio()

        assertFailurePreservesOriginal(audio)
        assertFalse(taggedOutput(audio).exists())
    }

    @Test
    fun `zero exit without output fails and preserves original`() = runTest {
        installFfmpeg("#!/bin/sh\nexit 0\n")
        val audio = newAudio()

        assertFailurePreservesOriginal(audio)
    }

    @Test
    fun `process start exception propagates and preserves original`() = runTest {
        File(nativeDir, "libffmpeg.so").mkdir()
        val audio = newAudio()

        assertFailurePreservesOriginal(audio)
    }

    @Test
    fun `failure to preserve original propagates and cleans replacement files`() = runTest {
        installSuccessfulFfmpeg()
        val audio = newAudio()
        val embedder = MetadataEmbedder(context, versionProvider).apply {
            renameFile = { source, target ->
                if (source == audio) false else source.renameTo(target)
            }
        }

        val failure = assertFailurePreservesOriginal(audio, embedder)

        assertTrue(failure is IOException)
        assertTrue(failure.message.orEmpty().contains("could not preserve original file"))
        assertReplacementFilesCleaned(audio)
    }

    @Test
    fun `failure to install tagged output restores original and cleans replacement files`() = runTest {
        installSuccessfulFfmpeg()
        val audio = newAudio()
        val output = taggedOutput(audio)
        val embedder = MetadataEmbedder(context, versionProvider).apply {
            renameFile = { source, target ->
                if (source == output) false else source.renameTo(target)
            }
        }

        val failure = assertFailurePreservesOriginal(audio, embedder)

        assertTrue(failure is IOException)
        assertTrue(failure.message.orEmpty().contains("could not install tagged file"))
        assertReplacementFilesCleaned(audio)
    }

    @Test
    fun `successful ffmpeg output replaces original and returns original path`() = runTest {
        installSuccessfulFfmpeg()
        val audio = newAudio()

        val result = MetadataEmbedder(context, versionProvider).embedMetadata(audio, track)

        assertSame(audio, result)
        assertEquals("tagged", audio.readText())
        assertFalse(taggedOutput(audio).exists())
        assertFalse(root.listFiles().orEmpty().any { "_untagged_" in it.name })
    }

    private suspend fun assertFailurePreservesOriginal(
        audio: File,
        embedder: MetadataEmbedder = MetadataEmbedder(context, versionProvider),
    ): Throwable {
        val original = audio.readBytes()

        val result = runCatching {
            embedder.embedMetadata(audio, track)
        }

        assertTrue("embedMetadata must propagate failure", result.isFailure)
        assertTrue("original file must remain at its path", audio.exists())
        assertArrayEquals(original, audio.readBytes())
        return result.exceptionOrNull()!!
    }

    private fun assertReplacementFilesCleaned(audio: File) {
        assertFalse("tagged output must be removed", taggedOutput(audio).exists())
        assertFalse(
            "backup must be removed",
            root.listFiles().orEmpty().any { "_untagged_" in it.name },
        )
    }

    private fun newAudio(): File = File(root, "track.opus").apply {
        writeText("original")
    }

    private fun taggedOutput(audio: File): File =
        File(audio.parent, "${audio.nameWithoutExtension}_tagged.${audio.extension}")

    private fun installSuccessfulFfmpeg() {
        installFfmpeg(
            """#!/bin/sh
                |for arg do output="${'$'}arg"; done
                |printf tagged > "${'$'}output"
                |exit 0
                |""".trimMargin(),
        )
    }

    private fun installFfmpeg(script: String) {
        val binary = File(nativeDir, "libffmpeg.so").apply { writeText(script) }
        assertTrue("test ffmpeg must be executable", binary.setExecutable(true))
    }
}
