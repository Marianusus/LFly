package org.sonora.android.ui

import androidx.annotation.StringRes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.api.AudioQuality
import org.sonora.android.api.PlaybackSource
import org.sonora.android.api.HealthStatus
import org.sonora.android.api.HighlightMode
import org.sonora.android.api.ProviderHealth
import org.sonora.android.AppLanguage
import org.sonora.android.R
import org.sonora.android.playback.AudioCacheManager
import org.sonora.android.playback.EqualizerState
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.SegmentedPills
import org.sonora.android.ui.theme.SonoraAmoledBlack
import org.sonora.android.ui.theme.SonoraDivider
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraPurple
import org.sonora.android.ui.theme.SonoraPurpleLight
import kotlin.math.roundToInt
import org.sonora.android.ui.theme.SonoraStatusDegraded
import org.sonora.android.ui.theme.SonoraStatusOffline
import org.sonora.android.ui.theme.SonoraStatusOnline
import org.sonora.android.ui.theme.SonoraSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import org.sonora.android.ui.theme.ThemeStyle
import org.sonora.android.ui.theme.AppearanceMode
import org.sonora.android.ui.theme.BackgroundSchemeFactory
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.AmoledEmerald
import org.sonora.android.ui.theme.AppleMusicPink
import org.sonora.android.ui.theme.AppleMusicCyan
import org.sonora.android.ui.theme.MidnightNeonPrimary
import org.sonora.android.ui.theme.MidnightNeonSecondary
import org.sonora.android.ui.theme.LiquidGlassCardBackground
import org.sonora.android.ui.theme.LiquidGlassCardBorder
import org.sonora.android.ui.theme.AccentPalette

private enum class SettingsTab(@StringRes val labelRes: Int) {
    GENERAL(R.string.settings_tab_general),
    APPEARANCE(R.string.settings_tab_appearance),
    MUSIC(R.string.settings_tab_music),
    API(R.string.settings_tab_api)
}

@Composable
private fun SettingsTabs(
    selected: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val labels = mapOf(
        SettingsTab.GENERAL to stringResource(R.string.settings_tab_general),
        SettingsTab.APPEARANCE to stringResource(R.string.settings_tab_appearance),
        SettingsTab.MUSIC to stringResource(R.string.settings_tab_music),
        SettingsTab.API to stringResource(R.string.settings_tab_api)
    )
    SegmentedPills(
        items = SettingsTab.entries,
        selected = selected,
        onSelect = onSelect,
        label = { labels.getValue(it) },
        modifier = modifier,
        slidingIndicator = true
    )
}

