package org.sonora.android.ui.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionFormattingTest {

    @Test
    fun durationParts_handleHoursMinutesAndSeconds() {
        assertEquals(Triple(0L, 3L, 42L), collectionDurationParts(222_000L))
        assertEquals(Triple(1L, 2L, 3L), collectionDurationParts(3_723_000L))
        assertEquals(Triple(0L, 0L, 0L), collectionDurationParts(0L))
        assertEquals(Triple(0L, 0L, 0L), collectionDurationParts(-5_000L))
    }

    @Test
    fun joinMeta_skipsBlanksAndKeepsOrder() {
        assertEquals("2024 · 12 brani · 43 min", joinCollectionMeta("2024", "12 brani", "43 min"))
        assertEquals("2024 · 12 brani", joinCollectionMeta("2024", "", null, "12 brani"))
        assertNull(joinCollectionMeta(null, "", "   "))
    }

    @Test
    fun descriptionExpansion_onlyForLongOrMultilineCopy() {
        assertFalse(isCollectionDescriptionExpandable(null))
        assertFalse(isCollectionDescriptionExpandable("Una nota breve."))
        assertTrue(isCollectionDescriptionExpandable("x".repeat(141)))
        assertTrue(isCollectionDescriptionExpandable("Prima riga\nSeconda riga\nTerza riga"))
    }
}
