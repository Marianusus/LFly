package org.sonora.android.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sonora.android.R
import org.sonora.android.connect.ConnectManager
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import org.sonora.connect.ConnectProtocol

/** One device in the picker, Spotify style: icon, name, a line under it, check or spinner. */
@Composable
internal fun DeviceRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    active: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) SonoraEmerald.copy(alpha = 0.14f) else SonoraSurfaceHighlight)
            .clickable(
                enabled = !active && !busy,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (active) SonoraEmerald.copy(alpha = 0.22f) else SonoraTextTertiary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (active) SonoraEmerald else SonoraTextSecondary, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                color = if (active) SonoraEmerald else SonoraTextPrimary
            )
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.labelMedium, color = SonoraTextSecondary)
            }
        }
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = SonoraEmerald)
            active -> Icon(Icons.Default.Check, contentDescription = null, tint = SonoraEmerald, modifier = Modifier.size(20.dp))
        }
    }
}

/** The volume of the LFly device that plays, under the current device. */
@Composable
internal fun RemoteVolumeRow(volume: Int) {
    var level by remember { mutableFloatStateOf(volume / 100f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(volume) { if (!dragging) level = volume / 100f }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SonoraSurfaceHighlight)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = SonoraEmerald, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(12.dp))
        Slider(
            value = level,
            onValueChange = {
                dragging = true
                level = it
            },
            onValueChangeFinished = {
                dragging = false
                ConnectManager.setRemoteVolume((level * 100).toInt())
            },
            colors = SliderDefaults.colors(
                thumbColor = SonoraEmerald,
                activeTrackColor = SonoraEmerald,
                inactiveTrackColor = SonoraTextTertiary.copy(alpha = 0.22f)
            ),
            modifier = Modifier.weight(1f).height(30.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text("${(level * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = SonoraTextSecondary, modifier = Modifier.width(38.dp))
    }
}

internal fun connectIconFor(kind: String): ImageVector = when (kind) {
    ConnectProtocol.KIND_DESKTOP -> Icons.Default.Computer
    ConnectProtocol.KIND_TABLET -> Icons.Default.Tablet
    ConnectProtocol.KIND_TV -> Icons.Default.Tv
    ConnectProtocol.KIND_WATCH -> Icons.Default.Watch
    else -> Icons.Default.Smartphone
}

/**
 * The picker is a window of its own, so the activity never sees the volume keys while it is open.
 * This routes them to the playing LFly device from inside that window too.
 */
@Composable
internal fun ConnectVolumeKeys() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        var parent: android.view.ViewParent? = view.parent
        while (parent != null && parent !is androidx.compose.ui.window.DialogWindowProvider) parent = parent.parent
        val window = (parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        val original = window?.callback
        if (window != null && original != null) {
            window.callback = object : android.view.Window.Callback by original {
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                    val up = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                    val down = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                    if ((up || down) && ConnectManager.following.value != null) {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN) ConnectManager.nudgeRemoteVolume(up)
                        return true
                    }
                    return original.dispatchKeyEvent(event)
                }
            }
        }
        onDispose { if (window != null && original != null) window.callback = original }
    }
}

/** Another device asking to control this phone: shown over the whole app until answered. */
@Composable
fun ConnectRequestDialog() {
    val request by ConnectManager.request.collectAsStateWithLifecycle()
    val current = request ?: return
    AlertDialog(
        onDismissRequest = current.refuse,
        title = { Text(stringResource(R.string.connect_request_title, current.deviceName)) },
        text = {
            Column {
                Text(stringResource(R.string.connect_request_body))
                Text(
                    text = current.code,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = SonoraTextPrimary,
                    modifier = Modifier.padding(top = 12.dp).align(Alignment.CenterHorizontally)
                )
            }
        },
        confirmButton = { TextButton(onClick = current.accept) { Text(stringResource(R.string.connect_request_accept)) } },
        dismissButton = { TextButton(onClick = current.refuse) { Text(stringResource(R.string.connect_request_refuse)) } }
    )
}