@Composable
fun SettingsScreen(
    currentThemeStyle: ThemeStyle = ThemeStyle.SONORA_AMOLED,
    onThemeStyleChange: (ThemeStyle) -> Unit = {},
    appearanceMode: AppearanceMode = AppearanceMode.AMOLED_BLACK,
    onAppearanceModeChange: (AppearanceMode) -> Unit = {},
    reduceMotion: Boolean = false,
    onReduceMotionChange: (Boolean) -> Unit = {},
    currentAudioQuality: AudioQuality,
    onAudioQualityChange: (AudioQuality) -> Unit,
    isAutoplayEnabled: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    cacheSizeBytes: Long,
    onClearCacheClick: () -> Unit,
    healthList: List<ProviderHealth>,
    isRefreshingApis: Boolean,
    onRefreshApisClick: () -> Unit,
    onViewDiagnosticsClick: () -> Unit,
    onBackClick: () -> Unit,
    showBack: Boolean = true,
    onOpenEqualizerClick: () -> Unit = {},
    equalizerState: EqualizerState? = null,
    translateLyrics: Boolean = false,
    onTranslateLyricsChange: (Boolean) -> Unit = {},
    translationTargetLang: String = "it",
    onTranslationTargetLangChange: (String) -> Unit = {},
    lyricsHighlightMode: HighlightMode = HighlightMode.AUTO,
    lyricsProviderMusixmatch: Boolean = true,
    onLyricsProviderMusixmatchChange: (Boolean) -> Unit = {},
    lyricsProviderLrclib: Boolean = true,
    onLyricsProviderLrclibChange: (Boolean) -> Unit = {},
    onLyricsHighlightModeChange: (HighlightMode) -> Unit = {},
    appLanguage: String = AppLanguage.SYSTEM,
    onAppLanguageChange: (String) -> Unit = {},
    accentColor: Int = AccentPalette.defaultArgb(),
    onAccentColorChange: (Int) -> Unit = {},
    sourcePreference: PlaybackSource = PlaybackSource.AUTO,
    onSourcePreferenceChange: (PlaybackSource) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var cacheClearedMessage by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.GENERAL) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (SonoraTheme.colors.isLiquidGlass) Color.Transparent else SonoraAmoledBlack
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = LocalBottomChromeInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header Bar
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showBack) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .size(40.dp)
                                .background(SonoraSurfaceHighlight, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back_content_description),
                                tint = SonoraTextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.settings_header_title),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = SonoraTextPrimary
                            )
                        )
                        Text(
                            text = stringResource(R.string.settings_header_subtitle),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SonoraTextSecondary
                            )
                        )
                    }
                }
            }

            // Tab selector
            item {
                SettingsTabs(
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            when (selectedTab) {
                SettingsTab.GENERAL -> {
                    item {
                        LanguageSection(
                            appLanguage = appLanguage,
                            onAppLanguageChange = onAppLanguageChange
                        )
                    }
                    item { AboutSonoraSection() }
                }

                SettingsTab.APPEARANCE -> {
                    item {
                        AppearanceSection(
                            appearanceMode = appearanceMode,
                            onAppearanceModeChange = onAppearanceModeChange,
                            reduceMotion = reduceMotion,
                            onReduceMotionChange = onReduceMotionChange
                        )
                    }
                    item {
                        AccentColorSection(
                            accentColor = accentColor,
                            onAccentColorChange = onAccentColorChange
                        )
                    }
                }

                SettingsTab.MUSIC -> {
                    item {
                        StreamingSourceSection(
                            currentSource = sourcePreference,
                            onSourceSelected = onSourcePreferenceChange
                        )
                    }
                    item {
                        AudioQualitySection(
                            currentQuality = currentAudioQuality,
                            onQualitySelected = onAudioQualityChange
                        )
                    }
                    item {
                        EqualizerSettingsSection(
                            equalizerState = equalizerState,
                            onOpenEqualizer = onOpenEqualizerClick
                        )
                    }
                    item {
                        PlaybackSection(
                            isAutoplayEnabled = isAutoplayEnabled,
                            onAutoplayChange = onAutoplayChange
                        )
                    }
                    item {
                        TranslationSection(
                            translateLyrics = translateLyrics,
                            onTranslateLyricsChange = onTranslateLyricsChange,
                            targetLang = translationTargetLang,
                            onTargetLangChange = onTranslationTargetLangChange,
                            highlightMode = lyricsHighlightMode,
                            onHighlightModeChange = onLyricsHighlightModeChange,
                            musixmatchEnabled = lyricsProviderMusixmatch,
                            onMusixmatchChange = onLyricsProviderMusixmatchChange,
                            lrclibEnabled = lyricsProviderLrclib,
                            onLrclibChange = onLyricsProviderLrclibChange
                        )
                    }
                    item {
                        CacheSection(
                            cacheSizeBytes = cacheSizeBytes,
                            cacheCleared = cacheClearedMessage,
                            onClearCache = {
                                onClearCacheClick()
                                cacheClearedMessage = true
                            }
                        )
                    }
                }

                SettingsTab.API -> {
                    item {
                        ApiHealthSection(
                            healthList = healthList,
                            isRefreshing = isRefreshingApis,
                            onRefresh = onRefreshApisClick,
                            onViewDiagnostics = onViewDiagnosticsClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageSection(
    appLanguage: String,
    onAppLanguageChange: (String) -> Unit
) {
    var pickerOpen by remember { mutableStateOf(false) }
    if (pickerOpen) {
        AppLanguageDialog(
            current = appLanguage,
            onDismiss = { pickerOpen = false },
            onSelect = { tag ->
                pickerOpen = false
                onAppLanguageChange(tag)
            }
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(SonoraPurple.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = SonoraPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_language_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_language_section_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SonoraSurfaceHighlight)
                    .then(
                        if (SonoraTheme.colors.isLiquidGlass) {
                            Modifier.border(1.dp, SonoraTheme.colors.cardBorder, RoundedCornerShape(12.dp))
                        } else Modifier
                    )
                    .clickable { pickerOpen = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_language_row_title),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = SonoraTextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = appLanguageLabel(appLanguage),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = SonoraTextSecondary
                )
            }
        }
    }
}

/** Localized label for the currently selected app language. */
@Composable
private fun appLanguageLabel(tag: String): String = when (tag) {
    AppLanguage.ITALIAN -> stringResource(R.string.language_italian)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.ROMANIAN -> stringResource(R.string.language_romanian)
    else -> stringResource(R.string.language_system)
}

@Composable
private fun AppLanguageDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val accent = SonoraTheme.colors.primaryAccent
    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.language_system),
        AppLanguage.ITALIAN to stringResource(R.string.language_italian),
        AppLanguage.ENGLISH to stringResource(R.string.language_english),
        AppLanguage.ROMANIAN to stringResource(R.string.language_romanian)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SonoraSurfaceCard,
        title = {
            Text(
                text = stringResource(R.string.settings_language_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                color = SonoraTextPrimary
            )
        },
        text = {
            Column {
                options.forEach { (tag, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(tag) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (tag == current) accent else SonoraTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (tag == current) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = accent
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_close), color = SonoraTextSecondary)
            }
        }
    )
}

@Composable
private fun ThemeSelectionSection(
    currentTheme: ThemeStyle,
    onThemeSelected: (ThemeStyle) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = SonoraEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_theme_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_theme_section_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val themeOptions = listOf(
                Triple(
                    ThemeStyle.SONORA_AMOLED,
                    stringResource(R.string.settings_theme_amoled_name),
                    stringResource(R.string.settings_theme_amoled_desc)
                ),
                Triple(
                    ThemeStyle.APPLE_LIQUID_GLASS,
                    stringResource(R.string.settings_theme_liquid_glass_name),
                    stringResource(R.string.settings_theme_liquid_glass_desc)
                ),
                Triple(
                    ThemeStyle.MIDNIGHT_NEON,
                    stringResource(R.string.settings_theme_midnight_neon_name),
                    stringResource(R.string.settings_theme_midnight_neon_desc)
                )
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                themeOptions.forEach { (style, name, desc) ->
                    val isSelected = style == currentTheme
                    val accentColor = when (style) {
                        ThemeStyle.SONORA_AMOLED -> AmoledEmerald
                        ThemeStyle.APPLE_LIQUID_GLASS -> AppleMusicPink
                        ThemeStyle.MIDNIGHT_NEON -> MidnightNeonPrimary
                    }
                    val secondaryColor = when (style) {
                        ThemeStyle.SONORA_AMOLED -> Color(0xFF8A2BE2)
                        ThemeStyle.APPLE_LIQUID_GLASS -> AppleMusicCyan
                        ThemeStyle.MIDNIGHT_NEON -> MidnightNeonSecondary
                    }
                    val cardBgColor = when (style) {
                        ThemeStyle.SONORA_AMOLED -> Color(0xFF141414)
                        ThemeStyle.APPLE_LIQUID_GLASS -> LiquidGlassCardBackground
                        ThemeStyle.MIDNIGHT_NEON -> Color(0xFF13132B)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) accentColor.copy(alpha = 0.14f)
                                else SonoraSurfaceHighlight
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentColor else if (style == ThemeStyle.APPLE_LIQUID_GLASS) LiquidGlassCardBorder else Color.Transparent,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onThemeSelected(style) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            // Mini Color Palette Preview Swatch
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(cardBgColor)
                                    .border(
                                        width = 1.dp,
                                        color = if (style == ThemeStyle.APPLE_LIQUID_GLASS) LiquidGlassCardBorder else Color(0x33FFFFFF),
                                        shape = RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .background(accentColor, CircleShape)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .background(secondaryColor, CircleShape)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isSelected) accentColor else SonoraTextPrimary
                                    )
                                )
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = SonoraTextSecondary
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(accentColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.settings_selected_content_description),
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .border(1.5.dp, SonoraTextTertiary, CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingSourceSection(
    currentSource: PlaybackSource,
    onSourceSelected: (PlaybackSource) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        tint = SonoraEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_source_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_source_section_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val tiers = listOf(
                Triple(PlaybackSource.AUTO, stringResource(R.string.source_auto_name), stringResource(R.string.source_auto_desc)),
                Triple(PlaybackSource.YOUTUBE_MUSIC, stringResource(R.string.source_youtube_name), stringResource(R.string.source_youtube_desc)),
                Triple(PlaybackSource.MONOCHROME, stringResource(R.string.source_monochrome_name), stringResource(R.string.source_monochrome_desc))
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiers.forEach { (source, name, desc) ->
                    val isSelected = source == currentSource
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) SonoraEmerald.copy(alpha = 0.12f)
                                else SonoraSurfaceHighlight
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else if (SonoraTheme.colors.isLiquidGlass) 1.dp else 0.dp,
                                color = if (isSelected) SonoraEmerald else if (SonoraTheme.colors.isLiquidGlass) SonoraTheme.colors.cardBorder else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSourceSelected(source) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) SonoraEmerald else SonoraTextPrimary
                                )
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = SonoraTextSecondary
                                )
                            )
                        }

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(SonoraEmerald, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.settings_selected_content_description),
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioQualitySection(
    currentQuality: AudioQuality,
    onQualitySelected: (AudioQuality) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = SonoraEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_audio_quality_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_audio_quality_section_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val qualityTiers = listOf(
                Triple(AudioQuality.LOW, stringResource(R.string.settings_quality_low_name), stringResource(R.string.settings_quality_low_desc)),
                Triple(AudioQuality.MEDIUM, stringResource(R.string.settings_quality_medium_name), stringResource(R.string.settings_quality_medium_desc)),
                Triple(AudioQuality.HIGH, stringResource(R.string.settings_quality_high_name), stringResource(R.string.settings_quality_high_desc)),
                Triple(AudioQuality.ULTRA, stringResource(R.string.settings_quality_ultra_name), stringResource(R.string.settings_quality_ultra_desc))
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                qualityTiers.forEach { (quality, name, desc) ->
                    val isSelected = quality == currentQuality
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) SonoraEmerald.copy(alpha = 0.12f)
                                else SonoraSurfaceHighlight
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else if (SonoraTheme.colors.isLiquidGlass) 1.dp else 0.dp,
                                color = if (isSelected) SonoraEmerald else if (SonoraTheme.colors.isLiquidGlass) SonoraTheme.colors.cardBorder else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onQualitySelected(quality) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) SonoraEmerald else SonoraTextPrimary
                                )
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = SonoraTextSecondary
                                )
                            )
                        }

                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(SonoraEmerald, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.settings_selected_content_description),
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EqualizerSettingsSection(
    equalizerState: EqualizerState?,
    onOpenEqualizer: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = null,
                        tint = SonoraEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_equalizer_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_equalizer_section_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val isEnabled = equalizerState?.isEnabled ?: true
            val presetName = equalizerState?.preset?.displayName ?: stringResource(R.string.settings_equalizer_preset_flat)
            val bassBoost = equalizerState?.bassBoostStrength ?: 0

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SonoraSurfaceHighlight)
                    .then(
                        if (SonoraTheme.colors.isLiquidGlass) {
                            Modifier.border(1.dp, SonoraTheme.colors.cardBorder, RoundedCornerShape(12.dp))
                        } else Modifier
                    )
                    .clickable { onOpenEqualizer() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.settings_equalizer_profile, presetName),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isEnabled) SonoraEmerald else SonoraTextSecondary
                            )
                        )
                        if (bassBoost > 0 && isEnabled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SonoraPurple.copy(alpha = 0.25f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_equalizer_bass_boost, (bassBoost / 10f).roundToInt()),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = SonoraPurpleLight,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }
                    Text(
                        text = if (isEnabled) stringResource(R.string.settings_equalizer_bands_active) else stringResource(R.string.settings_equalizer_bypassed),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextTertiary
                        )
                    )
                }

                Button(
                    onClick = onOpenEqualizer,
                    shape = SonoraTheme.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SonoraEmerald,
                        contentColor = if (SonoraTheme.colors.isLiquidGlass) Color.White else Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_equalizer_configure),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackSection(
    isAutoplayEnabled: Boolean,
    onAutoplayChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(SonoraPurple.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        tint = SonoraPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_playback_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_playback_section_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Autoplay / Infinite Radio Switch Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SonoraSurfaceHighlight)
                    .then(
                        if (SonoraTheme.colors.isLiquidGlass) {
                            Modifier.border(1.dp, SonoraTheme.colors.cardBorder, RoundedCornerShape(12.dp))
                        } else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_autoplay_title),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = SonoraTextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_autoplay_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Switch(
                    checked = isAutoplayEnabled,
                    onCheckedChange = onAutoplayChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = SonoraEmerald,
                        uncheckedThumbColor = SonoraTextSecondary,
                        uncheckedTrackColor = SonoraDivider
                    )
                )
            }
        }
    }
}

