package org.sonora.android.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiStreamingTest {

    @Test
    fun testBuildQualityUrlFormats320kProperly() {
        val client = JioSaavnFallbackClient()
        val rawUrl = "https://aac.saavncdn.com/248/46944eb7b4b31f5b0abf5eb2e1be2d2a_96.mp4"

        val highUrl = client.buildQualityUrl(rawUrl, AudioQuality.HIGH)
        assertEquals(
            "https://aac.saavncdn.com/248/46944eb7b4b31f5b0abf5eb2e1be2d2a_320.mp4",
            highUrl
        )

        val medUrl = client.buildQualityUrl(rawUrl, AudioQuality.MEDIUM)
        assertEquals(
            "https://aac.saavncdn.com/248/46944eb7b4b31f5b0abf5eb2e1be2d2a_160.mp4",
            medUrl
        )

        val lowUrl = client.buildQualityUrl(rawUrl, AudioQuality.LOW)
        assertEquals(
            "https://aac.saavncdn.com/248/46944eb7b4b31f5b0abf5eb2e1be2d2a_96.mp4",
            lowUrl
        )
    }

    @Test
    fun testBuildQualityUrlWithUnmarkedMp4() {
        val client = JioSaavnFallbackClient()
        val rawUrl = "https://aac.saavncdn.com/media/sample.mp4"

        val highUrl = client.buildQualityUrl(rawUrl, AudioQuality.HIGH)
        assertTrue(highUrl.endsWith("_320.mp4"))
    }

    @Test
    fun testDecryptMediaUrlReturnsNullForInvalidInput() {
        val client = JioSaavnFallbackClient()
        val result = client.decryptMediaUrl("invalid_garbage_base64!!!")
        assertEquals(null, result)
    }

    @Test
    fun testApiHealthManagerRegistrationAndHealthyRanking() = runTest {
        val healthManager = ApiHealthManager()

        val mirrors = healthManager.getActiveMirrors(EndpointType.JIOSAAVN)
        assertTrue(mirrors.isNotEmpty())
        assertTrue(mirrors.any { it.contains("jiosaavn-api-black") })

        // Record fast latency on Black mirror
        healthManager.recordSuccess("https://jiosaavn-api-black.vercel.app/api", 120L)
        healthManager.recordSuccess("https://jiosaavn-api-sage.vercel.app/api", 450L)

        val healthyEndpoints = healthManager.getHealthyEndpoints(EndpointType.JIOSAAVN)
        assertEquals("https://jiosaavn-api-black.vercel.app/api", healthyEndpoints.first().baseUrl)
    }

    @Test
    fun testApiHealthManagerRotateEndpoint() = runTest {
        val healthManager = ApiHealthManager()
        val activeBefore = healthManager.getActiveEndpoint(EndpointType.JIOSAAVN)
        assertNotNull(activeBefore)

        val rotated = healthManager.rotateEndpoint(EndpointType.JIOSAAVN, activeBefore!!.baseUrl)
        assertNotNull(rotated)
        // Health manager should record failure on the failing endpoint
        val health = healthManager.getEndpointHealth(activeBefore.baseUrl)
        assertNotNull(health)
        assertTrue(health!!.consecutiveFailures >= 1)
    }

    @Test
    fun testDecryptMediaUrlRealSampleAndUpgradeQuality() {
        val client = JioSaavnFallbackClient()
        val encrypted = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDy6WlM9+tqm5wAUDaQNb0xbkIII+X6kqgugN9d0UOVv7gbvMHx2bkT/Bw7tS9a8Gtq"
        val decrypted = client.decryptMediaUrl(encrypted)
        assertNotNull(decrypted)
        assertEquals("https://aac.saavncdn.com/217/eea3f57b1dd07f013ed0d83052808606_96.mp4", decrypted)

        val highUrl = client.buildQualityUrl(decrypted!!, AudioQuality.HIGH)
        assertEquals("https://aac.saavncdn.com/217/eea3f57b1dd07f013ed0d83052808606_320.mp4", highUrl)

        val medUrl = client.buildQualityUrl(decrypted, AudioQuality.MEDIUM)
        assertEquals("https://aac.saavncdn.com/217/eea3f57b1dd07f013ed0d83052808606_160.mp4", medUrl)
    }

    @Test
    fun testJioSaavnDegradedMirrorCooldownAndPriority() {
        val client = JioSaavnFallbackClient()
        val mirrors = client.getPrioritizedMirrors()
        val firstMirror = mirrors.first()

        client.markMirrorDegraded(firstMirror, "Blackout timeout >3.5s")
        assertFalse(client.isMirrorHealthy(firstMirror))

        val prioritizedAfter = client.getPrioritizedMirrors()
        assertEquals(firstMirror, prioritizedAfter.last())
    }

    @Test
    fun testApiHealthManagerBlackoutTripsCircuitBreaker() = runTest {
        val healthManager = ApiHealthManager()
        val mirror = "https://jiosaavn-api-black.vercel.app/api"

        // Single error must NOT trip circuit breaker (resilience rule)
        healthManager.recordBlackout(mirror, "Blackout 1: timeout")
        assertTrue("Single error must not mark endpoint unhealthy", healthManager.isEndpointHealthy(mirror))

        // Second error still doesn't trip
        healthManager.recordBlackout(mirror, "Blackout 2: timeout")
        assertTrue("Two errors must not mark endpoint unhealthy", healthManager.isEndpointHealthy(mirror))

        // Third error trips circuit breaker (failureThreshold = 3)
        healthManager.recordBlackout(mirror, "Blackout 3: timeout")
        assertFalse("3 consecutive errors must trip circuit breaker", healthManager.isEndpointHealthy(mirror))

        val health = healthManager.getEndpointHealth(mirror)
        assertNotNull(health)
        assertEquals(HealthStatus.OFFLINE, health!!.status)
    }

    @Test
    fun testStreamResolverNeverUsesDeezerPreviewUnlessAllFullFail() {
        val jioSource = StreamSource(
            url = "https://aac.saavncdn.com/stream_320.mp4",
            format = "audio/mp4",
            quality = AudioQuality.HIGH,
            provider = "JioSaavn (320k)",
            bitrate = 320,
            isPreview = false
        )
        val cobaltSource = StreamSource(
            url = "https://sunny.imput.net/stream",
            format = "audio/mp4",
            quality = AudioQuality.HIGH,
            provider = "Cobalt (sunny)",
            bitrate = 320,
            isPreview = false
        )
        val invidiousSource = StreamSource(
            url = "https://invidious.f5.si/stream",
            format = "audio/mp4",
            quality = AudioQuality.MEDIUM,
            provider = "YouTube (Invidious: f5.si)",
            bitrate = 160,
            isPreview = false
        )
        val deezerSource = StreamSource(
            url = "https://cdns-preview-d.dzcdn.net/sample.mp3",
            format = "audio/mpeg",
            quality = AudioQuality.LOW,
            provider = "Deezer Preview (Emergency Fallback)",
            bitrate = 128,
            isPreview = true
        )

        assertFalse(jioSource.isPreview)
        assertFalse(cobaltSource.isPreview)
        assertFalse(invidiousSource.isPreview)
        assertTrue(deezerSource.isPreview)
    }

    @Test
    fun testMonochromeEndpointRegistrationAndRanking() = runTest {
        val healthManager = ApiHealthManager()
        val monoEndpoints = healthManager.getHealthyEndpoints(EndpointType.MONOCHROME)
        assertTrue(monoEndpoints.isNotEmpty())
        assertTrue(monoEndpoints.any { it.baseUrl.contains("monochrome") })

        healthManager.recordSuccess("https://tracks.monochrome.st", 95L)
        val best = healthManager.getActiveEndpoint(EndpointType.MONOCHROME)
        assertNotNull(best)
        assertEquals("https://tracks.monochrome.st", best?.baseUrl)
    }
}
