package com.stash.data.download.files

/**
 * The audio types an imported file may be saved as, and how one is chosen.
 *
 * No sender-supplied text becomes a file extension: the provider's MIME type
 * or display name counts only when it maps to one of [EXTENSIONS]; otherwise
 * the content's own container type, as the media retriever reads it, decides
 * ([fromContent]); a file none of them identifies is refused. Whatever is
 * returned is always one of [EXTENSIONS].
 */
internal object ImportedAudioType {

    /**
     * Audio containers the import pipeline saves, all of which the player
     * plays: MP3, MP4/M4A and ADTS AAC, FLAC, Ogg (Vorbis or Opus), WAV,
     * WebM and Matroska audio, and AMR voice recordings (narrow- and
     * wide-band).
     */
    val EXTENSIONS: Set<String> = setOf(
        "mp3", "m4a", "mp4", "aac", "flac", "ogg", "oga", "opus", "wav", "webm", "mka", "amr", "awb",
    )

    /** Audio MIME types (as providers and the media retriever report them) to their extension. */
    private val BY_MIME: Map<String, String> = mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/mpeg3" to "mp3",
        "audio/x-mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/m4a" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/mp4a-latm" to "m4a",
        "audio/aac" to "aac",
        "audio/aac-adts" to "aac",
        "audio/x-aac" to "aac",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/ogg" to "ogg",
        "application/ogg" to "ogg",
        "audio/vorbis" to "ogg",
        "audio/opus" to "opus",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/vnd.wave" to "wav",
        "audio/webm" to "webm",
        "audio/x-matroska" to "mka",
        "audio/amr" to "amr",
        "audio/amr-wb" to "awb",
    )

    /**
     * Container types the media retriever reports for Matroska and WebM
     * files whatever their tracks hold, to the extension they're saved
     * under when the content has no video. Only [fromContent] reads these:
     * a provider's `video/` type never names a file.
     */
    private val AUDIO_ONLY_CONTAINERS: Map<String, String> = mapOf(
        "video/x-matroska" to "mka",
        "video/webm" to "webm",
    )

    /** The extension for [mime] (parameters ignored), or null when it isn't one of [EXTENSIONS]' types. */
    fun forMime(mime: String?): String? = normalize(mime)?.let(BY_MIME::get)

    /** [displayName]'s extension in lower case when it is one of [EXTENSIONS], else null. */
    fun forDisplayName(displayName: String?): String? {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase() ?: return null
        return EXTENSIONS.firstOrNull { it == ext }
    }

    /** What the provider's claims allow, MIME type first; null when neither is one of [EXTENSIONS]. */
    fun fromClaims(mime: String?, displayName: String?): String? =
        forMime(mime) ?: forDisplayName(displayName)

    /**
     * What the content itself is, from the media retriever's container type
     * ([containerMime]) and its "has video" answer ([hasVideo], `"yes"` or
     * null): an audio type as in [forMime], or Matroska / WebM content
     * without video. Null for anything else.
     *
     * The retriever reports the file's container type, not a track's codec:
     * `audio/amr` or `audio/amr-wb` for raw AMR, `audio/mp4` for MP4 and 3GP
     * audio.
     */
    fun fromContent(containerMime: String?, hasVideo: String?): String? =
        forMime(containerMime)
            ?: normalize(containerMime)?.takeIf { hasVideo != "yes" }?.let(AUDIO_ONLY_CONTAINERS::get)

    private fun normalize(mime: String?): String? = mime?.substringBefore(';')?.trim()?.lowercase()
}
