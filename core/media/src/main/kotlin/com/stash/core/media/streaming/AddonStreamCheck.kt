package com.stash.core.media.streaming

import android.util.Log
import androidx.media3.common.MediaItem
import com.stash.core.media.service.StashPlaybackService
import com.stash.data.download.isLosslessDurationDegraded
import com.stash.data.download.lossless.LosslessSourceHealthGate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The player's check of a stream an add-on answered, made before a sound of it is heard.
 *
 * An add-on's word for its own stream is only that. A real add-on (LastWave-style, 2026-11-13)
 * answered every `/stream` with "flac, Lossless, 16/44.1" and pointed at prank files: a 120 s AAC,
 * a 67 s video, a 32 s MP3. Downloads already refuse such a file (the downloader's format
 * check, the duration backstop); playback played it. Now each stream an add-on answered (`addon:<id>`
 * origin, its lossy pass included) is judged on what the player's extractor read from its bytes,
 * when the media period is prepared and before the player can select a track or feed a renderer
 * ([AddonCheckedMediaSource]). It is refused when:
 *  - [Why.VIDEO]: it has a motion-video track (a cover picture stored as a still-image track is not one);
 *  - [Why.LOSSY]: the add-on called it lossless (codec or tier) and none of its audio is: every audio
 *    format it lists is a lossy codec ([LOSSY_MIME_TYPES]). A manifest that offers FLAC or ALAC with an
 *    AAC or Atmos fallback beside it is honest: the player picks the lossless one (review-888 M2).
 *    FLAC, ALAC and PCM count as lossless whichever one the add-on named;
 *  - [Why.LENGTH]: it is far shorter than the song, by the downloads' own rule
 *    ([isLosslessDurationDegraded]), so a stream and a download agree. The song's length is the
 *    best one known: the queue item's (a Search tap's falls back to the library row's), else the
 *    one its source knew ([StreamUrl.songMs]: what the resolve asked with, else the add-on's own
 *    matched item's). Skipped only when none is known.
 *
 * A refusal sets the add-on aside, in the content-health gate the downloads use
 * ([LosslessSourceHealthGate.recordWrongFile], its cooldown): the stream chain ([AddonStreamRungs]),
 * the URL cache ([StreamUrlCache]) and the download chain all skip it until then, and Settings ›
 * Add-ons says so on its row. The player then resolves the song again from the next source in the
 * play order ([takeRefusal], read by `PlayerRepositoryImpl`), and every other queued song whose link
 * came from it ([refusals]). The log line names the add-on's id, the song's id and the rule, never
 * the media host or URL (the add-on logs' privacy rule).
 */
@Singleton
class AddonStreamCheck internal constructor(
    private val health: LosslessSourceHealthGate,
    /** Monotonic milliseconds, for how fresh a refusal is ([takeRefusal]). */
    private val elapsedMs: () -> Long,
) {
    @Inject constructor(health: LosslessSourceHealthGate) : this(health, { System.nanoTime() / 1_000_000L })

    /** Why a stream was refused: the word the log line says. */
    enum class Why(val word: String) {
        /** Called lossless, decodes as a lossy codec. */
        LOSSY("lossy"),

        /** Has a video track. */
        VIDEO("video"),

        /** Far shorter than the song ([isLosslessDurationDegraded]). */
        LENGTH("length"),
    }

    /**
     * What an add-on said about one stream, and of which song.
     *
     * @property origin `addon:<id>`: the add-on, as the stream's origin and its download source's id.
     * @property mediaId The song's queue item ([MediaItem.mediaId]): the crossfade spare is matched by it.
     * @property statedLossless The add-on called it lossless: its codec or tier said FLAC, ALAC or PCM.
     * @property songMs The song's length, the best known ([claimOf]); 0 when none is.
     */
    data class Claim(val origin: String, val trackId: Long, val mediaId: String, val statedLossless: Boolean, val songMs: Long) {
        val addonId: String get() = origin.removePrefix(AddonStreamRung.ORIGIN_PREFIX)
    }

    /** One refusal, for whoever has to act on it: the queue ([refusals]) and the crossfade spare. */
    data class Refused(val origin: String, val trackId: Long, val mediaId: String, val why: Why)

    private val events = MutableSharedFlow<Refused>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Every refusal as it happens. Emitted from a player's playback thread; collect where the players
     * are touched. No replay: a refusal emitted before a collector subscribes is not seen by it. For
     * the song playing that loses nothing, since its error carries the refusal ([AddonStreamRefusedException])
     * and [takeRefusal] holds the record.
     */
    val refusals: SharedFlow<Refused> = events.asSharedFlow()

    /** Songs whose stream was refused, by track id: when. Read once each by [takeRefusal]. */
    private val refusedSongs = ConcurrentHashMap<Long, Long>()

    /**
     * Which add-on's link each song's placeholder last opened, by track id (`addon:<id>`). A slot
     * resolved as it played keeps its `stash-resolve://` URI and no origin of its own, while the
     * player may have read it ahead from the add-on's host: this is how such a slot is found when
     * the add-on leaves or is set aside ([openedFrom]). Bounded: the oldest songs drop off first.
     */
    private val openedLinks = object : LinkedHashMap<Long, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>): Boolean = size > MAX_OPENED
    }

    /** Where the refusal line goes; tests read it. */
    internal var log: (String) -> Unit = { Log.i(TAG, it) }

    /** Whether the add-on that answered [origin] is set aside right now (its stream was refused, or a download's file). */
    fun isSetAside(origin: String?): Boolean =
        origin != null && AddonPlayback.isAddonOrigin(origin) && health.isDegraded(origin)

    /**
     * A player opened a stream answered by [origin] for song [trackId] (a placeholder's data source,
     * or a manifest picked for it). An add-on's is noted; any other replaces what was noted. Any thread.
     */
    fun opened(trackId: Long, origin: String?) {
        synchronized(openedLinks) {
            if (origin != null && AddonPlayback.isAddonOrigin(origin)) openedLinks[trackId] = origin else openedLinks.remove(trackId)
        }
    }

    /** The add-on whose link song [trackId] last opened ([opened]); null when none, or another source's. */
    fun openedFrom(trackId: Long): String? = synchronized(openedLinks) { openedLinks[trackId] }

    /** Song [trackId] is resolved again from the start: what it opened before no longer stands. */
    fun forget(trackId: Long) {
        synchronized(openedLinks) { openedLinks.remove(trackId) }
    }

    /**
     * Judges the stream [claim] describes by what the extractor read: [sampleMimeTypes] (every
     * track's, media3's `Format.sampleMimeType`) and [streamMs] (null: unknown). On a refusal the
     * add-on is set aside and everyone is told, before this returns: the player's error for the
     * song, which comes after, finds the refusal recorded ([takeRefusal]). Any thread.
     */
    fun judge(claim: Claim, sampleMimeTypes: List<String?>, streamMs: Long?): Why? {
        val why = verdict(claim.statedLossless, sampleMimeTypes, streamMs, claim.songMs) ?: return null
        health.recordWrongFile(claim.origin)
        val now = elapsedMs()
        // A refusal no error ever read (its slot was put back first) stands for nothing after a while.
        refusedSongs.entries.removeIf { now - it.value > REFUSAL_FRESH_MS }
        refusedSongs[claim.trackId] = now
        log("${claim.addonId} refused for ${claim.trackId}: ${why.word}")
        events.tryEmit(Refused(claim.origin, claim.trackId, claim.mediaId, why))
        return why
    }

    /**
     * True, once, when the stream of song [trackId] was refused a moment ago ([REFUSAL_FRESH_MS]):
     * the player's error for it is that refusal, and the song is resolved again from the next source
     * instead of counting as a failed stream. False for any other error.
     */
    fun takeRefusal(trackId: Long): Boolean {
        val at = refusedSongs.remove(trackId) ?: return false
        return elapsedMs() - at <= REFUSAL_FRESH_MS
    }

    companion object {
        private const val TAG = "AddonStreamCheck"

        /** How long a refusal stands for the error it causes: the error follows within milliseconds. */
        const val REFUSAL_FRESH_MS = 60_000L

        /** How many songs' opened add-on links [openedFrom] remembers: far more than a player reads ahead. */
        private const val MAX_OPENED = 512

        /**
         * Lossy codecs, as media3 names a track's `sampleMimeType`: AAC (and HE-AAC), MP3 and MPEG
         * layers 1 and 2, Opus, Vorbis, Dolby AC-3, E-AC-3 and AC-4, AMR, DTS core and DTS Express,
         * MPEG-H. A codec not named here is never refused for being lossy.
         */
        private val LOSSY_MIME_TYPES = setOf(
            "audio/mp4a-latm", "audio/aac",
            "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2",
            "audio/opus", "audio/vorbis",
            "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/ac4",
            "audio/3gpp", "audio/amr", "audio/amr-wb",
            "audio/vnd.dts", "audio/vnd.dts.hd;profile=lbr",
            "audio/mha1", "audio/mhm1",
        )

        /**
         * Lossless audio, as media3 names a track's `sampleMimeType`: FLAC, ALAC, PCM (raw and WAV),
         * Dolby TrueHD and DTS-HD (Master Audio). One of these in a stream is the lossless audio
         * its add-on promised, whatever else the stream also offers.
         */
        private val LOSSLESS_MIME_TYPES = setOf(
            "audio/flac", "audio/alac", "audio/raw", "audio/wav", "audio/true-hd", "audio/vnd.dts.hd",
        )

        /**
         * Still pictures media3 may report as a "video" track (a cover stored as a track of its own):
         * not a video. A file that is a video carries a motion codec (H.264, HEVC, VP9, AV1...).
         */
        private val STILL_PICTURE_MIME_TYPES = setOf("video/mjpeg")

        /**
         * The player's codec words ([AddonStreamRung.codecName]) that call a stream lossless:
         * "wav" is the word for PCM (`Codec.PCM`).
         */
        private val LOSSLESS_WORDS = setOf("flac", "alac", "wav")

        /** The rules, as one pure decision. Null: the stream may play. */
        fun verdict(statedLossless: Boolean, sampleMimeTypes: List<String?>, streamMs: Long?, songMs: Long): Why? {
            val mimes = sampleMimeTypes.mapNotNull { it?.lowercase() }
            if (mimes.any { it.startsWith("video/") && it !in STILL_PICTURE_MIME_TYPES }) return Why.VIDEO
            // Judged as a set: lossy only when no audio format of it is lossless (review-888 M2).
            if (statedLossless && mimes.any { it in LOSSY_MIME_TYPES } && mimes.none { it in LOSSLESS_MIME_TYPES }) return Why.LOSSY
            if (isLosslessDurationDegraded(streamMs, songMs)) return Why.LENGTH
            return null
        }

        /** Whether the player's codec word [codec] (a [StreamUrl.codec]) calls the stream lossless. */
        internal fun statesLossless(codec: String?): Boolean = codec?.lowercase() in LOSSLESS_WORDS

        /**
         * The claim of a queue item already resolved to an add-on's stream (a tapped song, a next song
         * the look-ahead swapped in): its origin and codec extras say who answered and what they
         * called it. Null for any other item.
         */
        fun claimOf(item: MediaItem): Claim? {
            val extras = item.mediaMetadata.extras ?: return null
            val origin = extras.getString(StashPlaybackService.EXTRA_STREAM_ORIGIN)?.takeIf { AddonPlayback.isAddonOrigin(it) } ?: return null
            return Claim(
                origin = origin,
                trackId = trackIdOf(item),
                mediaId = item.mediaId,
                statedLossless = statesLossless(extras.getString(StashPlaybackService.EXTRA_STREAM_CODEC)),
                songMs = songMsOf(item).takeIf { it > 0L }
                    ?: extras.getLong(StashPlaybackService.EXTRA_STREAM_SONG_MS, 0L).coerceAtLeast(0L),
            )
        }

        /**
         * The claim of [stream], opened for the placeholder [item] (a `stash-resolve://` song resolved
         * as it played). Null when the stream is not an add-on's.
         */
        fun claimOf(stream: StreamUrl, item: MediaItem): Claim? {
            val origin = stream.origin?.takeIf { AddonPlayback.isAddonOrigin(it) } ?: return null
            val songMs = songMsOf(item).takeIf { it > 0L } ?: stream.songMs?.takeIf { it > 0L } ?: 0L
            return Claim(origin, trackIdOf(item), item.mediaId, statesLossless(stream.codec), songMs)
        }

        /** The song's track id: its extra, else a placeholder's own (`stash-resolve://track/<id>`), else its media id. */
        private fun trackIdOf(item: MediaItem): Long =
            item.mediaMetadata.extras?.getLong(StashPlaybackService.EXTRA_TRACK_ID, 0L)?.takeIf { it != 0L }
                ?: item.localConfiguration?.uri?.takeIf { it.scheme == STASH_RESOLVE_SCHEME }?.lastPathSegment?.toLongOrNull()
                ?: item.mediaId.toLongOrNull()
                ?: 0L

        /** The song's length as the library knows it: its extra, else a placeholder's `d`; 0 when unknown. */
        private fun songMsOf(item: MediaItem): Long =
            item.mediaMetadata.extras?.getLong(StashPlaybackService.EXTRA_TRACK_DURATION_MS, 0L)?.takeIf { it > 0L }
                ?: item.localConfiguration?.uri?.takeIf { it.scheme == STASH_RESOLVE_SCHEME }?.getQueryParameter("d")?.toLongOrNull()
                ?: 0L
    }
}
