package org.sonora.android.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Playing straight to a USB DAC (Android 14+).
 *
 * Normally Android mixes every app at the phone's own rate (48 kHz) and resamples whatever plays.
 * With this on, before each song's audio track is made LFly asks the system for a mixer on the DAC
 * at the song's own format: bit-perfect when the DAC offers it (the decoded samples reach it
 * untouched, the system adds no volume and no effects), else at least at the song's sample rate.
 *
 * Bit-perfect needs the equalizer at rest: [EqualizerProcessor] copies the audio untouched when flat.
 */
object UsbDacOutput {
    private const val TAG = "LFlyUsbDac"
    private const val PREFS = "lfly_usb_dac"
    private const val KEY_ENABLED = "enabled"

    data class State(
        /** Android 14 or later: the system lets an app pick the DAC's mixer. */
        val supported: Boolean = false,
        /** The connected USB DAC's name, null when none is plugged in. */
        val dac: String? = null,
        /** Whether the user turned the direct output on. */
        val enabled: Boolean = false,
        /** The song's format as the DAC receives it now, e.g. "48 kHz · 16 bit"; null while not in use. */
        val format: String? = null,
        /** True when the DAC gets the samples untouched. */
        val bitPerfect: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var app: Context? = null

    /** Where the preference was applied last, to clear it when it no longer should apply. */
    @Volatile private var appliedTo: AudioDeviceInfo? = null
    @Volatile private var appliedFor: android.media.AudioAttributes? = null

    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        val enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
        _state.value = State(supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE, enabled = enabled)
        refreshDac()
        runCatching {
            AudioRouting.manager(ctx).registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshDac()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshDac()
            }, Handler(Looper.getMainLooper()))
        }
    }

    /** Turns the direct output on or off; it takes effect from the next song (or after a seek). */
    fun setEnabled(context: Context, enabled: Boolean) {
        init(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        _state.update { it.copy(enabled = enabled) }
        if (!enabled) clear()
        // A fresh audio track picks the new mixer up at once.
        PlaybackManager.getInstance(context).refreshAudioOutput()
    }

    private fun refreshDac() {
        val ctx = app ?: return
        val dac = usbOutput(ctx, chosenOnly = false)
        _state.update {
            it.copy(
                dac = dac?.productName?.toString()?.trim()?.ifBlank { null } ?: dac?.let { "USB" },
                format = if (dac == null) null else it.format,
                bitPerfect = dac != null && it.bitPerfect,
            )
        }
    }

    /** The USB output the music goes to: the one picked in the output sheet, else the plugged-in one. */
    private fun usbOutput(context: Context, chosenOnly: Boolean): AudioDeviceInfo? {
        val infos = runCatching { AudioRouting.manager(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull() ?: return null
        val chosen = AudioRouting.selectedId.value?.let { id -> infos.firstOrNull { it.id == id } }
        if (chosen != null) return chosen.takeIf { AudioRouting.kindOf(it.type) == AudioRouting.Kind.USB }
        if (chosenOnly) return null
        // Nothing picked: Android sends media to a plugged-in DAC by itself, unless Bluetooth is on.
        val active = AudioRouting.activeOf(infos, null)
        return infos.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            ?.takeIf { active == null || AudioRouting.kindOf(active.type) != AudioRouting.Kind.BLUETOOTH }
    }

    /**
     * Called by the player just before it makes an audio track for [config]: points the DAC's mixer
     * at that format, or clears what an earlier song set when the direct output does not apply.
     */
    @OptIn(UnstableApi::class)
    internal fun beforeTrack(
        context: Context?,
        config: AudioSink.AudioTrackConfig,
        attributes: androidx.media3.common.AudioAttributes
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val ctx = context?.applicationContext ?: app ?: return
        init(ctx)
        val platform = attributes.audioAttributesV21.audioAttributes
        val dac = if (_state.value.enabled && !config.offload && !config.tunneling) usbOutput(ctx, chosenOnly = false) else null
        if (dac == null) {
            clear()
            return
        }
        runCatching { apply(ctx, dac, platform, config) }
            .onFailure { Log.w(TAG, "Could not set the DAC's mixer: ${it.message}") }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun apply(context: Context, dac: AudioDeviceInfo, attributes: android.media.AudioAttributes, config: AudioSink.AudioTrackConfig) {
        val manager = AudioRouting.manager(context)
        val offered = manager.getSupportedMixerAttributes(dac)
        fun sameRate(m: AudioMixerAttributes) = m.format.sampleRate == config.sampleRate
        fun sameLayout(m: AudioMixerAttributes) = m.format.channelMask == config.channelConfig
        val exact = offered.firstOrNull {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                sameRate(it) && sameLayout(it) && it.format.encoding == config.encoding
        }
        // No bit-perfect mixer in this format: at least no resampling, the DAC runs at the song's rate.
        val chosen = exact
            ?: offered.firstOrNull { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT && sameRate(it) && sameLayout(it) }
            ?: offered.firstOrNull { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT && sameRate(it) }
        if (chosen == null) {
            Log.i(TAG, "The DAC offers no mixer at ${config.sampleRate} Hz")
            clear()
            return
        }
        val previousDevice = appliedTo
        val previousAttributes = appliedFor
        if (previousDevice != null && previousAttributes != null && previousDevice.id != dac.id) {
            runCatching { manager.clearPreferredMixerAttributes(previousAttributes, previousDevice) }
        }
        val ok = manager.setPreferredMixerAttributes(attributes, dac, chosen)
        if (!ok) {
            Log.w(TAG, "The system refused the DAC's mixer")
            clear()
            return
        }
        appliedTo = dac
        appliedFor = attributes
        val bitPerfect = chosen === exact
        val format = describe(config.sampleRate, chosen.format.encoding)
        Log.i(TAG, "DAC '${dac.productName}' at $format (bit-perfect: $bitPerfect)")
        _state.update { it.copy(format = format, bitPerfect = bitPerfect) }
    }

    private fun clear() {
        val device = appliedTo
        val attributes = appliedFor
        appliedTo = null
        appliedFor = null
        if (device != null && attributes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            app?.let { ctx -> runCatching { AudioRouting.manager(ctx).clearPreferredMixerAttributes(attributes, device) } }
        }
        _state.update { it.copy(format = null, bitPerfect = false) }
    }

    internal fun describe(sampleRate: Int, encoding: Int): String {
        val khz = if (sampleRate % 1000 == 0) "${sampleRate / 1000}" else "%.1f".format(sampleRate / 1000f)
        val bits = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> "8 bit"
            AudioFormat.ENCODING_PCM_16BIT -> "16 bit"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24 bit"
            AudioFormat.ENCODING_PCM_32BIT -> "32 bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "32 bit float"
            else -> null
        }
        return listOfNotNull("$khz kHz", bits).joinToString(" · ")
    }
}
