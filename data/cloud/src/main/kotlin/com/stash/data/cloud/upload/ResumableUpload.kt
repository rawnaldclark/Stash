package com.stash.data.cloud.upload

import com.stash.data.cloud.net.RequestGate
import com.stash.data.cloud.net.RequestPriority
import com.stash.data.cloud.net.await
import com.stash.data.cloud.provider.CloudApiException
import com.stash.data.cloud.provider.CloudUnavailableException
import com.stash.data.cloud.provider.gdrive.GoogleDriveJson
import com.stash.data.cloud.provider.gdrive.GoogleDriveJson.FileJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException

/**
 * The byte-moving half of a Drive resumable upload (developers.google.com/drive/api/guides/manage-uploads#resumable):
 * the file goes up in [chunkSize] pieces (a multiple of 256 KiB, as Drive requires), each a `PUT`
 * to the session address with `Content-Range: bytes start-end/total`.
 *
 *  - **308 Resume Incomplete**: Drive's `Range: bytes=0-N` says what it has committed; the next
 *    piece starts at N+1, whatever was sent (Drive may keep less than a whole piece).
 *  - **200/201**: done; the body is the new file.
 *  - **5xx / 429 / a 403 rate limit**: every Drive caller backs off through the [gate], Drive is
 *    asked where it stands (`Content-Range: bytes * /total`, empty body), and the upload goes on
 *    from there; after [maxServerErrors] in a row it gives up for now ([CloudUnavailableException]:
 *    the worker tries later, and the saved session picks up at the committed offset).
 *  - **404/410**: the session is gone (they last about a week): [UploadSessionExpiredException].
 *  - **403 storageQuotaExceeded**: [DriveFullException].
 *  - A dropped connection is thrown as is; the caller saved the session and the last committed
 *    offset, so the next run asks Drive ([status]) and carries on.
 *
 * The session address carries its own authorization: no bearer token is sent with these
 * requests. [client] has no logging interceptors, so the address never reaches a log.
 */
