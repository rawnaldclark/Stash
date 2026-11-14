package com.stash.data.download

import com.stash.core.data.audio.AudioDurationExtractor

/**
 * A finished download refused as a preview or clip of the song, and the lengths it was judged by
 * (for the log line).
 *
 * @property playsMs how long its audio runs: measured from its frames, else its header's word.
 * @property headerMs the length its header states; null when it states none.
 * @property expectedMs the song's length the download was checked against; 0 when unknown.
 */
data class Clip(val playsMs: Long, val headerMs: Long?, val expectedMs: Long) {
    override fun toString(): String =
        "plays $playsMs ms, its header says ${headerMs?.let { "$it ms" } ?: "nothing"}, the song is ${expectedMs.takeIf { it > 0 }?.let { "$it ms" } ?: "unknown"}"
}

/**
 * Whether a finished file is a clip, not the song: the downloads' and the add-on stream check's one
 * rule for "too short" ([isLosslessDurationDegraded]: 35 s or less AND under half), applied to what
 * its audio really runs, against two lengths:
 *  - the song's ([expectedMs]), as the duration backstop always did;
 *  - the length the file's own header states ([headerMs]). A preview cut from the whole song can
 *    keep the song's header: Tidal's 30 s PREVIEW of "Linger", remuxed to FLAC, said 4:34.77 and
 *    stopped at 0:29.91 (Pixel 5, 2026-11-13), and the backstop, which read only the header, kept
 *    it. This catches such a file even when the song's length is unknown.
 *
 * [framesMs] is the measured length ([AudioDurationExtractor.extractFramesMs]); 0 or null = not
 * measured, and then the header's word is all there is (the backstop as it was). Nothing known, or
 * nothing short: null. Missing data never refuses a file.
 */
fun clipOf(headerMs: Long?, framesMs: Long?, expectedMs: Long): Clip? {
    val frames = framesMs?.takeIf { it > 0L }
    val header = headerMs?.takeIf { it > 0L }
    val plays = frames ?: header ?: return null
    val short = isLosslessDurationDegraded(plays, expectedMs) || isLosslessDurationDegraded(frames, header ?: 0L)
    return if (short) Clip(plays, header, expectedMs) else null
}

/**
 * [clipOf] for the file at [path]: its header's length ([headerMs] when the caller has already read
 * it, else [AudioDurationExtractor.extractMs]) and its frames' ([AudioDurationExtractor.extractFramesMs]).
 * Never throws: a probe that fails is a length not known. Reads the file; call it off the main thread.
 */
fun AudioDurationExtractor.clipAt(path: String, expectedMs: Long, headerMs: Long? = null): Clip? {
    val header = headerMs ?: runCatching { extractMs(path) }.getOrNull()
    val frames = runCatching { extractFramesMs(path) }.getOrNull()
    return clipOf(header, frames, expectedMs)
}
