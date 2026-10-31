package com.stash.core.data.playlistfile

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.handoff.WireSong
import java.nio.charset.Charset
import org.junit.Test

/** Issue #61: playlist files as the exporters people use write them. */
class PlaylistFileParserTest {
    private fun parse(text: String, name: String?) = PlaylistFileParser.parse(text.toByteArray(Charsets.UTF_8), name)

    // ------------------------------------------------------------------------------------------------------- CSV

    @Test fun `an Exportify export gives each song its words, album, length, ISRC and Spotify id`() {
        val csv = """
            "Track URI","Track Name","Artist URI(s)","Artist Name(s)","Album URI","Album Name","Album Artist URI(s)","Album Artist Name(s)","Album Release Date","Album Image URL","Disc Number","Track Number","Track Duration (ms)","Track Preview URL","Explicit","Popularity","ISRC","Added By","Added At"
            "spotify:track:6LgJvl0Xdtc73RJ1mmpotq","Paranoid Android","spotify:artist:4Z8W4fKeB5YxbusRsdQVPb","Radiohead","spotify:album:6dVIqQ8qmQ5GBnJ9shOYGE","OK Computer","spotify:artist:4Z8W4fKeB5YxbusRsdQVPb","Radiohead","1997-05-21","https://i.scdn.co/image/ab67616d0000b273c8b444df094279e70d0ed856","1","2","387213","","false","73","GBAYE9700097","spotify:user:listener","2021-03-14T10:22:31Z"
            "spotify:track:2Foc5Q5nqNiosCNqttzHof","Get Lucky (feat. Pharrell Williams & Nile Rodgers) - Radio Edit","spotify:artist:4tZwfgrHOc3mvqYlEYSvVi, spotify:artist:2RdwBSPQiwcmiDo9kixcl8","Daft Punk, Pharrell Williams, Nile Rodgers","spotify:album:2v6ANhWhZBUKkg6pJJBs3B","Get Lucky (Radio Edit) [feat. Pharrell Williams and Nile Rodgers]","spotify:artist:4tZwfgrHOc3mvqYlEYSvVi","Daft Punk","2013-04-19","https://i.scdn.co/image/ab67616d0000b2731d5cf960e92686cc5bbb5ad6","1","1","248413","","false","80","USQX91300108","spotify:user:listener","2021-03-14T10:25:02Z"
            "spotify:track:5ZBeML7Lf3FMEVviTyvi8l","September","spotify:artist:4QQgXkCYTt3BlENzhyNETg","Earth, Wind & Fire","spotify:album:5EDp4x8bGtMbBxBuHC2rPn","The Best Of Earth, Wind & Fire Vol. 1","spotify:artist:4QQgXkCYTt3BlENzhyNETg","Earth, Wind & Fire","1978-11-23","https://i.scdn.co/image/x","1","2","215093","","false","81","USSM17800845","spotify:user:listener","2021-03-14T10:26:40Z"
        """.trimIndent()
        val file = parse(csv, "road_trip.csv")
        assertThat(file.format).isEqualTo(PlaylistFileFormat.CSV)
        assertThat(file.title).isNull()
        assertThat(file.cut).isFalse()
        assertThat(file.songs).containsExactly(
            WireSong("Paranoid Android", "Radiohead", "OK Computer", 387_213L, "GBAYE9700097", "6LgJvl0Xdtc73RJ1mmpotq"),
            WireSong(
                "Get Lucky (feat. Pharrell Williams & Nile Rodgers) - Radio Edit",
                "Daft Punk, Pharrell Williams, Nile Rodgers",
                "Get Lucky (Radio Edit) [feat. Pharrell Williams and Nile Rodgers]",
                248_413L,
                "USQX91300108",
                "2Foc5Q5nqNiosCNqttzHof",
            ),
            WireSong("September", "Earth, Wind & Fire", "The Best Of Earth, Wind & Fire Vol. 1", 215_093L, "USSM17800845", "5ZBeML7Lf3FMEVviTyvi8l"),
        ).inOrder()
    }