@Composable
private fun ProviderToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SonoraSurfaceHighlight)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = SonoraTextPrimary
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = SonoraEmerald,
                uncheckedThumbColor = SonoraTextSecondary,
                uncheckedTrackColor = SonoraDivider
            )
        )
    }
}

@Composable
private fun TranslationSection(
    translateLyrics: Boolean,
    onTranslateLyricsChange: (Boolean) -> Unit,
    targetLang: String,
    onTargetLangChange: (String) -> Unit,
    highlightMode: HighlightMode,
    onHighlightModeChange: (HighlightMode) -> Unit,
    musixmatchEnabled: Boolean,
    onMusixmatchChange: (Boolean) -> Unit,
    lrclibEnabled: Boolean,
    onLrclibChange: (Boolean) -> Unit
) {
    var languagePickerOpen by remember { mutableStateOf(false) }
    if (languagePickerOpen) {
        TranslationLanguageDialog(
            current = targetLang,
            onDismiss = { languagePickerOpen = false },
            onSelect = { code ->
                languagePickerOpen = false
                onTargetLangChange(code)
            }
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = SonoraEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_translation_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_translation_section_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SonoraSurfaceHighlight)
                    .then(
                        if (SonoraTheme.colors.isLiquidGlass) {
                            Modifier.border(1.dp, SonoraTheme.colors.cardBorder, RoundedCornerShape(12.dp))
                        } else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_translate_lyrics_title),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = SonoraTextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_translate_lyrics_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Switch(
                    checked = translateLyrics,
                    onCheckedChange = onTranslateLyricsChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = SonoraEmerald,
                        uncheckedThumbColor = SonoraTextSecondary,
                        uncheckedTrackColor = SonoraDivider
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.settings_translation_target_language_label),
                style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SonoraSurfaceHighlight)
                    .then(
                        if (SonoraTheme.colors.isLiquidGlass) {
                            Modifier.border(1.dp, SonoraTheme.colors.cardBorder, RoundedCornerShape(12.dp))
                        } else Modifier
                    )
                    .clickable { languagePickerOpen = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = translationLanguageLabel(targetLang),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = SonoraTextPrimary
                    )
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = SonoraTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.settings_translation_highlight_label),
                style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    HighlightMode.AUTO to stringResource(R.string.settings_highlight_auto),
                    HighlightMode.WORD to stringResource(R.string.settings_highlight_word),
                    HighlightMode.LINE to stringResource(R.string.settings_highlight_line)
                ).forEach { (mode, label) ->
                    val isSelected = mode == highlightMode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) SonoraEmerald.copy(alpha = 0.18f)
                                else SonoraSurfaceHighlight
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) SonoraEmerald else SonoraDivider,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onHighlightModeChange(mode) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) SonoraEmerald else SonoraTextSecondary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.settings_translation_sources_label),
                style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary)
            )

            Spacer(modifier = Modifier.height(8.dp))

            ProviderToggleRow(
                title = stringResource(R.string.settings_provider_musixmatch_title),
                subtitle = stringResource(R.string.settings_provider_musixmatch_subtitle),
                checked = musixmatchEnabled,
                onCheckedChange = onMusixmatchChange
            )

            Spacer(modifier = Modifier.height(10.dp))

            ProviderToggleRow(
                title = stringResource(R.string.settings_provider_lrclib_title),
                subtitle = stringResource(R.string.settings_provider_lrclib_subtitle),
                checked = lrclibEnabled,
                onCheckedChange = onLrclibChange
            )
        }
    }
}

