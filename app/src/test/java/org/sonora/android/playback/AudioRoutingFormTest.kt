package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import org.sonora.android.playback.AudioRouting.Form

class AudioRoutingFormTest {
    @Test
    fun earbudsByName() {
        assertEquals(Form.EARBUDS, AudioRouting.formByName("Galaxy Buds2 Pro"))
        assertEquals(Form.EARBUDS, AudioRouting.formByName("AirPods Pro"))
        assertEquals(Form.EARBUDS, AudioRouting.formByName("WF-1000XM5"))
    }

    @Test
    fun otherShapesByName() {
        assertEquals(Form.HEADPHONES, AudioRouting.formByName("WH-1000XM4"))
        assertEquals(Form.CAR, AudioRouting.formByName("My Car"))
        assertEquals(Form.SPEAKER, AudioRouting.formByName("JBL Flip 6"))
        assertEquals(Form.WATCH, AudioRouting.formByName("Galaxy Watch6"))
        assertEquals(null, AudioRouting.formByName("XYZ-123"))
    }
}
