package org.sonora.android.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class StreamResolverTest {

    private lateinit var fakeHealthManager: ApiHealthManager
    private lateinit var fakeMonochrome: FakeMonochromeApiClient
    private lateinit var fakeJioSaavn: FakeJioSaavnFallbackClient
    private lateinit var fakeCobalt: FakeCobaltAudioResolver
    private lateinit var fakeInvidious: FakeInvidiousStreamResolver
    private lateinit var fakePiped: FakePipedInvidiousClient
    private lateinit var fakeDeezer: FakeDeezerClient
    private lateinit var resolver: StreamResolver

    private val testTrack = Track(
        id = "track_123",
        title = "Blinding Lights",
        artist = "The Weeknd",
        album = "After Hours",
        durationMs = 200_000L,
        coverUrl = "https://example.com/cover.jpg",
        streamUrl = "https://cdns-preview-d.dzcdn.net/stream/sample.mp3",
        provider = "Deezer"
    )

    @Before
    fun setUp() {
        fakeHealthManager = ApiHealthManager()
        fakeMonochrome = FakeMonochromeApiClient()
        fakeJioSaavn = FakeJioSaavnFallbackClient()
        fakeCobalt = FakeCobaltAudioResolver()
        fakeInvidious = FakeInvidiousStreamResolver()
        fakePiped = FakePipedInvidiousClient()
        fakeDeezer = FakeDeezerClient()

        resolver = StreamResolver(
            apiHealthManager = fakeHealthManager,
            monochromeApiClient = fakeMonochrome,
            jioSaavnFallbackClient = fakeJioSaavn,
            cobaltAudioResolver = fakeCobalt,
            invidiousStreamResolver = fakeInvidious,
            pipedInvidiousClient = fakePiped,
            deezerClient = fakeDeezer
        )
    }

    @Test
    fun testMonochromeFlacPrioritizedAsTier1() = runBlocking {
        // Given: Monochrome succeeds with bit-perfect FLAC stream
        fakeMonochrome.resultToReturn = Result.success(
            StreamSource(
                url = "https://tracks.monochrome.st/track/12345",
                format = "audio/flac",
                quality = AudioQuality.ULTRA,
                provider = "Monochrome HiFi",
                bitrate = 1411,
                isPreview = false
            )
        )

        // When
        val resolved = resolver.resolveStream(testTrack)

        // Then: Tier 1 Monochrome is prioritized
        assertNotNull(resolved)
        assertEquals("https://tracks.monochrome.st/track/12345", resolved?.url)
        assertEquals(1411, resolved?.bitrate)
        assertFalse(resolved!!.isPreview)
        assertTrue(resolved.provider.contains("Monochrome"))
        assertEquals(1, fakeMonochrome.resolveCount)
        assertEquals("Downstream tiers must not be invoked", 0, fakeJioSaavn.resolveCount)
    }

    @Test
    fun testSaavn320kPrioritizedOverPreview() = runBlocking {
        // Given: Track has a 30s Deezer preview in its streamUrl, but JioSaavn resolves 320k full stream
        fakeJioSaavn.resultToReturn = Result.success(
            StreamSource(
                url = "https://aac.saavncdn.com/123/blinding_lights_320.mp4",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "JioSaavn (320k)",
                bitrate = 320,
                isPreview = false
            )
        )

        // When
        val resolved = resolver.resolveStream(testTrack)

        // Then: The 320k full stream must be prioritized over the 30s Deezer preview
        assertNotNull("Resolved stream should not be null", resolved)
        assertEquals("https://aac.saavncdn.com/123/blinding_lights_320.mp4", resolved?.url)
        assertEquals(320, resolved?.bitrate)
        assertFalse("Must NOT be marked as preview", resolved!!.isPreview)
        assertTrue("Provider should be JioSaavn", resolved.provider.contains("JioSaavn"))
        assertEquals("Tier 1 succeeded, downstream resolvers should not be called", 0, fakeCobalt.resolveCount)
    }

    @Test
    fun testFailoverRotatesToCobaltWhenSaavnFails() = runBlocking {
        // Given: JioSaavn fails, but Cobalt succeeds with high-bitrate stream
        fakeJioSaavn.resultToReturn = Result.failure(IOException("JioSaavn blackout / timeout"))
        fakeCobalt.resultToReturn = Result.success(
            StreamSource(
                url = "https://cobalt.stream.org/audio.opus",
                format = "audio/ogg",
                quality = AudioQuality.HIGH,
                provider = "Cobalt Extractor",
                bitrate = 256,
                isPreview = false
            )
        )

        // When
        val resolved = resolver.resolveStream(testTrack)

        // Then: Resolver automatically rotated to Tier 2 (Cobalt)
        assertNotNull(resolved)
        assertEquals("https://cobalt.stream.org/audio.opus", resolved?.url)
        assertFalse(resolved!!.isPreview)
        assertTrue(resolved.provider.contains("Cobalt"))
        assertEquals(1, fakeJioSaavn.resolveCount)
        assertEquals(1, fakeCobalt.resolveCount)
        assertEquals(0, fakeInvidious.resolveCount)
    }

    @Test
    fun testFailoverRotatesToInvidiousWhenSaavnAndCobaltFail() = runBlocking {
        // Given: Tier 1 & 2 fail, Invidious succeeds
        fakeJioSaavn.resultToReturn = Result.failure(IOException("JioSaavn down"))
        fakeCobalt.resultToReturn = Result.failure(IOException("Cobalt down"))
        fakeInvidious.resultToReturn = Result.success(
            StreamSource(
                url = "https://rr2---sn.googlevideo.com/videoplayback?id=123",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "Invidious Dedicated",
                bitrate = 160,
                isPreview = false
            )
        )

        // When
        val resolved = resolver.resolveStream(testTrack)

        // Then: Resolver rotated to Tier 3 (Invidious)
        assertNotNull(resolved)
        assertEquals("https://rr2---sn.googlevideo.com/videoplayback?id=123", resolved?.url)
        assertFalse(resolved!!.isPreview)
        assertTrue(resolved.provider.contains("Invidious"))
        assertEquals(1, fakeJioSaavn.resolveCount)
        assertEquals(1, fakeCobalt.resolveCount)
        assertEquals(1, fakeInvidious.resolveCount)
        assertEquals(0, fakePiped.resolveCount)
    }

    @Test
    fun testFailoverRotatesToPipedWhenTiers1To3Fail() = runBlocking {
        // Given: Tiers 1, 2, 3 fail, Piped succeeds
        fakeJioSaavn.resultToReturn = Result.failure(IOException("JioSaavn down"))
        fakeCobalt.resultToReturn = Result.failure(IOException("Cobalt down"))
        fakeInvidious.resultToReturn = Result.failure(IOException("Invidious down"))
        fakePiped.resultToReturn = Result.success(
            StreamSource(
                url = "https://pipedproxy.kavin.rocks/audio.m4a",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "Piped YouTube",
                bitrate = 128,
                isPreview = false
            )
        )

        // When
        val resolved = resolver.resolveStream(testTrack)

        // Then: Resolver rotated to Tier 4 (Piped)
        assertNotNull(resolved)
        assertEquals("https://pipedproxy.kavin.rocks/audio.m4a", resolved?.url)
        assertFalse(resolved!!.isPreview)
        assertTrue(resolved.provider.contains("Piped"))
        assertEquals(1, fakeJioSaavn.resolveCount)
        assertEquals(1, fakeCobalt.resolveCount)
        assertEquals(1, fakeInvidious.resolveCount)
        assertEquals(1, fakePiped.resolveCount)
    }

    @Test
    fun testNeverFallsBackToPreviewWhenAllFullProvidersFail() = runBlocking {
        // Given: Every full-length audio stream provider fails
        fakeJioSaavn.resultToReturn = Result.failure(IOException("Saavn down"))
        fakeCobalt.resultToReturn = Result.failure(IOException("Cobalt down"))
        fakeInvidious.resultToReturn = Result.failure(IOException("Invidious down"))
        fakePiped.resultToReturn = Result.failure(IOException("Piped down"))

        // When
        val resolved = resolver.resolveStream(testTrack)

        // Then: Must NEVER return 30s Deezer preview! Must return null
        org.junit.Assert.assertNull("Must not fall back to 30s preview; only full streams allowed", resolved)
    }

    @Test
    fun testStreamCachingAvoidsDuplicateUpstreamCalls() = runBlocking {
        // Given
        val trackWithoutCache = testTrack.copy(id = "unique_cached_track_999")
        fakeJioSaavn.resultToReturn = Result.success(
            StreamSource(
                url = "https://aac.saavncdn.com/cached_stream_320.mp4",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "JioSaavn",
                bitrate = 320,
                isPreview = false
            )
        )

        // When: Resolve twice
        val first = resolver.resolveStream(trackWithoutCache)
        val second = resolver.resolveStream(trackWithoutCache)

        // Then
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first?.url, second?.url)
        assertEquals("Upstream resolver should only be called once due to cache", 1, fakeJioSaavn.resolveCount)
    }

    @Test
    fun testYouTubeTrackFallsBackToInvidiousLastWhenOthersFail() = runBlocking {
        // Given: Track with YouTube ID and provider
        val ytTrack = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            album = "YouTube",
            durationMs = 213_000L,
            coverUrl = "https://example.com/rick.jpg",
            streamUrl = null,
            provider = "YouTube"
        )

        fakeInvidious.videoIdResultToReturn = Result.success(
            StreamSource(
                url = "https://invidious.nerdvpn.de/latest_version?id=dQw4w9WgXcQ&itag=140",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "YouTube (Invidious: nerdvpn.de)",
                bitrate = 128,
                isPreview = false
            )
        )
        resolver.dynamicStreamProber = { !it.contains("403") && !it.contains("fail") }
        // Deterministic no-match from Monochrome/JioSaavn and no YouTube Music VISIONOS network call
        fakeMonochrome.resultToReturn = Result.failure(IOException("No matching track found on Monochrome"))
        fakeJioSaavn.resultToReturn = Result.failure(IOException("JioSaavn not found"))
        resolver.youTubeMusicResolverOverride = { null }

        // When
        val resolved = resolver.resolveStream(ytTrack)

        // Then: Monochrome is attempted first, and Invidious resolves as the last fallback
        assertNotNull(resolved)
        assertEquals("https://invidious.nerdvpn.de/latest_version?id=dQw4w9WgXcQ&itag=140", resolved?.url)
        assertEquals(1, fakeInvidious.resolveByVideoIdCount)
        assertTrue("Monochrome must be attempted before Invidious", fakeMonochrome.resolveCount >= 1)
    }

    @Test
    fun testYouTubeTrackWith403DirectStreamFallsBackToMonochromeTier1() = runBlocking {
        // Given: YouTube track whose direct GoogleVideo stream has IP binding (&ip=)
        val ytTrack = Track(
            id = "yt_403_video",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "YouTube",
            durationMs = 200_000L,
            coverUrl = "https://example.com/cover.jpg",
            streamUrl = null,
            provider = "YouTube"
        )

        // Direct Invidious returns 403 GoogleVideo URL with IP binding
        fakeInvidious.videoIdResultToReturn = Result.success(
            StreamSource(
                url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=9999&ip=123.45.67.89&id=403_video",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "Invidious Dedicated",
                bitrate = 160,
                isPreview = false
            )
        )
        fakeCobalt.videoIdResultToReturn = Result.failure(IOException("Cobalt rate limit"))

        // Monochrome Tier 1 succeeds with Lossless FLAC
        fakeMonochrome.resultToReturn = Result.success(
            StreamSource(
                url = "https://tracks.monochrome.st/track/12345",
                format = "audio/flac",
                quality = AudioQuality.ULTRA,
                provider = "Monochrome HiFi",
                bitrate = 1411,
                isPreview = false
            )
        )

        // When
        val resolved = resolver.resolveStream(ytTrack)

        // Then: StreamResolver detected 403 GoogleVideo IP binding and seamlessly fell back to Tier 1 Monochrome!
        assertNotNull("Resolved stream must not be null", resolved)
        assertEquals("https://tracks.monochrome.st/track/12345", resolved?.url)
        assertEquals(1411, resolved?.bitrate)
        assertTrue("Provider must be Monochrome HiFi", resolved!!.provider.contains("Monochrome"))
        assertEquals("Tier 1 Monochrome must have resolved the track", 1, fakeMonochrome.resolveCount)
    }

    @Test
    fun testYouTubeTrackWith403DirectStreamFallsBackToSaavnTier2WhenTier1Fails() = runBlocking {
        // Given: YouTube track whose direct GoogleVideo stream fails with 403, Tier 1 is down, Tier 2 succeeds
        val ytTrack = Track(
            id = "yt_fallback_saavn",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "YouTube",
            durationMs = 200_000L,
            coverUrl = "https://example.com/cover.jpg",
            streamUrl = null,
            provider = "YouTube"
        )

        fakeInvidious.videoIdResultToReturn = Result.success(
            StreamSource(
                url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=9999&ip=1.2.3.4&id=fail",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "Invidious Dedicated",
                bitrate = 160,
                isPreview = false
            )
        )
        fakeCobalt.videoIdResultToReturn = Result.failure(IOException("Cobalt down"))
        fakeMonochrome.resultToReturn = Result.failure(IOException("Monochrome blackout"))
        fakeJioSaavn.resultToReturn = Result.success(
            StreamSource(
                url = "https://aac.saavncdn.com/123/blinding_lights_320.mp4",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "JioSaavn (320k)",
                bitrate = 320,
                isPreview = false
            )
        )

        // When
        val resolved = resolver.resolveStream(ytTrack)

        // Then: Seamless fallback to JioSaavn 320k
        assertNotNull(resolved)
        assertEquals("https://aac.saavncdn.com/123/blinding_lights_320.mp4", resolved?.url)
        assertEquals(320, resolved?.bitrate)
        assertTrue(resolved!!.provider.contains("JioSaavn"))
        assertEquals(1, fakeJioSaavn.resolveCount)
    }

    @Test
    fun testYouTubeTrackSanitizesTitleAndArtistOnFallback() = runBlocking {
        // Given: YouTube track with messy title typical of YouTube videos
        val ytTrack = Track(
            id = "yt_messy_video",
            title = "The Weeknd - Blinding Lights (Official Music Video)",
            artist = "The Weeknd",
            album = "YouTube",
            durationMs = 200_000L,
            coverUrl = "https://example.com/cover.jpg",
            streamUrl = null,
            provider = "YouTube"
        )

        fakeInvidious.videoIdResultToReturn = Result.failure(IOException("Invidious 403"))
        fakeCobalt.videoIdResultToReturn = Result.failure(IOException("Cobalt 403"))
        fakeMonochrome.resultToReturn = Result.success(
            StreamSource(
                url = "https://tracks.monochrome.st/track/12345",
                format = "audio/flac",
                quality = AudioQuality.ULTRA,
                provider = "Monochrome HiFi",
                bitrate = 1411,
                isPreview = false
            )
        )

        // When
        val resolved = resolver.resolveStream(ytTrack)

        // Then: Sanitized title and artist were sent to Tier 1
        assertNotNull(resolved)
        assertEquals("Blinding Lights", fakeMonochrome.lastTitleQueried)
        assertEquals("The Weeknd", fakeMonochrome.lastArtistQueried)
    }

    @Test
    fun testEnsureStreamResolverNeverReturns403Url() = runBlocking {
        // Given: Track where all resolvers return direct IP-bound 403 URLs
        val ytTrack = Track(
            id = "yt_blocked_track",
            title = "Unknown Song",
            artist = "Unknown Artist",
            album = "YouTube",
            durationMs = 180_000L,
            coverUrl = "https://example.com/cover.jpg",
            streamUrl = null,
            provider = "YouTube"
        )

        fakeInvidious.videoIdResultToReturn = Result.success(
            StreamSource(
                url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=9999&ip=1.1.1.1&id=fail",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "Invidious",
                bitrate = 128,
                isPreview = false
            )
        )
        fakeCobalt.videoIdResultToReturn = Result.failure(IOException("Cobalt down"))
        fakeMonochrome.resultToReturn = Result.failure(IOException("Monochrome not found"))
        fakeJioSaavn.resultToReturn = Result.failure(IOException("Saavn not found"))
        fakeInvidious.resultToReturn = Result.success(
            StreamSource(
                url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=9999&ip=1.1.1.1&id=fail",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "Invidious",
                bitrate = 128,
                isPreview = false
            )
        )
        fakePiped.resultToReturn = Result.failure(IOException("Piped not found"))

        // When
        val resolved = resolver.resolveStream(ytTrack)

        // Then: Must NEVER return a 403-failing URL; must return null
        org.junit.Assert.assertNull("StreamResolver must NEVER return a 403-failing URL", resolved)
    }

    @Test
    fun testYouTubeTrackMonochromeMatchPreferredBeforeVisionOs() = runBlocking {
        val ytTrack = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            album = "YouTube",
            durationMs = 213_000L,
            coverUrl = null,
            streamUrl = null,
            provider = "YouTube"
        )
        fakeMonochrome.resultToReturn = Result.success(
            StreamSource(
                url = "https://tracks.monochrome.st/track/12345",
                format = "audio/flac",
                quality = AudioQuality.ULTRA,
                provider = "Monochrome HiFi",
                bitrate = 1411,
                isPreview = false
            )
        )
        var visionCalled = false
        resolver.youTubeMusicResolverOverride = {
            visionCalled = true
            StreamSource(
                url = "https://example.com/visionos_stream.mp3",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "YouTube Music",
                bitrate = 131,
                isPreview = false
            )
        }

        val resolved = resolver.resolveStream(ytTrack)

        assertNotNull(resolved)
        assertTrue("Reliable Monochrome match must win", resolved!!.provider.contains("Monochrome"))
        assertFalse("VISIONOS must not be reached when Monochrome matches", visionCalled)
    }

    @Test
    fun testYouTubeTrackVisionOsTriedBeforeJioSaavnWhenNoMonochromeMatch() = runBlocking {
        val ytTrack = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Players Club '26",
            artist = "Night Skinny",
            album = "YouTube",
            durationMs = 304_000L,
            coverUrl = null,
            streamUrl = null,
            provider = "YouTube"
        )
        fakeMonochrome.resultToReturn = Result.failure(IOException("No matching track found on Monochrome (best score 40 < 75)"))
        resolver.youTubeMusicResolverOverride = {
            StreamSource(
                url = "https://example.com/visionos_stream.mp3",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "YouTube Music",
                bitrate = 131,
                isPreview = false
            )
        }
        fakeJioSaavn.resultToReturn = Result.success(
            StreamSource(
                url = "https://aac.saavncdn.com/123/players_club_320.mp4",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "JioSaavn (320k)",
                bitrate = 320,
                isPreview = false
            )
        )

        val resolved = resolver.resolveStream(ytTrack)

        assertNotNull(resolved)
        assertTrue("VISIONOS must resolve before JioSaavn", resolved!!.provider.contains("YouTube Music"))
        assertEquals("JioSaavn must not be called once VISIONOS resolves", 0, fakeJioSaavn.resolveCount)
    }

    @Test
    fun testSpotifyReliableYtMatchUsesVisionOsAndKeepsStableTrack() = runBlocking {
        val track = Track(
            id = "spotify_abc123",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "Album",
            durationMs = 200_000L,
            coverUrl = null,
            streamUrl = null,
            provider = "Spotify"
        )
        fakeMonochrome.resultToReturn = Result.failure(IOException("no match"))
        val match = Track(
            id = "yt_dQw4w9WgXcQ",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "YouTube",
            durationMs = 201_000L,
            coverUrl = null,
            streamUrl = null,
            provider = "YouTube"
        )
        resolver.youTubeSearchOverride = { _, _ -> listOf(match) }
        var visionCalled = false
        resolver.youTubeMusicResolverOverride = {
            visionCalled = true
            StreamSource(
                url = "https://example.com/visionos_stream.mp3",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "YouTube Music",
                bitrate = 131,
                isPreview = false
            )
        }

        val resolved = resolver.resolveStream(track)

        assertNotNull(resolved)
        assertTrue(resolved!!.provider.contains("YouTube Music"))
        assertTrue("VISIONOS must be used with a reliable YT match", visionCalled)
        assertEquals("Spotify stable id must be untouched", "spotify_abc123", track.id)
        assertEquals("Spotify", track.provider)
        assertEquals("JioSaavn must not be reached", 0, fakeJioSaavn.resolveCount)
    }

    @Test
    fun testSpotifyAmbiguousYtMatchFallsBackAndNeverUsesWrongSong() = runBlocking {
        val track = Track(
            id = "spotify_abc123",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "Album",
            durationMs = 200_000L,
            coverUrl = null,
            streamUrl = null,
            provider = "Spotify"
        )
        fakeMonochrome.resultToReturn = Result.failure(IOException("no match"))
        // Wrong artist -> must be rejected
        val wrongArtist = Track(
            id = "yt_wrongartist",
            title = "Blinding Lights",
            artist = "Cover Band",
            album = "YouTube",
            durationMs = 200_000L,
            coverUrl = null,
            streamUrl = null,
            provider = "YouTube"
        )
        // Right artist but duration off by > 8s -> must be rejected
        val wrongDuration = Track(
            id = "yt_wrongduration",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "YouTube",
            durationMs = 260_000L,
            coverUrl = null,
            streamUrl = null,
            provider = "YouTube"
        )
        resolver.youTubeSearchOverride = { _, _ -> listOf(wrongArtist, wrongDuration) }
        var visionCalled = false
        resolver.youTubeMusicResolverOverride = {
            visionCalled = true
            StreamSource(
                url = "https://example.com/wrong.mp3",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "YouTube Music",
                bitrate = 1,
                isPreview = false
            )
        }
        fakeJioSaavn.resultToReturn = Result.success(
            StreamSource(
                url = "https://aac.saavncdn.com/123/blinding_lights_320.mp4",
                format = "audio/mp4",
                quality = AudioQuality.HIGH,
                provider = "JioSaavn (320k)",
                bitrate = 320,
                isPreview = false
            )
        )

        val resolved = resolver.resolveStream(track)

        assertNotNull(resolved)
        assertTrue("Must fall back to JioSaavn", resolved!!.provider.contains("JioSaavn"))
        assertFalse("Ambiguous YT match must never be used", visionCalled)
        assertEquals(1, fakeJioSaavn.resolveCount)
        assertEquals("spotify_abc123", track.id)
    }

    // ==========================================
    // Fakes
    // ==========================================

    class FakeMonochromeApiClient : MonochromeApiClient() {
        var resultToReturn: Result<StreamSource>? = null
        var resolveCount = 0
        var lastTitleQueried: String? = null
        var lastArtistQueried: String? = null

        override suspend fun resolveStream(
            title: String,
            artist: String,
            preferredQuality: AudioQuality,
            durationMs: Long?,
            isrc: String?
        ): Result<StreamSource> {
            resolveCount++
            lastTitleQueried = title
            lastArtistQueried = artist
            return resultToReturn ?: Result.failure(IOException("Monochrome unavailable"))
        }
    }

    class FakeJioSaavnFallbackClient : JioSaavnFallbackClient() {
        var resultToReturn: Result<StreamSource>? = null
        var resolveCount = 0
        var lastTitleQueried: String? = null
        var lastArtistQueried: String? = null

        override suspend fun resolveStream(
            title: String,
            artist: String,
            preferredQuality: AudioQuality
        ): Result<StreamSource> {
            resolveCount++
            lastTitleQueried = title
            lastArtistQueried = artist
            return resultToReturn ?: Result.failure(IOException("Saavn unavailable"))
        }
    }

    class FakeCobaltAudioResolver : CobaltAudioResolver() {
        var resultToReturn: Result<StreamSource>? = null
        var videoIdResultToReturn: Result<StreamSource>? = null
        var resolveCount = 0
        var resolveByVideoIdCount = 0

        override suspend fun resolveStream(
            title: String,
            artist: String,
            preferredQuality: AudioQuality,
            durationMs: Long?
        ): Result<StreamSource> {
            resolveCount++
            return resultToReturn ?: Result.failure(IOException("Cobalt unavailable"))
        }

        override suspend fun resolveByVideoId(
            videoId: String,
            preferredQuality: AudioQuality
        ): Result<StreamSource> {
            resolveByVideoIdCount++
            return videoIdResultToReturn ?: resultToReturn ?: Result.failure(IOException("Cobalt unavailable"))
        }
    }

    class FakeInvidiousStreamResolver : InvidiousStreamResolver() {
        var resultToReturn: Result<StreamSource>? = null
        var videoIdResultToReturn: Result<StreamSource>? = null
        var resolveCount = 0
        var resolveByVideoIdCount = 0

        override suspend fun resolveStream(
            title: String,
            artist: String,
            preferredQuality: AudioQuality,
            durationMs: Long?
        ): Result<StreamSource> {
            resolveCount++
            return resultToReturn ?: Result.failure(IOException("Invidious unavailable"))
        }

        override suspend fun resolveByVideoId(
            videoId: String,
            preferredQuality: AudioQuality
        ): Result<StreamSource> {
            resolveByVideoIdCount++
            return videoIdResultToReturn ?: resultToReturn ?: Result.failure(IOException("Invidious unavailable"))
        }
    }

    class FakePipedInvidiousClient : PipedInvidiousClient() {
        var resultToReturn: Result<StreamSource>? = null
        var resolveCount = 0

        override suspend fun searchAndResolve(
            title: String,
            artist: String,
            durationMs: Long?
        ): Result<StreamSource> {
            resolveCount++
            return resultToReturn ?: Result.failure(IOException("Piped unavailable"))
        }
    }

    class FakeDeezerClient : DeezerClient() {
        var previewToReturn: String? = null

        override suspend fun searchTracks(query: String, limit: Int): Result<List<Track>> {
            val preview = previewToReturn
            return if (preview != null) {
                Result.success(listOf(
                    Track(
                        id = "preview_1",
                        title = "Test",
                        artist = "Test Artist",
                        album = "Test Album",
                        durationMs = 30_000L,
                        coverUrl = null,
                        streamUrl = preview,
                        provider = "Deezer"
                    )
                ))
            } else {
                Result.success(emptyList())
            }
        }
    }
}
