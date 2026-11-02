package com.stash.core.ui.ambient

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex

/*
 * A cold start opens in the album colour it closed in (review of #792): the app's first frame used
 * to be the idle palette, then, about a second in, the restored song's cover was read and every
 * accent snapped and the whole app redrew. The last cover's palette is kept beside the theme (the
 * app stores it in the theme snapshot, read synchronously before the first frame) and shown until
 * the player says otherwise.
 */

/** How long a cold start keeps the remembered colour while the player restores its queue. */
const val RESTORE_GRACE_MS = 3_000L

/** The cover [model] (path or URL) the app last showed, and its [palette]. */
@Immutable
data class PaletteSeed(val model: String, val palette: AmbientPalette) {

    /** The palette as one line of plain values (kind and six ARGB colours); the model and key go beside it. */
    fun encodedColours(): String = buildList {
        add(palette.kind.name)
        listOf(palette.dom, palette.vib, palette.mut, palette.textDark, palette.textLight, palette.ink)
            .forEach { add(it.toArgb().toString()) }
    }.joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = ","
        private const val COLOUR_COUNT = 6

        /**
         * The seed stored as [model], [key] and [encodedColours]; null when any part is missing or
         * unreadable (a first launch, a cleared store, an older format), so the app opens idle.
         */
        fun decode(model: String?, key: String?, colours: String?): PaletteSeed? {
            if (model.isNullOrEmpty() || key == null || colours == null) return null
            val parts = colours.split(SEPARATOR)
            if (parts.size != COLOUR_COUNT + 1) return null
            val kind = PaletteKind.entries.firstOrNull { it.name == parts[0] } ?: return null
            val c = parts.drop(1).map { part -> Color(part.toIntOrNull() ?: return null) }
            return PaletteSeed(
                model,
                AmbientPalette(
                    dom = c[0], vib = c[1], mut = c[2], key = key,
                    textDark = c[3], textLight = c[4], ink = c[5], kind = kind,
                ),
            )
        }
    }
}

/**
 * The palette of each cover [models] names (null: nothing loaded), paired with that cover, as the
 * app wears them. [load] reads a cover, emitting [load]'s fallback when it can't. With a [seed] (the
 * colour the app closed in):
 *  - a first "nothing loaded" waits [graceMs] before it turns idle, because on a cold start the player
 *    reports an empty state before it restores its queue; the restored song cancels the wait;
 *  - the seed's own cover shows the seed at once (no read to wait for), then whatever a fresh read
 *    gives, with the seed as its fallback, so an offline start never drops to idle.
 * Without a seed it is the plain read: idle for no cover, the cover's palette (idle while it loads).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun coverPalettes(
    models: Flow<String?>,
    seed: PaletteSeed?,
    load: (model: String, fallback: AmbientPalette) -> Flow<AmbientPalette>,
    graceMs: Long = RESTORE_GRACE_MS,
): Flow<Pair<String?, AmbientPalette>> =
    models.distinctUntilChanged().withIndex().flatMapLatest { (index, model) ->
        when {
            model == null && index == 0 && seed != null -> flow {
                delay(graceMs)
                emit(null to AmbientPalette.Default)
            }
            model == null -> flowOf(null to AmbientPalette.Default)
            seed != null && model == seed.model -> flow {
                emit(model to seed.palette)
                emitAll(load(model, seed.palette).map { model to it })
            }
            else -> load(model, AmbientPalette.Default).map { model to it }
        }
    }.distinctUntilChanged()
