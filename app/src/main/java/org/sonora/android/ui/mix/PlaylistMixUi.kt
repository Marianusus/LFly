package org.sonora.android.ui.mix

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import org.sonora.android.playback.automix.AutomixPlanner
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.Track
import org.sonora.android.playback.automix.MixAnalysisProgress
import org.sonora.android.playback.automix.EffectCurve
import org.sonora.android.playback.automix.EqCurve
import org.sonora.android.playback.automix.FilterCurve
import org.sonora.android.playback.automix.JogwheelCurve
import org.sonora.android.playback.automix.LoopCurve
import org.sonora.android.playback.automix.MixPreset
import org.sonora.android.playback.automix.MixRecipe
import org.sonora.android.playback.automix.MixTrackInfo
import org.sonora.android.playback.automix.MixTransition
import org.sonora.android.playback.automix.VolumeCurve
import org.sonora.android.playback.automix.Waveform
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.onColorFor
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Labels ──────────────────────────────────────────────────────────────────

@Composable
fun presetLabel(preset: MixPreset): String = stringResource(
    when (preset) {
        MixPreset.AUTO -> R.string.playlist_mixing_preset_auto
        MixPreset.FADE -> R.string.playlist_mixing_preset_fade
        MixPreset.RISE -> R.string.playlist_mixing_preset_rise
        MixPreset.BLEND -> R.string.playlist_mixing_preset_blend
        MixPreset.WAVE -> R.string.playlist_mixing_preset_wave
        MixPreset.MELT -> R.string.playlist_mixing_preset_melt
        MixPreset.SLAM -> R.string.playlist_mixing_preset_slam
        MixPreset.NONE -> R.string.playlist_mixing_preset_none
    }
)

@Composable
fun transitionLabel(transition: MixTransition): String =
    if (transition.custom != null) stringResource(R.string.playlist_mixing_preset_custom) else presetLabel(transition.preset)

@Composable
private fun volumeLabel(curve: VolumeCurve): String = stringResource(
    when (curve) {
        VolumeCurve.CROSSFADE -> R.string.playlist_mixing_volume_crossfade
        VolumeCurve.SMOOTH_CROSSFADE -> R.string.playlist_mixing_volume_smooth
        VolumeCurve.OVERLAP -> R.string.playlist_mixing_volume_overlap
        VolumeCurve.FADE_IN_FADE_OUT -> R.string.playlist_mixing_volume_fade_fade
        VolumeCurve.CUT_IN_FADE_OUT -> R.string.playlist_mixing_volume_cut_fade
        VolumeCurve.FADE_IN_CUT_OUT -> R.string.playlist_mixing_volume_fade_cut
        VolumeCurve.CENTER_CUT -> R.string.playlist_mixing_volume_center_cut
    }
)

@Composable
private fun eqLabel(curve: EqCurve): String = stringResource(
    when (curve) {
        EqCurve.NONE -> R.string.playlist_mixing_eq_none
        EqCurve.CENTER_BASS_SWAP -> R.string.playlist_mixing_eq_center_swap
        EqCurve.END_BASS_SWAP -> R.string.playlist_mixing_eq_end_swap
        EqCurve.THREE_BAND -> R.string.playlist_mixing_eq_three_band
    }
)

@Composable
private fun filterLabel(curve: FilterCurve): String = stringResource(
    when (curve) {
        FilterCurve.NONE -> R.string.playlist_mixing_filter_none
        FilterCurve.LOW_PASS -> R.string.playlist_mixing_filter_low
        FilterCurve.HIGH_PASS -> R.string.playlist_mixing_filter_high
    }
)

@Composable
private fun effectLabel(curve: EffectCurve): String = stringResource(
    when (curve) {
        EffectCurve.NONE -> R.string.playlist_mixing_fx_none
        EffectCurve.ECHO_HALF_OUT -> R.string.playlist_mixing_fx_echo_half_out
        EffectCurve.ECHO_3Q_OUT -> R.string.playlist_mixing_fx_echo_3q_out
        EffectCurve.ECHO_1_OUT -> R.string.playlist_mixing_fx_echo_1_out
        EffectCurve.ECHO_HALF_CUT -> R.string.playlist_mixing_fx_echo_half_cut
        EffectCurve.ECHO_3Q_CUT -> R.string.playlist_mixing_fx_echo_3q_cut
        EffectCurve.ECHO_1_CUT -> R.string.playlist_mixing_fx_echo_1_cut
        EffectCurve.DELAY_HALF_CUT -> R.string.playlist_mixing_fx_delay_half_cut
        EffectCurve.DELAY_3Q_CUT -> R.string.playlist_mixing_fx_delay_3q_cut
        EffectCurve.REVERB_OUT_CENTER -> R.string.playlist_mixing_fx_reverb_out_center
        EffectCurve.REVERB_OUT_END -> R.string.playlist_mixing_fx_reverb_out_end
        EffectCurve.REVERB_CUT_END -> R.string.playlist_mixing_fx_reverb_cut_end
        EffectCurve.NOISE_OUT_END -> R.string.playlist_mixing_fx_noise_out_end
    }
)

