package org.sonora.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.playback.EqualizerBand
import org.sonora.android.playback.EqualizerManager
import org.sonora.android.playback.EqualizerPreset
import org.sonora.android.playback.EqualizerState
import org.sonora.android.ui.theme.SonoraAmoledBlack
import org.sonora.android.ui.theme.SonoraDivider
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraEmeraldDark
import org.sonora.android.ui.theme.SonoraOverlay
import org.sonora.android.ui.theme.SonoraPurple
import org.sonora.android.ui.theme.SonoraPurpleLight
import org.sonora.android.ui.theme.SonoraSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraSurfaceSubtle
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Beautiful, interactive Modal Bottom Sheet for Native 5-Band Equalizer & Bass Boost.
 *
 * Supports real-time band gain adjustments [-15dB, +15dB] with interpolated frequency response curve,
 * preset dropdown & quick-select pills, master DSP toggle, and rotary knob / slider Bass Boost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerDialog(
    isOpen: Boolean,
    state: EqualizerState,
    onDismissRequest: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPresetChange: (EqualizerPreset) -> Unit,
    onBandGainChange: (bandIndex: Int, gainDb: Float) -> Unit,
    onBassBoostChange: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color(0xF2121216),
        contentColor = SonoraTextPrimary,
        scrimColor = SonoraOverlay,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = Color(0xFFFFFFFF).copy(alpha = 0.25f)
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
    ) {
        EqualizerDialogContent(
            state = state,
            onDismissRequest = onDismissRequest,
            onEnabledChange = onEnabledChange,
            onPresetChange = onPresetChange,
            onBandGainChange = onBandGainChange,
            onBassBoostChange = onBassBoostChange,
            onReset = onReset
        )
    }
}

