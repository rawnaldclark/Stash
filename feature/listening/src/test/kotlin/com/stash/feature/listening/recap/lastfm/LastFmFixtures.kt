package com.stash.feature.listening.recap.lastfm

import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.lastfm.LastFmScrobble
import com.stash.feature.listening.recap.RecapPeriod
import java.time.LocalDate
import java.time.ZoneId

/** A September 2026 of Last.fm scrobbles in New York, and a library that knows one of its songs. */
internal object LastFmFixtures {
    val zone: ZoneId = ZoneId.of("America/New_York")

    /** 2026-10-18, as FakeHistory.TODAY. */
    val today: Long = LocalDate.of(2026, 10, 18).toEpochDay()

    val september = RecapPeriod.MonthOf(2026, 9)

    fun at(month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDate.of(2026, month, day).atTime(hour, minute).atZone(zone).toEpochSecond()

    const val BLUE_IMAGE = "https://lastfm.freetls.fastly.net/i/u/770x0/blue.jpg"

    /** The library's copy of Taro: downloaded, 5:00, with its own cover file. */
    val localTaro = TrackEntity(
        id = 7, title = "Taro", artist = "alt-J", durationMs = 300_000, filePath = "/music/taro.opus",
        isDownloaded = true, albumArtPath = "/art/taro.jpg",
    )

    /**
     * 30 scrobbles: Taro 10 (5 of them on Sep 11), Common Blue 6 (Last.fm image), Drowning 4,
     * ten one-offs; one late-night scrobble on Sep 30 at 23:30 (already October in UTC) and one
     * on Aug 31 that must not count.
     */
    fun scrobbles(): List<LastFmScrobble> = buildList {
        repeat(5) { add(LastFmScrobble(at(9, 11, 20, it * 5), "alt-J", "Taro")) }
        listOf(3, 5, 7, 9, 12).forEach { add(LastFmScrobble(at(9, it, 9), "alt-J", "Taro")) }
        listOf(2, 3, 4, 5, 6, 7).forEach { add(LastFmScrobble(at(9, it, 13), "Warpaint", "Common Blue", "Common Blue", BLUE_IMAGE)) }
        listOf(14, 15, 16).forEach { add(LastFmScrobble(at(9, it, 18), "Slow Joy", "Drowning")) }
        add(LastFmScrobble(at(9, 30, 23, 30), "Slow Joy", "Drowning"))
        (1..10).forEach { add(LastFmScrobble(at(9, 20 + (it % 5), 17), "Artist $it", "One-off $it")) }
        add(LastFmScrobble(at(8, 31, 12), "Old", "August song"))
    }

    fun input(
        scrobbles: List<LastFmScrobble> = scrobbles(),
        local: List<TrackEntity> = listOf(localTaro),
        durations: Map<String, Long> = mapOf(LastFmRecapMapper.songKey("Common Blue", "Warpaint") to 240_000L),
    ) = LastFmRecapInput(
        period = september,
        today = today,
        zone = zone,
        scrobbles = scrobbles,
        localTracks = LastFmRecapMapper.localIndex(local),
        durations = durations,
        photos = emptyList(),
        genreIndex = ArtistGenreIndex.EMPTY,
    )
}