@Composable
private fun jogwheelLabel(curve: JogwheelCurve): String = stringResource(
    when (curve) {
        JogwheelCurve.NONE -> R.string.playlist_mixing_jog_none
        JogwheelCurve.VINYL_STOP_FAST_1 -> R.string.playlist_mixing_jog_vinyl_fast_1
        JogwheelCurve.VINYL_STOP_FAST_2 -> R.string.playlist_mixing_jog_vinyl_fast_2
        JogwheelCurve.VINYL_STOP_FAST_4 -> R.string.playlist_mixing_jog_vinyl_fast_4
        JogwheelCurve.VINYL_STOP_SLOW -> R.string.playlist_mixing_jog_vinyl_slow
        JogwheelCurve.SPINBACK_1 -> R.string.playlist_mixing_jog_spinback_1
        JogwheelCurve.SPINBACK_2 -> R.string.playlist_mixing_jog_spinback_2
        JogwheelCurve.SPINBACK_4 -> R.string.playlist_mixing_jog_spinback_4
    }
)

@Composable
private fun loopLabel(curve: LoopCurve): String = stringResource(
    when (curve) {
        LoopCurve.NONE -> R.string.playlist_mixing_loop_none
        LoopCurve.ONE_BEAT -> R.string.playlist_mixing_loop_1
        LoopCurve.TWO_BEAT -> R.string.playlist_mixing_loop_2
        LoopCurve.FOUR_BEAT -> R.string.playlist_mixing_loop_4
        LoopCurve.EIGHT_BEAT -> R.string.playlist_mixing_loop_8
        LoopCurve.SIXTEEN_BEAT -> R.string.playlist_mixing_loop_16
        LoopCurve.HALVING -> R.string.playlist_mixing_loop_halving
    }
)

/** "124 BPM · 8A", or what is known of it; [ending] shows the tempo of the song's ending instead of its start. */
@Composable
fun trackMixLabel(info: MixTrackInfo?, ending: Boolean = false): String? {
    if (info == null) return null
    val tempo = if (ending) info.tailBpm ?: info.headBpm else info.bpm
    val bpm = tempo?.let { stringResource(R.string.playlist_mixing_bpm, it.roundToInt().toString()) }
    return listOfNotNull(bpm, info.key?.camelot).joinToString(" · ").ifBlank { null }
}

// ── Playlist header and rows ────────────────────────────────────────────────

/**
 * The Mix pill in the playlist's action row, as in Spotify. Off, it turns Mix on; on, it shows
 * "Mixed" and opens a menu with Smart Reorder and turning Mix off.
 */
@Composable
fun PlaylistMixButton(
    mixed: Boolean,
    canMix: Boolean,
    onToggle: () -> Unit,
    onSmartReorder: () -> Unit,
) {
    val scheme = LocalBackgroundScheme.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .padding(start = Spacing.xs)
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(50))
                .background(if (mixed) scheme.accent.copy(alpha = 0.18f) else Color.Transparent)
                .border(BorderStroke(1.dp, if (mixed) scheme.accent else scheme.onBackgroundSecondary.copy(alpha = 0.6f)), RoundedCornerShape(50))
                .clickable(enabled = canMix || mixed, role = Role.Button) { if (mixed) menu = true else onToggle() }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                tint = if (mixed) scheme.accent else scheme.onBackground,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (mixed) R.string.playlist_mixing_on else R.string.playlist_mixing_button),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = when {
                    mixed -> scheme.accent
                    canMix -> scheme.onBackground
                    else -> scheme.onBackgroundTertiary
                },
            )
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = SonoraOpaqueSurfaceCard,
            border = BorderStroke(1.dp, scheme.glassBorder),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_mixing_smart_reorder), color = scheme.onBackground) },
                leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null, tint = scheme.onBackgroundSecondary) },
                onClick = {
                    menu = false
                    onSmartReorder()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_mixing_turn_off), color = scheme.onBackground) },
                leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, tint = scheme.onBackgroundSecondary) },
                onClick = {
                    menu = false
                    onToggle()
                },
            )
        }
    }
}

/** Under the action row while a mixed playlist's songs are analysed, or when it cannot be mixed yet. */
@Composable
fun PlaylistMixStatus(mixed: Boolean, canMix: Boolean, progress: MixAnalysisProgress?) {
    val scheme = LocalBackgroundScheme.current
    val text = when {
        progress != null -> stringResource(R.string.playlist_mixing_analyzing, (progress.done + 1).coerceAtMost(progress.total), progress.total)
        !canMix && !mixed -> stringResource(R.string.playlist_mixing_needs_tracks)
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (progress != null) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = scheme.accent)
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = scheme.onBackgroundSecondary)
    }
}

/**
 * The transition between two songs of a mixed playlist, as Spotify shows it: a dotted thread from
 * one cover to the next and, on it, a pill with the transition's shape, its name and both tempos
 * and keys. It opens the editor.
 */
