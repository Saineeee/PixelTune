package com.saine.pixeltune.data.surf

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SURF: the [YoutubeTrendingFeedParser] walk, pinned to fixtures captured
 * from REAL YouTube Music browse responses (FEmusic_home and a VL charts
 * playlist, WEB_REMIX client) — trimmed to the renderer spines the parser
 * reads. Structural shelf detection, flex/fixed column mapping, VL browse
 * id filtering and duration parsing are all covered.
 */
class YoutubeTrendingFeedParserTest {

    // ------------------------------------------------------------------
    // Fixtures — trimmed from live FEmusic_home / VL playlist responses.
    // ------------------------------------------------------------------

    private fun homeFeedFixture(): JSONObject = JSONObject(
        """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicCarouselShelfRenderer": {
                            "header": {
                              "musicCarouselShelfBasicHeaderRenderer": {
                                "title": { "runs": [{ "text": "Quick picks" }] }
                              }
                            },
                            "contents": [
                              {
                                "musicResponsiveListItemRenderer": {
                                  "thumbnail": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": {
                                        "thumbnails": [
                                          { "url": "https://example.com/t/120x120.jpg", "width": 120 },
                                          { "url": "https://example.com/t/544x544.jpg", "width": 544 }
                                        ]
                                      }
                                    }
                                  },
                                  "playlistItemData": { "videoId": "quickPick1" },
                                  "flexColumns": [
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Training Season" }] } } },
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Dua Lipa" }, { "text": " • " }, { "text": "324m plays" }] } } }
                                  ]
                                }
                              },
                              {
                                "musicResponsiveListItemRenderer": {
                                  "playlistItemData": { "videoId": "quickPick2" },
                                  "flexColumns": [
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Animal" }] } } },
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "KATSEYE" }] } } }
                                  ]
                                }
                              }
                            ]
                          }
                        },
                        {
                          "musicCarouselShelfRenderer": {
                            "header": {
                              "musicCarouselShelfBasicHeaderRenderer": {
                                "title": { "runs": [{ "text": "Today's fresh & popular" }] }
                              }
                            },
                            "contents": [
                              {
                                "musicTwoRowItemRenderer": {
                                  "thumbnailRenderer": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": {
                                        "thumbnails": [
                                          { "url": "https://example.com/p/226.jpg", "width": 226 },
                                          { "url": "https://example.com/p/544.jpg", "width": 544 }
                                        ]
                                      }
                                    }
                                  },
                                  "title": { "runs": [{ "text": "Pop Certified" }] },
                                  "subtitle": { "runs": [{ "text": "Taylor Swift, Ariana Grande, Olivia Rodrigo, Bruno Mars" }] },
                                  "navigationEndpoint": {
                                    "browseEndpoint": { "browseId": "VLRDCLAK5uy_playlist1" }
                                  }
                                }
                              },
                              {
                                "musicTwoRowItemRenderer": {
                                  "title": { "runs": [{ "text": "Not a charts playlist" }] },
                                  "subtitle": { "runs": [{ "text": "Channel" }] },
                                  "navigationEndpoint": {
                                    "browseEndpoint": { "browseId": "UCsome_channel" }
                                  }
                                }
                              },
                              {
                                "musicTwoRowItemRenderer": {
                                  "title": { "runs": [{ "text": "The Hit List" }] },
                                  "subtitle": { "runs": [{ "text": "aespa, BTS" }] },
                                  "navigationEndpoint": {
                                    "browseEndpoint": { "browseId": "VLRDCLAK5uy_playlist2" }
                                  }
                                }
                              }
                            ]
                          }
                        },
                        {
                          "musicCarouselShelfRenderer": {
                            "header": {
                              "musicCarouselShelfBasicHeaderRenderer": {
                                "title": { "runs": [{ "text": "Mixed shelf" }] }
                              }
                            },
                            "contents": [
                              {
                                "musicTwoRowItemRenderer": {
                                  "title": { "runs": [{ "text": "Also VL" }] },
                                  "subtitle": { "runs": [{ "text": "Artists" }] },
                                  "navigationEndpoint": {
                                    "browseEndpoint": { "browseId": "VLRDCLAK5uy_playlist3" }
                                  }
                                }
                              },
                              {
                                "musicResponsiveListItemRenderer": {
                                  "playlistItemData": { "videoId": "mixedShelfSong" },
                                  "flexColumns": [
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Shelf song" }] } } },
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Artist" }] } } }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()
    )

    private fun playlistFixture(): JSONObject = JSONObject(
        """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicPlaylistShelfRenderer": {
                            "contents": [
                              {
                                "musicResponsiveListItemRenderer": {
                                  "thumbnail": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": {
                                        "thumbnails": [
                                          { "url": "https://example.com/c/60x60.jpg", "width": 60 },
                                          { "url": "https://example.com/c/544x544.jpg", "width": 544 }
                                        ]
                                      }
                                    }
                                  },
                                  "playlistItemData": { "videoId": "track1", "playlistSetVideoId": "abc" },
                                  "flexColumns": [
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Sapphire" }] } } },
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Ed Sheeran" }] } } },
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": {} } }
                                  ],
                                  "fixedColumns": [
                                    {
                                      "musicResponsiveListItemFixedColumnRenderer": {
                                        "text": { "runs": [{ "text": "3:04" }] }
                                      }
                                    }
                                  ]
                                }
                              },
                              {
                                "musicResponsiveListItemRenderer": {
                                  "playlistItemData": { "videoId": "track2" },
                                  "flexColumns": [
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "ICONIC BY MISTAKE" }] } } },
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "LE SSERAFIM, ILLIT & KATSEYE" }] } } }
                                  ],
                                  "fixedColumns": [
                                    {
                                      "musicResponsiveListItemFixedColumnRenderer": {
                                        "text": { "runs": [{ "text": "1:02:03" }] }
                                      }
                                    }
                                  ]
                                }
                              },
                              {
                                "musicResponsiveListItemRenderer": {
                                  "playlistItemData": { "videoId": "track3" },
                                  "flexColumns": [
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "No duration here" }] } } },
                                    { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Artist" }] } } }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }]
            }
          }
        }
        """.trimIndent()
    )

    // ------------------------------------------------------------------
    // parseHomeFeed
    // ------------------------------------------------------------------

    @Test
    fun `home feed collects VL trending playlists across shelves`() {
        val (playlists, _) = YoutubeTrendingFeedParser.parseHomeFeed(homeFeedFixture())

        assertEquals(3, playlists.size)
        assertEquals("VLRDCLAK5uy_playlist1", playlists[0].browseId)
        assertEquals("Pop Certified", playlists[0].title)
        assertEquals("Taylor Swift, Ariana Grande, Olivia Rodrigo, Bruno Mars", playlists[0].artists)
        assertEquals("https://example.com/p/544.jpg", playlists[0].artworkUrl)
        assertEquals("The Hit List", playlists[1].title)
        assertEquals("Also VL", playlists[2].title)
    }

    @Test
    fun `home feed skips non VL browse ids`() {
        val (playlists, _) = YoutubeTrendingFeedParser.parseHomeFeed(homeFeedFixture())

        // "Not a charts playlist" navigates to a channel browse id (UC…)
        assertTrue(playlists.none { it.title == "Not a charts playlist" })
    }

    @Test
    fun `home feed collects song shelf entries`() {
        val (_, songs) = YoutubeTrendingFeedParser.parseHomeFeed(homeFeedFixture())

        assertEquals(3, songs.size)
        val quickPick = songs[0]
        assertEquals("quickPick1", quickPick.videoId)
        assertEquals("Training Season", quickPick.title)
        assertEquals("Dua Lipa • 324m plays", quickPick.artist)
        assertEquals("https://example.com/t/544x544.jpg", quickPick.artworkUrl)
        assertEquals(0L, quickPick.durationSeconds)

        // The mixed shelf's responsive-list item is picked up too.
        assertEquals("mixedShelfSong", songs[2].videoId)
    }

    @Test
    fun `home feed without carousel shelves yields empty lists`() {
        val (playlists, songs) = YoutubeTrendingFeedParser.parseHomeFeed(
            JSONObject("""{"contents": {"unexpected": {"shape": true}}}""")
        )

        assertTrue(playlists.isEmpty())
        assertTrue(songs.isEmpty())
    }

    // ------------------------------------------------------------------
    // parsePlaylistTracks
    // ------------------------------------------------------------------

    @Test
    fun `playlist tracks map columns durations and artwork`() {
        val tracks = YoutubeTrendingFeedParser.parsePlaylistTracks(playlistFixture())

        assertEquals(3, tracks.size)
        assertEquals("track1", tracks[0].videoId)
        assertEquals("Sapphire", tracks[0].title)
        assertEquals("Ed Sheeran", tracks[0].artist)
        assertEquals(184L, tracks[0].durationSeconds)
        assertEquals("https://example.com/c/544x544.jpg", tracks[0].artworkUrl)

        assertEquals(3723L, tracks[1].durationSeconds)
    }

    @Test
    fun `playlist track without duration column parses with zero duration`() {
        val tracks = YoutubeTrendingFeedParser.parsePlaylistTracks(playlistFixture())

        assertEquals("track3", tracks[2].videoId)
        assertEquals(0L, tracks[2].durationSeconds)
    }

    @Test
    fun `playlist response without shelf yields empty list`() {
        val tracks = YoutubeTrendingFeedParser.parsePlaylistTracks(
            JSONObject("""{"contents": {}}""")
        )

        assertTrue(tracks.isEmpty())
    }

    // ------------------------------------------------------------------
    // parseDurationSeconds
    // ------------------------------------------------------------------

    @Test
    fun `duration labels parse to seconds`() {
        assertEquals(238L, YoutubeTrendingFeedParser.parseDurationSeconds("3:58"))
        assertEquals(0L, YoutubeTrendingFeedParser.parseDurationSeconds("0:00"))
        assertEquals(61L, YoutubeTrendingFeedParser.parseDurationSeconds("1:01"))
        assertEquals(3723L, YoutubeTrendingFeedParser.parseDurationSeconds("1:02:03"))
    }

    @Test
    fun `malformed duration labels parse to zero`() {
        assertEquals(0L, YoutubeTrendingFeedParser.parseDurationSeconds(null))
        assertEquals(0L, YoutubeTrendingFeedParser.parseDurationSeconds(""))
        assertEquals(0L, YoutubeTrendingFeedParser.parseDurationSeconds("bad"))
        assertEquals(0L, YoutubeTrendingFeedParser.parseDurationSeconds("59"))
        assertEquals(0L, YoutubeTrendingFeedParser.parseDurationSeconds("1:2:3:4"))
        assertEquals(0L, YoutubeTrendingFeedParser.parseDurationSeconds("m:ss"))
    }
}