    @Test fun `the newer Exportify layout, with "Duration (ms)" and genres full of commas, reads the same`() {
        val csv = """
            "Track URI","Track Name","Album Name","Artist Name(s)","Release Date","Duration (ms)","Popularity","Explicit","Added By","Added At","Genres","Record Label","Danceability","Energy"
            "spotify:track:0DiWol3AO6WpXZgp0goxAV","One More Time","Discovery","Daft Punk","2001-03-12","320357","79","false","","2024-01-02T03:04:05Z","filter house,french house,electro","Parlophone (France)","0.611","0.697"
            "spotify:local:Unknown:Unknown:My+Demo:180","My Demo","","Me","","180000","0","false","","2024-01-02T03:04:05Z","","","",""
        """.trimIndent()
        assertThat(parse(csv, "Daft.csv").songs).containsExactly(
            WireSong("One More Time", "Daft Punk", "Discovery", 320_357L, spotifyId = "0DiWol3AO6WpXZgp0goxAV"),
            // A local file's URI is no Spotify track: its words alone.
            WireSong("My Demo", "Me", durationMs = 180_000L),
        ).inOrder()
    }

    @Test fun `an older Exportify's escaped commas and artists joined without a space read as names`() {
        val csv = "Track Name,Artist Name(s),Album Name\n" +
            "EARFQUAKE,\"Tyler\\, The Creator,Playboi Carti\",IGOR\n"
        assertThat(parse(csv, "x.csv").songs.single().artist).isEqualTo("Tyler, The Creator, Playboi Carti")
    }

    @Test fun `a TuneMyMusic export names its playlist on every row and gives bare Spotify ids`() {
        val csv = """
            Track name,Artist name,Album,Playlist name,Type,ISRC,Spotify - id
            One More Time,Daft Punk,Discovery,Road Trip,Playlist,GBDUW0000053,0DiWol3AO6WpXZgp0goxAV
            Creep,Radiohead,Pablo Honey,Road Trip,Playlist,GBAYE9200070,70LcF31zb1H0PyJoS1Sx1r
        """.trimIndent()
        val file = parse(csv, "tunemymusic_export.csv")
        assertThat(file.title).isEqualTo("Road Trip")
        assertThat(file.songs).containsExactly(
            WireSong("One More Time", "Daft Punk", "Discovery", isrc = "GBDUW0000053", spotifyId = "0DiWol3AO6WpXZgp0goxAV"),
            WireSong("Creep", "Radiohead", "Pablo Honey", isrc = "GBAYE9200070", spotifyId = "70LcF31zb1H0PyJoS1Sx1r"),
        ).inOrder()
    }

    @Test fun `a file of several playlists is one list, named by the file`() {
        val csv = "Track name,Artist name,Album,Playlist name\nCreep,Radiohead,Pablo Honey,Road Trip\nTeardrop,Massive Attack,Mezzanine,Rain\n"
        val file = parse(csv, "all.csv")
        assertThat(file.title).isNull()
        assertThat(file.songs.map { it.title }).containsExactly("Creep", "Teardrop").inOrder()
    }

    @Test fun `a lower-case Soundiiz header with quoted fields and a formatted ISRC`() {
        val csv = "title,artist,album,isrc\n\"Around the World\",\"Daft Punk\",\"Homework\",\"GB-DUW-97-00012\"\n"
        assertThat(parse(csv, "soundiiz.csv").songs)
            .containsExactly(WireSong("Around the World", "Daft Punk", "Homework", isrc = "GBDUW9700012"))
    }

    @Test fun `a plain header with semicolons, Excel's sep hint, and a row with no artist passed over`() {
        val csv = "sep=;\r\nTitle;Artist;Album\r\nKarma Police;Radiohead;OK Computer\r\nIntro;;\r\n\r\nNo Surprises;Radiohead;OK Computer\r\n"
        assertThat(parse(csv, "list.csv").songs).containsExactly(
            WireSong("Karma Police", "Radiohead", "OK Computer"),
            WireSong("No Surprises", "Radiohead", "OK Computer"),
        ).inOrder()
    }

    @Test fun `a quoted field holds commas, doubled quotes and a line break`() {
        val csv = "Title,Artist\n\"Say \"\"Hello\"\",\nGoodbye\",The Band\nNext,Other\n"
        assertThat(parse(csv, "x.csv").songs).containsExactly(
            WireSong("Say \"Hello\", Goodbye", "The Band"),
            WireSong("Next", "Other"),
        ).inOrder()
    }

