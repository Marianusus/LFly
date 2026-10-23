package org.sonora.android.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetMathTest {

    @Test
    fun opensAtProgressThreshold() {
        assertFalse(shouldOpenPlayerSheet(0.24f, 0f))
        assertTrue(shouldOpenPlayerSheet(0.25f, 0f))
        assertTrue(shouldOpenPlayerSheet(1f, 0f))
    }

    @Test
    fun upwardFlickOpensEvenBelowThreshold() {
        assertTrue(shouldOpenPlayerSheet(0.05f, -900f))
        assertTrue(shouldOpenPlayerSheet(0.05f, -1500f))
        assertFalse(shouldOpenPlayerSheet(0.05f, -899f))
    }

    @Test
    fun downwardVelocityNeverOpensBelowThreshold() {
        assertFalse(shouldOpenPlayerSheet(0.1f, 1200f))
        assertFalse(shouldOpenPlayerSheet(0.1f, 0f))
    }
}
