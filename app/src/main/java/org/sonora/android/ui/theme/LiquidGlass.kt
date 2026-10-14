package org.sonora.android.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.sonora.android.R
import androidx.compose.ui.unit.sp

/**
 * Resolved Liquid Glass surface colors taken from the current [BackgroundScheme].
 * Components default to these so every floating surface adapts to light/dark canvases.
 */
object GlassTokens {
    val tint: Color
        @Composable get() = LocalBackgroundScheme.current.glassTint

    val border: Color
        @Composable get() = LocalBackgroundScheme.current.glassBorder
}

@Immutable
data class GlassStyle(
    val tint: Color,
    val border: Color,
    val accent: Color
)

@Composable
fun rememberGlassStyle(): GlassStyle {
    val scheme = LocalBackgroundScheme.current
    return remember(scheme) { GlassStyle(scheme.glassTint, scheme.glassBorder, scheme.accent) }
}

/** Floating frosted panel (hero, sheets, expandable groups). */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.Large),
    tint: Color = GlassTokens.tint,
    border: Color = GlassTokens.border,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.liquidGlass(shape = shape, backgroundColor = tint, borderColor = border),
        content = content
    )
}

/** Compact glass card used for list/grid items and grouped controls. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.Small),
    tint: Color = GlassTokens.tint,
    border: Color = GlassTokens.border,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.liquidGlass(shape = shape, backgroundColor = tint, borderColor = border),
        content = content
    )
}

/** Floating glass bar (bottom navigation, transport rows). */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.Large),
    tint: Color = GlassTokens.tint,
    border: Color = GlassTokens.border,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.liquidGlass(shape = shape, backgroundColor = tint, borderColor = border),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
        content = content
    )
}

/**
 * Top app bar with a back affordance.
 *
 * @param glass when false the bar renders without a glass surface (plain, discreet
 *   header used by detail pages so it does not resemble a media/notification bar).
 * @param showTitle when false no title text is rendered (e.g. detail pages whose hero
 *   already shows the entity name — avoids a technical "YouTube Music" label).
 */
@Composable
fun GlassTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    glass: Boolean = true,
    showTitle: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val scheme = LocalBackgroundScheme.current
    val content: @Composable RowScope.() -> Unit = {
        if (onBack != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = scheme.onBackground,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(12.dp)
            )
        }
        if (showTitle && title.isNotBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.sm)
            )
        } else {
            Box(modifier = Modifier.weight(1f))
        }
        actions()
    }

    val barModifier = modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = Spacing.gutter, vertical = Spacing.sm)

    if (glass) {
        GlassBar(modifier = barModifier, horizontalArrangement = Arrangement.Start, content = content)
    } else {
        Row(
            modifier = barModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            content = content
        )
    }
}

/**
 * Selectable glass chip / pill (search filters, library tabs).
 * Selection tint uses the context accent; text stays high-contrast via the scheme.
 */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null
) {
    val scheme = LocalBackgroundScheme.current
    val shape = RoundedCornerShape(50)
    val background = if (selected) scheme.accent.copy(alpha = 0.22f) else scheme.glassTint
    val borderColor = if (selected) scheme.accent.copy(alpha = 0.55f) else scheme.glassBorder
    val contentColor = if (selected) scheme.accent else scheme.onBackgroundSecondary

    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (leadingIcon != null) {
            Icon(imageVector = leadingIcon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = contentColor,
            maxLines = 1
        )
    }
}

/**
 * Applies a subtle physical press-scale to any clickable surface.
 * Under Reduce Motion the scale is a no-op (1.0).
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource? = null,
    pressedScale: Float? = null
): Modifier {
    val motion = LocalMotionPreferences.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val target = pressedScale ?: motion.pressScale
    val scale by animateFloatAsState(
        targetValue = if (isPressed) target else 1f,
        animationSpec = motion.springSnap(),
        label = "pressScale"
    )
    return this.scale(scale)
}

/** Provides the glass content color so nested text/icons inherit scheme contrast. */
@Composable
fun ProvideGlassContent(content: @Composable () -> Unit) {
    val scheme = LocalBackgroundScheme.current
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
}

/** Convenience empty/placeholder panel with generous min-height for states. */
@Composable
fun GlassPlaceholder(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 120.dp)
            .padding(Spacing.lg),
        content = content
    )
}
