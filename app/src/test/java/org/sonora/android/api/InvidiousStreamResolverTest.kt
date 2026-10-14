package org.sonora.android.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvidiousStreamResolverTest {

    @Test
    fun testProxyStreamUrlFormatAvoidsGoogleVideoIpBinding() = runBlocking {
        val resolver = InvidiousStreamResolver(
            instances = listOf("https://test-invidious.example.com")
        )

        // Mock stream verifier to accept test proxy URLs
        resolver.streamVerifier = { url ->
            url.contains("/latest_version?id=") && !url.contains("403")
        }

        val result = resolver.resolveByVideoId("dQw4w9WgXcQ")
        assertTrue(result.isSuccess)
        val stream = result.getOrNull()
        assertNotNull(stream)

        // Verify that URL is an Invidious proxy stream URL and NOT a raw GoogleVideo URL
        val url = stream!!.url
        assertTrue("URL must be an Invidious proxy stream endpoint", url.contains("/latest_version?id=dQw4w9WgXcQ"))
        assertTrue("URL must use proxy itag 140 or 18", url.contains("itag=140") || url.contains("itag=18"))
        assertFalse("URL must NOT be a direct googlevideo URL with server IP binding", url.contains("googlevideo.com"))
        assertFalse(stream.isPreview)
    }

    @Test
    fun testBlackoutRotationWhenInstanceReturns403() = runBlocking {
        val instance1 = "https://failing-invidious.example.com"
        val instance2 = "https://healthy-invidious.example.com"
        val resolver = InvidiousStreamResolver(
            instances = listOf(instance1, instance2)
        )

        // instance1 returns 403 / unplayable, instance2 succeeds
        resolver.streamVerifier = { url ->
            if (url.contains("failing-invidious")) false
            else url.contains("healthy-invidious")
        }

        val result = resolver.resolveByVideoId("dQw4w9WgXcQ")
        assertTrue(result.isSuccess)
        val stream = result.getOrNull()
        assertNotNull(stream)
        assertTrue(
            "Resolver must rotate from failing instance to healthy instance",
            stream!!.url.startsWith(instance2)
        )
    }

    @Test
    fun testStreamVerifierDetectsInvalidUrls() {
        val resolver = InvidiousStreamResolver()
        assertFalse("Blank URL must not be playable", resolver.isStreamPlayable(""))
    }
}