    @Test fun `Apple Music's export is a txt in UTF-16 with tabs and old Mac line endings, its Time in seconds`() {
        val header = "Name\tArtist\tComposer\tAlbum\tGrouping\tWork\tMovement Number\tMovement Count\tMovement Name\tGenre\tSize\t" +
            "Time\tDisc Number\tDisc Count\tTrack Number\tTrack Count\tYear\tDate Modified\tDate Added\tBit Rate\tSample Rate\t" +
            "Volume Adjustment\tKind\tEqualiser\tComments\tPlays\tLast Played\tSkips\tLast Skipped\tMy Rating\tLocation"
        val rows = listOf(
            "Digital Love\tDaft Punk\tThomas Bangalter\tDiscovery\t\t\t\t\t\tElectronic\t9254420\t301\t1\t1\t3\t14\t2001\t" +
                "12/01/2020, 10:00\t12/01/2020, 10:00\t256\t44100\t\tAAC audio file\t\t\t12\t\t\t\t\t",
            "Teardrop\tMassive Attack\t\tMezzanine\t\t\t\t\t\tTrip-Hop\t13254420\t330\t1\t1\t3\t11\t1998\t" +
                "12/01/2020, 10:00\t12/01/2020, 10:00\t320\t44100\t\tMPEG audio file\t\t\t4\t\t\t\t\t",
        )
        val text = (listOf(header) + rows).joinToString("\r") + "\r"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val file = PlaylistFileParser.parse(bytes, "Chill.txt")
        assertThat(file.format).isEqualTo(PlaylistFileFormat.CSV)
        assertThat(file.songs).containsExactly(
            WireSong("Digital Love", "Daft Punk", "Discovery", 301_000L),
            WireSong("Teardrop", "Massive Attack", "Mezzanine", 330_000L),
        ).inOrder()
    }

    // ------------------------------------------------------------------------------------------------------- M3U

    @Test fun `an extended M3U reads its EXTINF lines, its name, and file names where a line has none`() {
        val m3u = """
            #EXTM3U
            #PLAYLIST:Late Night Drive
            #EXTINF:383,Radiohead - Paranoid Android
            D:\Music\Radiohead\OK Computer\02 Paranoid Android.mp3
            #EXTINF:-1,Daft Punk - One More Time
            https://stream.example.com/tracks/123
            #EXTINF:301,Digital Love
            /storage/emulated/0/Music/Daft Punk/Discovery/03 - Daft Punk - Digital Love.mp3
            /storage/emulated/0/Music/Daft Punk/Discovery/04 - Daft Punk - Harder, Better, Faster, Stronger.flac
            #EXTINF:240 tvg-id="x" tvg-name="Hello, World",Massive Attack - Teardrop
            http://example.com/teardrop.mp3
            /storage/emulated/0/Music/Unknown/05 Untitled.mp3
        """.trimIndent()
        val file = parse(m3u, "late.m3u8")
        assertThat(file.format).isEqualTo(PlaylistFileFormat.M3U)
        assertThat(file.title).isEqualTo("Late Night Drive")
        assertThat(file.songs).containsExactly(
            WireSong("Paranoid Android", "Radiohead", durationMs = 383_000L),
            WireSong("One More Time", "Daft Punk"),
            // A bare title and no #EXTART: the file name says who it is by; the #EXTINF its length.
            WireSong("Digital Love", "Daft Punk", durationMs = 301_000L),
            WireSong("Harder, Better, Faster, Stronger", "Daft Punk"),
            WireSong("Teardrop", "Massive Attack", durationMs = 240_000L),
        ).inOrder()
    }

    @Test fun `EXTART names the artist, so a line ending with it is Title - Artist, and EXTALB the album`() {
        val m3u = """
            #EXTM3U
            #EXTALB:OK Computer
            #EXTART:Radiohead
            #EXTINF:383,Paranoid Android - Radiohead
            02.mp3
            #EXTINF:264,Karma Police
            06.mp3
            #EXTINF:229,Radiohead - No Surprises
            10.mp3
        """.trimIndent()
        assertThat(parse(m3u, "ok.m3u").songs).containsExactly(
            WireSong("Paranoid Android", "Radiohead", "OK Computer", 383_000L),
            WireSong("Karma Police", "Radiohead", "OK Computer", 264_000L),
            WireSong("No Surprises", "Radiohead", "OK Computer", 229_000L),
        ).inOrder()
    }

