package org.sonora.android.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
        containerColor = Color(0xFF111113),
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.58f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.18f))
        },
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 2.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PartyHeader(
                role = state.role,
                busy = state.busy
            )

            PartyRadarCard(
                role = state.role,
                guestCount = state.guests.size,
                discoveredCount = discovered.size,
                busy = state.busy,
                syncReady = state.syncReady
            )

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
private fun PartyHeader(
    role: PartyRole,
    busy: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.party_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    letterSpacing = (-0.7).sp
                ),
                color = Color.White
            )
            Text(
                text = stringResource(R.string.party_subtitle),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 2
            )
        }

        if (role != PartyRole.IDLE || busy) {
            LivePill(
                label = if (busy && role == PartyRole.IDLE) {
                    stringResource(R.string.party_scanning)
                } else {
                    stringResource(R.string.party_live)
                },
                showDot = true
            )
        }
    }
}

@Composable
private fun LivePill(
    label: String,
    showDot: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val infinite = rememberInfiniteTransition(label = "partyLivePill")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "partyLiveDot"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.accent.copy(alpha = 0.14f))
            .border(1.dp, scheme.accent.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(scheme.accent.copy(alpha = alpha))
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp
            ),
            color = scheme.accent,
            maxLines = 1
        )
    }
}

@Composable
private fun PartyRadarCard(
    role: PartyRole,
    guestCount: Int,
    discoveredCount: Int,
    busy: Boolean,
    syncReady: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val accent = scheme.accent
    val infinite = rememberInfiniteTransition(label = "partyRadar")
    val wave by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "partyRadarWave"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "partyRadarPulse"
    )

    val orbitCount = when (role) {
        PartyRole.HOST -> guestCount
        PartyRole.IDLE -> discoveredCount
        PartyRole.GUEST -> 0
    }

    val status = when {
        role == PartyRole.HOST && guestCount == 0 ->
            stringResource(R.string.party_waiting_guests)
        role == PartyRole.HOST ->
            pluralStringResource(R.plurals.party_listening_count, guestCount, guestCount)
        role == PartyRole.GUEST ->
            stringResource(if (syncReady) R.string.party_synced else R.string.party_syncing)
        busy -> stringResource(R.string.party_scanning)
        else -> stringResource(R.string.party_ready)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.20f),
                        accent.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.02f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.075f), RoundedCornerShape(26.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minSide = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f - 8.dp.toPx())
            val baseRadius = minSide * 0.18f

            repeat(3) { index ->
                val progress = (wave + index / 3f) % 1f
                drawCircle(
                    color = accent.copy(alpha = (1f - progress) * 0.30f),
                    radius = baseRadius + minSide * 0.30f * progress,
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }

            repeat(orbitCount.coerceAtMost(8)) { index ->
                val angle = (index.toFloat() / orbitCount.coerceAtMost(8)) * (2f * PI.toFloat()) -
                    (PI.toFloat() / 2f)
                val radius = minSide * 0.31f
                drawCircle(
                    color = Color.White.copy(alpha = 0.82f),
                    radius = 3.2.dp.toPx(),
                    center = Offset(
                        x = center.x + cos(angle) * radius,
                        y = center.y + sin(angle) * radius
                    )
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    }
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                accent.copy(alpha = 0.95f),
                                accent.copy(alpha = 0.55f)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (role == PartyRole.GUEST) {
                        Icons.Default.Headphones
                    } else {
                        Icons.Default.Groups
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                ),
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 1
            )
        }

        Text(
            text = stringResource(R.string.party_title).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 8.5.sp,
                letterSpacing = 1.5.sp
            ),
            color = Color.White.copy(alpha = 0.34f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 13.dp)
        )
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

    if (!permissionsGranted) {
        PermissionCard(onRequestPermissions = onRequestPermissions)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PartyPrimaryButton(
            label = stringResource(R.string.party_create),
            icon = Icons.Default.Groups,
            enabled = !state.busy,
            onClick = onCreate
        )
        Text(
            text = stringResource(R.string.party_create_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
            color = Color.White.copy(alpha = 0.48f),
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            GlassPartyButton(
                label = stringResource(R.string.party_scan_qr),
                icon = Icons.Default.QrCodeScanner,
                onClick = onScanQr,
                modifier = Modifier.weight(1f)
            )
            GlassPartyButton(
                label = stringResource(R.string.party_join),
                icon = Icons.Default.Bluetooth,
                onClick = onScan,
                modifier = Modifier.weight(1f)
            )
        }

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
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.055f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.045f),
                    focusedBorderColor = scheme.accent.copy(alpha = 0.55f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.09f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Color.White.copy(alpha = 0.60f),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.42f),
                    cursorColor = scheme.accent
                ),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { onJoinByCode(codeInput) },
                enabled = PartyCode.isValid(codeInput),
                modifier = Modifier.height(54.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.accent,
                    contentColor = Color.White,
                    disabledContainerColor = Color.White.copy(alpha = 0.07f),
                    disabledContentColor = Color.White.copy(alpha = 0.32f)
                )
            ) {
                Text(stringResource(R.string.party_join), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.party_nearby_title),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontSize = 10.sp
                ),
                color = Color.White.copy(alpha = 0.50f),
                modifier = Modifier.weight(1f)
            )
            if (state.busy) {
                CircularProgressIndicator(
                    color = scheme.accent,
                    strokeWidth = 1.8.dp,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.party_scanning),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = Color.White.copy(alpha = 0.48f)
                )
            } else if (discovered.isNotEmpty()) {
                Text(
                    text = discovered.size.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    ),
                    color = scheme.accent
                )
            }
        }

        if (discovered.isNotEmpty()) {
            discovered.forEach { party ->
                NearbyPartyRow(
                    party = party,
                    onJoin = { onJoin(party.endpointId) }
                )
            }
            TextButton(
                onClick = onStopScan,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.55f))
            ) {
                Text(stringResource(R.string.party_stop_scan), fontSize = 12.sp)
            }
        } else if (!state.busy) {
            Text(
                text = stringResource(R.string.party_nearby_empty),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = Color.White.copy(alpha = 0.42f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun PartyPrimaryButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.accent.copy(alpha = 0.95f * alpha),
                        scheme.accent.copy(alpha = 0.58f * alpha)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.20f * alpha), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            ),
            color = Color.White.copy(alpha = alpha)
        )
    }
}