@Composable
fun MixTransitionRow(
    transition: MixTransition,
    from: MixTrackInfo?,
    to: MixTrackInfo?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalBackgroundScheme.current
    val fromBpm = from?.tailBpm ?: from?.bpm
    val toBpm = to?.headBpm ?: to?.bpm
    val matchable = fromBpm == null || toBpm == null || abs(toBpm / fromBpm - 1.0) <= AutomixPlanner.MAX_STRETCH
    val active = !transition.isNone
    val tint = if (active) scheme.accent else scheme.onBackgroundTertiary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.gutter + 23.dp, end = Spacing.gutter)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The thread between the two covers.
        Canvas(Modifier.width(2.dp).height(44.dp)) {
            drawLine(
                tint.copy(alpha = 0.7f),
                Offset(size.width / 2, 0f),
                Offset(size.width / 2, size.height),
                strokeWidth = size.width,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
        }
        Spacer(Modifier.width(Spacing.md + 8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (active) scheme.accent.copy(alpha = 0.14f) else scheme.glassTint)
                .border(BorderStroke(1.dp, if (active) scheme.accent.copy(alpha = 0.45f) else scheme.glassBorder), RoundedCornerShape(50))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (matchable) {
                MiniCurve(transition.custom ?: transition.preset.recipe, none = transition.isNone, out = scheme.onBackground, inc = tint)
            } else {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = scheme.onBackgroundSecondary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = transitionLabel(transition),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = scheme.onBackground,
                maxLines = 1,
            )
            val detail = when {
                !matchable -> stringResource(R.string.playlist_mixing_no_beatmatch)
                fromBpm != null && toBpm != null -> "${fromBpm.roundToInt()} → ${toBpm.roundToInt()} BPM" +
                    (if (from?.key != null && to?.key != null) " · ${from.key.camelot} → ${to.key.camelot}" else "")
                else -> null
            }
            if (detail != null) {
                Text(
                    text = "  $detail",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onBackgroundSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = scheme.onBackgroundSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

/** A transition's shape in a few pixels: the outgoing level falling, the incoming one rising. */
@Composable
private fun MiniCurve(recipe: MixRecipe?, none: Boolean, out: Color, inc: Color, modifier: Modifier = Modifier.size(width = 22.dp, height = 14.dp)) {
    Canvas(modifier) { drawCurves(recipe, none, out.copy(alpha = 0.8f), inc, 1.6.dp.toPx()) }
}

/** Draws [recipe]'s two levels across the box (or, with none, one song ending and the next starting). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCurves(recipe: MixRecipe?, none: Boolean, out: Color, inc: Color, stroke: Float) {
    val r = recipe ?: if (none) null else MixRecipe(VolumeCurve.CROSSFADE)
    if (none || r == null) {
        val mid = size.width / 2
        val y = size.height * 0.2f
        drawLine(out, Offset(0f, y), Offset(mid - stroke * 2, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(inc, Offset(mid + stroke * 2, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
        return
    }
    val steps = 40
    val outPath = androidx.compose.ui.graphics.Path()
    val inPath = androidx.compose.ui.graphics.Path()
    for (i in 0..steps) {
        val p = i / steps.toFloat()
        val frame = org.sonora.android.playback.automix.TransitionCurves.frame(r, p, swapWidth = 1f / 32)
        val x = p * size.width
        val yOut = size.height * (1f - 0.9f * presence(frame.outgoing)) + stroke / 2
        val yIn = size.height * (1f - 0.9f * presence(frame.incoming)) + stroke / 2
        if (i == 0) {
            outPath.moveTo(x, yOut); inPath.moveTo(x, yIn)
        } else {
            outPath.lineTo(x, yOut); inPath.lineTo(x, yIn)
        }
    }
    val style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    drawPath(outPath, out, style = style)
    drawPath(inPath, inc, style = style)
}

/** Smart Reorder's suggestion, to apply or keep the current order. */
@Composable
fun SmartReorderDialog(suggested: List<Track>, info: Map<String, MixTrackInfo>, onApply: () -> Unit, onDismiss: () -> Unit) {
    val scheme = LocalBackgroundScheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SonoraOpaqueSurfaceCard,
        title = { Text(stringResource(R.string.playlist_mixing_reorder_title), color = scheme.onBackground) },
        text = {
            Column {
                Text(stringResource(R.string.playlist_mixing_reorder_body), color = scheme.onBackgroundSecondary)
                Spacer(Modifier.height(Spacing.md))
                Text(
                    stringResource(R.string.playlist_mixing_reorder_suggested),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onBackground,
                )
                Spacer(Modifier.height(Spacing.xs))
                Column(
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggested.forEachIndexed { index, track ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onBackgroundTertiary,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                track.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            trackMixLabel(info[track.id])?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onBackgroundSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply) {
                Text(stringResource(R.string.playlist_mixing_reorder_apply), color = scheme.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.playlist_mixing_reorder_keep), color = scheme.onBackgroundSecondary)
            }
        },
    )
}

// ── Editor ──────────────────────────────────────────────────────────────────

/** Spotify-style "Edit transition", full screen. Shows a spinner until both songs are analysed. */
@Composable
fun TransitionEditorDialog(model: PlaylistMixModel, onClose: () -> Unit) {
    val data by model.editor.collectAsState()
    val loading by model.editorLoading.collectAsState()
    val preview by model.preview.collectAsState()
    val scheme = LocalBackgroundScheme.current

    DisposableEffect(Unit) { onDispose { model.stopPreview() } }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SonoraOpaqueSurfaceCard),
        ) {
            val current = data
            EditorBackdrop(current?.outgoing?.track?.coverUrl, current?.incoming?.track?.coverUrl)
            if (current == null) {
                Column(
                    modifier = Modifier.fillMaxSize().statusBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (loading) CircularProgressIndicator(color = scheme.accent)
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        stringResource(if (loading) R.string.playlist_mixing_loading else R.string.playlist_mixing_unavailable),
                        color = scheme.onBackgroundSecondary,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.playlist_mixing_cancel), color = scheme.accent)
                    }
                }
            } else {
                EditorContent(model, current, preview, onClose)
            }
        }
    }
}

