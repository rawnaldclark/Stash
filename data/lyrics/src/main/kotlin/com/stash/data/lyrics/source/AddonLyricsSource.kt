package com.stash.data.lyrics.source

import android.util.Log
import com.stash.addons.contract.Descriptor
import com.stash.addons.contract.LyricLine
import com.stash.addons.contract.Lyrics
import com.stash.addons.contract.Resource
import com.stash.core.addons.AddonFailure
import com.stash.core.addons.AddonRegistry
import com.stash.core.addons.AddonResult
import com.stash.core.addons.HttpAddonClient
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The add-ons that offer lyrics (Stash Add-ons v1, SPEC.md 4.6), as one place in the lyrics chain
 * (`LyricsModule`: after KuGou, before InnerTube's plain text). Asks each add-on that lists
 * `lyrics`, in the registry's order, and returns the first answer.
 *
 * As the other sources do: null is a definitive miss, and a throw is "couldn't ask", so the
 * repository never files a song as "no lyrics" over an add-on that was down. Only an outage
 * ([AddonFailure.isOutage]: a timeout, no connection, an invalid answer, `unavailable`) is
 * "couldn't ask". A paused add-on, or one that refused (rate limit, quota, unauthorized), counts as
 * absent, the way an add-on without lyrics does: otherwise every song no source has would be
 * walked again on every open for as long as the pause lasts.
 *
 * Phase 1: the registry is empty, so this returns null at once and asks nothing.
 */
@Singleton
class AddonLyricsSource @Inject constructor(
    private val registry: AddonRegistry,
    private val client: HttpAddonClient,
) : LyricsSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Add-ons"

    override suspend fun resolve(query: LyricsQuery): LyricsResult? {
        val addons = registry.offering(Resource.LYRICS)
        if (addons.isEmpty()) return null
        val descriptor = Descriptor(
            title = query.title,
            artist = query.artist,
            album = query.album,
            durationMs = query.durationMs?.takeIf { it > 0 },
            isrc = query.isrc,
        )
        var couldNotAsk: String? = null
        for (addon in addons) {
            when (val r = client.lyrics(addon, descriptor)) {
                is AddonResult.Ok -> return toResult(addon.id, r.value)
                is AddonResult.Failed -> if (r.failure.isOutage) {
                    Log.d(TAG, "${addon.id}: no answer (${r.failure.javaClass.simpleName})")
                    if (couldNotAsk == null) couldNotAsk = addon.id
                }
            }
        }
        couldNotAsk?.let { throw IOException("lyrics add-on $it did not answer") }
        return null
    }

    companion object {
        private const val TAG = "AddonLyricsSource"

        /** This source's [LyricsSource.id]; a stored answer names its add-on as `addon:<id>`. */
        const val SOURCE_ID = "addons"

        internal fun toResult(addonId: String, lyrics: Lyrics): LyricsResult {
            val synced = lyrics.synced?.takeIf { it.isNotEmpty() }
            return LyricsResult(
                sourceId = "addon:$addonId",
                plainText = lyrics.plain?.takeIf { it.isNotBlank() } ?: synced?.joinToString("\n") { it.text },
                syncedLrc = synced?.joinToString("\n") { lrcLine(it) },
                instrumental = false,
                language = null,
                sourceLyricsId = null,
            )
        }

        /** `[mm:ss.cc]text`, the LRC the other sources store. */
        internal fun lrcLine(line: LyricLine): String {
            val ms = line.startMs.coerceAtLeast(0)
            val min = ms / 60_000
            val sec = (ms / 1000) % 60
            val cs = (ms % 1000) / 10
            return String.format(Locale.ROOT, "[%02d:%02d.%02d]%s", min, sec, cs, line.text)
        }
    }
}
