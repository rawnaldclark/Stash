package com.stash.data.cloud.upload

import com.google.common.truth.Truth.assertThat
import com.stash.data.cloud.net.RequestGate
import com.stash.data.cloud.provider.CloudApiException
import com.stash.data.cloud.provider.CloudUnavailableException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Drive's resumable protocol as [ResumableUpload] speaks it, against [FakeDriveStore]: pieces on
 * 256 KiB boundaries, 308 + Range, resuming from the committed offset, server errors and the
 * refusals that end an upload.
 */
class ResumableUploadTest {
    private val drive = FakeDriveStore()
    private val files = MemoryAudioFiles()

    /** Virtual time: a back-off advances the clock instead of waiting. */
    private var now = 0L
    private val gate = RequestGate(requestsPerSecond = 1000, clock = { now }, sleep = { now += it })
    private val chunk = ResumableUpload.QUANTUM
    private val upload = ResumableUpload(drive.client, gate, files, chunkSize = chunk, maxServerErrors = 3)

    private val song = ByteArray(chunk * 2 + 1000) { (it % 251).toByte() }

    init {
        files.files["/m/a.flac"] = song
    }

    private suspend fun session(): String {
        val api = DriveUploadApi(com.stash.data.cloud.net.CloudHttp(drive.client, gate), tokens())
        return api.startSession(1, "root", "a.flac", "audio/flac", song.size.toLong(), "key")
    }

    private fun tokens(): com.stash.data.cloud.auth.CloudTokenManager {
        val store = com.stash.data.cloud.provider.gdrive.MemoryTokenStore()
        kotlinx.coroutines.runBlocking {
            store.save(
                com.stash.data.cloud.auth.CloudTokenManager.keyFor(com.stash.core.model.CloudProvider.GOOGLE_DRIVE, 1),
                com.stash.core.auth.model.ServiceToken("tok1", "ron@example.com", Long.MAX_VALUE / 2),
            )
        }
        return com.stash.data.cloud.auth.CloudTokenManager(store, emptyMap())
    }

    @Test fun `pieces follow 256 KiB boundaries and the file arrives whole`() = runTest {
        val uri = session()
        val committed = mutableListOf<Long>()

        val file = upload.upload(uri, "/m/a.flac", song.size.toLong(), 0) { committed += it }

        assertThat(drive.pieces().map { it.header("Content-Range") }).containsExactly(
            "bytes 0-262143/${song.size}",
            "bytes 262144-524287/${song.size}",
            "bytes 524288-${song.size - 1}/${song.size}",
        ).inOrder()
        assertThat(committed).containsExactly(262144L, 524288L).inOrder()
        assertThat(drive.entries[file.id]!!.bytes).isEqualTo(song)
        assertThat(file.md5Checksum).isEqualTo(FakeDriveStore.md5(song))
        // The session address is its own credential: no bearer token on the pieces.
        assertThat(drive.pieces().all { it.header("Authorization") == null }).isTrue()
    }

    @Test fun `a piece Drive kept only part of is resent from what it committed`() = runTest {
        val uri = session()
        drive.keepPerPiece = 100_000

        val file = upload.upload(uri, "/m/a.flac", song.size.toLong(), 0) {}

        val starts = drive.pieces().map { it.header("Content-Range")!!.removePrefix("bytes ").substringBefore('-').toLong() }
        assertThat(starts.take(3)).containsExactly(0L, 100_000L, 200_000L).inOrder()
        assertThat(drive.entries[file.id]!!.bytes).isEqualTo(song)
    }

    @Test fun `status says how far a session got, and the upload carries on from there`() = runTest {
        val uri = session()
        val s = drive.sessions.values.single()
        System.arraycopy(song, 0, s.data, 0, chunk)
        s.committed = chunk.toLong()

        val status = upload.status(uri, song.size.toLong())
        assertThat(status).isEqualTo(ResumableUpload.Status.Partial(chunk.toLong()))
        val file = upload.upload(uri, "/m/a.flac", song.size.toLong(), chunk.toLong()) {}

        assertThat(drive.pieces().first().header("Content-Range")).isEqualTo("bytes 262144-524287/${song.size}")
        assertThat(drive.entries[file.id]!!.bytes).isEqualTo(song)
    }

