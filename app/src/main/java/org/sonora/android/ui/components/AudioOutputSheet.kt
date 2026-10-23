package org.sonora.android.ui.components

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.sonora.android.R
import org.sonora.android.cast.CastDiscovery
import org.sonora.android.playback.AudioRouting
import org.sonora.android.playback.PlaybackManager
import org.sonora.android.playback.audioVolumeFraction
import org.sonora.android.playback.audioVolumeIndex
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary

private enum class AudioSheetPage { OUTPUTS, CAST }

/**
 * Opens the output picker, asking for the Bluetooth permission first on Android 12+.
 * The permission is only needed to read device names; refusing it still opens the
 * sheet so local outputs remain usable.
 */
@Composable
fun rememberAudioOutputLauncher(onOpen: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { currentOnOpen() }
    return {
        val granted = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            currentOnOpen()
        } else {
            runCatching {
                permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }.onFailure { currentOnOpen() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputSheet(
    onDismiss: () -> Unit,
    onOpenEqualizer: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val outputs = rememberAudioOutputs()
    val castDevices by CastDiscovery.devices.collectAsStateWithLifecycle()
    val castSupported by CastDiscovery.supported.collectAsStateWithLifecycle()
    val castActiveName by CastDiscovery.activeName.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(AudioSheetPage.OUTPUTS) }

    DisposableEffect(Unit) {
        CastDiscovery.ensureStarted(context)
        onDispose { CastDiscovery.stopDiscovery() }
    }

    LaunchedEffect(page) {
        if (page == AudioSheetPage.CAST) {
            CastDiscovery.discover(context)
        } else {
            CastDiscovery.stopDiscovery()
        }
    }

    BackHandler {
        if (page == AudioSheetPage.CAST) page = AudioSheetPage.OUTPUTS else onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SonoraOpaqueSurfaceCard,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        if (page == AudioSheetPage.CAST) {
            CastPickerPage(
                devices = castDevices,
                activeName = castActiveName,
                supported = castSupported,
                onBack = { page = AudioSheetPage.OUTPUTS },
                onConnect = { CastDiscovery.connect(it) },
                onDisconnect = { CastDiscovery.disconnect() },
            )
        } else {
            OutputsPage(
                outputs = outputs,
                castSupported = castSupported,
                castActiveName = castActiveName,
                onSelectOutput = { id ->
                    if (castActiveName != null) CastDiscovery.disconnect()
                    PlaybackManager.getInstance(context).setAudioOutput(id)
                },
                onOpenCast = { page = AudioSheetPage.CAST },
                onOpenEqualizer = onOpenEqualizer?.let {
                    {
                        onDismiss()
                        it()
                    }
                },
            )
        }
    }
}

@Composable
private fun OutputsPage(
    outputs: List<AudioRouting.Device>,
    castSupported: Boolean,
    castActiveName: String?,
    onSelectOutput: (Int) -> Unit,
    onOpenCast: () -> Unit,
    onOpenEqualizer: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.audio_output_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = SonoraTextPrimary,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 10.dp),
        )

        if (outputs.isEmpty()) {
            OutputRow(
                icon = Icons.Default.Speaker,
                title = stringResource(R.string.audio_output_other),
                subtitle = stringResource(R.string.audio_output_no_devices),
                enabled = false,
                onClick = {},
            )
        } else {
            outputs.forEach { device ->
                OutputRow(
                    icon = iconFor(device.kind),
                    title = labelFor(device),
                    active = device.isActive,
                    onClick = { onSelectOutput(device.id) },
                )
            }
        }

        if (castSupported) {
            OutputRow(
                icon = if (castActiveName != null) Icons.Default.CastConnected else Icons.Default.Cast,
                title = castActiveName ?: stringResource(R.string.audio_output_cast),
                subtitle = if (castActiveName != null) {
                    stringResource(R.string.audio_output_cast_active)
                } else {
                    stringResource(R.string.audio_output_cast_row_subtitle)
                },
                active = castActiveName != null,
                clickableWhenActive = true,
                onClick = onOpenCast,
            )
        }

        Spacer(Modifier.height(4.dp))
        VolumeRow(routeKey = outputs)

        if (onOpenEqualizer != null) {
            OutputRow(
                icon = Icons.Default.GraphicEq,
                title = stringResource(R.string.player_equalizer_dsp),
                subtitle = stringResource(R.string.audio_output_equalizer_subtitle),
                onClick = onOpenEqualizer,
            )
        }
    }
}

