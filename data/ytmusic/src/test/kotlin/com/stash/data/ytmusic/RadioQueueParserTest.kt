package com.stash.data.ytmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The station queue panel, against hand-built answers shaped like InnerTube WEB_REMIX's.
 *
 * These are NOT captured fixtures: a real `/next` radio answer needs the listener's own
 * cookie, so it can only be recorded on a signed-in device (see the plan's verification
 * step). Every path and key here is the one the live player uses and the one Metrolist's
 * `YouTubeRadioParser` reads; once a captured `radio_queue_next.json` exists it should
 * replace these, because that is what proves YouTube still answers in this shape.
 */
class RadioQueueParserTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private val firstPage = json(
        """
        {
          "contents": {
            "singleColumnMusicWatchNextResultsRenderer": {
              "tabbedRenderer": {
                "watchNextTabbedResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "musicQueueRenderer": {
                            "content": {
                              "playlistPanelRenderer": {
                                "contents": [
                                  {
                                    "playlistPanelVideoRenderer": {
                                      "videoId": "seedVid",
                                      "title": { "runs": [ { "text": "Seed Song" } ] },
                                      "longBylineText": {
                                        "runs": [
                                          { "text": "Seed Artist",
                                            "navigationEndpoint": { "browseEndpoint": { "browseId": "UCseed" } } },
                                          { "text": " • " },
                                          { "text": "Seed Album",
                                            "navigationEndpoint": { "browseEndpoint": { "browseId": "MPREb_seed" } } },
                                          { "text": " • " },
                                          { "text": "3:52" }
                                        ]
                                      },
                                      "lengthText": { "runs": [ { "text": "3:52" } ] },
                                      "thumbnail": { "thumbnails": [
                                        { "url": "https://img/small.jpg", "width": 60 },
                                        { "url": "https://img/large.jpg", "width": 544 }
                                      ] },
                                      "navigationEndpoint": {
                                        "watchEndpointMusicSupportedConfigs": {
                                          "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_ATV" }
                                        }
                                      }
                                    }
                                  },
                                  {
                                    "playlistPanelVideoWrapperRenderer": {
                                      "primaryRenderer": {
                                        "playlistPanelVideoRenderer": {
                                          "videoId": "wrappedVid",
                                          "title": { "runs": [ { "text": "Wrapped Song" } ] },
                                          "shortBylineText": {
                                            "runs": [
                                              { "text": "Wrapped Artist",
                                                "navigationEndpoint": { "browseEndpoint": { "browseId": "UCwrap" } } },
                                              { "text": " • " },
                                              { "text": "2:10" }
                                            ]
                                          },
                                          "lengthText": { "runs": [ { "text": "2:10" } ] }
                                        }
                                      }
                                    }
                                  },
                                  { "playlistPanelVideoRenderer": { "title": { "runs": [ { "text": "No id" } ] } } }
                                ],
                                "continuations": [
                                  { "nextRadioContinuationData": { "continuation": "TOKEN_ONE" } }
                                ]
                              }
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `the first page reads the queue panel`() {
        val page = RadioQueueParser.page(firstPage)
        assertEquals(listOf("seedVid", "wrappedVid"), page.tracks.map { it.videoId })
        assertEquals("TOKEN_ONE", page.continuation)
    }

    @Test
    fun `a row names its artist, album and length from the byline`() {
        val seed = RadioQueueParser.page(firstPage).tracks.first()
        assertEquals("Seed Song", seed.title)
        assertEquals("Seed Artist", seed.artist)
        assertEquals("Seed Album", seed.album)
        assertEquals(232.0, seed.durationSeconds, 0.001)
        // The model carries MusicVideoType's name ("ATV"), as every other parser hands it over.
        assertEquals("ATV", seed.musicVideoType)
    }

    /** The largest thumbnail wins, so a station card has a square cover and not a 60px one. */
    @Test
    fun `the biggest thumbnail is the one kept`() {
        assertEquals("https://img/large.jpg", RadioQueueParser.page(firstPage).tracks.first().thumbnailUrl)
    }

    /** A song offered with its video counterpart arrives one renderer deeper; it is still a row. */
    @Test
    fun `a wrapped row still parses`() {
        val wrapped = RadioQueueParser.page(firstPage).tracks[1]
        assertEquals("Wrapped Song", wrapped.title)
        assertEquals("Wrapped Artist", wrapped.artist)
    }

    /** Half a page beats no page: one unusable row must not cost the whole station. */
    @Test
    fun `a row without a videoId is skipped`() {
        assertEquals(2, RadioQueueParser.page(firstPage).tracks.size)
    }

    @Test
    fun `a repeated videoId appears once`() {
        val repeated = json(
            """
            { "continuationContents": { "playlistPanelContinuation": { "contents": [
              { "playlistPanelVideoRenderer": { "videoId": "v1", "title": { "runs": [ { "text": "A" } ] } } },
              { "playlistPanelVideoRenderer": { "videoId": "v1", "title": { "runs": [ { "text": "A" } ] } } }
            ] } } }
            """.trimIndent(),
        )
        assertEquals(1, RadioQueueParser.page(repeated).tracks.size)
    }

    @Test
    fun `a continuation page reads playlistPanelContinuation`() {
        val page = json(
            """
            { "continuationContents": { "playlistPanelContinuation": {
              "contents": [
                { "playlistPanelVideoRenderer": { "videoId": "nextVid", "title": { "runs": [ { "text": "Next" } ] } } }
              ],
              "continuations": [ { "nextRadioContinuationData": { "continuation": "TOKEN_TWO" } } ]
            } } }
            """.trimIndent(),
        )
        val parsed = RadioQueueParser.page(page)
        assertEquals(listOf("nextVid"), parsed.tracks.map { it.videoId })
        assertEquals("TOKEN_TWO", parsed.continuation)
    }

    /**
     * A station saved to the library answers with `nextContinuationData`, and some answers
     * carry the token only inside a row's `continuationCommand`. Missing either would end
     * a working station at the end of its first page.
     */
    @Test
    fun `the next-page token is found in any of its places`() {
        val libraryStation = json(
            """
            { "continuationContents": { "playlistPanelContinuation": {
              "contents": [],
              "continuations": [ { "nextContinuationData": { "continuation": "TOKEN_LIBRARY" } } ]
            } } }
            """.trimIndent(),
        )
        assertEquals("TOKEN_LIBRARY", RadioQueueParser.page(libraryStation).continuation)

        val tokenInRow = json(
            """
            { "continuationContents": { "playlistPanelContinuation": { "contents": [
              { "playlistPanelVideoRenderer": {
                "videoId": "v1", "title": { "runs": [ { "text": "A" } ] },
                "navigationEndpoint": { "watchEndpoint": {
                  "watchEndpointMusicSupportedConfigs": {
                    "watchEndpointMusicConfig": {
                      "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                    }
                  }
                } }
              } },
              { "continuationItemRenderer": { "continuationEndpoint": {
                "continuationCommand": { "token": "TOKEN_IN_ROW" }
              } } }
            ] } } }
            """.trimIndent(),
        )
        assertEquals("TOKEN_IN_ROW", RadioQueueParser.page(tokenInRow).continuation)
    }

    /** A station that offers nothing more is a normal answer, not a failure. */
    @Test
    fun `a page with no continuation ends the station`() {
        val page = json(
            """
            { "continuationContents": { "playlistPanelContinuation": { "contents": [
              { "playlistPanelVideoRenderer": { "videoId": "v1", "title": { "runs": [ { "text": "A" } ] } } }
            ] } } }
            """.trimIndent(),
        )
        assertNull(RadioQueueParser.page(page).continuation)
    }

    /** An answer with no queue panel at all (a plain watch page) yields nothing rather than throwing. */
    @Test
    fun `an answer without a queue panel is empty`() {
        val page = RadioQueueParser.page(json("""{ "contents": { "singleColumnMusicWatchNextResultsRenderer": {} } }"""))
        assertTrue(page.tracks.isEmpty())
        assertNull(page.continuation)
    }

    /**
     * An artist's station ids are `RDEM<channelId>`, and their play button sits under one of
     * two header renderers that have moved before; the endpoint is found wherever it is.
     */
    @Test
    fun `an artist page's radio button is found`() {
        val artistPage = json(
            """
            { "contents": { "singleColumnBrowseResultsRenderer": { "tabs": [ { "tabRenderer": { "content": {
              "sectionListRenderer": { "contents": [ { "musicImmersiveHeaderRenderer": {
                "title": { "runs": [ { "text": "An Artist" } ] },
                "buttons": [ { "musicPlayButtonRenderer": {
                  "playNavigationEndpoint": { "watchEndpoint": {
                    "videoId": "artistSeed",
                    "playlistId": "RDEMartistChannel",
                    "params": "wAEB"
                  } }
                } } ]
              } } ] }
            } } } ] } } }
            """.trimIndent(),
        )
        val endpoint = ArtistRadioEndpoint.from(artistPage)!!
        assertEquals("artistSeed", endpoint.videoId)
        assertEquals("RDEMartistChannel", endpoint.playlistId)
        assertEquals("wAEB", endpoint.params)
    }

    /** A song's own `RDAMVM…` endpoint is not an artist station, and an artist page without one is null. */
    @Test
    fun `an artist page without a radio button has no endpoint`() {
        val songPage = json(
            """
            { "playButton": { "musicPlayButtonRenderer": {
              "playNavigationEndpoint": { "watchEndpoint": {
                "videoId": "songVid", "playlistId": "RDAMVMsongVid"
              } }
            } } }
            """.trimIndent(),
        )
        assertNull(ArtistRadioEndpoint.from(songPage))
    }
}
