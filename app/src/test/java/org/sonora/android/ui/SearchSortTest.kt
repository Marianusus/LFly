package org.sonora.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.sonora.android.api.Track

class SearchSortTest {

    private fun track(id: String, streams: Long?) = Track(
        id = id,
        title = id,
        artist = "a",
        album = null,
        durationMs = 1000L,
        coverUrl = null,
        streamUrl = null,
        provider = "YouTube",
        streams = streams,
    )

    @Test
    fun sortByStreams_putsMostStreamedFirstAndNullsLast() {
        val sorted = sortByStreams(
            listOf(
                track("a", 20_000_000L),
                track("b", 3_600_000_000L),
                track("c", null),
                track("d", 81_000L),
            )
        )
        assertEquals(listOf("b", "a", "d", "c"), sorted.map { it.id })
    }

    @Test
    fun sortByStreams_isStableForTies() {
        val sorted = sortByStreams(listOf(track("a", null), track("b", null)))
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }
}
