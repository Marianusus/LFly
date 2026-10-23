package org.sonora.android.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local audio outputs available to the player. Ported from BitChord's
 * AudioRouting (GPLv3), trimmed to the sinks Sonora can route to.
 */
object AudioRouting {
    enum class Kind { PHONE, WIRED, USB, BLUETOOTH, HDMI, OTHER }

    data class Device(
        val id: Int,
        val type: Int,
        val name: String,
        val kind: Kind,
        val isActive: Boolean = false,
    )

    private val _selectedId = MutableStateFlow<Int?>(null)
    val selectedId: StateFlow<Int?> = _selectedId.asStateFlow()

    fun select(id: Int?) {
        _selectedId.value = id
    }

    fun forget() {
        _selectedId.value = null
    }

    fun manager(context: Context): AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun infoFor(context: Context, id: Int?): AudioDeviceInfo? {
        if (id == null) return null
        return runCatching {
            manager(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull()?.firstOrNull { it.id == id }
    }

    fun outputs(context: Context, selected: Int? = _selectedId.value): List<Device> {
        val infos = runCatching {
            manager(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull() ?: return emptyList()
        val active = activeOf(infos, selected)
        return infos
            .mapNotNull { info -> kindOf(info.type)?.let { info to it } }
            .map { (info, kind) ->
                Device(
                    id = info.id,
                    type = info.type,
                    name = nameOf(info, kind),
                    kind = kind,
                    isActive = info.id == active?.id,
                )
            }
            .groupBy { it.name to it.kind }
            .map { (_, same) -> same.minByOrNull { preferenceWithin(it.type) }!! }
            .sortedBy { it.kind.ordinal }
    }

    fun activeOf(infos: Array<AudioDeviceInfo>, chosenId: Int?): AudioDeviceInfo? {
        chosenId?.let { id -> infos.firstOrNull { it.id == id } }?.let { return it }
        return infos.firstOrNull { kindOf(it.type) == Kind.BLUETOOTH }
            ?: infos.firstOrNull { kindOf(it.type) == Kind.WIRED }
            ?: infos.firstOrNull { kindOf(it.type) == Kind.USB }
            ?: infos.firstOrNull { kindOf(it.type) == Kind.HDMI }
            ?: infos.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    internal fun kindOf(type: Int): Kind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.PHONE
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_AUX_LINE -> Kind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> Kind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC -> Kind.HDMI
        else -> null
    }

    private fun preferenceWithin(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 1
        else -> 2
    }

    private fun nameOf(info: AudioDeviceInfo, kind: Kind): String = when (kind) {
        Kind.PHONE, Kind.WIRED -> ""
        else -> info.productName?.toString()?.trim().orEmpty()
    }
}
