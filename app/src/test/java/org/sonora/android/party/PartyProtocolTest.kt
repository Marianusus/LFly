package org.sonora.android.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track

class PartyProtocolTest {

    private val track = Track(
        id = "yt_abc",
        title = "Stereo Love",
        artist = "Edward Maya",
        album = "Stereo Love",
        durationMs = 185_000L,
        coverUrl = "https://example.com/cover.jpg",
        streamUrl = "https://secret.example.com/audio",
        provider = "YouTube"
    )

    @Test
    fun helloRoundTrips() {
        val decoded = PartyProtocol.decode(PartyProtocol.encode(PartyMessage.Hello("Marian")))
        assertEquals(PartyMessage.Hello("Marian"), decoded)
    }

    @Test
    fun welcomeRoundTrips() {
        val message = PartyMessage.Welcome(
            hostNick = "Marian",
            allowGuestControl = true,
            allowQueueAdd = false
        )
        assertEquals(message, PartyProtocol.decode(PartyProtocol.encode(message)))
    }

    @Test
    fun stateRoundTripsWithAndWithoutTrack() {
        val withTrack = PartyMessage.State(
            track = PartyTrack.from(track),
            positionMs = 42_000L,
            isPlaying = true,
            startsAtHostMs = 1_000_000L,
            seq = 7L
        )
        assertEquals(withTrack, PartyProtocol.decode(PartyProtocol.encode(withTrack)))

        val empty = PartyMessage.State(null, 0L, false, 0L, 8L)
        assertEquals(empty, PartyProtocol.decode(PartyProtocol.encode(empty)))
    }

    @Test
    fun stateNeverLeaksTheStreamUrl() {
        val encoded = String(PartyProtocol.encode(
            PartyMessage.State(PartyTrack.from(track), 1L, true, 0L, 1L)
        ))
        assertTrue(!encoded.contains("secret.example.com"))
        assertTrue(!encoded.contains("streamUrl"))
    }

    @Test
    fun slimTrackKeepsMetadataAndDropsStreamUrl() {
        val slim = PartyTrack.from(track).toTrack()
        assertEquals("yt_abc", slim.id)
        assertEquals("Stereo Love", slim.title)
        assertEquals(185_000L, slim.durationMs)
        assertNull(slim.streamUrl)
    }

    @Test
    fun queueRoundTrips() {
        val message = PartyMessage.Queue(
            tracks = listOf(PartyTrack.from(track), PartyTrack.from(track.copy(id = "yt_def"))),
            currentIndex = 1
        )
        assertEquals(message, PartyProtocol.decode(PartyProtocol.encode(message)))
    }

    @Test
    fun pingPongControlPermsAndByeRoundTrip() {
        assertEquals(
            PartyMessage.Ping(123L),
            PartyProtocol.decode(PartyProtocol.encode(PartyMessage.Ping(123L)))
        )
        assertEquals(
            PartyMessage.Pong(123L, 456L),
            PartyProtocol.decode(PartyProtocol.encode(PartyMessage.Pong(123L, 456L)))
        )
        assertEquals(
            PartyMessage.Control(PartyControl.SEEK, 9_000L),
            PartyProtocol.decode(PartyProtocol.encode(PartyMessage.Control(PartyControl.SEEK, 9_000L)))
        )
        assertEquals(
            PartyMessage.Perms(true, true),
            PartyProtocol.decode(PartyProtocol.encode(PartyMessage.Perms(true, true)))
        )
        assertEquals(
            PartyMessage.Bye("host left"),
            PartyProtocol.decode(PartyProtocol.encode(PartyMessage.Bye("host left")))
        )
    }

    @Test
    fun decodeRejectsGarbageAndUnknownTypes() {
        assertNull(PartyProtocol.decode("not json".toByteArray()))
        assertNull(PartyProtocol.decode("""{"t":"unknown"}""".toByteArray()))
        assertNull(PartyProtocol.decode(ByteArray(0)))
    }

    @Test
    fun fitQueueAlwaysProducesAPayloadWithinLimits() {
        val bigQueue = (0 until 400).map {
            PartyTrack.from(
                track.copy(
                    id = "yt_$it",
                    title = "Track number $it with a reasonably long title",
                    coverUrl = "https://example.com/covers/very/long/path/number/$it/image.jpg"
                )
            )
        }
        val message = PartyProtocol.fitQueue(bigQueue, currentIndex = 200)
        val size = PartyProtocol.encode(message).size
        assertTrue("payload was $size bytes", size <= PartyProtocol.MAX_PAYLOAD_BYTES)
        assertTrue(message.tracks.isNotEmpty())
        assertEquals("yt_200", message.tracks[message.currentIndex].id)
    }

    @Test
    fun fitQueueKeepsSmallQueuesIntact() {
        val small = listOf(PartyTrack.from(track))
        val message = PartyProtocol.fitQueue(small, currentIndex = 0)
        assertEquals(1, message.tracks.size)
        assertEquals(0, message.currentIndex)
    }

    @Test
    fun fitQueueHandlesEmptyAndOutOfRangeIndex() {
        val empty = PartyProtocol.fitQueue(emptyList(), currentIndex = 5)
        assertEquals(0, empty.tracks.size)
        assertEquals(-1, empty.currentIndex)

        val one = PartyProtocol.fitQueue(listOf(PartyTrack.from(track)), currentIndex = 99)
        assertEquals(1, one.tracks.size)
        assertEquals(0, one.currentIndex)
    }

    @Test
    fun protocolVersionIsStable() {
        assertNotNull(PartyProtocol.decode(PartyProtocol.encode(PartyMessage.Hello("x"))))
        assertEquals(1, PartyProtocol.VERSION)
    }
}