@Composable
private fun GlassPartyButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.065f))
            .border(1.dp, Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.84f),
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 12.5.sp
            ),
            color = Color.White.copy(alpha = 0.88f),
            maxLines = 1
        )
    }
}

@Composable
private fun NearbyPartyRow(
    party: DiscoveredParty,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.065f), RoundedCornerShape(18.dp))
            .clickable(onClick = onJoin)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(scheme.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = party.hostNick.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.accent
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = party.hostNick,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${stringResource(R.string.party_code_label)}: ${party.code}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = Color.White.copy(alpha = 0.48f)
            )
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.party_join),
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(15.dp)
            )
        }
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SessionCodeCard(
            code = code,
            qr = qr,
            onCopy = { copyToClipboard(context, code) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.party_guests),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontSize = 10.sp
                ),
                color = Color.White.copy(alpha = 0.50f),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = state.guests.size.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                ),
                color = scheme.accent
            )
        }

        if (state.guests.isEmpty()) {
            Text(
                text = stringResource(R.string.party_waiting_guests),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = Color.White.copy(alpha = 0.42f)
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.guests.forEach { guest ->
                    GuestChip(nick = guest.nick)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp)
        ) {
            SwitchRow(
                label = stringResource(R.string.party_allow_control),
                checked = state.allowGuestControl,
                onCheckedChange = onToggleAllowControl
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.065f))
            SwitchRow(
                label = stringResource(R.string.party_allow_queue),
                checked = state.allowQueueAdd,
                onCheckedChange = onToggleAllowQueue
            )
        }

        NudgeSlider(nudgeMs = state.nudgeMs, onNudgeChange = onNudgeChange)

        Button(
            onClick = onLeave,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE57373).copy(alpha = 0.11f),
                contentColor = Color(0xFFE57373)
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.party_end), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SessionCodeCard(
    code: String,
    qr: androidx.compose.ui.graphics.ImageBitmap?,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.075f), RoundedCornerShape(24.dp))
            .clickable(onClick = onCopy)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (qr != null) {
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.party_show_qr),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(7.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.party_code_label).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                    fontSize = 9.sp
                ),
                color = Color.White.copy(alpha = 0.46f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = code,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 25.sp,
                    letterSpacing = 3.5.sp
                ),
                color = scheme.accent,
                maxLines = 1
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.52f),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.party_tap_copy),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                    color = Color.White.copy(alpha = 0.52f)
                )
            }
        }
    }
}

