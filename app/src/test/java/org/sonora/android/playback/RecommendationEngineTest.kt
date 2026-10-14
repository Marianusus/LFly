package org.sonora.android.playback

import kotlinx.coroutines.runBlocking
import org.sonora.android.api.AudioQuality
import org.sonora.android.api.StreamResolver
import org.sonora.android.api.StreamSource
import org.sonora.android.api.Track
import org.sonora.android.api.YouTubeMusicClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the taste-based recommendation engine. All candidate sources are faked through
 * [FakeYouTubeMusicClient], so no network call is ever performed.
 */
class RecommendationEngineTest {

    private lateinit var fakeClient: FakeYouTubeMusicClient
    private lateinit var fakeStreamResolver: FakeStreamResolver
    private lateinit var engine: RecommendationEngine

    private fun ytTrack(
        videoId: String,
        title: String,
        artist: String,
        album: String? = "Album",
        durationMs: Long = 200_000L,
        bpm: Float? = null,
        gain: Float? = null,
        rank: Int? = null
    ) = Track(
        id = "yt_$videoId",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = null,
        provider = "YouTube",
        bpm = bpm,
        gain = gain,
        rank = rank
    )

    private val seedTrack = ytTrack(
        videoId = "dQw4w9WgXcQ",
        title = "Dancing Queen",
        artist = "ABBA",
        album = "Arrival",
        durationMs = 230_000L
    )

    @Before
    fun setUp() {
        fakeClient = FakeYouTubeMusicClient()
        fakeStreamResolver = FakeStreamResolver()
        engine = RecommendationEngine(
            youTubeMusicClient = fakeClient,
            streamResolver = fakeStreamResolver
        )
    }

    @Test
    fun testRecommendationFromSeedRadio() = runBlocking {
        fakeClient.radioTracks = listOf(
            seedTrack,
            ytTrack("aaaaaaaaaaa", "Gimme! Gimme! Gimme!", "ABBA", "Greatest Hits", 290_000L),
            ytTrack("bbbbbbbbbbb", "Take A Chance On Me", "ABBA", "The Album", 245_000L),
            ytTrack("ccccccccccc", "Waterloo", "ABBA", "Waterloo", 165_000L),
            ytTrack("ddddddddddd", "Hung Up", "Madonna", "Confessions", 336_000L),
            ytTrack("eeeeeeeeeee", "La Isla Bonita", "Madonna", "True Blue", 242_000L),
            ytTrack("fffffffffff", "Rasputin", "Boney M.", "Nightflight to Venus", 340_000L),
            ytTrack("ggggggggggg", "Heart of Glass", "Blondie", "Parallel Lines", 270_000L),
            ytTrack("hhhhhhhhhhh", "Stayin' Alive", "Bee Gees", "Saturday Night Fever", 285_000L)
        )

        val recommendations = engine.getRecommendedTracks(seedTrack, count = 5)

        assertTrue("Recommendations should not be empty", recommendations.isNotEmpty())
        assertFalse("Seed track should not be in recommendations", recommendations.any { it.id == seedTrack.id })
        val artists = recommendations.map { it.artist }.toSet()
        assertTrue("Should contain Madonna or Boney M or Bee Gees", artists.contains("Madonna") || artists.contains("Boney M."))
    }

    @Test
    fun testRecommendationExcludesActiveQueueAndHistory() = runBlocking {
        val cand201 = ytTrack("aaaaaaaaaaa", "Gimme! Gimme! Gimme!", "ABBA", "Greatest Hits", 290_000L)
        val cand301 = ytTrack("bbbbbbbbbbb", "Hung Up", "Madonna", "Confessions", 336_000L)
        val cand401 = ytTrack("ccccccccccc", "Rasputin", "Boney M.", "Nightflight", 340_000L)
        val cand501 = ytTrack("ddddddddddd", "Heart of Glass", "Blondie", "Parallel", 270_000L)
        fakeClient.radioTracks = listOf(cand201, cand301, cand401, cand501)

        val excluded = setOf(cand201.id, cand301.id)
        val recommendations = engine.getRecommendedTracks(seedTrack, count = 5, excludedTrackIds = excluded)

        assertFalse("Excluded track 201 should not be returned", recommendations.any { it.id == cand201.id })
        assertFalse("Excluded track 301 should not be returned", recommendations.any { it.id == cand301.id })
        assertTrue("Should return Rasputin", recommendations.any { it.id == cand401.id })
        assertTrue("Should return Heart of Glass", recommendations.any { it.id == cand501.id })
    }