class ResumableUpload(
    private val client: OkHttpClient,
    private val gate: RequestGate,
    private val files: LocalAudioFiles,
    val chunkSize: Int = DEFAULT_CHUNK,
    private val maxServerErrors: Int = 5,
) {
    init {
        require(chunkSize > 0 && chunkSize % QUANTUM == 0) { "chunk size must be a multiple of 256 KiB" }
    }

    /** Where a session stands. */
    sealed interface Status {
        data class Partial(val committed: Long) : Status
        data class Complete(val file: FileJson) : Status
    }

    /** Asks Drive how much of [size] bytes it has for [session]. */
    suspend fun status(session: String, size: Long): Status {
        var errors = 0
        while (true) {
            gate.acquire(RequestPriority.BACKGROUND)
            val request = Request.Builder()
                .url(session)
                .header("Content-Range", "bytes */$size")
                .put(ByteArray(0).toRequestBody(null))
                .build()
            val answer = send(request)
            when (val outcome = classify(answer)) {
                is Outcome.Committed -> return Status.Partial(outcome.offset)
                is Outcome.Finished -> return Status.Complete(outcome.file)
                is Outcome.ServerTrouble -> errors = troubled(errors, answer)
            }
        }
    }

    /**
     * Sends [path]'s bytes from [from] to the end and returns the finished file. [onCommitted]
     * hears every offset Drive confirms (to persist and show progress).
     */
    suspend fun upload(
        session: String,
        path: String,
        size: Long,
        from: Long,
        onCommitted: suspend (Long) -> Unit,
    ): FileJson {
        require(size > 0) { "nothing to upload" }
        var offset = from.coerceIn(0, size)
        var errors = 0
        var stalled = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (offset >= size) {
                // Everything was sent but the final answer was lost: ask how it ended.
                when (val s = status(session, size)) {
                    is Status.Complete -> return s.file
                    is Status.Partial -> if (s.committed >= size) {
                        throw CloudApiException(0, "Google Drive kept every byte but made no file")
                    } else {
                        offset = s.committed
                        continue
                    }
                }
            }
            val length = minOf(chunkSize.toLong(), size - offset)
            gate.acquire(RequestPriority.BACKGROUND)
            val request = Request.Builder()
                .url(session)
                .header("Content-Range", "bytes $offset-${offset + length - 1}/$size")
                .put(FileRegionBody(files, path, offset, length))
                .build()
            val answer = send(request)
            when (val outcome = classify(answer)) {
                is Outcome.Finished -> return outcome.file
                is Outcome.Committed -> {
                    errors = 0
                    // Drive kept nothing of a whole piece, again and again: stop rather than loop.
                    stalled = if (outcome.offset > offset) 0 else stalled + 1
                    if (stalled >= maxServerErrors) throw CloudUnavailableException("Google Drive isn't keeping the upload")
                    offset = outcome.offset
                    onCommitted(offset)
                }
                is Outcome.ServerTrouble -> {
                    errors = troubled(errors, answer)
                    // Drive may have kept part of the piece: carry on from what it says it has.
                    when (val s = status(session, size)) {
                        is Status.Complete -> return s.file
                        is Status.Partial -> offset = s.committed
                    }
                }
            }
        }
    }

    private data class Answer(val code: Int, val body: String, val range: String?, val retryAfter: String?)

    private sealed interface Outcome {
        data class Committed(val offset: Long) : Outcome
        data class Finished(val file: FileJson) : Outcome
        data object ServerTrouble : Outcome
    }

    private suspend fun send(request: Request): Answer =
        client.newCall(request).await().use { r ->
            Answer(r.code, r.body?.string().orEmpty(), r.header("Range"), r.header("Retry-After"))
        }

    private fun classify(a: Answer): Outcome = when {
        a.code == 200 || a.code == 201 -> Outcome.Finished(GoogleDriveJson.file(a.body))
        a.code == 308 -> Outcome.Committed(committedOffset(a.range))
        a.code == 404 || a.code == 410 -> throw UploadSessionExpiredException(a.code)
        GoogleDriveJson.isStorageFull(a.code, a.body) -> throw DriveFullException()
        GoogleDriveJson.isSlowDown(a.code, a.body) -> Outcome.ServerTrouble
        else -> throw CloudApiException(a.code, "Google Drive upload answered ${a.code}")
    }

    /** Counts one more server error in a row, backs off every Drive caller, or gives up for now. */
    private fun troubled(errors: Int, a: Answer): Int {
        gate.backOff(RequestGate.parseRetryAfter(a.retryAfter) ?: backOffFor(errors))
        if (errors + 1 >= maxServerErrors) throw CloudUnavailableException("Google Drive isn't taking uploads right now")
        return errors + 1
    }

    /** 2 s, 4 s, 8 s... capped at a minute. */
    private fun backOffFor(errors: Int): Long = (2_000L shl errors.coerceAtMost(5)).coerceAtMost(60_000L)

    /** Streams `[offset, offset + length)` of a file; reopened on each write, so OkHttp may retry it. */
    private class FileRegionBody(
        private val files: LocalAudioFiles,
        private val path: String,
        private val offset: Long,
        private val length: Long,
    ) : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            // Reading the phone's file failing is the song's problem ([LocalFileException]);
            // writing to the socket failing is the connection's (a plain IOException).
            val input = try {
                files.open(path, offset)
            } catch (e: Exception) {
                throw LocalFileException("the song's file couldn't be opened", e)
            }
            input.use {
                val buffer = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = try {
                        it.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    } catch (e: IOException) {
                        throw LocalFileException("the song's file couldn't be read", e)
                    }
                    if (n < 0) throw LocalFileException("the file got shorter while uploading")
                    sink.write(buffer, 0, n)
                    left -= n
                }
            }
        }
    }

    companion object {
        /** Drive's unit for resumable pieces. */
        const val QUANTUM = 256 * 1024

        /** 8 MiB: few requests per song, little to resend after a drop. */
        const val DEFAULT_CHUNK = 32 * QUANTUM

        /**
         * The offset after Drive's `Range: bytes=0-N` (N+1); 0 when it has nothing yet (no header).
         * Anything unreadable also means "start over": resending bytes is safe, skipping them isn't.
         */
        fun committedOffset(range: String?): Long {
            val end = range?.trim()?.substringAfter("bytes=", "")?.substringAfter('-', "")?.toLongOrNull() ?: return 0
            return end + 1
        }
    }
}