@Composable
private fun GuestChip(
    nick: String,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.065f), RoundedCornerShape(50))
            .padding(start = 4.dp, end = 11.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(scheme.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = nick.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.accent
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = nick,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
            color = Color.White.copy(alpha = 0.82f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
    val spin = rememberInfiniteTransition(label = "partySyncSpin")
    val rotation by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "partySyncRotation"
    )

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.075f), RoundedCornerShape(22.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(scheme.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = scheme.accent,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.party_connected_to, host),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = if (state.syncReady) scheme.accent else Color.White.copy(alpha = 0.45f),
                        modifier = Modifier
                            .size(13.dp)
                            .graphicsLayer { rotationZ = if (state.syncReady) 0f else rotation }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (state.syncReady) R.string.party_synced else R.string.party_syncing
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = if (state.syncReady) scheme.accent else Color.White.copy(alpha = 0.48f)
                    )
                }
            }

            LivePill(
                label = stringResource(
                    if (state.syncReady) R.string.party_live else R.string.party_syncing
                ),
                showDot = true
            )
        }

        if (!state.allowGuestControl && !state.allowQueueAdd) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.party_action_not_allowed),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = Color.White.copy(alpha = 0.48f)
                )
            }
        }

        NudgeSlider(nudgeMs = state.nudgeMs, onNudgeChange = onNudgeChange)

        Button(
            onClick = onLeave,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.065f),
                contentColor = Color.White.copy(alpha = 0.82f)
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.party_leave), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scheme = LocalBackgroundScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
            color = Color.White.copy(alpha = 0.84f),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp, horizontal = 2.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = scheme.accent.copy(alpha = 0.62f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.60f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.14f)
            )
        )
    }
}

@Composable
private fun NudgeSlider(nudgeMs: Long, onNudgeChange: (Long) -> Unit) {
    val scheme = LocalBackgroundScheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.065f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.party_nudge),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp
                ),
                color = Color.White.copy(alpha = 0.84f)
            )
            Text(
                text = if (nudgeMs == 0L) "0 ms" else "${if (nudgeMs > 0) "+" else ""}$nudgeMs ms",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                ),
                color = scheme.accent
            )
        }
        Slider(
            value = nudgeMs.toFloat(),
            onValueChange = { onNudgeChange(it.toLong()) },
            valueRange = -SyncEngine.NUDGE_RANGE_MS.toFloat()..SyncEngine.NUDGE_RANGE_MS.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = scheme.accent,
                activeTrackColor = scheme.accent.copy(alpha = 0.85f),
                inactiveTrackColor = Color.White.copy(alpha = 0.12f)
            )
        )
        Text(
            text = stringResource(R.string.party_nudge_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
            color = Color.White.copy(alpha = 0.42f)
        )
    }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit) {
    val scheme = LocalBackgroundScheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(scheme.accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = scheme.accent,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.party_permissions),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                color = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = onRequestPermissions,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.accent,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(15.dp)
        ) {
            Text(stringResource(R.string.party_grant), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
