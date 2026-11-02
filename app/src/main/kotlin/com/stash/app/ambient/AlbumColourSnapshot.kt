package com.stash.app.ambient

import android.content.SharedPreferences
import com.stash.core.ui.ambient.AmbientPalette
import com.stash.core.ui.ambient.PaletteKind
import com.stash.core.ui.ambient.PaletteSeed

/**
 * The album colour the app last showed (its cover and palette) and the Album colours choice,
 * mirrored into the theme snapshot: the plain SharedPreferences `MainActivity` already reads
 * synchronously for the theme mode and AMOLED, so a cold start's first frame is in the right colour
 * instead of idle and then, a second in, the restored song's (review of #792). The DataStore stays
 * the source of truth for the choice; this is a mirror, and a missing or unreadable entry just means
 * the app opens idle / following the song.
 *
 * Colours only (a kind and six ARGB values) plus the cover path or URL they came from: nothing
 * about what was played beyond what the cover cache already holds on disk.
 */
internal class AlbumColourSnapshot(private val prefs: SharedPreferences) {

    /** The palette the app closed in, or null (first launch, nothing playing, a cleared store). */
    fun readSeed(): PaletteSeed? = PaletteSeed.decode(
        model = prefs.getString(KEY_MODEL, null),
        key = prefs.getString(KEY_PALETTE_KEY, null),
        colours = prefs.getString(KEY_COLOURS, null),
    )

    /**
     * Records what the app now shows for [model]: a cover's palette is kept; nothing loaded clears
     * it (the next start opens idle); the idle palette for a cover (unreadable or still failing to
     * load) changes nothing, so an offline start keeps the colour it had.
     */
    fun record(model: String?, palette: AmbientPalette) {
        when {
            model == null -> prefs.edit().remove(KEY_MODEL).remove(KEY_PALETTE_KEY).remove(KEY_COLOURS).apply()
            palette.kind == PaletteKind.BRAND -> Unit
            else -> {
                val seed = PaletteSeed(model, palette)
                prefs.edit()
                    .putString(KEY_MODEL, model)
                    .putString(KEY_PALETTE_KEY, palette.key)
                    .putString(KEY_COLOURS, seed.encodedColours())
                    .apply()
            }
        }
    }

    /** True unless the user chose Album colours: Neutral (the default follows the song). */
    fun readFollowSong(): Boolean = prefs.getBoolean(KEY_FOLLOW_SONG, true)

    fun writeFollowSong(follow: Boolean) {
        if (prefs.getBoolean(KEY_FOLLOW_SONG, true) != follow) prefs.edit().putBoolean(KEY_FOLLOW_SONG, follow).apply()
    }

    private companion object {
        const val KEY_MODEL = "album_colour_cover"
        const val KEY_PALETTE_KEY = "album_colour_key"
        const val KEY_COLOURS = "album_colour_palette"
        const val KEY_FOLLOW_SONG = "album_colours_follow_song"
    }
}