@Composable
private fun CastPickerPage(
    devices: List<CastDiscovery.Device>,
    activeName: String?,
    supported: Boolean,
    onBack: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.audio_output_back),
                    tint = SonoraTextPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.audio_output_cast_section),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = SonoraTextPrimary,
                )
                if (activeName != null) {
                    Text(
                        text = activeName,
                        style = MaterialTheme.typography.bodySmall,
                        color = SonoraTextSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        when {
            !supported -> OutputRow(
                icon = Icons.Default.Cast,
                title = stringResource(R.string.audio_output_cast_unavailable),
                subtitle = stringResource(R.string.audio_output_cast_row_subtitle),
                enabled = false,
                onClick = {},
            )
            devices.isEmpty() -> OutputRow(
                icon = Icons.Default.Cast,
                title = stringResource(R.string.audio_output_cast_searching),
                subtitle = stringResource(R.string.audio_output_cast_row_subtitle),
                enabled = false,
                onClick = {},
            )
            else -> devices.forEach { device ->
                OutputRow(
                    icon = if (device.connected) Icons.Default.CastConnected else Icons.Default.Cast,
                    title = device.name,
                    subtitle = when {
                        device.connecting -> stringResource(R.string.audio_output_cast_connecting)
                        device.connected -> stringResource(R.string.audio_output_cast_connected)
                        else -> stringResource(R.string.audio_output_cast_row_subtitle)
                    },
                    active = device.connected,
                    onClick = { onConnect(device.id) },
                )
            }
        }

        if (activeName != null) {
            OutputRow(
                icon = Icons.Default.CastConnected,
                title = stringResource(R.string.audio_output_disconnect_cast, activeName),
                onClick = onDisconnect,
            )
        }
    }
}

@Composable
private fun VolumeRow(routeKey: Any) {
    val context = LocalContext.current
    val manager = remember(context) { AudioRouting.manager(context) }
    var level by remember { mutableFloatStateOf(readVolume(manager)) }
    var dragging by remember { mutableStateOf(false) }
    var maxVolume by remember { mutableIntStateOf(streamMaxVolume(manager)) }

    fun refresh() {
        maxVolume = streamMaxVolume(manager)
        if (!dragging) level = readVolume(manager)
    }

    LaunchedEffect(routeKey) {
        repeat(4) {
            refresh()
            delay(250)
        }
    }

    DisposableEffect(manager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val stream = intent.getIntExtra(
                    EXTRA_VOLUME_STREAM_TYPE,
                    -1
                )
                if (stream == AudioManager.STREAM_MUSIC) refresh()
            }
        }
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SonoraSurfaceHighlight)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (level > 0f) {
                Icons.AutoMirrored.Filled.VolumeUp
            } else {
                Icons.AutoMirrored.Filled.VolumeOff
            },
            contentDescription = null,
            tint = SonoraTextSecondary,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.width(12.dp))
        Slider(
            value = level,
            onValueChange = { value ->
                dragging = true
                level = value
                writeVolume(manager, value, maxVolume)
            },
            onValueChangeFinished = { dragging = false },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = SonoraEmerald,
                activeTrackColor = SonoraEmerald,
                inactiveTrackColor = SonoraTextTertiary.copy(alpha = 0.22f),
            ),
            modifier = Modifier
                .weight(1f)
                .height(30.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${(level * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = SonoraTextSecondary,
            modifier = Modifier.width(38.dp),
        )
    }
}

private fun streamMaxVolume(manager: AudioManager): Int =
    runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
        .getOrDefault(1)
        .coerceAtLeast(1)