@Composable
private fun EditorContent(
    model: PlaylistMixModel,
    data: TransitionEditorData,
    preview: org.sonora.android.playback.PreviewState?,
    onClose: () -> Unit,
) {
    val scheme = LocalBackgroundScheme.current
    var transition by remember(data) { mutableStateOf(data.saved) }
    val undo = remember(data) { mutableStateListOf<MixTransition>() }
    val redo = remember(data) { mutableStateListOf<MixTransition>() }
    var showDiscard by remember { mutableStateOf(false) }
    var customize by remember(data) { mutableStateOf(data.saved.custom != null) }
    val plan = remember(data, transition) { model.plan(data, transition) }
    val changed = transition != data.saved
    val playing = preview?.playing == true
    val preparing by model.previewPreparing.collectAsState()

    fun change(next: MixTransition) {
        if (next == transition) return
        model.stopPreview()
        undo += transition
        redo.clear()
        transition = next
    }

    fun close() {
        model.stopPreview()
        if (changed) showDiscard = true else onClose()
    }

    androidx.activity.compose.BackHandler(onBack = ::close)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Bar: a way out, the task's name, and Save as the one prominent action.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs).heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::close, modifier = Modifier.size(44.dp)) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(scheme.glassTint).border(BorderStroke(1.dp, scheme.glassBorder), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.playlist_mixing_cancel), tint = scheme.onBackground, modifier = Modifier.size(20.dp))
                }
            }
            Text(
                stringResource(R.string.playlist_mixing_edit_title_short),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .padding(end = Spacing.xs)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (changed) scheme.accent else scheme.glassTint)
                    .clickable(enabled = changed, role = Role.Button) {
                        model.save(data, transition)
                        model.stopPreview()
                        onClose()
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.playlist_mixing_save),
                    color = if (changed) onColorFor(scheme.accent) else scheme.onBackgroundSecondary,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            EditorHero(data, modifier = Modifier.padding(horizontal = Spacing.gutter))

            TimelinePanel(
                data = data,
                plan = plan,
                transition = transition,
                previewProgress = preview?.progress,
                onExit = { change(transition.copy(exitMs = it)) },
                onEntry = { change(transition.copy(entryMs = it)) },
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )

            // Transport: undo and redo either side of the preview, where the result is heard.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    IconButton(enabled = undo.isNotEmpty(), modifier = Modifier.size(48.dp), onClick = {
                        model.stopPreview()
                        redo += transition
                        transition = undo.removeAt(undo.lastIndex)
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(R.string.playlist_mixing_undo),
                            tint = if (undo.isNotEmpty()) scheme.onBackground else scheme.onBackgroundTertiary,
                        )
                    }
                    val canPlay = !transition.isNone && plan != null
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
                        // The preview's progress runs around the button.
                        val progress = preview?.progress?.takeIf { playing } ?: 0f
                        Canvas(Modifier.fillMaxSize()) {
                            val w = 3.dp.toPx()
                            drawCircle(scheme.onBackground.copy(alpha = 0.12f), radius = size.minDimension / 2 - w / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                            if (progress > 0f) {
                                drawArc(
                                    scheme.accent, -90f, 360f * progress, false,
                                    topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = StrokeCap.Round),
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(if (canPlay) scheme.accent else scheme.glassTint)
                                .clickable(enabled = canPlay, role = Role.Button) {
                                    if (playing || preparing) model.stopPreview() else plan?.let { model.preview(data, it) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (preparing) {
                                CircularProgressIndicator(color = onColorFor(scheme.accent), strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                            } else Icon(
                                imageVector = if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = stringResource(
                                    if (playing) R.string.playlist_mixing_stop_preview else R.string.playlist_mixing_play_preview
                                ),
                                tint = if (canPlay) onColorFor(scheme.accent) else scheme.onBackgroundTertiary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    IconButton(enabled = redo.isNotEmpty(), modifier = Modifier.size(48.dp), onClick = {
                        model.stopPreview()
                        undo += transition
                        transition = redo.removeAt(redo.lastIndex)
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = stringResource(R.string.playlist_mixing_redo),
                            tint = if (redo.isNotEmpty()) scheme.onBackground else scheme.onBackgroundTertiary,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = if (plan != null && !transition.isNone) {
                        stringResource(R.string.playlist_mixing_bars, plan.bars) + " · " + "%.1f s".format(plan.mixMs / 1_000.0) +
                            (if (plan.beatMatched) " · " + stringResource(R.string.playlist_mixing_beatmatching) else "")
                    } else {
                        presetLabel(MixPreset.NONE)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onBackgroundSecondary,
                )
            }

            // Presets: a row of cards, each drawn as the shape it gives the two songs.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.gutter)) {
                SectionHeader(stringResource(R.string.playlist_mixing_presets), modifier = Modifier.weight(1f))
                // Changed ingredients leave every preset unselected: say what is playing instead.
                if (transition.custom != null) {
                    Text(
                        stringResource(R.string.playlist_mixing_preset_custom),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.accent,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MixPreset.SHOWN.forEach { preset ->
                    val recipe = if (preset == MixPreset.AUTO) plan?.let { p -> p.autoPreset?.recipe ?: p.recipe } else preset.recipe
                    PresetCard(
                        label = presetLabel(preset),
                        caption = if (preset == MixPreset.AUTO) plan?.autoPreset?.let { presetLabel(it) } else null,
                        recipe = recipe,
                        none = preset == MixPreset.NONE,
                        selected = transition.custom == null && transition.preset == preset,
                        onClick = { change(transition.copy(preset = preset, custom = null)) },
                    )
                }
            }

            if (!transition.isNone) {
                Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    GroupCard {
                        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)) {
                            Text(
                                stringResource(R.string.playlist_mixing_duration_bars),
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onBackground,
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Segmented(
                                options = listOf<Int?>(null) + DURATION_SEGMENTS,
                                selected = transition.bars,
                                label = { it?.toString() ?: presetLabel(MixPreset.AUTO) },
                                onSelect = { change(transition.copy(bars = it)) },
                            )
                        }
                        GroupDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = transition.beatmatch && data.beatmatchPossible,
                                    enabled = data.beatmatchPossible,
                                    role = Role.Switch,
                                    onValueChange = { change(transition.copy(beatmatch = it)) },
                                )
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                                .heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.playlist_mixing_beatmatching), style = MaterialTheme.typography.bodyLarge, color = scheme.onBackground)
                                Text(
                                    stringResource(
                                        if (data.beatmatchPossible) R.string.playlist_mixing_beatmatching_desc else R.string.playlist_mixing_no_beatmatch
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onBackgroundSecondary,
                                )
                            }
                            Switch(
                                checked = transition.beatmatch && data.beatmatchPossible,
                                onCheckedChange = null,
                                enabled = data.beatmatchPossible,
                                colors = SwitchDefaults.colors(checkedTrackColor = scheme.accent),
                            )
                        }
                    }

                    // Customize: progressive disclosure for the ingredients behind the presets.
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { customize = !customize }
                                .heightIn(min = 44.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = scheme.onBackgroundSecondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(Spacing.sm))
                            SectionHeader(stringResource(R.string.playlist_mixing_customize), modifier = Modifier.weight(1f))
                            Icon(
                                if (customize) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = scheme.onBackgroundSecondary,
                            )
                        }
                        AnimatedVisibility(visible = customize) {
                            val base = transition.custom ?: plan?.recipe ?: MixRecipe(VolumeCurve.CROSSFADE)
                            fun custom(recipe: MixRecipe) = change(transition.copy(custom = recipe.copy(riser = false)))
                            Column(Modifier.padding(top = Spacing.sm)) {
                                GroupCard {
                                    MenuRow(stringResource(R.string.playlist_mixing_volume), VolumeCurve.entries, base.volume, { volumeLabel(it) }) {
                                        custom(base.copy(volume = it))
                                    }
                                    GroupDivider()
                                    MenuRow(stringResource(R.string.playlist_mixing_eq), EqCurve.entries, base.eq, { eqLabel(it) }) {
                                        custom(base.copy(eq = it))
                                    }
                                    GroupDivider()
                                    SegmentRow(stringResource(R.string.playlist_mixing_filter_in), base.filterIn) { custom(base.copy(filterIn = it)) }
                                    GroupDivider()
                                    SegmentRow(stringResource(R.string.playlist_mixing_filter_out), base.filterOut) { custom(base.copy(filterOut = it)) }
                                    GroupDivider()
                                    MenuRow(stringResource(R.string.playlist_mixing_effects), EffectCurve.entries, base.effect, { effectLabel(it) }) {
                                        custom(base.copy(effect = it))
                                    }
                                    GroupDivider()
                                    MenuRow(stringResource(R.string.playlist_mixing_jogwheel), JogwheelCurve.entries, base.jogwheel, { jogwheelLabel(it) }) {
                                        custom(base.copy(jogwheel = it))
                                    }
                                    GroupDivider()
                                    MenuRow(stringResource(R.string.playlist_mixing_looping), LoopCurve.entries, base.loop, { loopLabel(it) }) {
                                        custom(base.copy(loop = it))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            containerColor = SonoraOpaqueSurfaceCard,
            title = { Text(stringResource(R.string.playlist_mixing_discard_title), color = scheme.onBackground) },
            text = { Text(stringResource(R.string.playlist_mixing_discard_body), color = scheme.onBackgroundSecondary) },
            confirmButton = {
                TextButton(onClick = { showDiscard = false }) {
                    Text(stringResource(R.string.playlist_mixing_keep_editing), color = scheme.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscard = false
                    onClose()
                }) {
                    Text(stringResource(R.string.playlist_mixing_discard), color = scheme.onBackgroundSecondary)
                }
            },
        )
    }
}

/** The two songs, as Spotify opens its editor: both covers leaning together with the mix between them. */
@Composable
private fun EditorHero(data: TransitionEditorData, modifier: Modifier = Modifier) {
    val scheme = LocalBackgroundScheme.current
    val out = data.outgoing
    val inc = data.incoming
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(128.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            listOf(Triple(out.track, -46.dp, -7f), Triple(inc.track, 46.dp, 7f)).forEach { (track, dx, angle) ->
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset(x = dx)
                        .graphicsLayer { rotationZ = angle }
                        .size(112.dp)
                        .shadow(18.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.glassTint),
                )
            }
            Box(
                Modifier
                    .size(46.dp)
                    .shadow(10.dp, CircleShape)
                    .clip(CircleShape)
                    .background(scheme.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = onColorFor(scheme.accent), modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SongLabel(out.track, stringResource(R.string.playlist_mixing_out_label), Alignment.End, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = scheme.onBackgroundSecondary, modifier = Modifier.padding(horizontal = 10.dp).size(18.dp))
            SongLabel(inc.track, stringResource(R.string.playlist_mixing_in_label), Alignment.Start, Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.sm))
        // Tempo and key, from the outgoing song's ending to the incoming one's start.
        val fromBpm = out.info.tailBpm ?: out.info.bpm
        val toBpm = inc.info.headBpm ?: inc.info.bpm
        val fromKey = out.info.key
        val toKey = inc.info.key
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fromBpm != null && toBpm != null) {
                InfoChip("${fromBpm.roundToInt()} → ${toBpm.roundToInt()} BPM", good = abs(toBpm / fromBpm - 1.0) <= AutomixPlanner.MAX_STRETCH)
            }
            if (fromKey != null && toKey != null) {
                val near = fromKey.distance(toKey) <= 1
                InfoChip(
                    "${fromKey.camelot} → ${toKey.camelot} · " + stringResource(if (near) R.string.playlist_mixing_keys_match else R.string.playlist_mixing_keys_clash),
                    good = near,
                )
            }
        }
    }
}

@Composable
private fun SongLabel(track: Track, label: String, align: Alignment.Horizontal, modifier: Modifier) {
    val scheme = LocalBackgroundScheme.current
    val textAlign = if (align == Alignment.End) TextAlign.End else TextAlign.Start
    Column(modifier, horizontalAlignment = align) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = scheme.onBackgroundTertiary,
        )
        Text(
            track.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = scheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
        )
    }
}

@Composable
private fun InfoChip(text: String, good: Boolean) {
    val scheme = LocalBackgroundScheme.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.glassTint)
            .border(BorderStroke(1.dp, scheme.glassBorder), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (good) scheme.accent else Color(0xFFFFB020)))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = scheme.onBackground, maxLines = 1)
    }
}

