package org.sonora.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.SonoraDivider
import org.sonora.android.ui.theme.SonoraSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import org.sonora.android.ui.theme.SonoraTheme

/** Icon tiles, one hue per subject. Every one keeps a white glyph above 4.4:1. */
internal object SettingsTile {
    val Blue = Color(0xFF0A6CFF)
    val Indigo = Color(0xFF5856D6)
    val Purple = Color(0xFF8944AB)
    val Red = Color(0xFFD70015)
    val Teal = Color(0xFF0E7C86)
    val Orange = Color(0xFFC2570C)
    val Graphite = Color(0xFF636366)
    val Green = Color(0xFF248A3D)
}

/** Both reds keep 4.5:1 on their own surface: #FF6961 on dark, #D70015 on light. */
internal val SettingsDestructive: Color
    @Composable get() = if (LocalBackgroundScheme.current.isLight) Color(0xFFD70015) else Color(0xFFFF6961)

private val GroupShape = RoundedCornerShape(16.dp)
private val RowHorizontalPadding = 16.dp
private val RowMinHeight = 50.dp
private val TileSize = 30.dp
private val TileGap = 12.dp

private val RowTitleStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    )

private val CaptionStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )

/** An inset grouped list section: a caption, rows on one surface, and an optional footnote. */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                text = header,
                style = CaptionStyle.copy(fontWeight = FontWeight.Medium),
                color = SonoraTextSecondary,
                modifier = Modifier.padding(start = RowHorizontalPadding, end = RowHorizontalPadding, bottom = 7.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GroupShape)
                .background(SonoraSurfaceCard)
                .then(
                    if (SonoraTheme.colors.isLiquidGlass) {
                        Modifier.border(0.5.dp, SonoraTheme.colors.cardBorder, GroupShape)
                    } else {
                        Modifier
                    }
                ),
            content = content
        )
        if (footer != null) {
            SettingsFootnote(
                text = footer,
                modifier = Modifier.padding(start = RowHorizontalPadding, end = RowHorizontalPadding, top = 7.dp)
            )
        }
    }
}

/** The stacked groups of one topic. */
@Composable
internal fun SettingsGroups(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp), content = content)
}

@Composable
internal fun SettingsFootnote(text: String, modifier: Modifier = Modifier, color: Color = SonoraTextSecondary) {
    Text(text = text, style = CaptionStyle, color = color, modifier = modifier)
}

/** A hairline that starts at the row text, so it reads as one list. */
@Composable
internal fun SettingsRowDivider(hasLeading: Boolean = false) {
    HorizontalDivider(
        modifier = Modifier.padding(
            start = if (hasLeading) RowHorizontalPadding + TileSize + TileGap else RowHorizontalPadding
        ),
        thickness = 0.5.dp,
        color = SonoraDivider
    )
}

@Composable
internal fun SettingsIconTile(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(TileSize)
            .background(color, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun RowLayout(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    titleColor: Color,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)?
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(horizontal = RowHorizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(TileGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = RowTitleStyle, color = titleColor)
            if (subtitle != null) {
                Text(text = subtitle, style = CaptionStyle, color = SonoraTextSecondary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A row that shows a value and, when it has [onClick], opens or does something. */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    tile: Color = SettingsTile.Graphite,
    leading: (@Composable () -> Unit)? = null,
    titleColor: Color = SonoraTextPrimary,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    RowLayout(
        modifier = if (onClick != null) modifier.clickable(enabled = enabled, onClick = onClick) else modifier,
        title = title,
        subtitle = subtitle,
        titleColor = titleColor,
        leading = leading ?: icon?.let { { SettingsIconTile(it, tile) } },
        trailing = if (value != null || showChevron || trailing != null) {
            {
                if (value != null) {
                    Text(
                        text = value,
                        style = RowTitleStyle,
                        color = SonoraTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                trailing?.invoke(this)
                if (showChevron) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = SonoraTextTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        } else {
            null
        }
    )
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tile: Color = SettingsTile.Graphite,
    enabled: Boolean = true
) {
    RowLayout(
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange
        ),
        title = title,
        subtitle = subtitle,
        titleColor = if (enabled) SonoraTextPrimary else SonoraTextSecondary,
        leading = icon?.let { { SettingsIconTile(it, tile) } },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SonoraTheme.colors.primaryAccent,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = SonoraSurfaceHighlight,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    )
}

/** One choice of a list: the selection is a checkmark, never a colour alone. */
@Composable
internal fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null
) {
    RowLayout(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        title = title,
        subtitle = subtitle,
        titleColor = SonoraTextPrimary,
        leading = leading,
        trailing = {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.settings_selected_content_description),
                        tint = SonoraTheme.colors.primaryAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    )
}
