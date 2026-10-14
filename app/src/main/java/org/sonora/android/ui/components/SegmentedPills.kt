package org.sonora.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.sonora.android.ui.theme.GlassChip
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.onColorFor

/**
 * Horizontally scrollable row of glass filter pills.
 * Selection lives in the caller's state (VM-driven), preserving search state on back.
 *
 * When [slidingIndicator] is true the pills render as an equal-width segmented control with
 * a single Liquid Glass "water bubble" that slides between the options (used by Settings tabs).
 */
@Composable
fun <T> SegmentedPills(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    slidingIndicator: Boolean = false
) {
    if (slidingIndicator) {
        SlidingSegmentedPills(items, selected, onSelect, label, modifier)
        return
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items.forEach { item ->
            GlassChip(
                label = label(item),
                selected = item == selected,
                onClick = { onSelect(item) }
            )
        }
    }
}

@Composable
private fun <T> SlidingSegmentedPills(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier
) {
    if (items.isEmpty()) return
    val scheme = LocalBackgroundScheme.current
    val selectedIndex = items.indexOf(selected).coerceAtLeast(0)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(SonoraSurfaceHighlight)
    ) {
        SlidingLiquidIndicator(
            selectedIndex = selectedIndex,
            itemCount = items.size,
            modifier = Modifier.matchParentSize(),
            horizontalInset = 4.dp,
            verticalInset = 4.dp
        )
        Row(modifier = Modifier.matchParentSize()) {
            items.forEach { item ->
                val isSelected = item == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onSelect(item) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(item),
                        color = if (isSelected) onColorFor(scheme.accent) else scheme.onBackgroundSecondary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
