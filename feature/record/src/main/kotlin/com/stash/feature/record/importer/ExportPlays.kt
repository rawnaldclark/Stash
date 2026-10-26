package com.stash.feature.record.importer

import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags

/**
 * The plays an export holds, as read (export.ts `ExportPlay`), kept small: a decade of heavy listening is half a million rows of
 * a few thousand songs, so each play is five numbers and its song, album and Spotify id are kept once each. A play's archive
 * flags ([RecordFlags]) are worked out as it is read (they depend on the row alone), and say which file it came from: Account
 * data's plays carry [RecordFlags.ACCOUNT], Extended history's never do.
 */
internal class ExportPlays {
    /** Each song (exact title and artist) once. */
    private val songIds = HashMap<String, Int>()
    private val titles = ArrayList<String>()
    private val artists = ArrayList<String>()
    private val albumIds = HashMap<String, Int>()
    private val albumNames = ArrayList<String>()
    private val sidIds = HashMap<String, Int>()
    private val sidNames = ArrayList<String>()

    var size = 0
        private set

    private var song = IntArray(INITIAL)
    private var album = IntArray(INITIAL)
    private var sid = IntArray(INITIAL)
    private var end = LongArray(INITIAL)
    private var ms = LongArray(INITIAL)
    private var flags = IntArray(INITIAL)

    /** Songs kept so far. */
    val songs: Int get() = titles.size

    fun add(title: String, artist: String, album: String?, sid: String?, endedAt: Long, ms: Long, f: Int) {
        if (size == song.size) grow()
        val k = title + '\u0000' + artist // no cleaned word holds a control character
        this.song[size] = songIds.getOrPut(k) { titles += title; artists += artist; titles.size - 1 }
        this.album[size] = album?.let { a -> albumIds.getOrPut(a) { albumNames += a; albumNames.size - 1 } } ?: -1
        this.sid[size] = sid?.let { s -> sidIds.getOrPut(s) { sidNames += s; sidNames.size - 1 } } ?: -1
        this.end[size] = endedAt
        this.ms[size] = ms
        this.flags[size] = f
        size++
    }

    /** Back to [n] plays: a file that turned out not to be JSON adds none (the web parses a file whole before reading a row). */
    fun truncate(n: Int) {
        require(n in 0..size)
        size = n
    }

    fun songOf(i: Int): Int = song[i]

    fun titleOfSong(s: Int): String = titles[s]

    fun artistOfSong(s: Int): String = artists[s]

    fun title(i: Int): String = titles[song[i]]

    fun artist(i: Int): String = artists[song[i]]

    fun album(i: Int): String? = album[i].takeIf { it >= 0 }?.let { albumNames[it] }

    fun sid(i: Int): String? = sid[i].takeIf { it >= 0 }?.let { sidNames[it] }

    fun end(i: Int): Long = end[i]

    fun ms(i: Int): Long = ms[i]

    /** `startedAt = endedAt − ms_played` (spec §3). */
    fun start(i: Int): Long = end[i] - ms[i]

    fun flags(i: Int): Int = flags[i]

    /** From Account data (export.ts `src: 'a'`). */
    fun isAccount(i: Int): Boolean = flags[i] and RecordFlags.ACCOUNT != 0

    /** Play [i] as the archive keeps it (plays.ts `toArchive`), its id made from [textKey] (the song's folded words). */
    fun toArchive(i: Int, textKey: String): ArchivePlay =
        ArchivePlay(id = "$textKey|${start(i)}", t = title(i), a = artist(i), al = album(i), sid = sid(i), s = start(i), ms = ms[i], f = flags[i])

    private fun grow() {
        val n = song.size * 2
        song = song.copyOf(n)
        album = album.copyOf(n)
        sid = sid.copyOf(n)
        end = end.copyOf(n)
        ms = ms.copyOf(n)
        flags = flags.copyOf(n)
    }

    private companion object {
        const val INITIAL = 1024
    }
}