    @Test fun `a bare M3U of paths is read from its file names, by its extension`() {
        val m3u = "Music/Radiohead - Creep.mp3\nMusic/Daft%20Punk/x.mp3\nhttp://example.com/a/Daft%20Punk%20-%20Around%20the%20World.mp3\n"
        val file = parse(m3u, "old.m3u")
        assertThat(file.format).isEqualTo(PlaylistFileFormat.M3U)
        assertThat(file.songs).containsExactly(
            WireSong("Creep", "Radiohead"),
            WireSong("Around the World", "Daft Punk"),
        ).inOrder()
    }

    @Test fun `what a file holds decides its format, not its extension`() {
        assertThat(parse("#EXTM3U\n#EXTINF:1,A - B\na.mp3\n", "list.txt").format).isEqualTo(PlaylistFileFormat.M3U)
        assertThat(parse("Title,Artist\nB,A\n", "list.txt").format).isEqualTo(PlaylistFileFormat.CSV)
        assertThat(parse("<?xml version=\"1.0\"?><playlist><trackList></trackList></playlist>", "x.xml").format)
            .isEqualTo(PlaylistFileFormat.XSPF)
        assertThat(parse("A - B\n", "list.m3u").format).isEqualTo(PlaylistFileFormat.M3U)
        assertThat(parse("A - B\n", null).format).isEqualTo(PlaylistFileFormat.TEXT)
    }

    @Test fun `an old player's M3U in Windows-1252 or Windows-1251 reads its accents and Cyrillic`() {
        val western = "#EXTM3U\r\n#EXTINF:261,Beyoncé - Halo\r\nhalo.mp3\r\n#EXTINF:271,Sigur Rós - Hoppípolla\r\nh.mp3\r\n"
        assertThat(PlaylistFileParser.parse(western.toByteArray(Charset.forName("windows-1252")), "a.m3u").songs.map { it.artist })
            .containsExactly("Beyoncé", "Sigur Rós").inOrder()
        val russian = "#EXTM3U\r\n#EXTINF:285,Кино - Группа крови\r\nkino.mp3\r\n"
        assertThat(PlaylistFileParser.parse(russian.toByteArray(Charset.forName("windows-1251")), "b.m3u").songs)
            .containsExactly(WireSong("Группа крови", "Кино", durationMs = 285_000L))
    }

    // ------------------------------------------------------------------------------------------------------ XSPF

    private val xspf = """
        <?xml version="1.0" encoding="UTF-8"?>
        <playlist xmlns="http://xspf.org/ns/0/" xmlns:vlc="http://www.videolan.org/vlc/playlist/ns/0/" version="1">
        	<!-- <title>Not this one</title> -->
        	<title>Sunday &amp; Chill</title>
        	<trackList>
        		<track>
        			<location>file:///home/me/Music/Massive%20Attack/Mezzanine/03%20Teardrop.mp3</location>
        			<title>Teardrop</title>
        			<creator>Massive Attack</creator>
        			<album>Mezzanine</album>
        			<trackNum>3</trackNum>
        			<duration>330000</duration>
        			<extension application="http://www.videolan.org/vlc/playlist/0">
        				<vlc:id>0</vlc:id>
        			</extension>
        		</track>
        		<track>
        			<title><![CDATA[Halo & Light]]></title>
        			<creator>Beyonc&#233;</creator>
        			<identifier>isrc:USSM10804556</identifier>
        		</track>
        		<track>
        			<location>https://example.com/files/Daft%20Punk%20-%20Digital%20Love.mp3</location>
        		</track>
        		<track>
        			<location>file:///music/untitled.mp3</location>
        		</track>
        		<track>
        			<title>Karma Police</title>
        			<creator>Radiohead</creator>
        			<location>spotify:track:63OQupATfueTdZMWTxzD5T</location>
        		</track>
        	</trackList>
        </playlist>
    """.trimIndent()

    @Test fun `an XSPF gives each track's words, album and length, and the playlist's own title`() {
        val file = parse(xspf, "vlc.xspf")
        assertThat(file.format).isEqualTo(PlaylistFileFormat.XSPF)
        assertThat(file.title).isEqualTo("Sunday & Chill")
        assertThat(file.songs).containsExactly(
            WireSong("Teardrop", "Massive Attack", "Mezzanine", 330_000L),
            WireSong("Halo & Light", "Beyoncé", isrc = "USSM10804556"),
            // No title or creator: its file's name.
            WireSong("Digital Love", "Daft Punk"),
            WireSong("Karma Police", "Radiohead", spotifyId = "63OQupATfueTdZMWTxzD5T"),
        ).inOrder()
    }

