package org.sonora.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.ui.components.SlidingLiquidIndicator
import org.sonora.android.ui.components.chromeSurface
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.Radius
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.liquidGlass
import org.sonora.android.ui.theme.onColorFor

sealed class NavItem(val tab: NavigationTab, @StringRes val titleRes: Int, val icon: ImageVector) {
    object Home : NavItem(NavigationTab.HOME, R.string.nav_home, Icons.Default.Home)
    object Search : NavItem(NavigationTab.SEARCH, R.string.nav_search, Icons.Default.Search)
    object Library : NavItem(NavigationTab.LIBRARY, R.string.nav_library, Icons.Default.LibraryMusic)
    object Settings : NavItem(NavigationTab.SETTINGS, R.string.nav_settings, Icons.Default.Settings)
}

/**
 * Floating Liquid Glass bottom navigation with a single sliding "water bubble" indicator
 * (Apple Music style): the pill physically travels between the equally sized tabs and
 * squashes/stretches while moving. Snaps instantly under Reduced Motion.
 */
@Composable
fun SonoraBottomNav(
    navItems: List<NavItem>,
    currentTab: NavigationTab,
    isTabActive: Boolean,
    onSelect: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val chrome = chromeSurface(scheme)
    val selectedIndex = navItems.indexOfFirst { it.tab == currentTab }.coerceAtLeast(0)

    Box(
        modifier = modifier
            .height(Spacing.barHeight)
            .liquidGlass(
                shape = RoundedCornerShape(Radius.Large),
                backgroundColor = chrome.tint,
                borderColor = chrome.border
            )
    ) {
        SlidingLiquidIndicator(
            selectedIndex = selectedIndex,
            itemCount = navItems.size,
            modifier = Modifier.matchParentSize(),
            horizontalInset = 6.dp,
            verticalInset = 7.dp
        )

        Row(modifier = Modifier.matchParentSize()) {
            navItems.forEach { item ->
                val isSelected = isTabActive && currentTab == item.tab
                NavItemButton(
                    item = item,
                    isSelected = isSelected,
                    onClick = { onSelect(item.tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavItemButton(
    item: NavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val interaction = remember { MutableInteractionSource() }
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) onColorFor(scheme.accent) else scheme.onBackgroundSecondary,
        animationSpec = motion.tween(durationMs = motion.standardMs),
        label = "navContentColor"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = stringResource(item.titleRes),
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = stringResource(item.titleRes),
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
