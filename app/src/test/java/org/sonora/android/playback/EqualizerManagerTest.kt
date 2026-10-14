package org.sonora.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerManagerTest {

    @Test
    fun testDefaultBandsConfiguration() {
        val bands = EqualizerState.defaultBands()
        assertEquals(5, bands.size)

        assertEquals(60, bands[0].centerFreqHz)
        assertEquals("60Hz", bands[0].label)
        assertEquals(0f, bands[0].gainDb, 0.001f)

        assertEquals(230, bands[1].centerFreqHz)
        assertEquals("230Hz", bands[1].label)
        assertEquals(0f, bands[1].gainDb, 0.001f)

        assertEquals(910, bands[2].centerFreqHz)
        assertEquals("910Hz", bands[2].label)
        assertEquals(0f, bands[2].gainDb, 0.001f)

        assertEquals(3600, bands[3].centerFreqHz)
        assertEquals("3.6kHz", bands[3].label)
        assertEquals(0f, bands[3].gainDb, 0.001f)

        assertEquals(14000, bands[4].centerFreqHz)
        assertEquals("14kHz", bands[4].label)
        assertEquals(0f, bands[4].gainDb, 0.001f)
    }

    @Test
    fun testPresetDefinitionsAndGains() {
        val flat = EqualizerPreset.FLAT
        assertEquals("Flat", flat.displayName)
        assertEquals(5, flat.bandGains.size)
        assertTrue(flat.bandGains.all { it == 0f })

        val bassBooster = EqualizerPreset.BASS_BOOSTER
        assertEquals("Bass Booster", bassBooster.displayName)
        assertEquals(7f, bassBooster.bandGains[0], 0.001f)
        assertEquals(5f, bassBooster.bandGains[1], 0.001f)
        assertEquals(2f, bassBooster.bandGains[2], 0.001f)
        assertEquals(0f, bassBooster.bandGains[3], 0.001f)
        assertEquals(0f, bassBooster.bandGains[4], 0.001f)
        assertEquals(500, bassBooster.recommendedBassBoost)

        val rock = EqualizerPreset.ROCK
        assertEquals("Rock", rock.displayName)
        assertEquals(5f, rock.bandGains[0], 0.001f)
        assertEquals(3f, rock.bandGains[1], 0.001f)
        assertEquals(-1f, rock.bandGains[2], 0.001f)
        assertEquals(3f, rock.bandGains[3], 0.001f)
        assertEquals(5f, rock.bandGains[4], 0.001f)

        val pop = EqualizerPreset.POP
        assertEquals("Pop", pop.displayName)
        assertEquals(-1f, pop.bandGains[0], 0.001f)
        assertEquals(2f, pop.bandGains[1], 0.001f)
        assertEquals(5f, pop.bandGains[2], 0.001f)

        val electronic = EqualizerPreset.ELECTRONIC
        assertEquals("Electronic", electronic.displayName)
        assertEquals(6f, electronic.bandGains[0], 0.001f)

        val jazz = EqualizerPreset.JAZZ
        assertEquals("Jazz", jazz.displayName)
        assertEquals(3f, jazz.bandGains[0], 0.001f)

        val vocalBooster = EqualizerPreset.VOCAL_BOOSTER
        assertEquals("Vocal Booster", vocalBooster.displayName)
        assertEquals(-4f, vocalBooster.bandGains[0], 0.001f)
        assertEquals(6f, vocalBooster.bandGains[2], 0.001f)
    }

    @Test
    fun testPresetFromNameResolution() {
        assertEquals(EqualizerPreset.FLAT, EqualizerPreset.fromName("Flat"))
        assertEquals(EqualizerPreset.FLAT, EqualizerPreset.fromName("flat"))
        assertEquals(EqualizerPreset.BASS_BOOSTER, EqualizerPreset.fromName("Bass Booster"))
        assertEquals(EqualizerPreset.BASS_BOOSTER, EqualizerPreset.fromName("Bass Boost"))
        assertEquals(EqualizerPreset.ROCK, EqualizerPreset.fromName("Rock"))
        assertEquals(EqualizerPreset.POP, EqualizerPreset.fromName("POP"))
        assertEquals(EqualizerPreset.ELECTRONIC, EqualizerPreset.fromName("Electronic"))
        assertEquals(EqualizerPreset.JAZZ, EqualizerPreset.fromName("Jazz"))
        assertEquals(EqualizerPreset.VOCAL_BOOSTER, EqualizerPreset.fromName("Vocal Booster"))
        assertEquals(EqualizerPreset.CUSTOM, EqualizerPreset.fromName("Custom"))
        assertEquals(EqualizerPreset.FLAT, EqualizerPreset.fromName("unknown_preset"))
        assertEquals(EqualizerPreset.FLAT, EqualizerPreset.fromName(null))
    }

    @Test
    fun testGainAndBassBoostLimits() {
        assertEquals(-15f, EqualizerManager.MIN_GAIN_DB, 0.001f)
        assertEquals(15f, EqualizerManager.MAX_GAIN_DB, 0.001f)
        assertEquals(0, EqualizerManager.MIN_BASS_BOOST)
        assertEquals(1000, EqualizerManager.MAX_BASS_BOOST)

        // Verify clamping logic
        val clampedGainLow = (-25f).coerceIn(EqualizerManager.MIN_GAIN_DB, EqualizerManager.MAX_GAIN_DB)
        assertEquals(-15f, clampedGainLow, 0.001f)

        val clampedGainHigh = (25f).coerceIn(EqualizerManager.MIN_GAIN_DB, EqualizerManager.MAX_GAIN_DB)
        assertEquals(15f, clampedGainHigh, 0.001f)

        val clampedBassLow = (-100).coerceIn(EqualizerManager.MIN_BASS_BOOST, EqualizerManager.MAX_BASS_BOOST)
        assertEquals(0, clampedBassLow)

        val clampedBassHigh = (1500).coerceIn(EqualizerManager.MIN_BASS_BOOST, EqualizerManager.MAX_BASS_BOOST)
        assertEquals(1000, clampedBassHigh)
    }
}
