package org.sonora.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sonora.android.api.Track

class QueuePanelSectionsTest {

    private fun track(id: String) = Track(
        id = id,
        title = "title-$id",
        artist = "artist",
        album = "album",
        durationMs = 1_000L,
        coverUrl = null
    )

    @Test
    fun buildQueuePanelSections_splitsCurrentUpcomingAndRadio() {
        val queue = listOf("h", "current", "u1", "u2", "radio1").map(::track)

        val sections = buildQueuePanelSections(queue, currentIndex = 1, userQueueSize = 4)

        assertEquals("current", sections.current?.track?.id)
        assertEquals(listOf("u1", "u2"), sections.upcoming.map { it.track.id })
        assertEquals(listOf("radio1"), sections.radio.map { it.track.id })
        assertEquals(listOf(2, 3), sections.upcoming.map { it.index })
        assertEquals(listOf(4), sections.radio.map { it.index })
    }

    @Test
    fun buildQueuePanelSections_keepsDuplicateRowsDistinct() {
        val queue = listOf("a", "a", "a", "b").map(::track)

        val sections = buildQueuePanelSections(queue, currentIndex = 0, userQueueSize = 3)

        assertEquals(2, sections.upcoming.size)
        assertEquals(sections.upcoming.size, sections.upcoming.map { it.key }.toSet().size)
        assertTrue(sections.upcoming.map { it.key }.all { it.startsWith("u_") })
    }

    @Test
    fun buildQueuePanelSections_handlesPlayingFromRadioTail() {
        val queue = listOf("h", "u1", "r1", "r2").map(::track)

        val sections = buildQueuePanelSections(queue, currentIndex = 2, userQueueSize = 2)

        assertTrue(sections.upcoming.isEmpty())
        assertEquals(listOf("r2"), sections.radio.map { it.track.id })
    }

    @Test
    fun buildQueuePanelSections_returnsEmptyForInvalidCurrent() {
        assertTrue(buildQueuePanelSections(emptyList(), -1, 0).current == null)
    }
}
