package com.stash.core.media.automix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.media.streaming.CloudCopyFinder
import com.stash.core.media.streaming.STASH_RESOLVE_SCHEME
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.media.streaming.placeholderEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

enum class AutoMixWindow { HEAD, TAIL }

@Singleton
class AutoMixTrackAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streamUrlCache: StreamUrlCache,
    private val streamResolver: StreamSourceRegistry,
    private val trackDao: TrackDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val completed = ConcurrentHashMap<String, AutoMixTrackAnalysis>()
    private val inFlight = ConcurrentHashMap<String, Deferred<AutoMixTrackAnalysis?>>()

    suspend fun analyze(item: MediaItem, window: AutoMixWindow, knownDurationMs: Long = 0L): AutoMixTrackAnalysis? {
        val uriKey = item.localConfiguration?.uri?.toString().orEmpty()
        val key = "${item.mediaId}|$window|$knownDurationMs|${uriKey.hashCode()}"
        completed[key]?.let { return it }
        inFlight[key]?.let { return it.await() }
        val deferred = scope.async { analyzeInternal(item, window, knownDurationMs) }
        val raceWinner = inFlight.putIfAbsent(key, deferred)
        val chosen = raceWinner ?: deferred
        if (raceWinner != null) deferred.cancel()
        return try {
            chosen.await()?.also { analysis ->
                if (completed.size > MAX_CACHED_ANALYSES) completed.clear()
                completed[key] = analysis
            }
        } finally { inFlight.remove(key, chosen) }
    }

    private suspend fun analyzeInternal(item: MediaItem, window: AutoMixWindow, knownDurationMs: Long): AutoMixTrackAnalysis? {
        val uri = resolveAnalysisUri(item) ?: return null
        val decoded = withContext(Dispatchers.IO) { decodeWindow(uri, window, knownDurationMs) } ?: return null
        val beatWindow = AutoMixBeatAnalyzer.analyze(decoded.samples, decoded.sampleRate, decoded.windowStartMs) ?: return null
        val durationMs = decoded.durationMs.takeIf { it > 0L } ?: knownDurationMs
        if (durationMs <= 0L) return null
        return AutoMixTrackAnalysis(
            mediaId = item.mediaId,
            durationMs = durationMs,
            // Use the phase fitted at the edge this analysis will be cued
            // from: HEAD analyses cue the incoming entry, TAIL analyses cue
            // the outgoing exit/loop. The global grid remains the fallback.
            beatGrid = when (window) {
                AutoMixWindow.HEAD -> beatWindow.entryBeatGrid ?: beatWindow.beatGrid
                AutoMixWindow.TAIL -> beatWindow.exitBeatGrid ?: beatWindow.beatGrid
            },
            effectiveStartMs = beatWindow.effectiveStartMs.coerceIn(0L, durationMs),
            effectiveEndMs = beatWindow.effectiveEndMs.coerceIn(0L, durationMs),
            firstStrongDownbeatMs = beatWindow.firstStrongDownbeatMs?.coerceIn(0L, durationMs),
            sections = beatWindow.sections.map { section ->
                section.copy(
                    startMs = section.startMs.coerceIn(0L, durationMs),
                    endMs = section.endMs.coerceIn(0L, durationMs),
                )
            }.filter { it.endMs > it.startMs },
            preferredEntryDownbeatMs = beatWindow.preferredEntryDownbeatMs?.coerceIn(0L, durationMs),
            preferredExitDownbeatMs = beatWindow.preferredExitDownbeatMs?.coerceIn(0L, durationMs),
            musicalKey = beatWindow.musicalKey,
        )
    }

    private suspend fun resolveAnalysisUri(item: MediaItem): Uri? {
        val uri = item.localConfiguration?.uri ?: return null
        return when (uri.scheme?.lowercase()) {
            "file", "content", "http", "https", "android.resource" -> uri
            STASH_RESOLVE_SCHEME -> resolvePlaceholderUri(item, uri)
            else -> null
        }
    }

    private suspend fun resolvePlaceholderUri(item: MediaItem, uri: Uri): Uri? {
        val trackId = uri.lastPathSegment?.toLongOrNull()
            ?: item.mediaMetadata.extras?.getLong(StashPlaybackService.EXTRA_TRACK_ID, -1L)?.takeIf { it > 0L }
            ?: item.mediaId.toLongOrNull() ?: return null
        streamUrlCache.get(trackId)?.let { cached -> playableUri(cached)?.let { return it } }
        val entity = placeholderEntity(trackDao, trackId, uri) ?: return null
        entity.filePath?.takeIf { entity.isDownloaded && it.isNotBlank() }?.let { path ->
            return if (path.startsWith("/")) Uri.parse("file://$path") else Uri.parse(path)
        }
        val resolved = streamResolver.resolve(entity, speculative = true) ?: return null
        val playable = playableUri(resolved) ?: return null
        streamUrlCache.put(trackId, resolved)
        return playable
    }

    private fun playableUri(stream: StreamUrl): Uri? {
        if (stream.isDash || stream.origin == CloudCopyFinder.ORIGIN) return null
        val uri = runCatching { Uri.parse(stream.url) }.getOrNull() ?: return null
        return uri.takeIf { it.scheme?.lowercase() in setOf("file", "content", "http", "https", "android.resource") }
    }

    private data class DecodedWindow(val samples: FloatArray, val sampleRate: Int, val windowStartMs: Long, val durationMs: Long)

    private fun decodeWindow(uri: Uri, window: AutoMixWindow, knownDurationMs: Long): DecodedWindow? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { audioTrack = i; format = candidate; break }
            }
            if (audioTrack < 0 || format == null) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val inputSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
            if (inputSampleRate <= 0) return null
            val extractorDurationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000L else 0L
            val durationMs = maxOf(extractorDurationMs, knownDurationMs)
            val windowStartMs = when (window) {
                AutoMixWindow.HEAD -> 0L
                AutoMixWindow.TAIL -> {
                    if (durationMs <= 0L) return null
                    (durationMs - WINDOW_MS).coerceAtLeast(0L)
                }
            }
            extractor.selectTrack(audioTrack)
            if (windowStartMs > 0L) extractor.seekTo(windowStartMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val maxSamples = ((inputSampleRate.toLong() * WINDOW_MS) / 1_000L).toInt().coerceIn(inputSampleRate * 4, MAX_SAMPLES)
            val samples = FloatArray(maxSamples)
            var sampleCount = 0
            var outputFormat = codec.outputFormat
            var firstPresentationMs: Long? = null
            var inputEos = false
            var outputEos = false
            val bufferInfo = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + DECODE_DEADLINE_MS
            while (!outputEos && sampleCount < maxSamples && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            if (firstPresentationMs == null) firstPresentationMs = bufferInfo.presentationTimeUs / 1_000L
                            sampleCount = appendPcm(outputBuffer, bufferInfo, outputFormat, samples, sampleCount)
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (sampleCount < inputSampleRate * 3) return null
            val outputSampleRate = if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else inputSampleRate
            return DecodedWindow(samples.copyOf(sampleCount), outputSampleRate, firstPresentationMs ?: windowStartMs, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "analysis decode skipped for $uri: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { extractor.release() }
        }
    }

    private fun appendPcm(buffer: ByteBuffer, info: MediaCodec.BufferInfo, format: MediaFormat, destination: FloatArray, offset: Int): Int {
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1) else 1
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> return offset
        }
        val frameBytes = bytesPerSample * channels
        if (frameBytes <= 0 || info.size < frameBytes) return offset
        val pcm = buffer.duplicate().order(ByteOrder.nativeOrder())
        pcm.position(info.offset); pcm.limit((info.offset + info.size).coerceAtMost(pcm.capacity()))
        val framesToCopy = min(info.size / frameBytes, destination.size - offset)
        var next = offset
        repeat(framesToCopy) {
            var sum = 0f
            repeat(channels) {
                sum += when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> pcm.short.toFloat() / 32_768f
                    AudioFormat.ENCODING_PCM_FLOAT -> pcm.float
                    else -> ((pcm.get().toInt() and 0xff) - 128).toFloat() / 128f
                }
            }
            destination[next++] = (sum / channels).coerceIn(-1f, 1f)
        }
        return next
    }

    private companion object {
        const val TAG = "AutoMixAnalyzer"
        const val WINDOW_MS = 65_000L
        const val DECODE_DEADLINE_MS = 45_000L
        const val CODEC_TIMEOUT_US = 10_000L
        const val MAX_SAMPLES = 7_000_000
        const val MAX_CACHED_ANALYSES = 24
    }
}
