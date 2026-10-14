package org.sonora.android.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * Native Android 5-Band Equalizer and Bass Boost manager.
 *
 * Wraps [android.media.audiofx.Equalizer] and [android.media.audiofx.BassBoost],
 * seamlessly attaching to ExoPlayer's `audioSessionId`.
 *
 * Persists user customizations to [SharedPreferences] across application lifecycles.
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

    // Native Audio FX instances
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var currentAudioSessionId: Int = 0

    // Hardware parameters
    private var minBandLevelMb: Int = -1500
    private var maxBandLevelMb: Int = 1500
    private val hwBandIndices = ShortArray(5) { it.toShort() }

    /**
     * Attaches audio effects to the given [audioSessionId] from ExoPlayer.
     * Safe to call multiple times or with 0 / C.AUDIO_SESSION_ID_UNSET.
     */
    @OptIn(UnstableApi::class)
    @Synchronized
    fun attachAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            Log.d(TAG, "Ignoring attach request for uninitialized audio session: $audioSessionId")
            return
        }

        if (currentAudioSessionId == audioSessionId && equalizer != null && bassBoost != null) {
            Log.d(TAG, "Already attached to audioSessionId: $audioSessionId")
            return
        }

        Log.i(TAG, "Attaching audio effects to audioSessionId: $audioSessionId")
        releaseHardwareEffects()
        currentAudioSessionId = audioSessionId

        // Initialize Equalizer
        try {
            val eq = Equalizer(0, audioSessionId)
            this.equalizer = eq

            val bandRange = try { eq.bandLevelRange } catch (e: Exception) { shortArrayOf(-1500, 1500) }
            minBandLevelMb = bandRange?.getOrNull(0)?.toInt() ?: -1500
            maxBandLevelMb = bandRange?.getOrNull(1)?.toInt() ?: 1500

            val numHwBands = try { eq.numberOfBands.toInt() } catch (e: Exception) { 0 }
            mapHardwareBands(eq, numHwBands)

            eq.enabled = _state.value.isEnabled
            applyBandsToHardware(eq)
            Log.i(TAG, "Equalizer successfully initialized. Bands: $numHwBands, range: [$minBandLevelMb, $maxBandLevelMb] mB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate native Equalizer for session $audioSessionId: ${e.message}", e)
            equalizer = null
        }

        // Initialize BassBoost
        try {
            val bb = BassBoost(0, audioSessionId)
            this.bassBoost = bb
            val strengthSupported = try { bb.strengthSupported } catch (e: Exception) { true }
            bb.enabled = _state.value.isEnabled && _state.value.bassBoostStrength > 0
            if (strengthSupported && _state.value.bassBoostStrength > 0) {
                bb.setStrength(_state.value.bassBoostStrength.toShort())
            }
            Log.i(TAG, "BassBoost successfully initialized on session $audioSessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate native BassBoost for session $audioSessionId: ${e.message}", e)
            bassBoost = null
        }

        val isAttached = equalizer != null || bassBoost != null
        _state.update {
            it.copy(
                isHardwareAttached = isAttached,
                audioSessionId = audioSessionId
            )
        }
    }

    /**
     * Detaches and releases native audio effect handles to avoid native resource leaks.
     */
    @Synchronized
    fun detachAudioSession() {
        Log.i(TAG, "Detaching audio effects from session $currentAudioSessionId")
        releaseHardwareEffects()
        currentAudioSessionId = 0
        _state.update {
            it.copy(
                isHardwareAttached = false,
                audioSessionId = 0
            )
        }
    }

    /**
     * Toggles master equalizer & audio DSP processing on or off.
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        _state.update { it.copy(isEnabled = enabled) }

        try {
            equalizer?.enabled = enabled
        } catch (e: Exception) {
            Log.e(TAG, "Error setting equalizer enabled state: ${e.message}", e)
        }

        try {
            bassBoost?.enabled = enabled && _state.value.bassBoostStrength > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bass boost enabled state: ${e.message}", e)
        }

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

        equalizer?.let { eq ->
            val hwBand = hwBandIndices[bandIndex]
            val mb = (clamped * 100f).roundToInt().coerceIn(minBandLevelMb, maxBandLevelMb).toShort()
            try {
                eq.setBandLevel(hwBand, mb)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting band $hwBand level to $mb mB: ${e.message}", e)
            }
        }

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

        equalizer?.let { applyBandsToHardware(it) }
        bassBoost?.let { applyBassBoostToHardware(it) }

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

        bassBoost?.let { applyBassBoostToHardware(it) }
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

    // ==========================================
    // Internal Hardware Helpers
    // ==========================================

    private fun mapHardwareBands(eq: Equalizer, numHwBands: Int) {
        if (numHwBands <= 0) return

        // Query center frequencies for all hardware bands (getCenterFreq returns milliHertz)
        val hwBandsWithFreq = (0 until numHwBands).mapNotNull { b ->
            try {
                val centerMhz = eq.getCenterFreq(b.toShort())
                b.toShort() to centerMhz
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading center freq for band $b", e)
                null
            }
        }.sortedBy { it.second } // Sort ascending by frequency (mHz)

        if (hwBandsWithFreq.isEmpty()) {
            for (i in 0 until 5) {
                hwBandIndices[i] = i.coerceAtMost(numHwBands - 1).toShort()
            }
            return
        }

        if (hwBandsWithFreq.size == 5) {
            // Exactly 5 bands: map them strictly in ascending frequency order so band 0 is lowest (60Hz) and band 4 is highest (14kHz)
            for (i in 0 until 5) {
                hwBandIndices[i] = hwBandsWithFreq[i].first
            }
        } else {
            // Different number of bands: match each target frequency to the closest hardware band
            for (i in 0 until 5) {
                val targetMhz = EqualizerState.TARGET_CENTER_FREQS_HZ[i] * 1000L
                val closest = hwBandsWithFreq.minByOrNull { (_, freqMhz) ->
                    abs(freqMhz.toLong() - targetMhz)
                }
                hwBandIndices[i] = closest?.first ?: 0.toShort()
            }
        }

        Log.i(TAG, "Mapped 5 equalizer bands to hardware indices: ${hwBandIndices.joinToString { it.toString() }}")
    }

    private fun applyBandsToHardware(eq: Equalizer) {
        val bands = _state.value.bands
        bands.forEach { band ->
            val hwBand = hwBandIndices[band.index]
            val mb = (band.gainDb * 100f).roundToInt().coerceIn(minBandLevelMb, maxBandLevelMb).toShort()
            try {
                eq.setBandLevel(hwBand, mb)
            } catch (e: Exception) {
                Log.e(TAG, "Error applying hardware band $hwBand ($mb mB): ${e.message}", e)
            }
        }
    }

    private fun applyBassBoostToHardware(bb: BassBoost) {
        val strength = _state.value.bassBoostStrength.coerceIn(MIN_BASS_BOOST, MAX_BASS_BOOST)
        val isEnabled = _state.value.isEnabled
        try {
            bb.enabled = isEnabled && strength > 0
            if (bb.strengthSupported) {
                bb.setStrength(strength.toShort())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying bass boost ($strength): ${e.message}", e)
        }
    }

    private fun releaseHardwareEffects() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Exception releasing Equalizer", e)
        }
        equalizer = null

        try {
            bassBoost?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Exception releasing BassBoost", e)
        }
        bassBoost = null
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