private fun readVolume(manager: AudioManager): Float {
    val value = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }
        .getOrDefault(0)
    return audioVolumeFraction(value, streamMaxVolume(manager))
}

private fun writeVolume(manager: AudioManager, fraction: Float, max: Int) {
    val value = audioVolumeIndex(fraction, max)
    runCatching {
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
    }
}

@Composable
private fun rememberAudioOutputs(): List<AudioRouting.Device> {
    val context = LocalContext.current
    val manager = remember(context) { AudioRouting.manager(context) }
    val selected by AudioRouting.selectedId.collectAsStateWithLifecycle()
    val playbackManager = remember(context) { PlaybackManager.getInstance(context) }
    var outputs by remember(manager) {
        mutableStateOf(AudioRouting.outputs(context, selected))
    }

    LaunchedEffect(selected) {
        outputs = AudioRouting.outputs(context, selected)
    }

    LaunchedEffect(selected, outputs) {
        if (selected != null && outputs.isNotEmpty() && outputs.none { it.id == selected }) {
            playbackManager.setAudioOutput(null)
        }
    }

    DisposableEffect(manager) {
        val handler = Handler(Looper.getMainLooper())
        fun refresh() {
            outputs = AudioRouting.outputs(context, AudioRouting.selectedId.value)
        }
        fun refreshSoon() {
            refresh()
            SETTLE_MS.forEach { wait -> handler.postDelayed(::refresh, wait) }
        }

        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshSoon()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshSoon()
            }
        }
        manager.registerAudioDeviceCallback(deviceCallback, handler)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshSoon()
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_HDMI_AUDIO_PLUG)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }

        onDispose {
            manager.unregisterAudioDeviceCallback(deviceCallback)
            runCatching { context.unregisterReceiver(receiver) }
            handler.removeCallbacksAndMessages(null)
        }
    }

    return outputs
}

private val SETTLE_MS = longArrayOf(350L, 1_200L, 2_500L)

private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

@Composable
private fun OutputRow(
    icon: ImageVector,
    title: String,
    active: Boolean = false,
    subtitle: String? = null,
    enabled: Boolean = true,
    clickableWhenActive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) SonoraEmerald.copy(alpha = 0.14f) else SonoraSurfaceHighlight
            )
            .clickable(
                enabled = enabled && (!active || clickableWhenActive),
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (active) SonoraEmerald.copy(alpha = 0.22f)
                    else SonoraTextTertiary.copy(alpha = 0.12f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) SonoraEmerald else SonoraTextSecondary,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = SonoraTextPrimary,
            )
            val caption = subtitle ?: if (active) {
                stringResource(R.string.audio_output_active)
            } else {
                null
            }
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelMedium,
                    color = SonoraTextSecondary,
                )
            }
        }
        if (active) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = SonoraEmerald,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun labelFor(device: AudioRouting.Device): String = when (device.kind) {
    AudioRouting.Kind.PHONE -> stringResource(R.string.audio_output_this_phone)
    AudioRouting.Kind.WIRED -> stringResource(R.string.audio_output_wired)
    AudioRouting.Kind.BLUETOOTH -> device.name.ifBlank { stringResource(R.string.audio_output_bluetooth) }
    AudioRouting.Kind.USB -> device.name.ifBlank { stringResource(R.string.audio_output_usb) }
    AudioRouting.Kind.HDMI -> device.name.ifBlank { stringResource(R.string.audio_output_hdmi) }
    AudioRouting.Kind.OTHER -> device.name.ifBlank { stringResource(R.string.audio_output_other) }
}

private fun iconFor(kind: AudioRouting.Kind): ImageVector = when (kind) {
    AudioRouting.Kind.PHONE -> Icons.Default.PhoneAndroid
    AudioRouting.Kind.WIRED -> Icons.Default.Headphones
    AudioRouting.Kind.BLUETOOTH -> Icons.Default.Bluetooth
    AudioRouting.Kind.USB -> Icons.Default.Usb
    AudioRouting.Kind.HDMI -> Icons.Default.Tv
    AudioRouting.Kind.OTHER -> Icons.Default.Speaker
}
