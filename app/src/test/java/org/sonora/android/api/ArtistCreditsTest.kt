package org.sonora.android.api

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistCreditsTest {

    @Test
    fun textKeepsCommaNamesAndRAndBIntact() {
        val tyler = ArtistCredits.fromText("Tyler, The Creator")
        assertEquals(1, tyler.size)
        assertEquals("Tyler, The Creator", tyler[0].name)

        val earth = ArtistCredits.fromText("Earth, Wind & Fire")
        assertEquals(1, earth.size)
        assertEquals("Earth, Wind & Fire", earth[0].name)

        val rnb = ArtistCredits.fromText("R&B")
        assertEquals(1, rnb.size)
        assertEquals("R&B", rnb[0].name)

        val duo = ArtistCredits.fromText("Simon & Garfunkel")
        assertEquals(listOf("Simon & Garfunkel"), duo.map { it.name })

        val withBand = ArtistCredits.fromText("Sleeping with Sirens")
        assertEquals(listOf("Sleeping with Sirens"), withBand.map { it.name })

        // A real collaboration after a comma name still splits.
        val collab = ArtistCredits.fromText("Tyler, The Creator & Kali Uchis")
        assertEquals(listOf("Tyler, The Creator", "Kali Uchis"), collab.map { it.name })
    }

    @Test
    fun textSplitsOnlyOnStrongMarkers() {
        val feat = ArtistCredits.fromText("A feat. B & C")
        assertEquals(listOf("A", "B", "C"), feat.map { it.name })
        assertEquals(ArtistRole.PRIMARY, feat[0].role)
        assertEquals(ArtistRole.FEATURED, feat[1].role)
        assertEquals(ArtistRole.FEATURED, feat[2].role)

        val x = ArtistCredits.fromText("Artist One x Artist Two")
        assertEquals(listOf("Artist One", "Artist Two"), x.map { it.name })

        val slash = ArtistCredits.fromText("Artist One / Artist Two")
        assertEquals(listOf("Artist One", "Artist Two"), slash.map { it.name })

        val prod = ArtistCredits.fromText("Artist One prod. Producer Two")
        assertEquals(listOf("Artist One", "Producer Two"), prod.map { it.name })
        assertEquals(ArtistRole.PRODUCER, prod[1].role)
    }

    @Test
    fun textDoesNotSplitTrailingXOrSlashInsideNames() {
        assertEquals(listOf("Malcolm X"), ArtistCredits.fromText("Malcolm X").map { it.name })
        assertEquals(listOf("AC/DC"), ArtistCredits.fromText("AC/DC").map { it.name })
    }

    @Test
    fun runsCollectOnlyArtistPagesInOrder() {
        val json = """
            {"runs":[
              {"text":"Artist A","navigationEndpoint":{"browseEndpoint":{
                "browseId":"UCaaa",
                "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},
              {"text":" • "},
              {"text":"Artist B","navigationEndpoint":{"browseEndpoint":{
                "browseId":"UCbbb","canonicalBaseUrl":"/channel/UCbbb"}}},
              {"text":" • "},
              {"text":"Album Name","navigationEndpoint":{"browseEndpoint":{
                "browseId":"MPREb_album",
                "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}},
              {"text":" • "},
              {"text":"3:45"},
              {"text":"2.1B plays"}
            ]}
        """.trimIndent()

        val credits = ArtistCredits.fromRuns(JsonParser.parseString(json).asJsonObject)

        assertEquals(listOf("Artist A", "Artist B"), credits.map { it.name })
        assertEquals("UCaaa", credits[0].browseId)
        assertEquals("UCbbb", credits[1].browseId)
        assertEquals("UCbbb", credits[1].channelId)
    }

    @Test
    fun runsIgnoreNonArtistEndpointsAndSeparators() {
        val json = """
            {"runs":[
              {"text":"3:45"},
              {"text":"Song"},
              {"text":"Some Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_x"}}},
              {"text":"Separator"}
            ]}
        """.trimIndent()
        assertTrue(ArtistCredits.fromRuns(JsonParser.parseString(json).asJsonObject).isEmpty())
    }

    @Test
    fun mergeKeepsIdsAndLegacyArtist() {
        val existing = listOf(ArtistCredit("A", browseId = "UCa"))
        val incoming = listOf(ArtistCredit("B", channelId = "UCb"))

        val merged = ArtistCredits.merge(existing, incoming, legacyName = "A", legacyArtistId = "UClegacy")

        assertEquals(listOf("A", "B"), merged!!.map { it.name })
        assertEquals("UCa", merged[0].browseId)
        assertEquals("UCb", merged[1].channelId)
    }

    @Test
    fun mergeGraftsLegacyIdAndFallsBackToLegacyName() {
        val grafted = ArtistCredits.merge(
            existing = listOf(ArtistCredit("A")),
            incoming = null,
            legacyName = "A",
            legacyArtistId = "UCfromTrack"
        )!!
        assertEquals("UCfromTrack", grafted[0].channelId)

        val fromLegacy = ArtistCredits.merge(
            existing = null,
            incoming = null,
            legacyName = "Solo Artist",
            legacyArtistId = "UCsolo"
        )!!
        assertEquals(1, fromLegacy.size)
        assertEquals("Solo Artist", fromLegacy[0].name)
        assertEquals("UCsolo", fromLegacy[0].channelId)

        val structured = listOf(ArtistCredit("Artist A"), ArtistCredit("Artist B"))
        val noDuplicate = ArtistCredits.merge(
            existing = structured,
            incoming = null,
            legacyName = "Artist A, Artist B",
            legacyArtistId = "UCfirst"
        )
        assertEquals(listOf("Artist A", "Artist B"), noDuplicate!!.map { it.name })
        assertEquals("UCfirst", noDuplicate[0].browseId)

        assertNull(ArtistCredits.merge(null, null, null, null))
    }

    @Test
    fun helpersExposePrimaryDisplayAndNormalizedForms() {
        val credits = listOf(
            ArtistCredit("Earth, Wind & Fire", browseId = "UCearth"),
            ArtistCredit("Guest", channelId = "UCguest", role = ArtistRole.FEATURED)
        )
        assertEquals("Earth, Wind & Fire", ArtistCredits.primaryName(credits))
        assertEquals("Earth, Wind & Fire, Guest", ArtistCredits.displayName(credits))
        assertEquals("UCearth", ArtistCredits.firstArtistId(credits))
        assertEquals("earth wind and fire", credits[0].normalized)

        // "First with an id", not simply the first credit.
        assertEquals(
            "UCguest",
            ArtistCredits.firstArtistId(
                listOf(ArtistCredit("No Id"), ArtistCredit("Guest", channelId = "UCguest"))
            )
        )
    }
}
