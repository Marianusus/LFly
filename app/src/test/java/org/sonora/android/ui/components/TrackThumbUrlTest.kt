package org.sonora.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackThumbUrlTest {

    @Test
    fun acceptsOnlyHttpUrls() {
        assertEquals("https://img.example/a.jpg", "https://img.example/a.jpg".asImageUrlOrNull())
        assertEquals("http://img.example/a.jpg", " http://img.example/a.jpg ".asImageUrlOrNull())
    }

    @Test
    fun rejectsNonImageOrBlankValues() {
        assertNull(null.asImageUrlOrNull())
        assertNull("".asImageUrlOrNull())
        assertNull("   ".asImageUrlOrNull())
        assertEquals("file:///tmp/a.jpg", "file:///tmp/a.jpg".asImageUrlOrNull())
        assertEquals("content://media/1", "content://media/1".asImageUrlOrNull())
        assertEquals("file:///storage/emulated/0/a.jpg", "/storage/emulated/0/a.jpg".asImageUrlOrNull())
        assertNull("ytsearch:foo".asImageUrlOrNull())
    }
}
