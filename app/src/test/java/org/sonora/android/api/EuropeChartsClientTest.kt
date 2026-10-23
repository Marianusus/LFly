package org.sonora.android.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EuropeChartsClientTest {

    private val italy = EuropeanCountry("it", "it", "Italia")

    @Test
    fun parseAppleChartEntry_readsFirstResult() {
        val json = """
            {"feed":{"results":[
              {"name":"Lontano","artistName":"Sfera Ebbasta","artworkUrl100":"https://example.com/it.jpg"}
            ]}}
        """.trimIndent()

        val entry = EuropeChartsClient.parseAppleChartEntry(json)

        assertNotNull(entry)
        assertEquals("Lontano", entry!!.title)
        assertEquals("Sfera Ebbasta", entry.artist)
        assertEquals("https://example.com/it.jpg", entry.artworkUrl)
    }

    @Test
    fun parseAppleChartEntries_keepsOrderAndSkipsInvalidRows() {
        val json = """
            {"feed":{"results":[
              {"name":"Uno","artistName":"A"},
              {"name":"","artistName":"Skip"},
              {"name":"Due","artistName":"B"},
              {"artistName":"Skip"}
            ]}}
        """.trimIndent()

        val entries = EuropeChartsClient.parseAppleChartEntries(json)

        assertEquals(2, entries.size)
        assertEquals("Uno", entries[0].title)
        assertEquals("Due", entries[1].title)
    }

    @Test
    fun parseAppleChartEntry_fallsBackTo60pxArtwork() {
        val json = """{"feed":{"results":[{"name":"A","artistName":"B","artworkUrl60":"https://example.com/s.jpg"}]}}"""

        val entry = EuropeChartsClient.parseAppleChartEntry(json)

        assertEquals("https://example.com/s.jpg", entry?.artworkUrl)
    }

    @Test
    fun parseAppleChartEntry_returnsNullOnEmptyOrMalformed() {
        assertNull(EuropeChartsClient.parseAppleChartEntry("""{"feed":{"results":[]}}"""))
        assertNull(EuropeChartsClient.parseAppleChartEntry("""{"feed":{}}"""))
        assertNull(EuropeChartsClient.parseAppleChartEntry("not json"))
        assertNull(EuropeChartsClient.parseAppleChartEntry("""{"feed":{"results":[{"name":"","artistName":""}]}}"""))
    }

    @Test
    fun buildTrack_isStableAndPlayableByTitle() {
        val entry = AppleChartEntry("Lontano", "Sfera Ebbasta", "https://example.com/it.jpg")

        val a = EuropeChartsClient.buildTrack(italy, entry)
        val b = EuropeChartsClient.buildTrack(italy, entry)

        assertTrue("id must be prefixed with the country", a.id.startsWith("eu_it_"))
        assertEquals("stable id across rebuilds", a.id, b.id)
        assertEquals("Lontano", a.title)
        assertEquals("Sfera Ebbasta", a.artist)
        assertEquals(EuropeChartsClient.PROVIDER, a.provider)
        assertNull("no stream url; resolved lazily", a.streamUrl)
        assertEquals(listOf("Sfera Ebbasta"), a.artists?.map { it.name })
    }

    @Test
    fun buildTrack_keepsCommaBandNamesIntact() {
        val entry = AppleChartEntry("September", "Earth, Wind & Fire", null)
        val track = EuropeChartsClient.buildTrack(italy, entry)
        assertEquals(listOf("Earth, Wind & Fire"), track.artists?.map { it.name })
    }

    @Test
    fun buildTrack_differsAcrossCountries() {
        val entry = AppleChartEntry("Same", "Artist", null)
        val it = EuropeChartsClient.buildTrack(italy, entry)
        val de = EuropeChartsClient.buildTrack(EuropeanCountry("de", "de", "Germania"), entry)
        assertTrue(it.id != de.id)
    }

    @Test
    fun highResArtwork_upscalesAppleArtwork() {
        val low = "https://is1-ssl.mzstatic.com/image/thumb/Music/a/b/c/cover.jpg/100x100bb.jpg"
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/a/b/c/cover.jpg/1000x1000bb.jpg",
            EuropeChartsClient.highResArtwork(low)
        )
        assertNull(EuropeChartsClient.highResArtwork(null))
        assertNull(EuropeChartsClient.highResArtwork(""))
    }

    @Test
    fun buildTrack_usesHighResArtwork() {
        val entry = AppleChartEntry(
            "Lontano",
            "Sfera Ebbasta",
            "https://is1-ssl.mzstatic.com/image/thumb/Music/a/b/c/cover.jpg/100x100bb.jpg"
        )
        val track = EuropeChartsClient.buildTrack(italy, entry)
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/a/b/c/cover.jpg/1000x1000bb.jpg",
            track.coverUrl
        )
    }

    @Test
    fun flagUrl_pointsToFlagCdn() {
        assertEquals("https://flagcdn.com/w80/it.png", EuropeChartsClient.flagUrl("it", 80))
        assertEquals("https://flagcdn.com/w160/gb.png", EuropeChartsClient.flagUrl("GB"))
    }

    @Test
    fun countries_haveUniqueCodes() {
        val codes = EuropeChartsClient.COUNTRIES.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }
}