@Composable
private fun EqualizerDialogContent(
    state: EqualizerState,
    onDismissRequest: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPresetChange: (EqualizerPreset) -> Unit,
    onBandGainChange: (bandIndex: Int, gainDb: Float) -> Unit,
    onBassBoostChange: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // 1. Header Bar: Title, DSP Switch, Close Button
        HeaderSection(
            isEnabled = state.isEnabled,
            isHardwareAttached = state.isHardwareAttached,
            onEnabledChange = onEnabledChange,
            onClose = onDismissRequest
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Content alpha based on master enable/bypass switch
        val contentAlpha = if (state.isEnabled) 1.0f else 0.45f

        Box(modifier = Modifier.alpha(contentAlpha)) {
            Column {
                // 2. Preset Selection: Dropdown Menu & Quick Pills
                PresetSelectorSection(
                    currentPreset = state.preset,
                    isEnabled = state.isEnabled,
                    onPresetSelected = onPresetChange
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 3. Frequency Response Curve & 5-Band Equalizer Sliders
                GraphicEqualizerSection(
                    bands = state.bands,
                    isEnabled = state.isEnabled,
                    onBandGainChange = onBandGainChange,
                    onResetToFlat = onReset
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 4. Bass Boost Section (Interactive Rotary Knob & Linear Slider)
                BassBoostSection(
                    strength = state.bassBoostStrength,
                    isEnabled = state.isEnabled,
                    onStrengthChange = onBassBoostChange
                )

                Spacer(modifier = Modifier.height(28.dp))

                // 5. Bottom Actions: Reset to Flat & Apply/Done
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onReset,
                        enabled = state.isEnabled,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SonoraTextSecondary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.equalizer_reset_to_flat),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }

                    Button(
                        onClick = onDismissRequest,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SonoraEmerald,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.equalizer_done),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ==========================================
// Section 1: Header Bar
// ==========================================

@Composable
private fun HeaderSection(
    isEnabled: Boolean,
    isHardwareAttached: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Equalizer,
                    contentDescription = null,
                    tint = SonoraEmerald,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = stringResource(R.string.equalizer_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = SonoraTextPrimary
                    )
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                if (isEnabled) SonoraEmerald else SonoraTextTertiary,
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isEnabled) {
                            if (isHardwareAttached) stringResource(R.string.equalizer_status_native_dsp_active) else stringResource(R.string.equalizer_status_ready)
                        } else {
                            stringResource(R.string.equalizer_status_bypassed)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isEnabled) SonoraEmerald else SonoraTextTertiary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = SonoraEmerald,
                    uncheckedThumbColor = SonoraTextTertiary,
                    uncheckedTrackColor = SonoraSurfaceHighlight
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(36.dp)
                    .background(SonoraSurfaceHighlight, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.equalizer_close_content_description),
                    tint = SonoraTextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ==========================================
// Section 2: Preset Dropdown & Quick Selector
// ==========================================

@Composable
private fun PresetSelectorSection(
    currentPreset: EqualizerPreset,
    isEnabled: Boolean,
    onPresetSelected: (EqualizerPreset) -> Unit
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceSubtle),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = SonoraEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.equalizer_sound_profile),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = SonoraTextSecondary
                        )
                    )
                }

                // Interactive Preset Dropdown Button
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(SonoraSurfaceHighlight)
                            .clickable(enabled = isEnabled) { dropdownExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentPreset.displayName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = SonoraEmerald
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(R.string.equalizer_select_preset_content_description),
                            tint = SonoraEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraTheme.colors.cardBorder)
                    ) {
                        EqualizerPreset.entries.forEach { preset ->
                            val isSelected = preset == currentPreset
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = preset.displayName,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) SonoraEmerald else SonoraTextPrimary
                                            )
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = SonoraEmerald,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onPresetSelected(preset)
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Scrollable Preset Pills for 1-Tap Switching
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EqualizerPreset.entries.forEach { preset ->
                    val isSelected = preset == currentPreset
                    val pillBg = if (isSelected) SonoraEmerald else SonoraSurfaceHighlight
                    val pillText = if (isSelected) Color.Black else SonoraTextPrimary

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(pillBg)
                            .clickable(enabled = isEnabled) { onPresetSelected(preset) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset.displayName,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = pillText
                            )
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// Section 3: Graphic 5-Band Equalizer
// ==========================================

@Composable
private fun GraphicEqualizerSection(
    bands: List<EqualizerBand>,
    isEnabled: Boolean,
    onBandGainChange: (Int, Float) -> Unit,
    onResetToFlat: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceSubtle),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Section label & dB range
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.equalizer_band_header),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = SonoraTextSecondary
                    )
                )

                Text(
                    text = stringResource(R.string.equalizer_double_tap_hint),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SonoraTextTertiary
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Visual Frequency Response Curve Canvas
            FrequencyCurveGraph(
                bands = bands,
                isEnabled = isEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SonoraSurfaceHighlight.copy(alpha = 0.5f))
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 5 Vertical Equalizer Sliders
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                bands.forEach { band ->
                    VerticalEqualizerBandSlider(
                        band = band,
                        isEnabled = isEnabled,
                        onGainChange = { newGain -> onBandGainChange(band.index, newGain) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Visual Frequency Response Curve canvas interpolating smoothly between the 5 frequency bands.
 */
@Composable
private fun FrequencyCurveGraph(
    bands: List<EqualizerBand>,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val dividerColor = SonoraDivider
    val emeraldColor = SonoraEmerald
    val textTertiaryColor = SonoraTextTertiary
    val curveColor = if (isEnabled) emeraldColor else textTertiaryColor
    val fillGradientColors = listOf(
        emeraldColor.copy(alpha = 0.25f),
        emeraldColor.copy(alpha = 0.03f)
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        // Draw 0 dB center reference dashed guideline
        drawLine(
            color = dividerColor,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        )

        if (bands.isEmpty()) return@Canvas

        // Calculate points for the 5 bands
        val points = bands.mapIndexed { index, band ->
            val fractionX = (index + 0.5f) / bands.size.toFloat()
            val x = fractionX * width

            // Gain range [-15dB, +15dB] mapped to [height, 0]
            val normalized = (band.gainDb.coerceIn(-15f, 15f) / 15f) // -1.0 .. +1.0
            val y = centerY - (normalized * (centerY * 0.85f))
            Offset(x, y)
        }

        // Build cubic bezier curve connecting the 5 points
        val strokePath = Path()
        val fillPath = Path()

        strokePath.moveTo(0f, points.first().y)
        fillPath.moveTo(0f, centerY)
        fillPath.lineTo(0f, points.first().y)

        for (i in 0 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val controlX = (p0.x + p1.x) / 2f

            strokePath.cubicTo(controlX, p0.y, controlX, p1.y, p1.x, p1.y)
            fillPath.cubicTo(controlX, p0.y, controlX, p1.y, p1.x, p1.y)
        }

        // Extend to end of canvas
        val lastPoint = points.last()
        strokePath.lineTo(width, lastPoint.y)
        fillPath.lineTo(width, lastPoint.y)
        fillPath.lineTo(width, centerY)
        fillPath.close()

        // Draw fill gradient
        if (isEnabled) {
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = fillGradientColors,
                    startY = 0f,
                    endY = height
                )
            )
        }

        // Draw curve stroke
        drawPath(
            path = strokePath,
            color = curveColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw band node points
        points.forEach { point ->
            drawCircle(
                color = curveColor,
                radius = 4.dp.toPx(),
                center = point
            )
            drawCircle(
                color = Color.White,
                radius = 2.dp.toPx(),
                center = point
            )
        }
    }
}

/**
 * Individual vertical equalizer slider column for one frequency band.
 * Supports smooth dragging, tap-to-set, and double-tap to reset to 0dB.
 */
@Composable
private fun VerticalEqualizerBandSlider(
    band: EqualizerBand,
    isEnabled: Boolean,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalHeight = 150.dp
    val trackWidth = 6.dp

    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Gain Readout Text (e.g. "+5dB", "0dB", "-2dB")
        val gainFormatted = if (band.gainDb >= 0.05f) {
            "+%.1f".format(band.gainDb)
        } else if (band.gainDb <= -0.05f) {
            "%.1f".format(band.gainDb)
        } else {
            "0"
        }

        val gainColor = when {
            !isEnabled -> SonoraTextTertiary
            band.gainDb > 0.1f -> SonoraEmerald
            band.gainDb < -0.1f -> SonoraPurpleLight
            else -> SonoraTextSecondary
        }

        Text(
            text = stringResource(R.string.equalizer_gain_value, gainFormatted),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = gainColor
            ),
            maxLines = 1,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        val thumbWidth = 28.dp
        val thumbHeight = 20.dp
        val thumbRadius = 10.dp

        // Interactive Vertical Slider Track & Thumb
        BoxWithConstraints(
            modifier = Modifier
                .width(42.dp)
                .height(totalHeight)
                .pointerInput(isEnabled) {
                    if (!isEnabled) return@pointerInput
                    val density = this
                    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
                    val usableTrackHeight = (size.height - (2f * thumbRadiusPx)).coerceAtLeast(1f)
                    detectTapGestures(
                        onDoubleTap = {
                            // Double tap resets band to 0 dB
                            onGainChange(0f)
                        },
                        onTap = { offset ->
                            val touchY = (offset.y - thumbRadiusPx).coerceIn(0f, usableTrackHeight)
                            val fraction = 1f - (touchY / usableTrackHeight)
                            val newGain = -15f + (fraction * 30f)
                            val rounded = (newGain * 2f).roundToInt() / 2f
                            onGainChange(rounded.coerceIn(-15f, 15f))
                        }
                    )
                }
                .pointerInput(isEnabled) {
                    if (!isEnabled) return@pointerInput
                    val density = this
                    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
                    val usableTrackHeight = (size.height - (2f * thumbRadiusPx)).coerceAtLeast(1f)
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val deltaFraction = -dragAmount / usableTrackHeight
                        val deltaGain = deltaFraction * 30f
                        val updatedGain = (band.gainDb + deltaGain).coerceIn(-15f, 15f)
                        val rounded = (updatedGain * 2f).roundToInt() / 2f
                        onGainChange(rounded)
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            val trackHeightPx = constraints.maxHeight.toFloat()
            val centerYPx = trackHeightPx / 2f
            val thumbRadiusPx = with(LocalDensity.current) { thumbRadius.toPx() }
            val usableTrackHeight = (trackHeightPx - (2f * thumbRadiusPx)).coerceAtLeast(1f)

            // Fraction 0.0 (-15dB, bottom) .. 0.5 (0dB, center) .. 1.0 (+15dB, top)
            val fraction = ((band.gainDb + 15f) / 30f).coerceIn(0f, 1f)
            val thumbTopPx = usableTrackHeight * (1f - fraction)
            val thumbCenterYPx = thumbTopPx + thumbRadiusPx

            val surfaceHighlightColor = SonoraSurfaceHighlight
            val dividerColor = SonoraDivider
            val emeraldColor = SonoraEmerald
            val purpleLightColor = SonoraPurpleLight

            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f

                // 1. Inactive background track
                drawLine(
                    color = surfaceHighlightColor,
                    start = Offset(cx, thumbRadiusPx),
                    end = Offset(cx, trackHeightPx - thumbRadiusPx),
                    strokeWidth = trackWidth.toPx(),
                    cap = StrokeCap.Round
                )

                // 2. Center 0dB reference tick
                drawLine(
                    color = dividerColor,
                    start = Offset(cx - 10f, centerYPx),
                    end = Offset(cx + 10f, centerYPx),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )

                // 3. Active fill bar from 0dB center to thumb center position
                if (isEnabled && kotlin.math.abs(centerYPx - thumbCenterYPx) > 1f) {
                    val activeColor = if (band.gainDb >= 0) emeraldColor else purpleLightColor
                    drawLine(
                        color = activeColor,
                        start = Offset(cx, centerYPx),
                        end = Offset(cx, thumbCenterYPx),
                        strokeWidth = trackWidth.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // 4. Draggable Thumb / Pill positioned accurately from Top
            val thumbOffsetY = with(LocalDensity.current) { thumbTopPx.toDp() }

            Box(
                modifier = Modifier
                    .offset(y = thumbOffsetY)
                    .size(width = thumbWidth, height = thumbHeight)
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isEnabled) {
                            if (band.gainDb != 0f) SonoraEmerald else Color(0xFFE0E0E0)
                        } else {
                            SonoraTextTertiary
                        }
                    )
                    .border(
                        width = 1.dp,
                        color = if (isEnabled) Color.White.copy(alpha = 0.6f) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Subtle tactile grip bar on thumb
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .height(2.dp)
                        .background(
                            if (isEnabled && band.gainDb != 0f) Color.Black.copy(alpha = 0.7f)
                            else Color.Gray
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Frequency Label Badge below slider
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(SonoraSurfaceHighlight.copy(alpha = 0.8f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = band.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = SonoraTextPrimary
                )
            )
        }
    }
}

// ==========================================
// Section 4: Bass Boost (Knob & Slider)
// ==========================================

@Composable
private fun BassBoostSection(
    strength: Int, // 0..1000
    isEnabled: Boolean,
    onStrengthChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceSubtle),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(SonoraPurple.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SurroundSound,
                            contentDescription = null,
                            tint = SonoraPurpleLight,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.equalizer_bass_boost),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = SonoraTextSecondary
                            )
                        )
                        Text(
                            text = stringResource(R.string.equalizer_bass_boost_subtitle),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SonoraTextTertiary
                            )
                        )
                    }
                }

                // Numerical Readout Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (strength > 0 && isEnabled) SonoraPurple.copy(alpha = 0.3f)
                            else SonoraSurfaceHighlight
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    val percent = (strength / 10f).roundToInt()
                    Text(
                        text = stringResource(R.string.equalizer_percent, percent),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (strength > 0 && isEnabled) SonoraPurpleLight else SonoraTextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Knob and Slider Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Interactive Rotary Bass Boost Dial
                BassBoostRotaryKnob(
                    strength = strength,
                    isEnabled = isEnabled,
                    onStrengthChange = onStrengthChange,
                    modifier = Modifier.size(88.dp)
                )

                Spacer(modifier = Modifier.width(20.dp))

                // Interactive Slider + Quick Preset Pills
                Column(modifier = Modifier.weight(1f)) {
                    Slider(
                        value = strength.toFloat(),
                        onValueChange = { onStrengthChange(it.roundToInt().coerceIn(0, 1000)) },
                        valueRange = 0f..1000f,
                        enabled = isEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = SonoraPurpleLight,
                            activeTrackColor = SonoraPurple,
                            inactiveTrackColor = SonoraSurfaceHighlight
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Quick Step Buttons (Off, 25%, 50%, 75%, Max)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val offLabel = stringResource(R.string.equalizer_step_off)
                        val maxLabel = stringResource(R.string.equalizer_step_max)
                        val steps = listOf(
                            Pair(offLabel, 0),
                            Pair(stringResource(R.string.equalizer_percent, 25), 250),
                            Pair(stringResource(R.string.equalizer_percent, 50), 500),
                            Pair(stringResource(R.string.equalizer_percent, 75), 750),
                            Pair(maxLabel, 1000)
                        )

                        steps.forEach { (label, value) ->
                            val isSelected = strength == value
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isSelected && isEnabled) SonoraPurpleLight.copy(alpha = 0.25f)
                                        else SonoraSurfaceHighlight
                                    )
                                    .clickable(enabled = isEnabled) { onStrengthChange(value) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 10.sp,
                                        color = if (isSelected && isEnabled) SonoraPurpleLight else SonoraTextSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * High-precision interactive rotary knob for Bass Boost control.
 * Supports radial and vertical drag adjustments.
 */
@Composable
private fun BassBoostRotaryKnob(
    strength: Int, // 0..1000
    isEnabled: Boolean,
    onStrengthChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .pointerInput(isEnabled) {
                if (!isEnabled) return@pointerInput
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    // Drag up increases, drag down decreases
                    val deltaStrength = (-dragAmount * 8f).roundToInt()
                    val newStrength = (strength + deltaStrength).coerceIn(0, 1000)
                    onStrengthChange(newStrength)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val fraction = (strength / 1000f).coerceIn(0f, 1f)

        val surfaceHighlightColor = SonoraSurfaceHighlight
        val emeraldColor = SonoraEmerald
        val purpleColor = SonoraPurple
        val purpleLightColor = SonoraPurpleLight
        val textTertiaryColor = SonoraTextTertiary
        val sweepGradientColors = listOf(emeraldColor, purpleColor, purpleLightColor)
        val pointerColor = if (isEnabled) purpleLightColor else textTertiaryColor

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (size.minDimension / 2f) - 6.dp.toPx()
            val strokeWidth = 6.dp.toPx()

            // Sweep angle: 270 degrees total (from 135 deg to 405 deg)
            val startAngle = 135f
            val totalSweep = 270f
            val activeSweep = totalSweep * fraction

            // 1. Inactive background arc
            drawArc(
                color = surfaceHighlightColor,
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 2. Active arc with vibrant purple gradient
            if (isEnabled && activeSweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = sweepGradientColors
                    ),
                    startAngle = startAngle,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // 3. Inner Knob Body
            val innerRadius = radius - 8.dp.toPx()
            drawCircle(
                color = surfaceHighlightColor,
                radius = innerRadius,
                center = center
            )

            // 4. Pointer Dot on perimeter of inner knob
            val currentAngleRad = Math.toRadians((startAngle + activeSweep).toDouble())
            val dotRadius = innerRadius - 6.dp.toPx()
            val dotCenter = Offset(
                x = center.x + (dotRadius * cos(currentAngleRad)).toFloat(),
                y = center.y + (dotRadius * sin(currentAngleRad)).toFloat()
            )

            drawCircle(
                color = pointerColor,
                radius = 3.dp.toPx(),
                center = dotCenter
            )
        }

        // Center Text
        val percent = (strength / 10f).roundToInt()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.equalizer_percent, percent),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isEnabled && strength > 0) SonoraPurpleLight else SonoraTextSecondary
                )
            )
            Text(
                text = stringResource(R.string.equalizer_bass_label),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = SonoraTextTertiary
                )
            )
        }
    }
}