    @Test fun `a finished session reports its file`() = runTest {
        val uri = session()
        val file = upload.upload(uri, "/m/a.flac", song.size.toLong(), 0) {}
        assertThat((upload.status(uri, song.size.toLong()) as ResumableUpload.Status.Complete).file.id).isEqualTo(file.id)
    }

    @Test fun `a server error backs off, asks where it stands and carries on`() = runTest {
        val uri = session()
        drive.putFaults += listOf(503, 500)

        val file = upload.upload(uri, "/m/a.flac", song.size.toLong(), 0) {}

        assertThat(drive.statusQueries()).hasSize(2)
        assertThat(drive.statusQueries().first().header("Content-Range")).isEqualTo("bytes */${song.size}")
        assertThat(now).isGreaterThan(0L) // backed off (virtual time)
        assertThat(drive.entries[file.id]!!.bytes).isEqualTo(song)
    }

    @Test fun `a 403 rate limit is a slow down, not a failure`() = runTest {
        val uri = session()
        drive.putFaults += 403
        val file = upload.upload(uri, "/m/a.flac", song.size.toLong(), 0) {}
        assertThat(drive.entries[file.id]!!.bytes).isEqualTo(song)
    }

    @Test fun `server errors in a row give up for now`() = runTest {
        val uri = session()
        drive.putFaults += listOf(503, 503, 503, 503)
        assertThrows(CloudUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking { upload.upload(uri, "/m/a.flac", song.size.toLong(), 0) {} }
        }
    }

    @Test fun `a session Drive forgot is expired`() = runTest {
        val uri = session()
        drive.sessions.values.single().expired = true
        assertThrows(UploadSessionExpiredException::class.java) {
            kotlinx.coroutines.runBlocking { upload.upload(uri, "/m/a.flac", song.size.toLong(), 0) {} }
        }
        assertThrows(UploadSessionExpiredException::class.java) {
            kotlinx.coroutines.runBlocking { upload.status(uri, song.size.toLong()) }
        }
    }

    @Test fun `a full Drive stops the upload`() = runTest {
        val uri = session()
        drive.storageFull = true
        assertThrows(DriveFullException::class.java) {
            kotlinx.coroutines.runBlocking { upload.upload(uri, "/m/a.flac", song.size.toLong(), 0) {} }
        }
    }

    @Test fun `any other refusal is an API error`() = runTest {
        val uri = session()
        drive.putFaults += 400
        val e = assertThrows(CloudApiException::class.java) {
            kotlinx.coroutines.runBlocking { upload.upload(uri, "/m/a.flac", song.size.toLong(), 0) {} }
        }
        assertThat(e.status).isEqualTo(400)
    }

    @Test fun `Drive keeping nothing, again and again, stops instead of looping`() = runTest {
        val uri = session()
        drive.keepPerPiece = 0
        assertThrows(CloudUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking { upload.upload(uri, "/m/a.flac", song.size.toLong(), 0) {} }
        }
        assertThat(drive.pieces().size).isAtMost(4)
    }

    @Test fun `a file that can't be read is the song's problem, not the connection's`() = runTest {
        val uri = session()
        val broken = object : LocalAudioFiles {
            override suspend fun size(path: String) = song.size.toLong()
            override fun open(path: String, offset: Long): java.io.InputStream = throw java.io.IOException("EIO")
        }
        val failing = ResumableUpload(drive.client, gate, broken, chunkSize = chunk)
        assertThrows(LocalFileException::class.java) {
            kotlinx.coroutines.runBlocking { failing.upload(uri, "/m/a.flac", song.size.toLong(), 0) {} }
        }
        assertThrows(LocalFileException::class.java) { kotlinx.coroutines.runBlocking { broken.md5("/m/a.flac") } }
    }

    @Test fun `Range headers become the next offset`() {
        assertThat(ResumableUpload.committedOffset("bytes=0-262143")).isEqualTo(262144L)
        assertThat(ResumableUpload.committedOffset(null)).isEqualTo(0L)
        assertThat(ResumableUpload.committedOffset("nonsense")).isEqualTo(0L)
    }

    @Test fun `pieces must be multiples of 256 KiB`() {
        assertThrows(IllegalArgumentException::class.java) { ResumableUpload(drive.client, gate, files, chunkSize = 1000) }
    }
}