    @Test fun `an XSPF cut short gives its whole tracks, and says it was cut`() {
        val cut = xspf.substring(0, xspf.indexOf("<![CDATA["))
        val file = PlaylistFileParser.parse(cut.toByteArray(Charsets.UTF_8), "vlc.xspf", truncated = true)
        assertThat(file.songs.map { it.title }).containsExactly("Teardrop")
        assertThat(file.cut).isTrue()
    }

    @Test fun `an XSPF's document type is never expanded`() {
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE playlist [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">]>
            <playlist xmlns="http://xspf.org/ns/0/"><trackList>
            <track><title>&lol2;</title><creator>Someone</creator></track>
            </trackList></playlist>
        """.trimIndent()
        assertThat(parse(hostile, "x.xspf").songs).containsExactly(WireSong("&lol2;", "Someone"))
    }

    // ------------------------------------------------------------------------------------------------------- TXT

    @Test fun `a plain list reads Artist - Title lines, numbered or not, with any dash`() {
        val txt = """
            My road trip

            1. Radiohead - Creep
            2) Daft Punk – One More Time
            Кино — Группа крови
            10cc - Dreadlock Holiday
            a note with no separator
            Massive Attack -Teardrop
            https://example.com/Radiohead%20-%20Karma%20Police.mp3
            Radiohead - Paranoid Android - Remastered 2017
        """.trimIndent()
        val file = parse(txt, "road trip.txt")
        assertThat(file.format).isEqualTo(PlaylistFileFormat.TEXT)
        assertThat(file.songs).containsExactly(
            WireSong("Creep", "Radiohead"),
            WireSong("One More Time", "Daft Punk"),
            WireSong("Группа крови", "Кино"),
            WireSong("Dreadlock Holiday", "10cc"),
            WireSong("Karma Police", "Radiohead"),
            WireSong("Paranoid Android - Remastered 2017", "Radiohead"),
        ).inOrder()
    }

    @Test fun `nothing that names a song gives no songs`() {
        assertThat(parse("hello\nworld\n", "notes.txt").songs).isEmpty()
        assertThat(parse("", "empty.csv").songs).isEmpty()
        assertThat(PlaylistFileParser.parse(ByteArray(64) { (it * 7).toByte() }, "song.mp3").songs).isEmpty()
    }

    // ---------------------------------------------------------------------------------------------- limits

    @Test fun `a file of more than 2,000 songs brings the first 2,000 and says it was cut`() {
        val txt = (1..2_500).joinToString("\n") { "Artist $it - Song $it" }
        val file = parse(txt, "big.txt")
        assertThat(file.songs).hasSize(PlaylistFileParser.MAX_SONGS)
        assertThat(file.songs.first()).isEqualTo(WireSong("Song 1", "Artist 1"))
        assertThat(file.songs.last()).isEqualTo(WireSong("Song 2000", "Artist 2000"))
        assertThat(file.cut).isTrue()
        assertThat(parse((1..2_000).joinToString("\n") { "A $it - S $it" }, "exact.txt").cut).isFalse()
    }

    @Test fun `the start of a longer file drops its half line, even one cut inside a character`() {
        val whole = "Sigur Rós - Hoppípolla\nSigur Rós - Glósóli\n".toByteArray(Charsets.UTF_8)
        // Cut between the two bytes of the second line's "ó".
        val cut = whole.copyOf(whole.size - "sóli\n".toByteArray(Charsets.UTF_8).size + 2)
        val file = PlaylistFileParser.parse(cut, "x.txt", truncated = true)
        assertThat(file.songs).containsExactly(WireSong("Hoppípolla", "Sigur Rós"))
        assertThat(file.cut).isTrue()
    }

    @Test fun `text is cleaned as the web library's is, and a long title is cut`() {
        val file = parse("Title,Artist\n\"  Teardrop\u200B  \",\" Massive\u0007Attack \"\n${"x".repeat(400)},Someone\n", "x.csv")
        assertThat(file.songs[0]).isEqualTo(WireSong("Teardrop", "Massive Attack"))
        assertThat(file.songs[1].title).hasLength(300)
    }
}