/** Behind the editor: the two covers side by side, blurred and darkened. */
@Composable
private fun EditorBackdrop(outCover: String?, inCover: String?) {
    Box(Modifier.fillMaxSize().background(SonoraOpaqueSurfaceCard)) {
        Row(Modifier.fillMaxSize().blur(70.dp)) {
            listOf(outCover, inCover).forEach { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.4f to Color.Black.copy(alpha = 0.7f),
                    1f to Color.Black.copy(alpha = 0.9f),
                )
            )
        )
    }
}

/** The bar counts the duration control offers: five segments, the most a phone row holds comfortably. */
private val DURATION_SEGMENTS = listOf(4, 8, 16, 32)

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val scheme = LocalBackgroundScheme.current
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = scheme.onBackground,
        modifier = modifier,
    )
}

/** An inset grouped card, as Settings groups related rows. */
@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    val scheme = LocalBackgroundScheme.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.glassTint)
            .border(BorderStroke(1.dp, scheme.glassBorder), shape),
    ) { content() }
}

@Composable
private fun GroupDivider() {
    val scheme = LocalBackgroundScheme.current
    Box(Modifier.padding(start = Spacing.md).fillMaxWidth().height(1.dp).background(scheme.glassBorder))
}

/** A row whose value opens a menu of choices: for lists too long for a segmented control. */
@Composable
private fun <T> MenuRow(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val scheme = LocalBackgroundScheme.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onBackground, modifier = Modifier.weight(1f))
            Text(label(selected), style = MaterialTheme.typography.bodyLarge, color = scheme.onBackgroundSecondary, maxLines = 1)
            Icon(Icons.Default.UnfoldMore, contentDescription = null, tint = scheme.onBackgroundSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = SonoraOpaqueSurfaceCard,
            border = BorderStroke(1.dp, scheme.glassBorder),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option), color = scheme.onBackground) },
                    trailingIcon = if (option == selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = scheme.accent) }
                    } else null,
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** A filter row: its three choices side by side in a segmented control. */
@Composable
private fun SegmentRow(title: String, selected: FilterCurve, onSelect: (FilterCurve) -> Unit) {
    val scheme = LocalBackgroundScheme.current
    Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onBackground)
        Spacer(Modifier.height(Spacing.sm))
        Segmented(FilterCurve.entries, selected, { filterLabel(it) }, onSelect)
    }
}

