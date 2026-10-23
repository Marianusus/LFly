package org.sonora.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.sonora.android.R
import org.sonora.android.ui.components.chromeSurface
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.LocalMotionPreferences
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.liquidGlass

sealed class NavItem(val tab: NavigationTab, @StringRes val titleRes: Int, val icon: ImageVector) {
    object Home : NavItem(NavigationTab.HOME, R.string.nav_home, Icons.Default.Home)
    object Search : NavItem(NavigationTab.SEARCH, R.string.nav_search, Icons.Default.Search)
    object Library : NavItem(NavigationTab.LIBRARY, R.string.nav_library, Icons.Default.LibraryMusic)
    object Settings : NavItem(NavigationTab.SETTINGS, R.string.nav_settings, Icons.Default.Settings)
}

/**
 * Geometry and motion are modelled on BitChord's floating tab bar (GPL-3.0):
 * a single selection pill that can be picked up and dragged across the tabs,
 * stretching with the remaining travel and ticking over each tab boundary.
 * Sonora's tabs, routing and glass material remain the source of truth.
 */
private val NavPillInset = 6.dp
private val NavTabGap = 6.dp
private val NavPillShape = RoundedCornerShape(percent = 50)
private val NavSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)
private const val NavStretch = 0.16f
private const val NavSquash = 0.50f

@Composable
fun SonoraBottomNav(
    navItems: List<NavItem>,
    currentTab: NavigationTab,
    isTabActive: Boolean,
    onSelect: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    if (navItems.isEmpty()) return

    val scheme = LocalBackgroundScheme.current
    val motion = LocalMotionPreferences.current
    val chrome = chromeSurface(scheme)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val selectedIndex = navItems.indexOfFirst { it.tab == currentTab }.coerceAtLeast(0)

    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var lastTickIndex by remember { mutableIntStateOf(selectedIndex) }
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val gapPx = with(density) { NavTabGap.toPx() }

    LaunchedEffect(selectedIndex) {
        dragOffsetPx = 0f
        lastTickIndex = selectedIndex
    }

    Box(
        modifier = modifier
            .height(Spacing.barHeight)
            .liquidGlass(
                shape = NavPillShape,
                backgroundColor = chrome.tint,
                borderColor = chrome.border
            )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(NavPillInset)
        ) {
            val n = navItems.size
            val rowWidthPx = with(density) { maxWidth.toPx() }
            val tabWidthPx = navTabWidthPx(rowWidthPx, gapPx, n)
            val tabStepPx = navTabStepPx(rowWidthPx, gapPx, n)
            val tabWidth = with(density) { tabWidthPx.toDp() }

            val targetPosition = if (tabStepPx > 0f) {
                (selectedIndex + dragOffsetPx / tabStepPx)
                    .coerceIn(0f, navItems.lastIndex.toFloat())
            } else {
                selectedIndex.toFloat()
            }
            val animatedPosition by animateFloatAsState(
                targetValue = targetPosition,
                animationSpec = if (motion.reduceMotion) snap() else NavSpring,
                label = "navBubblePosition"
            )
            val lag = if (tabStepPx > 0f) {
                (abs(targetPosition - animatedPosition) / 1f).coerceIn(0f, 1f)
            } else {
                0f
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset((animatedPosition * tabStepPx).roundToInt(), 0) }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val stretch = if (motion.reduceMotion) 0f else lag * NavStretch
                        scaleX = 1f + stretch
                        scaleY = 1f - stretch * NavSquash
                        transformOrigin = TransformOrigin.Center
                    }
                    .clip(NavPillShape)
                    .background(scheme.accent.copy(alpha = if (scheme.isLight) 0.14f else 0.17f))
                    .border(1.dp, scheme.accent.copy(alpha = 0.26f), NavPillShape)
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(tabStepPx, gapPx) {
                        if (tabStepPx <= 0f) return@pointerInput
                        var totalDrag = 0f
                        var dragStartIndex = currentSelectedIndex
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                dragStartIndex = currentSelectedIndex
                                lastTickIndex = currentSelectedIndex
                            },
                            onDragCancel = {
                                dragOffsetPx = 0f
                            },
                            onDragEnd = {
                                val targetIndex = navDragTargetIndex(
                                    dragStartIndex = dragStartIndex,
                                    totalDragPx = totalDrag,
                                    stepPx = tabStepPx,
                                    lastIndex = navItems.lastIndex
                                )
                                if (targetIndex != currentSelectedIndex) {
                                    currentOnSelect(navItems[targetIndex].tab)
                                }
                                dragOffsetPx = 0f
                            },
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                totalDrag += delta
                                dragOffsetPx = navDragOffsetPx(
                                    dragStartIndex = dragStartIndex,
                                    totalDragPx = totalDrag,
                                    stepPx = tabStepPx,
                                    selectedIndex = currentSelectedIndex,
                                    lastIndex = navItems.lastIndex
                                )
                                val absolute = (dragStartIndex + totalDrag / tabStepPx)
                                    .coerceIn(0f, navItems.lastIndex.toFloat())
                                val tickIndex = absolute.roundToInt()
                                if (tickIndex != lastTickIndex) {
                                    lastTickIndex = tickIndex
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        )
                    },
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(NavTabGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                navItems.forEach { item ->
                    val isSelected = isTabActive && currentTab == item.tab
                    NavItemButton(
                        item = item,
                        isSelected = isSelected,
                        onClick = {
                            if (!isSelected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            currentOnSelect(item.tab)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
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
        targetValue = if (isSelected) scheme.accent else scheme.onBackgroundSecondary,
        animationSpec = motion.tween(durationMs = 200),
        label = "navContentColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1f,
        animationSpec = if (motion.reduceMotion) snap() else NavSpring,
        label = "navIconScale"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(NavPillShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = stringResource(item.titleRes),
            tint = contentColor,
            modifier = Modifier
                .size(25.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(item.titleRes),
            color = contentColor,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
