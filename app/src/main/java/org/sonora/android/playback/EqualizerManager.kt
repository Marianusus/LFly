package org.sonora.android.playback

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

/**
 * 5-Band Equalizer preset definitions.
 * Gains are specified in decibels (dB) in range [-15.0 dB, +15.0 dB] for:
 * [60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz].
 */
enum class EqualizerPreset(
    val displayName: String,
    val bandGains: FloatArray,
    val recommendedBassBoost: Int = 0
) {
    FLAT("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f), 0),
    BASS_BOOSTER("Bass Booster", floatArrayOf(7f, 5f, 2f, 0f, 0f), 500),
    ROCK("Rock", floatArrayOf(5f, 3f, -1f, 3f, 5f), 200),
    POP("Pop", floatArrayOf(-1f, 2f, 5f, 3f, -1f), 100),
    ELECTRONIC("Electronic", floatArrayOf(6f, 4f, 0f, 2f, 5f), 400),
    JAZZ("Jazz", floatArrayOf(3f, 2f, -1f, 2f, 4f), 150),
    VOCAL_BOOSTER("Vocal Booster", floatArrayOf(-4f, 0f, 6f, 4f, 1f), 0),
    CUSTOM("Custom", floatArrayOf(0f, 0f, 0f, 0f, 0f), 0);

    companion object {
        fun fromName(name: String?): EqualizerPreset {
            if (name == null) return FLAT
            if (name.equals("Bass Boost", ignoreCase = true)) return BASS_BOOSTER
            return entries.firstOrNull {
                it.name.equals(name, ignoreCase = true) ||
                        it.displayName.equals(name, ignoreCase = true)
            } ?: FLAT
        }
    }
}

/**
 * Metadata and gain value for an individual equalizer frequency band.
 */
data class EqualizerBand(
    val index: Int,
    val centerFreqHz: Int,
    val label: String,
    val gainDb: Float
)

/**
 * Immutable state representation of the native audio DSP processor.
 */
data class EqualizerState(
    val isEnabled: Boolean = true,
    val preset: EqualizerPreset = EqualizerPreset.FLAT,
    val bands: List<EqualizerBand> = defaultBands(),
    val bassBoostStrength: Int = 0,
    val isHardwareAttached: Boolean = false,
    val audioSessionId: Int = 0
) {
    companion object {
        val TARGET_CENTER_FREQS_HZ = intArrayOf(60, 230, 910, 3600, 14000)
        val TARGET_CENTER_FREQS_LABEL = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")

        fun defaultBands(): List<EqualizerBand> {
            return TARGET_CENTER_FREQS_HZ.indices.map { i ->
                EqualizerBand(
                    index = i,
                    centerFreqHz = TARGET_CENTER_FREQS_HZ[i],
                    label = TARGET_CENTER_FREQS_LABEL[i],
                    gainDb = 0f
                )
            }
        }
    }
}

/**
 * The 5-band equalizer and bass boost.
 *
 * The sound is shaped by LFly's own [EqualizerProcessor] inside every player's audio chain, not by
 * the platform's audio effects: those depend on the phone (Samsung's system sound effects override
 * them) and never reach a USB DAC played bit-perfect. This class keeps the settings, persists them
 * and publishes them as the [EqualizerCurve] the processors read.
 */
class EqualizerManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "EqualizerManager"
        private const val PREFS_NAME = "sonora_equalizer_settings"

        private const val KEY_ENABLED = "eq_enabled"
        private const val KEY_PRESET = "eq_preset"
        private const val KEY_BASS_BOOST = "eq_bass_boost"
        private const val KEY_BAND_PREFIX = "eq_band_"

        const val MIN_GAIN_DB = -15f
        const val MAX_GAIN_DB = 15f
        const val MIN_BASS_BOOST = 0
        const val MAX_BASS_BOOST = 1000

        @Volatile
        private var instance: EqualizerManager? = null

        fun getInstance(context: Context): EqualizerManager {
            return instance ?: synchronized(this) {
                instance ?: EqualizerManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Reactive State
    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    init {
        publish()
    }

    /** The player's audio session, shown in the state; the sound itself is shaped inside the player. */
    @OptIn(UnstableApi::class)
    @Synchronized
    fun attachAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        Log.i(TAG, "Equalizer follows audio session $audioSessionId")
        _state.update { it.copy(isHardwareAttached = true, audioSessionId = audioSessionId) }
    }

    @Synchronized
    fun detachAudioSession() {
        _state.update { it.copy(isHardwareAttached = false, audioSessionId = 0) }
    }

    /**
     * Toggles master equalizer & audio DSP processing on or off.
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(isEnabled = enabled) }
        publish()
        persistSettings()
    }

    /**
     * Sets gain in dB for the specific frequency band [0..4]. Range [-15dB, +15dB].
     */
    @Synchronized
    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until 5) return
        val clamped = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

        val updatedBands = _state.value.bands.toMutableList()
        val currentBand = updatedBands[bandIndex]
        if (abs(currentBand.gainDb - clamped) < 0.05f) return

        updatedBands[bandIndex] = currentBand.copy(gainDb = clamped)

        // Determine if customized bands still match an existing preset
        val matchingPreset = EqualizerPreset.entries.firstOrNull { preset ->
            preset != EqualizerPreset.CUSTOM && preset.bandGains.indices.all { i ->
                abs(preset.bandGains[i] - updatedBands[i].gainDb) < 0.1f
            }
        } ?: EqualizerPreset.CUSTOM

        _state.update {
            it.copy(
                bands = updatedBands,
                preset = matchingPreset
            )
        }
        publish()
        persistSettings()
    }

    /**
     * Selects a named preset profile and applies its band gain curves.
     */
    @Synchronized
    fun setPreset(preset: EqualizerPreset) {
        if (preset == EqualizerPreset.CUSTOM) {
            _state.update { it.copy(preset = EqualizerPreset.CUSTOM) }
            persistSettings()
            return
        }

        val updatedBands = _state.value.bands.mapIndexed { index, band ->
            band.copy(gainDb = preset.bandGains[index])
        }

        val newBassBoost = if (preset.recommendedBassBoost > 0 && _state.value.bassBoostStrength == 0) {
            preset.recommendedBassBoost
        } else {
            _state.value.bassBoostStrength
        }

        _state.update {
            it.copy(
                preset = preset,
                bands = updatedBands,
                bassBoostStrength = newBassBoost
            )
        }
        publish()
        persistSettings()
    }

    /**
     * Sets the bass boost intensity in range [0, 1000].
     */
    @Synchronized
    fun setBassBoostStrength(strength: Int) {
        val clamped = strength.coerceIn(MIN_BASS_BOOST, MAX_BASS_BOOST)
        if (_state.value.bassBoostStrength == clamped) return

        _state.update { it.copy(bassBoostStrength = clamped) }
        publish()
        persistSettings()
    }

    /**
     * Resets the equalizer curve to Flat (0dB across all bands) and resets bass boost to 0.
     */
    @Synchronized
    fun resetToFlat() {
        setPreset(EqualizerPreset.FLAT)
        setBassBoostStrength(0)
    }

    /** Hands the settings to the players' processors. */
    private fun publish() {
        val s = _state.value
        EqualizerCurve.current = EqualizerCurve(
            enabled = s.isEnabled,
            bandsDb = FloatArray(5) { i -> s.bands.getOrNull(i)?.gainDb ?: 0f },
            bassBoost = s.bassBoostStrength
        )
    }

    // ==========================================
    // Persistence
    // ==========================================

    private fun loadInitialState(): EqualizerState {
        val isEnabled = sharedPreferences.getBoolean(KEY_ENABLED, true)
        val savedPresetName = sharedPreferences.getString(KEY_PRESET, EqualizerPreset.FLAT.name)
        val preset = EqualizerPreset.fromName(savedPresetName)
        val savedBassBoost = sharedPreferences.getInt(KEY_BASS_BOOST, 0).coerceIn(MIN_BASS_BOOST, MAX_BASS_BOOST)

        val bands = EqualizerState.TARGET_CENTER_FREQS_HZ.indices.map { i ->
            val defaultGain = if (preset != EqualizerPreset.CUSTOM) preset.bandGains[i] else 0f
            val savedGain = sharedPreferences.getFloat("$KEY_BAND_PREFIX$i", defaultGain)
                .coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            EqualizerBand(
                index = i,
                centerFreqHz = EqualizerState.TARGET_CENTER_FREQS_HZ[i],
                label = EqualizerState.TARGET_CENTER_FREQS_LABEL[i],
                gainDb = savedGain
            )
        }

        return EqualizerState(
            isEnabled = isEnabled,
            preset = preset,
            bands = bands,
            bassBoostStrength = savedBassBoost,
            isHardwareAttached = false,
            audioSessionId = 0
        )
    }

    private fun persistSettings() {
        val current = _state.value
        sharedPreferences.edit().apply {
            putBoolean(KEY_ENABLED, current.isEnabled)
            putString(KEY_PRESET, current.preset.name)
            putInt(KEY_BASS_BOOST, current.bassBoostStrength)
            current.bands.forEach { band ->
                putFloat("$KEY_BAND_PREFIX${band.index}", band.gainDb)
            }
            apply()
        }
    }
}