    @Test
    fun testArtistDiversityConstraint() = runBlocking {
        val abba = (1..10).map { i -> ytTrack("abba${i}0000000", "ABBA Song $i", "ABBA", "Album", 200_000L) }
        val others = listOf(
            ytTrack("cher0000000", "Pop Song 1", "Cher", "Album", 200_000L),
            ytTrack("madon000000", "Pop Song 2", "Madonna", "Album", 200_000L)
        )
        fakeClient.radioTracks = abba + others

        val recommendations = engine.getRecommendedTracks(seedTrack, count = 10)
        val abbaCount = recommendations.count { it.artist == "ABBA" }

        assertTrue("ABBA tracks should not monopolize the entire playlist", abbaCount <= 4)
        assertTrue("Should include other artists for a diverse radio station", recommendations.any { it.artist != "ABBA" })
    }

    @Test
    fun testFiltersJunkTracks() = runBlocking {
        fakeClient.radioTracks = listOf(
            ytTrack("karaoke0001", "Dancing Queen (Karaoke Version)", "Karaoke Band", "Karaoke", 230_000L),
            ytTrack("tribute0001", "Tribute to ABBA", "Tribute Band", "Tribute", 230_000L),
            ytTrack("ringtone001", "Dancing Queen Ringtone", "Ringtones", "Ringtones", 30_000L),
            ytTrack("noise0000001", "Sleep White Noise Rain Sound", "Sounds", "White Noise", 600_000L),
            ytTrack("voulez000001", "Voulez-Vous", "ABBA", "Voulez-Vous", 300_000L),
            ytTrack("trouper00001", "Super Trouper", "ABBA", "Super Trouper", 250_000L)
        )

        val recommendations = engine.getRecommendedTracks(seedTrack, count = 5)

        assertFalse("Should filter karaoke", recommendations.any { it.title.contains("Karaoke") })
        assertFalse("Should filter tribute", recommendations.any { it.title.contains("Tribute") })
        assertFalse("Should filter ringtone", recommendations.any { it.durationMs < 45_000L })
        assertFalse("Should filter sound effect/noise", recommendations.any { it.title.contains("White Noise") })
        assertTrue("Should include good track", recommendations.any { it.title == "Voulez-Vous" })
    }

    @Test
    fun testLoopPreventionAcrossMultipleCalls() = runBlocking {
        fakeClient.radioTracks = listOf(
            ytTrack("rec100000001", "Track 1", "Artist A"),
            ytTrack("rec200000001", "Track 2", "Artist B"),
            ytTrack("rec300000001", "Track 3", "Artist C"),
            ytTrack("rec400000001", "Track 4", "Artist D")
        )

        val batch1 = engine.getRecommendedTracks(seedTrack, count = 2)
        assertEquals(2, batch1.size)
        val batch1Ids = batch1.map { it.id }.toSet()

        val batch2 = engine.getRecommendedTracks(seedTrack, count = 2)
        val batch2Ids = batch2.map { it.id }.toSet()

        assertTrue("Batch 2 should not contain tracks already recommended in Batch 1", batch1Ids.intersect(batch2Ids).isEmpty())
    }

    @Test
    fun testPrefetchTrack() = runBlocking {
        val trackToPrefetch = ytTrack("prefetch001", "Voyage", "ABBA", "Voyage", 250_000L)
        fakeStreamResolver.streamToReturn = StreamSource(
            url = "https://stream.cdn.net/audio.m4a",
            format = "audio/mp4",
            quality = AudioQuality.HIGH,
            provider = "JioSaavn"
        )

        val prefetched = engine.prefetchTrack(trackToPrefetch)
        assertNotNull(prefetched)
        assertEquals("https://stream.cdn.net/audio.m4a", prefetched?.url)
        assertEquals("JioSaavn", prefetched?.provider)
    }

