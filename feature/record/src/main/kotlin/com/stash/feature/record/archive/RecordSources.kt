package com.stash.feature.record.archive

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Where a play came from (spec §7, record.ts `Source`): the Spotify export, Stash itself, or a Last.fm scrobble. In this order. */
enum class RecordSource(val key: String) {
    SPOTIFY("spotify"),
    STASH("stash"),
    LASTFM("lastfm"),
    ;

    companion object {
        /** embed.ts `sourceOf`: a row's source by its flags. */
        fun of(f: Int): RecordSource = when {
            f and RecordFlags.LASTFM != 0 -> LASTFM
            f and RecordFlags.STASH != 0 -> STASH
            else -> SPOTIFY
        }
    }
}

/** Which sources the record shows (record.ts `SourcesOn`). */
data class SourcesOn(val spotify: Boolean = true, val stash: Boolean = true, val lastfm: Boolean = true) {
    operator fun get(s: RecordSource): Boolean = when (s) {
        RecordSource.SPOTIFY -> spotify
        RecordSource.STASH -> stash
        RecordSource.LASTFM -> lastfm
    }

    fun with(s: RecordSource, on: Boolean): SourcesOn = when (s) {
        RecordSource.SPOTIFY -> copy(spotify = on)
        RecordSource.STASH -> copy(stash = on)
        RecordSource.LASTFM -> copy(lastfm = on)
    }

    companion object {
        val ALL_ON = SourcesOn()
    }
}

/** How many listens (30 s or more) each source brings, before the switches (record.ts `Counts`, the page's `stash:counts`). */
data class SourceCounts(val spotify: Int, val stash: Int, val lastfm: Int) {
    operator fun get(s: RecordSource): Int = when (s) {
        RecordSource.SPOTIFY -> spotify
        RecordSource.STASH -> stash
        RecordSource.LASTFM -> lastfm
    }
}

/**
 * The switches' rules (stash-player `src/lib/ui/spotify/record.ts`), ported exactly: a switch for each source with plays, the
 * switches at all only with two or more, one source with plays always on, and a choice that leaves none of those on (its one
 * source was removed since) shows everything.
 */
object RecordSources {
    /** record.ts `readSources`: the choice as kept, read back; anything unreadable is every source on. */
    fun read(raw: String?): SourcesOn {
        val o = try {
            raw?.let { Json.parseToJsonElement(it) }
        } catch (e: IllegalArgumentException) {
            null
        } as? JsonObject ?: return SourcesOn.ALL_ON
        // `!== false`: only a real false switches one off
        fun on(key: String) = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != false
        return SourcesOn(spotify = on("spotify"), stash = on("stash"), lastfm = on("lastfm"))
    }

    /** As the web keeps it (`JSON.stringify` of the choice). */
    fun write(on: SourcesOn): String = "{\"spotify\":${on.spotify},\"stash\":${on.stash},\"lastfm\":${on.lastfm}}"

    /** record.ts `present`: the sources with plays, in order. */
    fun present(counts: SourceCounts?): List<RecordSource> = if (counts == null) emptyList() else RecordSource.entries.filter { counts[it] > 0 }

    /** record.ts `shownSources`: the choice, unless it leaves no source with plays on; then every source. */
    fun shown(on: SourcesOn, counts: SourceCounts?): SourcesOn {
        val here = present(counts)
        return if (here.isNotEmpty() && here.none { on[it] }) SourcesOn.ALL_ON else on
    }

    /** record.ts `toggleSource`: [s] switched over; null when that would leave no source with plays on. */
    fun toggle(on: SourcesOn, s: RecordSource, counts: SourceCounts?): SourcesOn? {
        val next = on.with(s, !on[s])
        val here = present(counts)
        return if (here.isNotEmpty() && here.none { next[it] }) null else next
    }

    /** Listens by source of rows as the page takes them ([PageRows.accepts], 30 s or more), counted as they pass. */
    class Counter {
        private val n = IntArray(3)

        fun add(r: ArchivePlay) {
            if (r.ms >= RecordSummaryBuilder.STREAM_MS && PageRows.accepts(r)) n[RecordSource.of(r.f).ordinal]++
        }

        fun counts(): SourceCounts = SourceCounts(n[0], n[1], n[2])
    }
}

/** The choice kept on this phone (the web keeps it per browser, `stash.record.sources`), as it changes. */
@Singleton
class RecordSourcesStore internal constructor(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val _on = MutableStateFlow(RecordSources.read(prefs.getString(KEY, null)))
    val on: StateFlow<SourcesOn> = _on.asStateFlow()

    fun set(on: SourcesOn) {
        prefs.edit().putString(KEY, RecordSources.write(on)).apply()
        _on.value = on
    }

    private companion object {
        /** Your record's own preferences (the import's meta lives there too). */
        const val FILE = "your_record"
        const val KEY = "record_sources"
    }
}
