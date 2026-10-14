package org.sonora.android.data

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.sonora.android.api.youTubeArtistKey
import java.io.File

class FollowedArtistStoreTest {

    private lateinit var tempDir: File
    private lateinit var repository: MusicRepository

    @Before
    fun setUp() {
        tempDir = File.createTempFile("sonora_follow_test", "").apply {
            delete()
            mkdir()
        }
        repository = MusicRepository(ContextWrapper(null), storageDir = tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun artist(
        browseId: String?,
        channelId: String?,
        name: String,
        coverUrl: String? = null
    ) = FollowedArtist(
        artistKey = youTubeArtistKey(browseId, channelId, name),
        browseId = browseId,
        channelId = channelId,
        name = name,
        coverUrl = coverUrl,
        provider = "YouTube",
        followedAtEpochMs = 1_700_000_000_000L
    )

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    // ==========================================
    // artistKey derivation
    // ==========================================

    @Test
    fun testArtistKeyPrefersBrowseId() {
        assertEquals("yt_browse_MPREb_x", youTubeArtistKey("MPREb_x", "UC_y", "Name"))
    }

    @Test
    fun testArtistKeyFallsBackToChannelId() {
        assertEquals("yt_channel_UC_y", youTubeArtistKey(null, "UC_y", "Name"))
    }

    @Test
    fun testArtistKeyFallsBackToNormalizedName() {
        assertEquals("yt_name_the_weeknd", youTubeArtistKey(null, null, "The Weeknd"))
    }

    // ==========================================
    // Toggle / follow state
    // ==========================================

    @Test
    fun testToggleFollowAndUnfollow() {
        assertTrue(repository.followedArtists.value.isEmpty())
        val a = artist("UC_abc", "UC_abc", "Alpha")

        val nowFollowing = repository.toggleFollowArtist(a)
        assertTrue(nowFollowing)
        assertTrue(repository.isFollowingKey("yt_browse_UC_abc"))
        assertEquals(1, repository.followedArtists.value.size)

        val nowUnfollowed = repository.toggleFollowArtist(a)
        assertFalse(nowUnfollowed)
        assertFalse(repository.isFollowingKey("yt_browse_UC_abc"))
        assertTrue(repository.followedArtists.value.isEmpty())
    }

    @Test
    fun testIsFollowingIsAliasAware() {
        // Followed first with name only, without stable ids.
        repository.toggleFollowArtist(artist(null, null, "The Weeknd"))

        // Same artist later discovered with a browseId must be recognised as followed.
        val withId = artist("UC_weeknd", "UC_weeknd", "the weeknd")
        assertTrue("Alias by normalized name must match", repository.isFollowing(withId))
    }

    @Test
    fun testNoDuplicateWhenNameThenBrowseId() {
        val byName = artist(null, null, "Daft Punk")
        repository.toggleFollowArtist(byName)
        assertEquals(1, repository.followedArtists.value.size)

        val byId = artist("UC_daft", "UC_daft", "Daft Punk")
        repository.toggleFollowArtist(byId)

        // Same artist must not duplicate; the id-carrying entry replaces the name-only one.
        assertEquals(1, repository.followedArtists.value.size)
        val merged = repository.followedArtists.value.first()
        assertEquals("yt_browse_UC_daft", merged.artistKey)
        assertEquals("UC_daft", merged.browseId)
    }

    @Test
    fun testDifferentArtistsDoNotMerge() {
        repository.toggleFollowArtist(artist("UC_a", "UC_a", "Alpha"))
        repository.toggleFollowArtist(artist("UC_b", "UC_b", "Beta"))
        assertEquals(2, repository.followedArtists.value.size)
    }

    // ==========================================
    // Persistence
    // ==========================================

    @Test
    fun testFollowPersistsAfterReload() {
        val a = artist("UC_persist", "UC_persist", "Persisted", coverUrl = "https://yt.example/a.jpg")
        repository.toggleFollowArtist(a)

        val file = File(tempDir, "followed_artists.json")
        assertTrue(
            "followed_artists.json must be written",
            waitUntil { file.exists() && file.readText().contains("UC_persist") }
        )

        val reloaded = MusicRepository(ContextWrapper(null), storageDir = tempDir)
        assertTrue(
            "Followed artist must be restored after reload",
            waitUntil { reloaded.followedArtists.value.any { it.artistKey == "yt_browse_UC_persist" } }
        )
        val restored = reloaded.followedArtists.value.first { it.artistKey == "yt_browse_UC_persist" }
        assertEquals("Persisted", restored.name)
        assertEquals("YouTube", restored.provider)
        assertEquals("https://yt.example/a.jpg", restored.coverUrl)
    }

    @Test
    fun testRollbackOnPersistenceFailure() {
        // Use a regular file as the storage "directory": writing must fail and roll back.
        val notADir = File.createTempFile("sonora_not_dir", "")
        try {
            val failingRepo = MusicRepository(ContextWrapper(null), storageDir = notADir)
            val a = artist("UC_fail", "UC_fail", "Failing")

            val optimistic = failingRepo.toggleFollowArtist(a)
            assertTrue("Optimistic state is applied immediately", optimistic)
            assertTrue(failingRepo.followedArtists.value.any { it.artistKey == "yt_browse_UC_fail" })

            assertTrue(
                "State must roll back after persistence failure",
                waitUntil { failingRepo.followedArtists.value.none { it.artistKey == "yt_browse_UC_fail" } }
            )
        } finally {
            notADir.delete()
        }
    }
}