/** Equal segments in a rounded track, the selected one raised: closely related, mutually exclusive choices. */
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val scheme = LocalBackgroundScheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.onBackground.copy(alpha = 0.08f))
            .padding(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) scheme.onBackground.copy(alpha = 0.18f) else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A preset card of the carousel: its shape drawn large (the outgoing song's level in white, the
 * incoming one's in the accent), its name under it, a check when chosen.
 */
@Composable
private fun PresetCard(label: String, caption: String?, recipe: MixRecipe?, none: Boolean, selected: Boolean, onClick: () -> Unit) {
    val scheme = LocalBackgroundScheme.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .width(104.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(104.dp, 78.dp)
                .clip(shape)
                .background(if (selected) scheme.accent.copy(alpha = 0.18f) else scheme.glassTint)
                .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.accent else scheme.glassBorder), shape)
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCurves(recipe, none, scheme.onBackground.copy(alpha = 0.8f), scheme.accent, 3.dp.toPx())
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-8).dp).size(18.dp).clip(CircleShape).background(scheme.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = onColorFor(scheme.accent), modifier = Modifier.size(12.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold),
            color = if (selected) scheme.accent else scheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How much of a deck is heard, 0..1: its fader, thinned by its EQ cuts and by a filter's sweep. */
private fun presence(deck: org.sonora.android.playback.automix.DeckMix): Float {
    val f = deck.filter
    var share = 1f
    if (f.bassDb < -1f) share -= 0.35f * (-f.bassDb / 20f).coerceAtMost(1f)
    if (f.midDb < -1f) share -= 0.2f * (-f.midDb / 12f).coerceAtMost(1f)
    if (f.highDb < -1f) share -= 0.1f * (-f.highDb / 12f).coerceAtMost(1f)
    if (f.highPassHz > 30f) share *= (1f - (kotlin.math.ln(f.highPassHz / 20f) / kotlin.math.ln(1_000f)).coerceIn(0f, 1f) * 0.8f)
    if (f.lowPassHz in 1f..19_000f) share *= (kotlin.math.ln(f.lowPassHz / 20f) / kotlin.math.ln(1_000f)).coerceIn(0.15f, 1f)
    return (deck.volume * share).coerceIn(0f, 1f)
}

/**
 * Both songs on one timeline, the outgoing song's clock: the outgoing waveform on top, the incoming
 * one under it, the mix shaded across both with the transition's two levels drawn over it. Dragging a
 * waveform moves that song's mix point; it snaps to the nearest bar line when the finger lifts.
 */
@Composable
private fun TimelinePanel(
    data: TransitionEditorData,
    plan: EditorPlan?,
    transition: MixTransition,
    previewProgress: Float?,
    onExit: (Long) -> Unit,
    onEntry: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalBackgroundScheme.current
    val exit = plan?.exitMs ?: (data.outgoing.track.durationMs - 5_000L).coerceAtLeast(0L)
    val entry = plan?.entryMs ?: 0L
    val mix = plan?.mixMs ?: 5_000L
    val rate = plan?.incomingRate ?: 1f
    // The view: a little before the mix to a little after it.
    val margin = (mix * 0.45).toLong().coerceAtLeast(4_000L)
    val viewStart = exit - margin
    val viewSpan = (mix + 2 * margin).toFloat()
    var exitDrag by remember(data, exit) { mutableFloatStateOf(0f) }
    var entryDrag by remember(data, entry) { mutableFloatStateOf(0f) }
    val enabled = !transition.isNone
    val playhead = previewProgress?.let { playheadMs(it, exit, mix, data) }
    val recipe = transition.custom ?: plan?.recipe
    val shape = RoundedCornerShape(20.dp)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.28f))
                .border(BorderStroke(1.dp, scheme.glassBorder), shape),
        ) {
            Column {
                Lane(
                    wave = data.outgoing.waveform,
                    toView = { it },
                    viewStart = viewStart, viewSpan = viewSpan, mixStart = exit, mixMs = mix,
                    dragMs = exitDrag,
                    color = Color.White, accent = scheme.accent,
                    playhead = playhead, enabled = enabled, top = true,
                    curve = recipe?.takeIf { enabled }, none = transition.isNone,
                    onDrag = { exitDrag = it },
                    onDragEnd = { shift ->
                        val wanted = exit + shift.toLong()
                        val snapped = plan?.exitChoicesMs?.minByOrNull { abs(it - wanted) } ?: wanted
                        exitDrag = 0f
                        if (snapped != exit) onExit(snapped)
                    },
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.glassBorder))
                Lane(
                    wave = data.incoming.waveform,
                    toView = { songMs -> exit + ((songMs - entry) / rate).toLong() },
                    viewStart = viewStart, viewSpan = viewSpan, mixStart = exit, mixMs = mix,
                    dragMs = entryDrag,
                    color = scheme.accent, accent = scheme.accent,
                    playhead = playhead, enabled = enabled, top = false,
                    curve = recipe?.takeIf { enabled }, none = transition.isNone,
                    onDrag = { entryDrag = it },
                    onDragEnd = { shift ->
                        val wanted = entry + (shift * rate).toLong()
                        val snapped = plan?.entryChoicesMs?.minByOrNull { abs(it - wanted) } ?: wanted.coerceAtLeast(0L)
                        entryDrag = 0f
                        if (snapped != entry) onEntry(snapped)
                    },
                )
            }
            Text(
                stringResource(R.string.playlist_mixing_out_label),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )
            Text(
                stringResource(R.string.playlist_mixing_in_label),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = scheme.accent,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
        Text(
            stringResource(R.string.playlist_mixing_drag_hint),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onBackgroundSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The preview's position on the outgoing clock: it covers two bars before the mix and two after. */
private fun playheadMs(progress: Float, exit: Long, mix: Long, data: TransitionEditorData): Long {
    val barMs = data.outgoing.grid?.let { (4 * it.beatSeconds * 1_000).toLong() } ?: 2_000L
    val start = (exit - 2 * barMs).coerceAtLeast(0L)
    val end = exit + mix + 2 * barMs
    return start + ((end - start) * progress).toLong()
}

/** One song's lane of the timeline: its waveform, the mix shaded, that song's level over the mix. */
@Composable
private fun Lane(
    wave: Waveform?,
    toView: (Long) -> Long,
    viewStart: Long,
    viewSpan: Float,
    mixStart: Long,
    mixMs: Long,
    dragMs: Float,
    color: Color,
    accent: Color,
    playhead: Long?,
    enabled: Boolean,
    top: Boolean,
    curve: MixRecipe?,
    none: Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
) {
    var width by remember { mutableFloatStateOf(1f) }
    val currentDrag by rememberUpdatedState(dragMs)
    val currentSpan by rememberUpdatedState(viewSpan)
    val onDragState by rememberUpdatedState(onDrag)
    val onDragEndState by rememberUpdatedState(onDragEnd)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = { onDragEndState(currentDrag) },
                    onDragCancel = { onDragState(0f) },
                ) { change, dx ->
                    change.consume()
                    // Moving the waveform left brings later music to the mix: the mix point moves later.
                    onDragState(currentDrag - dx / width * currentSpan)
                }
            },
    ) {
        width = size.width
        fun x(ms: Long): Float = ((ms - viewStart) / viewSpan) * size.width
        val left = x(mixStart)
        val right = x(mixStart + mixMs)
        if (!none) {
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(accent.copy(alpha = if (top) 0.06f else 0.20f), accent.copy(alpha = if (top) 0.20f else 0.06f))
                ),
                topLeft = Offset(left, 0f),
                size = Size(right - left, size.height),
            )
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            drawLine(accent.copy(alpha = 0.8f), Offset(left, 0f), Offset(left, size.height), strokeWidth = 2f, pathEffect = dash)
            drawLine(accent.copy(alpha = 0.8f), Offset(right, 0f), Offset(right, size.height), strokeWidth = 2f, pathEffect = dash)
        }
        if (wave != null) {
            val step = (size.width / (viewSpan / 1_000f * wave.rate.toFloat())).coerceAtLeast(1.5f)
            val mid = size.height / 2
            for (index in wave.levels.indices) {
                val songMs = ((wave.startSeconds + index / wave.rate) * 1_000).toLong()
                val px = x(toView(songMs) - dragMs.toLong())
                if (px < -step || px > size.width + step) continue
                // Heard while it plays: the outgoing song before and in the mix, the incoming one in it and after.
                val heard = if (top) px <= right else px >= left
                val half = (wave.levels[index] * (size.height * 0.4f)).coerceAtLeast(1f)
                drawLine(
                    color = color.copy(alpha = if (heard) 0.9f else 0.25f),
                    start = Offset(px, mid - half),
                    end = Offset(px, mid + half),
                    strokeWidth = (step * 0.6f).coerceIn(1f, 6f),
                    cap = StrokeCap.Round,
                )
            }
        }
        // This song's level across the mix.
        if (curve != null && right > left) {
            val path = androidx.compose.ui.graphics.Path()
            val steps = 48
            for (i in 0..steps) {
                val p = i / steps.toFloat()
                val frame = org.sonora.android.playback.automix.TransitionCurves.frame(curve, p, swapWidth = 1f / 32)
                val level = presence(if (top) frame.outgoing else frame.incoming)
                val px = left + (right - left) * p
                val py = size.height * (0.92f - 0.84f * level)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        if (playhead != null) {
            val px = x(playhead)
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.5f)
            if (top) drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(px, 5.dp.toPx()))
        }
    }
}

/** What a playlist screen needs to show Mix: its state and what to do on each tap. */
class PlaylistMixUiState(
    val mixed: Boolean,
    val progress: MixAnalysisProgress?,
    val info: Map<String, MixTrackInfo>,
    val transitionFor: (Track, Track) -> MixTransition,
    val onToggle: () -> Unit,
    val onSmartReorder: () -> Unit,
    val onEditTransition: (index: Int) -> Unit,
)
