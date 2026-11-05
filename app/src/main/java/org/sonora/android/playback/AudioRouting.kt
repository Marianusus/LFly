package org.sonora.android.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local audio outputs available to the player. Ported from BitChord's
 * AudioRouting (GPLv3), trimmed to the sinks Sonora can route to; the output forms are LFly's own.
 */
object AudioRouting {
    enum class Kind { PHONE, WIRED, USB, BLUETOOTH, HDMI, OTHER }

    /** What the output physically is, for its icon: earbuds, over-ear headphones, a car, a speaker. */
    enum class Form { DEFAULT, EARBUDS, HEADPHONES, HEADSET, SPEAKER, CAR, WATCH }

    data class Device(
        val id: Int,
        val type: Int,
        val name: String,
        val kind: Kind,
        val isActive: Boolean = false,
        val form: Form = Form.DEFAULT,
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
                    form = formOf(context, info, kind),
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

    /**
     * The output's shape. Bluetooth says it in the paired device's class (headphones, car audio,
     * loudspeaker, watch); earbuds report themselves as headphones, so their name tells them apart.
     */
    private fun formOf(context: Context, info: AudioDeviceInfo, kind: Kind): Form {
        when (info.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> return Form.HEADSET
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> return Form.HEADPHONES
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> return Form.SPEAKER
            AudioDeviceInfo.TYPE_HEARING_AID -> return Form.EARBUDS
        }
        if (kind != Kind.BLUETOOTH) return Form.DEFAULT
        val name = info.productName?.toString().orEmpty()
        val byName = formByName(name)
        val major = bluetoothClassOf(context, info)
        val byClass = when (major) {
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> Form.CAR
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> Form.SPEAKER
            android.bluetooth.BluetoothClass.Device.WEARABLE_WRIST_WATCH -> Form.WATCH
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> Form.HEADPHONES
            else -> null
        }
        return when {
            // Earbuds call themselves headphones: the name is the better clue.
            byName == Form.EARBUDS && (byClass == null || byClass == Form.HEADPHONES) -> Form.EARBUDS
            byClass != null -> byClass
            byName != null -> byName
            else -> Form.HEADPHONES
        }
    }

    internal fun formByName(name: String): Form? {
        val n = name.lowercase()
        return when {
            listOf("buds", "pods", "earbud", "wf-", "tws", "elite", "freebuds", "liberty", "ear (", "nothing ear").any { it in n } -> Form.EARBUDS
            listOf("car", "auto", "carplay", "sync", "uconnect", "mylink", "bmw", "audi", "vw ", "toyota", "ford", "fiat", "mercedes").any { it in n } -> Form.CAR
            listOf("speaker", "boom", "flip", "charge", "soundlink", "soundbar", "home", "nest", "echo", "sonos").any { it in n } -> Form.SPEAKER
            listOf("watch").any { it in n } -> Form.WATCH
            listOf("headphone", "wh-", "qc", "momentum", "studio", "solo").any { it in n } -> Form.HEADPHONES
            else -> null
        }
    }

    /** The paired device's class, by address; null without the Bluetooth permission or address. */
    @Suppress("MissingPermission")
    private fun bluetoothClassOf(context: Context, info: AudioDeviceInfo): Int? = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 28) return null
        val address = info.address.takeIf { it.isNotBlank() } ?: return null
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return null
        adapter.bondedDevices?.firstOrNull { it.address.equals(address, ignoreCase = true) }?.bluetoothClass?.deviceClass
    }.getOrNull()

    private fun nameOf(info: AudioDeviceInfo, kind: Kind): String = when (kind) {
        Kind.PHONE, Kind.WIRED -> ""
        else -> info.productName?.toString()?.trim().orEmpty()
    }
}
