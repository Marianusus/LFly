package org.sonora.android.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.sonora.android.R
import org.sonora.android.party.DiscoveredParty
import org.sonora.android.party.PartyCode
import org.sonora.android.party.PartyRole
import org.sonora.android.party.PartyState
import org.sonora.android.party.SyncEngine
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary

/**
 * Bottom sheet for the synchronized listening "Party": create a session, join a
 * nearby one (list, QR or code), tune per-device headphone delay, manage guest
 * permissions and leave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyBottomSheet(
    isOpen: Boolean,
    state: PartyState,
    discovered: List<DiscoveredParty>,
    permissionsGranted: Boolean,
    onDismissRequest: () -> Unit,
    onCreate: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onJoin: (String) -> Unit,
    onJoinByCode: (String) -> Unit,
    onLeave: () -> Unit,
    onToggleAllowControl: (Boolean) -> Unit,
    onToggleAllowQueue: (Boolean) -> Unit,
    onNudgeChange: (Long) -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val scheme = LocalBackgroundScheme.current
    val chrome = chromeSurface(scheme)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw -> PartyCode.parseUri(raw)?.first?.let(onJoinByCode) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = chrome.tint,
        contentColor = scheme.onBackground,
        scrimColor = scheme.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Groups,
                    contentDescription = null,
                    tint = scheme.accent,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.party_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = SonoraTextPrimary
                )
            }

            when (state.role) {
                PartyRole.IDLE -> PartyIdleContent(
                    state = state,
                    discovered = discovered,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = onRequestPermissions,
                    onCreate = onCreate,
                    onScan = onScan,
                    onStopScan = onStopScan,
                    onJoin = onJoin,
                    onJoinByCode = onJoinByCode,
                    onScanQr = {
                        scanLauncher.launch(
                            ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("")
                                setBeepEnabled(false)
                                setOrientationLocked(true)
                            }
                        )
                    }
                )

                PartyRole.HOST -> PartyHostContent(
                    state = state,
                    onLeave = onLeave,
                    onToggleAllowControl = onToggleAllowControl,
                    onToggleAllowQueue = onToggleAllowQueue,
                    onNudgeChange = onNudgeChange
                )

                PartyRole.GUEST -> PartyGuestContent(
                    state = state,
                    onLeave = onLeave,
                    onNudgeChange = onNudgeChange
                )
            }

            state.error?.let { error ->
                Text(
                    text = errorMessage(error),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE57373)
                )
            }
        }
    }
}

@Composable
private fun PartyIdleContent(
    state: PartyState,
    discovered: List<DiscoveredParty>,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onCreate: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onJoin: (String) -> Unit,
    onJoinByCode: (String) -> Unit,
    onScanQr: () -> Unit
) {
    val scheme = LocalBackgroundScheme.current
    var codeInput by remember { mutableStateOf("") }

    Text(
        text = stringResource(R.string.party_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = SonoraTextSecondary
    )

    if (!permissionsGranted) {
        PermissionCard(onRequestPermissions = onRequestPermissions)
        return
    }

    Button(
        onClick = onCreate,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.party_create), fontWeight = FontWeight.SemiBold)
    }

    HorizontalDivider(color = scheme.onBackground.copy(alpha = 0.12f))

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onScanQr,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.party_scan_qr), maxLines = 1)
        }
        OutlinedButton(
            onClick = onScan,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.party_join), maxLines = 1)
        }
    }

    // Manual code entry
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = codeInput,
            onValueChange = { raw -> codeInput = raw.uppercase().take(PartyCode.LENGTH) },
            singleLine = true,
            label = { Text(stringResource(R.string.party_enter_code)) },
            placeholder = { Text(stringResource(R.string.party_code_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = { onJoinByCode(codeInput) },
            enabled = PartyCode.isValid(codeInput),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(stringResource(R.string.party_join))
        }
    }

    if (state.busy) {
        Text(
            text = stringResource(R.string.party_scanning),
            style = MaterialTheme.typography.bodySmall,
            color = SonoraTextSecondary
        )
    }

    if (discovered.isNotEmpty()) {
        Text(
            text = stringResource(R.string.party_nearby_title),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = SonoraTextPrimary
        )
        discovered.forEach { party ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SonoraOpaqueSurfaceCard)
                    .clickable { onJoin(party.endpointId) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = scheme.accent)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = party.hostNick,
                        style = MaterialTheme.typography.bodyLarge,
                        color = SonoraTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${stringResource(R.string.party_code_label)}: ${party.code}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SonoraTextSecondary
                    )
                }
                TextButton(onClick = { onJoin(party.endpointId) }) {
                    Text(stringResource(R.string.party_join))
                }
            }
        }
        TextButton(onClick = onStopScan) {
            Text(stringResource(R.string.party_stop_scan))
        }
    } else if (!state.busy) {
        Text(
            text = stringResource(R.string.party_nearby_empty),
            style = MaterialTheme.typography.bodySmall,
            color = SonoraTextSecondary
        )
    }
}

@Composable
private fun PartyHostContent(
    state: PartyState,
    onLeave: () -> Unit,
    onToggleAllowControl: (Boolean) -> Unit,
    onToggleAllowQueue: (Boolean) -> Unit,
    onNudgeChange: (Long) -> Unit
) {
    val scheme = LocalBackgroundScheme.current
    val context = LocalContext.current
    val code = state.code ?: "------"
    val qr = remember(code, state.selfNick) {
        runCatching {
            val text = PartyCode.toUri(code, state.selfNick)
            val size = 512
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }

    Text(
        text = stringResource(R.string.party_you_are_host),
        style = MaterialTheme.typography.bodyMedium,
        color = SonoraTextSecondary
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = code,
            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 4.sp, fontWeight = FontWeight.Bold),
            color = scheme.accent
        )
        IconButton(onClick = {
            copyToClipboard(context, code)
        }) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = SonoraTextSecondary)
        }
    }

    qr?.let { image ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.party_show_qr),
                modifier = Modifier.size(180.dp)
            )
        }
    }

    Icon(Icons.Default.QrCode2, contentDescription = null, tint = SonoraTextSecondary)

    Text(
        text = pluralStringResource(R.plurals.party_guest_count, state.guests.size, state.guests.size),
        style = MaterialTheme.typography.labelLarge,
        color = SonoraTextPrimary
    )
    state.guests.forEach { guest ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Headphones, contentDescription = null, tint = scheme.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(guest.nick, color = SonoraTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    HorizontalDivider(color = scheme.onBackground.copy(alpha = 0.12f))

    SwitchRow(
        label = stringResource(R.string.party_allow_control),
        checked = state.allowGuestControl,
        onCheckedChange = onToggleAllowControl
    )
    SwitchRow(
        label = stringResource(R.string.party_allow_queue),
        checked = state.allowQueueAdd,
        onCheckedChange = onToggleAllowQueue
    )

    NudgeSlider(nudgeMs = state.nudgeMs, onNudgeChange = onNudgeChange)

    OutlinedButton(
        onClick = onLeave,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.party_end))
    }
}

@Composable
private fun PartyGuestContent(
    state: PartyState,
    onLeave: () -> Unit,
    onNudgeChange: (Long) -> Unit
) {
    val scheme = LocalBackgroundScheme.current
    val host = state.hostNick ?: "Host"
    Text(
        text = stringResource(R.string.party_connected_to, host),
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        color = SonoraTextPrimary
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Sync,
            contentDescription = null,
            tint = if (state.syncReady) scheme.accent else SonoraTextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(if (state.syncReady) R.string.party_synced else R.string.party_syncing),
            style = MaterialTheme.typography.bodyMedium,
            color = SonoraTextSecondary
        )
    }

    if (!state.allowGuestControl && !state.allowQueueAdd) {
        Text(
            text = stringResource(R.string.party_action_not_allowed),
            style = MaterialTheme.typography.bodySmall,
            color = SonoraTextSecondary
        )
    }

    NudgeSlider(nudgeMs = state.nudgeMs, onNudgeChange = onNudgeChange)

    OutlinedButton(
        onClick = onLeave,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.party_leave))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = SonoraTextPrimary,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = LocalBackgroundScheme.current.accent)
        )
    }
}

@Composable
private fun NudgeSlider(nudgeMs: Long, onNudgeChange: (Long) -> Unit) {
    val scheme = LocalBackgroundScheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.party_nudge),
                style = MaterialTheme.typography.labelLarge,
                color = SonoraTextPrimary
            )
            Text(
                text = if (nudgeMs == 0L) "0 ms" else "${if (nudgeMs > 0) "+" else ""}$nudgeMs ms",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.accent
            )
        }
        Slider(
            value = nudgeMs.toFloat(),
            onValueChange = { onNudgeChange(it.toLong()) },
            valueRange = -SyncEngine.NUDGE_RANGE_MS.toFloat()..SyncEngine.NUDGE_RANGE_MS.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = scheme.accent,
                activeTrackColor = scheme.accent
            )
        )
        Text(
            text = stringResource(R.string.party_nudge_hint),
            style = MaterialTheme.typography.bodySmall,
            color = SonoraTextSecondary
        )
    }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit) {
    val scheme = LocalBackgroundScheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SonoraOpaqueSurfaceCard)
            .border(1.dp, scheme.onBackground.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = scheme.accent)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.party_permissions),
                style = MaterialTheme.typography.bodyMedium,
                color = SonoraTextPrimary
            )
        }
        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = scheme.accent),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.party_grant))
        }
    }
}

@Composable
private fun errorMessage(code: String): String = when (code) {
    "code_not_found" -> stringResource(R.string.party_error_code_not_found)
    "host_left" -> stringResource(R.string.party_error_host_left)
    "host_disconnected" -> stringResource(R.string.party_error_host_disconnected)
    else -> stringResource(R.string.party_error_generic)
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Party code", text))
}
