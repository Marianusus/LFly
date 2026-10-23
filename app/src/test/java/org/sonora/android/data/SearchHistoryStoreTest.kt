package org.sonora.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryStoreTest {

    private val store = SearchHistoryStore

    @Test
    fun newestEntryComesFirstAndDuplicatesAreMovedToTop() {
        val first = entry("t1", "One")
        val second = entry("t2", "Two")
        val merged = store.mergeHistory(
            current = listOf(first),
            entry = second,
            maxEntries = 20
        )
        assertEquals(listOf("t2", "t1"), merged.map { it.id })

        val moved = store.mergeHistory(
            current = merged,
            entry = first.copy(title = "One updated"),
            maxEntries = 20
        )
        assertEquals(listOf("t1", "t2"), moved.map { it.id })
        assertEquals("One updated", moved.first().title)
    }

    @Test
    fun differentTypesMayShareAnId() {
        val track = entry("same", "Track", SearchHistoryType.TRACK)
        val album = entry("same", "Album", SearchHistoryType.ALBUM)
        val merged = store.mergeHistory(listOf(track), album)
        assertEquals(2, merged.size)
    }

    @Test
    fun historyIsCappedAtTheRequestedSize() {
        val merged = (1..5).fold(emptyList<SearchHistoryEntry>()) { current, index ->
            store.mergeHistory(current, entry("t$index", "Track $index"), maxEntries = 3)
        }
        assertEquals(3, merged.size)
        assertEquals(listOf("t5", "t4", "t3"), merged.map { it.id })
    }

    @Test
    fun blankEntriesAndNonPositiveLimitsAreIgnored() {
        val current = listOf(entry("t1", "One"))
        assertEquals(current, store.mergeHistory(current, entry(" ", "Blank")))
        assertEquals(current, store.mergeHistory(current, entry("t2", "Two"), maxEntries = 0))
    }

    private fun entry(
        id: String,
        title: String,
        type: SearchHistoryType = SearchHistoryType.TRACK
    ) = SearchHistoryEntry(
        id = id,
        title = title,
        subtitle = "Artist",
        artworkUrl = null,
        type = type,
        timestamp = 0L
    )
}
