package org.sonora.android.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.playback.dj.DjBlock
import org.sonora.android.playback.dj.DjBlockKind
import org.sonora.android.playback.dj.DjState
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.pressScale

/** The DJ card: one tap starts the DJ, the same tap stops it. */
@Composable
internal fun HomeDj(
    state: DjState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val running = state.active || state.loading
    val blockLabel = state.block?.let { djBlockLabel(it) }
    val caption = when {
        state.loading -> stringResource(R.string.dj_card_loading)
        state.active -> state.lastLine ?: blockLabel ?: stringResource(R.string.dj_card_subtitle)
        else -> stringResource(R.string.dj_card_subtitle)
    }
    val eyebrow = if (state.active && state.lastLine != null) blockLabel else null
    val actionLabel = stringResource(if (running) R.string.dj_card_stop else R.string.dj_card_start)
    val speakingLabel = stringResource(R.string.dj_card_speaking)
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(HomeDimens.HeroRadius)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDimens.Gutter)
            .pressScale(interaction)
            .homeLiquidGlass(shape = shape, fallbackColor = scheme.glassTint)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = actionLabel,
                onClick = onClick
            )
            .semantics { if (state.speaking) stateDescription = speakingLabel }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(HomeDimens.Target)
                .clip(CircleShape)
                .background(scheme.accent),
            contentAlignment = Alignment.Center
        ) {
            if (state.speaking) {
                DjSpeakingBars(color = homeOnColor(scheme.accent))
            } else {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = homeOnColor(scheme.accent),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(scheme.accent)
                    )
                    Text(
                        text = eyebrow,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            letterSpacing = 1.sp
                        ),
                        color = scheme.onBackgroundSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
            }
            Text(
                text = stringResource(R.string.dj_card_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    lineHeight = 25.sp,
                    letterSpacing = (-0.4).sp
                ),
                color = scheme.onBackground,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                ),
                color = scheme.onBackgroundSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(HomeDimens.Target)
                .homeLiquidGlass(CircleShape, scheme.glassTint),
            contentAlignment = Alignment.Center
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = scheme.accent,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = if (state.active) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = scheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun djBlockLabel(block: DjBlock): String = when (block.kind) {
    DjBlockKind.TOP_PLAYED -> stringResource(R.string.dj_block_top_played)
    DjBlockKind.FOLLOWED_ARTIST -> block.subject?.let { stringResource(R.string.dj_block_followed_artist, it) }
        ?: stringResource(R.string.dj_block_followed_artist_generic)
    DjBlockKind.DISCOVERY -> stringResource(R.string.dj_block_discovery)
    DjBlockKind.THROWBACK -> stringResource(R.string.dj_block_throwback)
    DjBlockKind.MIX -> block.subject?.let { stringResource(R.string.dj_block_mix, it) }
        ?: stringResource(R.string.dj_block_mix_generic)
}

private val DjBarRest = floatArrayOf(0.55f, 0.9f, 0.7f)

/** Three bars that move while the DJ talks; still under Reduce Motion. */
@Composable
private fun DjSpeakingBars(color: Color) {
    val motion = LocalMotionPreferences.current
    Row(
        modifier = Modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (motion.allowContinuousMotion) {
            val transition = rememberInfiniteTransition(label = "djSpeaking")
            DjBarRest.forEachIndexed { index, rest ->
                val scale by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = rest,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 460, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(index * 150)
                    ),
                    label = "djBar$index"
                )
                DjBar(color = color, scale = { scale })
            }
        } else {
            DjBarRest.forEach { rest -> DjBar(color = color, scale = { rest }) }
        }
    }
}

@Composable
private fun DjBar(color: Color, scale: () -> Float) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(18.dp)
            .graphicsLayer { scaleY = scale() }
            .clip(CircleShape)
            .background(color)
    )
}
