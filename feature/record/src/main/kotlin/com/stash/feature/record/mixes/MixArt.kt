package com.stash.feature.record.mixes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.stash.feature.record.art.ArtSong
import com.stash.feature.record.art.ArtText
import com.stash.feature.record.art.RecordArt
import kotlinx.coroutines.CoroutineScope

/** One square of a mix's tile: a cover, or none ([url] null: the song's own colours, from [seed]). */
data class TileCover(val url: String?, val seed: String)

/**
 * The covers behind a mix (stash-player `src/lib/spotify/art.ts` `withCovers`): each song's own row's cover when the library has
 * it, else the record's lookup by its album ([RecordArt], cached by album), asked for the first [albums] albums with none. Each
 * song's cover so far goes to [onBatch] as the lookups land; the last word is returned.
 */
internal suspend fun mixCovers(
    art: RecordArt,
    items: List<MixSong>,
    albums: Int,
    retry: CoroutineScope? = null,
    onBatch: (List<String?>) -> Unit = {},
): List<String?> {
    val own = items.map { it.lib?.art }
    val songs = items.map { ArtSong(title = it.title, artist = it.artist, album = it.album) }
    val keys = songs.map(ArtText::artKey)
    val asked = LinkedHashMap<String, ArtSong>()
    for (i in items.indices) {
        if (own[i] != null || keys[i] in asked) continue
        asked[keys[i]] = songs[i]
        if (asked.size >= albums) break
    }
    if (asked.isEmpty()) return own
    val order = asked.keys.toList()
    fun fill(urls: List<String?>): List<String?> {
        val byKey = order.indices.associate { order[it] to urls.getOrNull(it) }
        return items.indices.map { own[it] ?: byKey[keys[it]] }
    }
    return fill(art.covers(asked.values.toList(), retry) { onBatch(fill(it)) })
}

/** Tile.svelte's squares: the first four songs with different covers (a song with none counts by its key). */
internal fun tileCovers(items: List<MixSong>, covers: List<String?>): List<TileCover> {
    val seen = HashSet<String>()
    val out = ArrayList<TileCover>(4)
    for ((i, s) in items.withIndex()) {
        val url = covers.getOrNull(i)
        if (!seen.add(url ?: s.key)) continue
        out += TileCover(url, s.title)
        if (out.size == 4) break
    }
    return out
}

/** The Mixes tab's covers behind a card (routes/spotify/+page.svelte `coversOf`): its first four different album covers found. */
internal fun cardCovers(covers: List<String?>): List<String> = covers.filterNotNull().distinct().take(4)

/** A hue's two colours, as the web's cards and covers wash them (`hsl(h 50% 36%)` to `hsl(h + 40 55% 16%)`). */
internal fun hueBrush(hue: Int): Brush = Brush.linearGradient(
    listOf(Color.hsl((Math.floorMod(hue, 360)).toFloat(), 0.5f, 0.36f), Color.hsl((Math.floorMod(hue + 40, 360)).toFloat(), 0.55f, 0.16f)),
)

/** One cover, or its song's colours while there is none (Cover.svelte with a seed). */
@Composable
internal fun MixCover(url: String?, seed: String, modifier: Modifier = Modifier) {
    Box(modifier.background(hueBrush(HistoryMixes.hueOf(seed)))) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Tile.svelte: a 2x2 mosaic once four songs have different covers, else the first one's, else the mix's own colours. */
@Composable
internal fun MixTile(tiles: List<TileCover>, seed: String, modifier: Modifier = Modifier) {
    when {
        tiles.size >= 4 -> Column(modifier) {
            for (row in 0 until 2) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (col in 0 until 2) {
                        val t = tiles[row * 2 + col]
                        MixCover(t.url, t.seed, Modifier.weight(1f).fillMaxSize())
                    }
                }
            }
        }
        tiles.isNotEmpty() -> MixCover(tiles[0].url, tiles[0].seed, modifier)
        else -> MixCover(null, seed, modifier)
    }
}