    @Test
    fun testDislikePenalizerExcludesDislikedTracksAndArtists() = runBlocking {
        val track101 = ytTrack("aaaaaaaaaaa", "Dancing Queen", "ABBA", "Arrival", 230_000L)
        val track201 = ytTrack("bbbbbbbbbbb", "Gimme! Gimme! Gimme!", "ABBA", "Greatest Hits", 290_000L)
        val track301 = ytTrack("ccccccccccc", "Hung Up", "Madonna", "Confessions", 336_000L)
        val track302 = ytTrack("ddddddddddd", "Holiday", "Madonna", "Madonna", 230_000L)
        val track401 = ytTrack("eeeeeeeeeee", "Rasputin", "Boney M.", "Nightflight to Venus", 340_000L)
        val track501 = ytTrack("fffffffffff", "Heart of Glass", "Blondie", "Parallel Lines", 270_000L)
        fakeClient.radioTracks = listOf(track101, track201, track301, track302, track401, track501)

        val recommendations = engine.getRecommendedTracks(
            currentTrack = seedTrack,
            count = 5,
            dislikedTrackIds = setOf(track201.id),
            dislikedArtists = setOf("Madonna")
        )

        assertFalse("Disliked track 201 should be excluded", recommendations.any { it.id == track201.id })
        assertFalse("Disliked artist Madonna track 301 should be excluded", recommendations.any { it.id == track301.id })
        assertFalse("Disliked artist Madonna track 302 should be excluded", recommendations.any { it.id == track302.id })
        assertTrue("Rasputin by Boney M. should be included", recommendations.any { it.id == track401.id })
        assertTrue("Heart of Glass by Blondie should be included", recommendations.any { it.id == track501.id })

        val seedDislikedRecs = engine.getRecommendedTracks(
            currentTrack = seedTrack,
            count = 5,
            dislikedTrackIds = setOf(seedTrack.id)
        )
        assertTrue("Disliked seed track should yield empty list", seedDislikedRecs.isEmpty())

        val seedArtistDislikedRecs = engine.getRecommendedTracks(
            currentTrack = seedTrack,
            count = 5,
            dislikedArtists = setOf("ABBA")
        )
        assertTrue("Disliked seed artist should yield empty list", seedArtistDislikedRecs.isEmpty())
    }

    @Test
    fun testTasteArtistTopTracksAreUsed() = runBlocking {
        // Seed radio empty: recommendations must come from the taste profile artist top tracks.
        fakeClient.radioTracks = emptyList()
        fakeClient.artistTopTracks = listOf(
            ytTrack("taste000001", "Rasputin", "Boney M.", "Nightflight", 340_000L),
            ytTrack("taste000002", "Daddy Cool", "Boney M.", "Take the Heat", 210_000L),
            ytTrack("taste000003", "Hung Up", "Madonna", "Confessions", 336_000L)
        )
        val taste = TasteProfile(
            seedTracks = listOf(seedTrack),
            seedArtists = listOf(TasteArtistSeed("Boney M.", "UC_boneym", 3.0))
        )

        val recommendations = engine.getRecommendedTracks(seedTrack, count = 3, tasteProfile = taste)

        assertTrue("Taste artist top tracks should produce recommendations", recommendations.isNotEmpty())
        val artistNames = recommendations.map { it.artist }.toSet()
        assertTrue("Should include tracks from the taste artists", artistNames.contains("Boney M.") || artistNames.contains("Madonna"))
        assertTrue("All tracks should have reasonable durations", recommendations.all { it.durationMs in 45_000L..900_000L })
    }

    @Test
    fun testStrictArtistDiversityMaxTwoAndNonConsecutive() = runBlocking {
        val tracks = mutableListOf<Track>()
        listOf("Artist A", "Artist B", "Artist C", "Artist D").forEachIndexed { artistIdx, artistName ->
            for (i in 1..5) {
                tracks.add(
                    ytTrack(
                        videoId = "div${artistIdx}00${i}0000",
                        title = "Song $i by $artistName",
                        artist = artistName,
                        durationMs = 210_000L
                    )
                )
            }
        }
        fakeClient.radioTracks = tracks

        val recommendations = engine.getRecommendedTracks(seedTrack, count = 10)
        assertEquals("Should return exactly requested batch size", 8, recommendations.size)

        val artistCounts = recommendations.groupingBy { it.artist }.eachCount()
        for ((artist, count) in artistCounts) {
            assertTrue("Artist $artist exceeded max 2 tracks ratio: count=$count", count <= 2)
        }

        for (i in 0 until recommendations.size - 1) {
            val currentArtist = recommendations[i].artist
            val nextArtist = recommendations[i + 1].artist
            assertFalse("Consecutive tracks cannot be by the same artist at index $i ($currentArtist)",
                currentArtist.equals(nextArtist, ignoreCase = true))
        }
    }

