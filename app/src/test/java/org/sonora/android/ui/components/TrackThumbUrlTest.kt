package org.sonora.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackThumbUrlTest {

    @Test
    fun acceptsOnlyHttpUrls() {
        assertEquals("https://img.example/a.jpg", "https://img.example/a.jpg".asHttpImageUrlOrNull())
        assertEquals("http://img.example/a.jpg", " http://img.example/a.jpg ".asHttpImageUrlOrNull())
    }

    @Test
    fun rejectsNonImageOrBlankValues() {
        assertNull(null.asHttpImageUrlOrNull())
        assertNull("".asHttpImageUrlOrNull())
        assertNull("   ".asHttpImageUrlOrNull())
        assertNull("file:///tmp/a.jpg".asHttpImageUrlOrNull())
        assertNull("content://media/1".asHttpImageUrlOrNull())
        assertNull("ytsearch:foo".asHttpImageUrlOrNull())
    }
}
