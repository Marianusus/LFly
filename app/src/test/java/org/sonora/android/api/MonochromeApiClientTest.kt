package org.sonora.android.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonochromeApiClientTest {

    @Test
    fun testMonochromeTrackParsingAndMatching() = runBlocking {
        val client = object : MonochromeApiClient() {
            override suspend fun searchTracks(
                query: String,
                limit: Int,
                baseUrl: String
            ): Result<List<MonochromeTrack>> {
                return Result.success(
                    listOf(
                        MonochromeTrack(
                            id = "170668574766026752",
                            trackId = "170668574766026752",
                            title = "So Bizzare - Extended Version",
                            artistNames = listOf("David DeeJay", "Dony"),
                            durationMs = 313821L,
                            isrc = "ROCMA0901943",
                            artwork = "https://tracks.monochrome.st/proxy/mi/sample.jpg",
                            playable = true
                        ),
                        MonochromeTrack(
                            id = "170668559192576000",
                            trackId = "170668559192576000",
                            title = "So Bizzare",
                            artistNames = listOf("David DeeJay", "Dony"),
                            durationMs = 199600L,
                            isrc = "ROCMA0901567",
                            artwork = "https://tracks.monochrome.st/proxy/mi/sample.jpg",
                            playable = true
                        )
                    )
                )
            }
        }

        // When resolving with "So Bizzare (Extended Version)"
        val streamResult = client.resolveStream(
            title = "So Bizzare (Extended Version)",
            artist = "David Deejay",
            preferredQuality = AudioQuality.ULTRA,
            durationMs = 313000L
        )

        assertTrue(streamResult.isSuccess)
        val stream = streamResult.getOrNull()
        assertEquals("https://api.monochrome.tf/track/170668574766026752", stream?.url)
        assertEquals("audio/flac", stream?.format)
        assertEquals(AudioQuality.ULTRA, stream?.quality)
        assertEquals(1411, stream?.bitrate)
        assertFalse(stream!!.isPreview)
        assertEquals("Monochrome HiFi", stream.provider)
    }

    @Test
    fun testNonMatchingArtistIsDisqualified() = runBlocking {
        val client = object : MonochromeApiClient() {
            override suspend fun searchTracks(
                query: String,
                limit: Int,
                baseUrl: String
            ): Result<List<MonochromeTrack>> {
                return Result.success(
                    listOf(
                        MonochromeTrack(
                            id = "cover123",
                            trackId = "cover123",
                            title = "Hello",
                            artistNames = listOf("Cover Band Tribute"),
                            durationMs = 295000L,
                            isrc = null,
                            artwork = null,
                            playable = true
                        )
                    )
                )
            }
        }

        // Searching for "Hello" by Adele when only "Cover Band Tribute" is returned
        val result = client.resolveStream("Hello", "Adele")
        // Must fail to resolve because artist does not match!
        assertFalse("Candidate with different artist must NOT be matched!", result.isSuccess)
    }

    @Test
    fun testIsrcDefinitiveMatching() = runBlocking {
        val client = object : MonochromeApiClient() {
            override suspend fun searchTracks(
                query: String,
                limit: Int,
                baseUrl: String
            ): Result<List<MonochromeTrack>> {
                return Result.success(
                    listOf(
                        MonochromeTrack(
                            id = "999888777",
                            trackId = "999888777",
                            title = "Different Title",
                            artistNames = listOf("Different Artist"),
                            durationMs = 180000L,
                            isrc = "USUM72401994",
                            artwork = null,
                            playable = true
                        )
                    )
                )
            }
        }

        val result = client.resolveStream(
            title = "Birds of a Feather",
            artist = "Billie Eilish",
            preferredQuality = AudioQuality.ULTRA,
            isrc = "USUM72401994"
        )

        assertTrue(result.isSuccess)
        val stream = result.getOrNull()
        assertNotNull(stream)
        assertEquals("https://api.monochrome.tf/track/999888777", stream?.url)
    }

    @Test
    fun testHealthReportingOnResolution() = runBlocking {
        var recordedSuccess = false
        var recordedLatency = 0L

        val client = object : MonochromeApiClient(
            healthCallback = { url, success, latency, error ->
                recordedSuccess = success
                recordedLatency = latency
            }
        ) {
            override suspend fun searchTracks(
                query: String,
                limit: Int,
                baseUrl: String
            ): Result<List<MonochromeTrack>> {
                return Result.success(
                    listOf(
                        MonochromeTrack(
                            id = "111222",
                            trackId = "111222",
                            title = "Blinding Lights",
                            artistNames = listOf("The Weeknd"),
                            durationMs = 200000L,
                            isrc = null,
                            artwork = null,
                            playable = true
                        )
                    )
                )
            }
        }

        val result = client.resolveStream("Blinding Lights", "The Weeknd")
        assertTrue(result.isSuccess)
        assertTrue(recordedSuccess)
        assertTrue(recordedLatency >= 0L)
    }
}