@Composable
private fun CacheSection(
    cacheSizeBytes: Long,
    cacheCleared: Boolean,
    onClearCache: () -> Unit
) {
    val cacheMb = cacheSizeBytes.toDouble() / (1024.0 * 1024.0)
    val formattedCacheSize = stringResource(R.string.settings_cache_size_mb, cacheMb)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = SonoraEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_cache_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_cache_section_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cache size indicator row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SonoraSurfaceHighlight)
                    .then(
                        if (SonoraTheme.colors.isLiquidGlass) {
                            Modifier.border(1.dp, SonoraTheme.colors.cardBorder, RoundedCornerShape(12.dp))
                        } else Modifier
                    )
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_cache_size_title),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_cache_limit_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraTextSecondary
                        )
                    )
                }

                Text(
                    text = formattedCacheSize,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = SonoraEmerald
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onClearCache,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SonoraSurfaceHighlight,
                    contentColor = SonoraTextPrimary
                ),
                shape = SonoraTheme.buttonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (SonoraTheme.colors.isLiquidGlass) {
                            Modifier.border(1.dp, SonoraTheme.colors.cardBorder, SonoraTheme.buttonShape)
                        } else Modifier
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = SonoraEmerald,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_cache_clear_button),
                    fontWeight = FontWeight.SemiBold
                )
            }

            AnimatedVisibility(visible = cacheCleared) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(SonoraEmerald.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.settings_cache_cleared_message),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = SonoraEmerald,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiHealthSection(
    healthList: List<ProviderHealth>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onViewDiagnostics: () -> Unit
) {
    val totalCount = healthList.size
    val onlineCount = healthList.count { it.status == HealthStatus.ONLINE }
    val degradedCount = healthList.count { it.status == HealthStatus.DEGRADED }
    val offlineCount = healthList.count { it.status == HealthStatus.OFFLINE }

    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = SonoraEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_api_section_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = SonoraTextPrimary
                            )
                        )
                        Text(
                            text = stringResource(R.string.settings_api_providers_registered, totalCount),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SonoraTextSecondary
                            )
                        )
                    }
                }

                // Refresh APIs Button
                IconButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier
                        .size(36.dp)
                        .background(SonoraSurfaceHighlight, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.settings_api_refresh_content_description),
                        tint = SonoraEmerald,
                        modifier = Modifier
                            .size(18.dp)
                            .then(if (isRefreshing) Modifier.rotate(rotation) else Modifier)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Status Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SonoraStatusOnline.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.settings_api_status_online, onlineCount),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraStatusOnline
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SonoraStatusDegraded.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.settings_api_status_degraded, degradedCount),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraStatusDegraded
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SonoraStatusOffline.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.settings_api_status_offline, offlineCount),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraStatusOffline
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SonoraEmerald,
                        contentColor = if (SonoraTheme.colors.isLiquidGlass) Color.White else Color.Black
                    ),
                    shape = SonoraTheme.buttonShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isRefreshing) stringResource(R.string.settings_api_checking) else stringResource(R.string.settings_api_refresh_button),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = onViewDiagnostics,
                    shape = SonoraTheme.buttonShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SonoraTheme.colors.cardBorder)
                ) {
                    Text(
                        text = stringResource(R.string.settings_api_details_button),
                        color = SonoraTextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSonoraSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = SonoraEmerald,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.settings_about_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    color = SonoraTextPrimary
                )
            )

            Text(
                text = stringResource(R.string.settings_about_version),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = SonoraEmerald
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_about_description),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = SonoraTextSecondary,
                    lineHeight = 18.sp
                ),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .background(SonoraSurfaceHighlight, RoundedCornerShape(12.dp))
                    .then(
                        if (SonoraTheme.colors.isLiquidGlass) {
                            Modifier.border(1.dp, SonoraTheme.colors.cardBorder, RoundedCornerShape(12.dp))
                        } else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_about_license),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SonoraTextTertiary,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