    @Test
    fun testStrictAntiRepetitionWithHistoryAndQueueSignatures() = runBlocking {
        val queueTrack = ytTrack("queue000001", "Billie Jean", "Michael Jackson", "Thriller", 294_000L)
        val historyTrack = ytTrack("history00001", "Thriller", "Michael Jackson", "Thriller", 357_000L)

        fakeClient.radioTracks = listOf(
            ytTrack("cand10000001", "Billie Jean (Remastered 2011)", "Michael Jackson", "Bad 25", 294_000L),
            ytTrack("cand20000001", "Billie Jean - Live at Wembley", "Michael Jackson", "Live", 310_000L),
            ytTrack("cand30000001", "Thriller (2003 Edit)", "Michael Jackson", "Greatest Hits", 320_000L),
            ytTrack("cand40000001", "Smooth Criminal", "Michael Jackson", "Bad", 257_000L),
            ytTrack("cand50000001", "Beat It", "Michael Jackson", "Thriller", 258_000L),
            ytTrack("cand60000001", "Bad", "Michael Jackson", "Bad", 247_000L)
        )

        val recommendations = engine.getRecommendedTracks(
            currentTrack = seedTrack,
            count = 5,
            activeQueueTracks = listOf(queueTrack),
            historyTracks = listOf(historyTrack)
        )

        assertFalse("Billie Jean remaster should be deduplicated by signature", recommendations.any { it.title.contains("Billie Jean") })
        assertFalse("Thriller edit should be deduplicated by signature", recommendations.any { it.title.contains("Thriller") })
        assertTrue("Smooth Criminal should be included", recommendations.any { it.title.contains("Smooth Criminal") })
        assertTrue("Beat It should be included", recommendations.any { it.title.contains("Beat It") })
    }

    @Test
    fun testAcousticSimilarityPrioritizesMatchingTempoAndGain() = runBlocking {
        val seedDanceTrack = ytTrack(
            videoId = "onemoretime",
            title = "One More Time",
            artist = "Daft Punk",
            album = "Discovery",
            durationMs = 320_000L,
            bpm = 123.0f,
            gain = -8.0f,
            rank = 800_000
        )

        fakeClient.radioTracks = listOf(
            ytTrack("slow0000001", "Slow Ballad", "Balladeer", "Album", 300_000L, bpm = 65.0f, gain = -18.0f, rank = 200_000),
            ytTrack("match000001", "Aerodynamic", "Daft Punk", "Discovery", 212_000L, bpm = 124.0f, gain = -8.5f, rank = 750_000),
            ytTrack("match000002", "Music Sounds Better With You", "Stardust", "Roule", 260_000L, bpm = 126.0f, gain = -7.8f, rank = 700_000)
        )

        val recommendations = engine.getRecommendedTracks(seedDanceTrack, count = 2)

        assertTrue("Recommendations should not be empty", recommendations.isNotEmpty())
        val recommendedIds = recommendations.map { it.id }.toSet()
        assertTrue("Should contain match_1", recommendedIds.contains("yt_match000001"))
        assertTrue("Should contain match_2", recommendedIds.contains("yt_match000002"))
        assertFalse("Should deprioritize slow ballad mismatch", recommendedIds.contains("yt_slow0000001"))
    }

    // ==========================================
    // Fakes
    // ==========================================

    class FakeYouTubeMusicClient : YouTubeMusicClient() {
        var radioTracks: List<Track> = emptyList()
        var artistTopTracks: List<Track> = emptyList()
        var searchResults: List<Track> = emptyList()

        override suspend fun getYouTubeRadio(videoId: String, limit: Int): Result<List<Track>> =
            Result.success(radioTracks)

        override suspend fun getYouTubeArtistTopTracks(browseId: String, limit: Int): Result<List<Track>> =
            Result.success(artistTopTracks)

        override suspend fun searchTracks(query: String, limit: Int): Result<List<Track>> =
            Result.success(searchResults)
    }

    class FakeStreamResolver : StreamResolver() {
        var streamToReturn: StreamSource? = null

        override suspend fun resolveStream(track: Track, forceSource: org.sonora.android.api.PlaybackSource?): StreamSource? {
            return streamToReturn
        }
    }
}