private fun AccentColorSection(
    accentColor: Int,
    onAccentColorChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(accentColor).copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = Color(accentColor),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_accent_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_accent_section_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(AccentPalette.options, key = { it.id }) { option ->
                    val isSelected = option.color.toArgb() == accentColor
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(option.color)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) SonoraTextPrimary else SonoraDivider,
                                shape = CircleShape
                            )
                            .clickable { onAccentColorChange(option.color.toArgb()) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(
    appearanceMode: AppearanceMode,
    onAppearanceModeChange: (AppearanceMode) -> Unit,
    reduceMotion: Boolean,
    onReduceMotionChange: (Boolean) -> Unit
) {
    val scheme = SonoraTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SonoraTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(scheme.primaryAccent.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = scheme.primaryAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.settings_appearance_section_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_appearance_section_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppearanceMode.entries.forEach { mode ->
                    val preview = BackgroundSchemeFactory.fallback(mode)
                    val isSelected = mode == appearanceMode
                    val accentColor = preview.accent
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) accentColor.copy(alpha = 0.14f) else SonoraSurfaceHighlight)
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) accentColor else Color.Transparent,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onAppearanceModeChange(mode) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Brush.verticalGradient(preview.gradient))
                                    .border(1.dp, preview.glassBorder, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(9.dp).background(preview.accent, CircleShape))
                                    Box(modifier = Modifier.size(9.dp).background(preview.accentSecondary, CircleShape))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(mode.titleRes),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isSelected) accentColor else SonoraTextPrimary
                                    )
                                )
                                Text(
                                    text = stringResource(mode.subtitleRes),
                                    style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary)
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_reduce_motion_title),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = SonoraTextPrimary
                        )
                    )
                    Text(
                        text = stringResource(R.string.settings_reduce_motion_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary)
                    )
                }
                Switch(
                    checked = reduceMotion,
                    onCheckedChange = onReduceMotionChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = scheme.primaryAccent
                    )
                )
            }
        }
    }
}
