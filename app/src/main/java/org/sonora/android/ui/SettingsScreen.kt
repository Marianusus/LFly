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
import androidx.compose.material.icons.filled.Code
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.sonora.android.ui.settings.SettingsDestructive
import org.sonora.android.ui.settings.SettingsFootnote
import org.sonora.android.ui.settings.SettingsChoiceRow
import org.sonora.android.ui.settings.SettingsGroup
import org.sonora.android.ui.settings.SettingsGroups
import org.sonora.android.ui.settings.SettingsRow
import org.sonora.android.ui.settings.SettingsRowDivider
import org.sonora.android.ui.settings.SettingsTile
import org.sonora.android.ui.settings.SettingsToggleRow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
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
import java.util.Locale
import org.sonora.android.api.AudioQuality
import org.sonora.android.api.PlaybackSource
import org.sonora.android.api.HealthStatus
import org.sonora.android.api.HighlightMode
import org.sonora.android.api.lyrics.sources.LyricsSource
import org.sonora.android.api.ProviderHealth
import org.sonora.android.AppLanguage
import org.sonora.android.R
import org.sonora.android.api.EuropeChartsClient
import org.sonora.android.playback.AudioCacheManager
import org.sonora.android.playback.EqualizerState
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.components.LyricsSourcesDialog
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
import org.sonora.android.ui.components.backdrop.isRenderEffectSupported

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
    ambientRotationEnabled: Boolean = true,
    onAmbientRotationChange: (Boolean) -> Unit = {},
    liquidGlass: Boolean = false,
    onLiquidGlassChange: (Boolean) -> Unit = {},
    currentAudioQuality: AudioQuality,
    onAudioQualityChange: (AudioQuality) -> Unit,
    downloadQuality: AudioQuality = AudioQuality.MEDIUM,
    onDownloadQualityChange: (AudioQuality) -> Unit = {},
    downloadWifiOnly: Boolean = true,
    onDownloadWifiOnlyChange: (Boolean) -> Unit = {},
    downloadLyrics: Boolean = false,
    onDownloadLyricsChange: (Boolean) -> Unit = {},
    onOpenOfflineLyrics: () -> Unit = {},
    downloadsSizeBytes: Long = 0L,
    onClearDownloads: () -> Unit = {},
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
    lyricsSources: Set<LyricsSource> = LyricsSource.offered.toSet(),
    lyricsSourceOrder: List<LyricsSource> = LyricsSource.entries,
    prioritizeSyllableSync: Boolean = false,
    paxsenixApiKey: String = "",
    onLyricsSourceEnabledChange: (LyricsSource, Boolean) -> Unit = { _, _ -> },
    onLyricsSourceOrderChange: (List<LyricsSource>) -> Unit = {},
    onPrioritizeSyllableSyncChange: (Boolean) -> Unit = {},
    onPaxsenixApiKeyChange: (String) -> Unit = {},
    onResetLyricsSources: () -> Unit = {},
    onClearTranslationModels: () -> Unit = {},
    onLyricsHighlightModeChange: (HighlightMode) -> Unit = {},
    appLanguage: String = AppLanguage.SYSTEM,
    onAppLanguageChange: (String) -> Unit = {},
    homeCountryCode: String = "it",
    onHomeCountryChange: (String) -> Unit = {},
    accentColor: Int = AccentPalette.defaultArgb(),
    onAccentColorChange: (Int) -> Unit = {},
    sourcePreference: PlaybackSource = PlaybackSource.AUTO,
    onSourcePreferenceChange: (PlaybackSource) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var cacheClearedMessage by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.GENERAL) }
    var lyricsSourcesOpen by remember { mutableStateOf(false) }
    if (lyricsSourcesOpen) {
        LyricsSourcesDialog(
            sources = lyricsSources,
            order = lyricsSourceOrder,
            prioritizeSyllableSync = prioritizeSyllableSync,
            apiKey = paxsenixApiKey,
            onDismiss = { lyricsSourcesOpen = false },
            onApply = { chosen, order, syllable, key ->
                onLyricsSourceOrderChange(order)
                LyricsSource.entries.forEach { source ->
                    val enabled = source in chosen
                    if (enabled != (source in lyricsSources)) {
                        onLyricsSourceEnabledChange(source, enabled)
                    }
                }
                onPrioritizeSyllableSyncChange(syllable)
                onPaxsenixApiKeyChange(key)
            },
            onReset = onResetLyricsSources,
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (SonoraTheme.colors.isLiquidGlass) Color.Transparent else SonoraAmoledBlack
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = LocalBottomChromeInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header Bar
            item {
                Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                    if (showBack) {
                        IconButton(onClick = onBackClick, modifier = Modifier.offset(x = (-12).dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back_content_description),
                                tint = SonoraTheme.colors.primaryAccent
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.settings_header_title),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontSize = 34.sp,
                            lineHeight = 41.sp,
                            color = SonoraTextPrimary
                        )
                    )
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
                        SettingsGroup {
                            LanguageSection(
                                appLanguage = appLanguage,
                                onAppLanguageChange = onAppLanguageChange
                            )
                            SettingsRowDivider(hasLeading = true)
                            CountrySection(
                                currentCountryCode = homeCountryCode,
                                onCountryChange = onHomeCountryChange
                            )
                        }
                    }
                    item { DeveloperSection() }
                    item { AboutSonoraSection() }
                }

                SettingsTab.APPEARANCE -> {
                    item {
                        AppearanceSection(
                            appearanceMode = appearanceMode,
                            onAppearanceModeChange = onAppearanceModeChange,
                            reduceMotion = reduceMotion,
                            onReduceMotionChange = onReduceMotionChange,
                            ambientRotationEnabled = ambientRotationEnabled,
                            onAmbientRotationChange = onAmbientRotationChange,
                            liquidGlass = liquidGlass,
                            onLiquidGlassChange = onLiquidGlassChange
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
                        DownloadSettingsSection(
                            currentQuality = downloadQuality,
                            onQualitySelected = onDownloadQualityChange,
                            wifiOnly = downloadWifiOnly,
                            onWifiOnlyChange = onDownloadWifiOnlyChange,
                            downloadLyrics = downloadLyrics,
                            onDownloadLyricsChange = onDownloadLyricsChange,
                            onOpenOfflineLyrics = onOpenOfflineLyrics,
                            sizeBytes = downloadsSizeBytes,
                            onClearDownloads = onClearDownloads
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
                            onLrclibChange = onLyricsProviderLrclibChange,
                            sources = lyricsSources,
                            onOpenSources = { lyricsSourcesOpen = true },
                            onClearTranslationModels = onClearTranslationModels
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
    SettingsRow(
        title = stringResource(R.string.settings_language_row_title),
        value = appLanguageLabel(appLanguage),
        icon = Icons.Default.Language,
        tile = SettingsTile.Blue,
        showChevron = true,
        onClick = { pickerOpen = true }
    )
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
private fun CountrySection(
    currentCountryCode: String,
    onCountryChange: (String) -> Unit
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val current = EuropeChartsClient.COUNTRIES.firstOrNull { it.code == currentCountryCode }
        ?: EuropeChartsClient.COUNTRIES.firstOrNull { it.code == "it" }

    if (pickerOpen) {
        CountryPickerDialog(
            currentCountryCode = currentCountryCode,
            onDismiss = { pickerOpen = false },
            onSelect = { code ->
                pickerOpen = false
                onCountryChange(code)
            }
        )
    }

    SettingsRow(
        title = stringResource(R.string.settings_country_row_title),
        value = current?.let { "${countryFlagEmoji(it.code)}  ${it.name}" },
        icon = Icons.Default.Public,
        tile = SettingsTile.Teal,
        showChevron = true,
        onClick = { pickerOpen = true }
    )
}

@Composable
private fun CountryPickerDialog(
    currentCountryCode: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val accent = SonoraTheme.colors.primaryAccent
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SonoraSurfaceCard,
        title = {
            Text(
                text = stringResource(R.string.settings_country_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                color = SonoraTextPrimary
            )
        },
        text = {
            LazyColumn(modifier = Modifier.height(420.dp)) {
                items(
                    items = EuropeChartsClient.COUNTRIES,
                    key = { it.code }
                ) { country ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(country.code) }
                            .padding(horizontal = 8.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = countryFlagEmoji(country.code),
                            fontSize = 17.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = country.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (country.code == currentCountryCode) accent else SonoraTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (country.code == currentCountryCode) {
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

private fun countryFlagEmoji(code: String): String {
    if (code.length != 2) return "\uD83C\uDF0D"
    return runCatching {
        String(Character.toChars(0x1F1E6 + (code[0].uppercaseChar() - 'A'))) +
            String(Character.toChars(0x1F1E6 + (code[1].uppercaseChar() - 'A')))
    }.getOrDefault("\uD83C\uDF0D")
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
    val tiers = listOf(
        Triple(PlaybackSource.AUTO, stringResource(R.string.source_auto_name), stringResource(R.string.source_auto_desc)),
        Triple(PlaybackSource.YOUTUBE_MUSIC, stringResource(R.string.source_youtube_name), stringResource(R.string.source_youtube_desc)),
        Triple(PlaybackSource.MONOCHROME, stringResource(R.string.source_monochrome_name), stringResource(R.string.source_monochrome_desc))
    )
    SettingsGroup(
        header = stringResource(R.string.settings_source_section_title),
        footer = stringResource(R.string.settings_source_section_subtitle)
    ) {
        tiers.forEachIndexed { index, (source, name, desc) ->
            if (index > 0) SettingsRowDivider()
            SettingsChoiceRow(
                title = name,
                subtitle = desc,
                selected = source == currentSource,
                onClick = { onSourceSelected(source) }
            )
        }
    }
}

@Composable
private fun AudioQualitySection(
    currentQuality: AudioQuality,
    onQualitySelected: (AudioQuality) -> Unit
) {
    AudioQualityChoices(
        header = stringResource(R.string.settings_audio_quality_section_title),
        footer = stringResource(R.string.settings_audio_quality_section_subtitle),
        currentQuality = currentQuality,
        onQualitySelected = onQualitySelected
    )
}

@Composable
private fun AudioQualityChoices(
    header: String,
    footer: String?,
    currentQuality: AudioQuality,
    onQualitySelected: (AudioQuality) -> Unit
) {
    val tiers = listOf(
        Triple(AudioQuality.LOW, stringResource(R.string.settings_quality_low_name), stringResource(R.string.settings_quality_low_desc)),
        Triple(AudioQuality.MEDIUM, stringResource(R.string.settings_quality_medium_name), stringResource(R.string.settings_quality_medium_desc)),
        Triple(AudioQuality.HIGH, stringResource(R.string.settings_quality_high_name), stringResource(R.string.settings_quality_high_desc)),
        Triple(AudioQuality.ULTRA, stringResource(R.string.settings_quality_ultra_name), stringResource(R.string.settings_quality_ultra_desc))
    )
    SettingsGroup(header = header, footer = footer) {
        tiers.forEachIndexed { index, (quality, name, desc) ->
            if (index > 0) SettingsRowDivider()
            SettingsChoiceRow(
                title = name,
                subtitle = desc,
                selected = quality == currentQuality,
                onClick = { onQualitySelected(quality) }
            )
        }
    }
}

@Composable
private fun DownloadSettingsSection(
    currentQuality: AudioQuality,
    onQualitySelected: (AudioQuality) -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    downloadLyrics: Boolean,
    onDownloadLyricsChange: (Boolean) -> Unit,
    onOpenOfflineLyrics: () -> Unit,
    sizeBytes: Long,
    onClearDownloads: () -> Unit
) {
    SettingsGroups {
        AudioQualityChoices(
            header = stringResource(R.string.settings_download_quality_label),
            footer = stringResource(R.string.settings_downloads_subtitle),
            currentQuality = currentQuality,
            onQualitySelected = onQualitySelected
        )
        SettingsGroup(header = stringResource(R.string.settings_downloads_title)) {
            SettingsToggleRow(
                title = stringResource(R.string.settings_download_wifi_only),
                subtitle = stringResource(R.string.settings_download_wifi_only_desc),
                checked = wifiOnly,
                onCheckedChange = onWifiOnlyChange,
                icon = Icons.Default.Wifi,
                tile = SettingsTile.Blue
            )
            SettingsRowDivider(hasLeading = true)
            SettingsToggleRow(
                title = stringResource(R.string.settings_download_lyrics),
                subtitle = stringResource(R.string.settings_download_lyrics_desc),
                checked = downloadLyrics,
                onCheckedChange = onDownloadLyricsChange,
                icon = Icons.Default.Lyrics,
                tile = SettingsTile.Purple
            )
            SettingsRowDivider(hasLeading = true)
            SettingsRow(
                title = stringResource(R.string.offline_lyrics_title),
                subtitle = stringResource(R.string.settings_offline_lyrics_desc),
                icon = Icons.Default.Translate,
                tile = SettingsTile.Indigo,
                showChevron = true,
                onClick = onOpenOfflineLyrics
            )
            SettingsRowDivider(hasLeading = true)
            SettingsRow(
                title = if (sizeBytes > 0L) {
                    stringResource(R.string.settings_download_storage, formatDownloadBytes(sizeBytes))
                } else {
                    stringResource(R.string.settings_download_storage_empty)
                },
                icon = Icons.Default.Storage,
                tile = SettingsTile.Graphite
            )
            if (sizeBytes > 0L) {
                SettingsRowDivider(hasLeading = true)
                SettingsRow(
                    title = stringResource(R.string.settings_download_clear),
                    titleColor = SettingsDestructive,
                    icon = Icons.Default.DeleteOutline,
                    tile = SettingsTile.Red,
                    onClick = onClearDownloads
                )
            }
        }
    }
}

private fun formatDownloadBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

@Composable
private fun EqualizerSettingsSection(
    equalizerState: EqualizerState?,
    onOpenEqualizer: () -> Unit
) {
    val isEnabled = equalizerState?.isEnabled ?: true
    val presetName = equalizerState?.preset?.displayName ?: stringResource(R.string.settings_equalizer_preset_flat)
    val bassBoost = equalizerState?.bassBoostStrength ?: 0
    val status = if (isEnabled) {
        stringResource(R.string.settings_equalizer_bands_active)
    } else {
        stringResource(R.string.settings_equalizer_bypassed)
    }
    val bassBoostLabel = if (bassBoost > 0 && isEnabled) {
        stringResource(R.string.settings_equalizer_bass_boost, (bassBoost / 10f).roundToInt())
    } else {
        null
    }
    SettingsGroup(
        header = stringResource(R.string.settings_equalizer_section_title),
        footer = stringResource(R.string.settings_equalizer_section_subtitle)
    ) {
        SettingsRow(
            title = stringResource(R.string.settings_equalizer_profile, presetName),
            subtitle = listOfNotNull(status, bassBoostLabel).joinToString(" · "),
            icon = Icons.Default.Equalizer,
            tile = SettingsTile.Red,
            showChevron = true,
            onClick = onOpenEqualizer
        )
    }
}

@Composable
private fun PlaybackSection(
    isAutoplayEnabled: Boolean,
    onAutoplayChange: (Boolean) -> Unit
) {
    SettingsGroup(
        header = stringResource(R.string.settings_playback_section_title),
        footer = stringResource(R.string.settings_playback_section_subtitle)
    ) {
        SettingsToggleRow(
            title = stringResource(R.string.settings_autoplay_title),
            subtitle = stringResource(R.string.settings_autoplay_subtitle),
            checked = isAutoplayEnabled,
            onCheckedChange = onAutoplayChange,
            icon = Icons.Default.Radio,
            tile = SettingsTile.Green
        )
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
    onLrclibChange: (Boolean) -> Unit,
    sources: Set<LyricsSource> = LyricsSource.offered.toSet(),
    onOpenSources: () -> Unit = {},
    onClearTranslationModels: () -> Unit = {}
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
    val highlightLabels = mapOf(
        HighlightMode.AUTO to stringResource(R.string.settings_highlight_auto),
        HighlightMode.WORD to stringResource(R.string.settings_highlight_word),
        HighlightMode.LINE to stringResource(R.string.settings_highlight_line)
    )
    SettingsGroups {
        SettingsGroup(
            header = stringResource(R.string.settings_translation_section_title),
            footer = stringResource(R.string.settings_translation_section_subtitle)
        ) {
            SettingsToggleRow(
                title = stringResource(R.string.settings_translate_lyrics_title),
                subtitle = stringResource(R.string.settings_translate_lyrics_subtitle),
                checked = translateLyrics,
                onCheckedChange = onTranslateLyricsChange,
                icon = Icons.Default.Translate,
                tile = SettingsTile.Blue
            )
            SettingsRowDivider(hasLeading = true)
            SettingsRow(
                title = stringResource(R.string.settings_translation_target_language_label),
                value = translationLanguageLabel(targetLang),
                icon = Icons.Default.Language,
                tile = SettingsTile.Teal,
                showChevron = true,
                onClick = { languagePickerOpen = true }
            )
            SettingsRowDivider(hasLeading = true)
            SettingsRow(
                title = stringResource(R.string.settings_translation_clear_models),
                subtitle = stringResource(R.string.settings_translation_clear_models_subtitle),
                icon = Icons.Default.DeleteOutline,
                tile = SettingsTile.Graphite,
                onClick = onClearTranslationModels
            )
        }
        Column {
            SettingsFootnote(
                text = stringResource(R.string.settings_translation_highlight_label),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp)
            )
            SegmentedPills(
                items = listOf(HighlightMode.AUTO, HighlightMode.WORD, HighlightMode.LINE),
                selected = highlightMode,
                onSelect = onHighlightModeChange,
                label = { highlightLabels.getValue(it) },
                modifier = Modifier.fillMaxWidth(),
                slidingIndicator = true
            )
        }
        SettingsGroup(header = stringResource(R.string.settings_translation_sources_label)) {
            SettingsRow(
                title = stringResource(R.string.settings_lyrics_sources_title),
                subtitle = stringResource(R.string.settings_lyrics_sources_subtitle),
                value = sources.size.toString(),
                icon = Icons.Default.Lyrics,
                tile = SettingsTile.Orange,
                showChevron = true,
                onClick = onOpenSources
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

    Column {
        SettingsGroup(
            header = stringResource(R.string.settings_cache_section_title),
            footer = stringResource(R.string.settings_cache_section_subtitle)
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_cache_size_title),
                subtitle = stringResource(R.string.settings_cache_limit_subtitle),
                value = formattedCacheSize,
                icon = Icons.Default.Storage,
                tile = SettingsTile.Graphite
            )
            SettingsRowDivider(hasLeading = true)
            SettingsRow(
                title = stringResource(R.string.settings_cache_clear_button),
                titleColor = SettingsDestructive,
                icon = Icons.Default.Delete,
                tile = SettingsTile.Red,
                onClick = onClearCache
            )
        }
        AnimatedVisibility(visible = cacheCleared) {
            SettingsFootnote(
                text = stringResource(R.string.settings_cache_cleared_message),
                color = SonoraTheme.colors.primaryAccent,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp)
            )
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

    SettingsGroups {
        SettingsGroup(
            header = stringResource(R.string.settings_api_section_title),
            footer = stringResource(R.string.settings_api_providers_registered, totalCount)
        ) {
            ApiStatusRow(stringResource(R.string.settings_api_status_online, onlineCount), SonoraStatusOnline)
            SettingsRowDivider()
            ApiStatusRow(stringResource(R.string.settings_api_status_degraded, degradedCount), SonoraStatusDegraded)
            SettingsRowDivider()
            ApiStatusRow(stringResource(R.string.settings_api_status_offline, offlineCount), SonoraStatusOffline)
        }
        SettingsGroup {
            SettingsRow(
                title = if (isRefreshing) {
                    stringResource(R.string.settings_api_checking)
                } else {
                    stringResource(R.string.settings_api_refresh_button)
                },
                titleColor = if (isRefreshing) SonoraTextSecondary else SonoraTheme.colors.primaryAccent,
                icon = Icons.Default.Refresh,
                tile = SettingsTile.Green,
                enabled = !isRefreshing,
                onClick = onRefresh,
                trailing = if (isRefreshing) {
                    {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = SonoraTextSecondary,
                            modifier = Modifier.size(20.dp).rotate(rotation)
                        )
                    }
                } else {
                    null
                }
            )
            SettingsRowDivider(hasLeading = true)
            SettingsRow(
                title = stringResource(R.string.settings_api_details_button),
                icon = Icons.Default.Speed,
                tile = SettingsTile.Graphite,
                showChevron = true,
                onClick = onViewDiagnostics
            )
        }
    }
}

@Composable
private fun ApiStatusRow(label: String, color: Color) {
    SettingsRow(
        title = label,
        trailing = { Box(modifier = Modifier.size(10.dp).background(color, CircleShape)) }
    )
}

private const val DEVELOPER_NAME = "Marian"
private const val DEVELOPER_HANDLE = "@Marianusus"
private const val DEVELOPER_AVATAR_URL = "https://avatars.githubusercontent.com/u/169485288?s=132"
private const val DEVELOPER_PROFILE_URL = "https://github.com/Marianusus"
private const val PROJECT_REPOSITORY_URL = "https://github.com/Marianusus/LFly"

@Composable
private fun DeveloperSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    SettingsGroup(header = stringResource(R.string.settings_developer_title)) {
        SettingsRow(
            title = DEVELOPER_NAME,
            subtitle = stringResource(R.string.settings_developer_profile_subtitle, DEVELOPER_HANDLE),
            leading = { DeveloperAvatar() },
            showChevron = true,
            onClick = { openLink(DEVELOPER_PROFILE_URL) }
        )
        SettingsRowDivider(hasLeading = true)
        SettingsRow(
            title = stringResource(R.string.settings_developer_source_title),
            subtitle = stringResource(R.string.settings_developer_source_subtitle),
            icon = Icons.Default.Code,
            tile = SettingsTile.Graphite,
            showChevron = true,
            onClick = { openLink(PROJECT_REPOSITORY_URL) }
        )
        if (version != null) {
            SettingsRowDivider(hasLeading = true)
            SettingsRow(
                title = stringResource(R.string.settings_developer_version_title),
                value = version,
                icon = Icons.Default.Info,
                tile = SettingsTile.Blue
            )
        }
    }
}

/** The initial stays underneath, so an offline phone still shows who made the app. */
@Composable
private fun DeveloperAvatar() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(SonoraTheme.colors.primaryAccent),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = DEVELOPER_NAME.take(1),
            style = MaterialTheme.typography.titleMedium.copy(color = Color.White)
        )
        AsyncImage(
            model = DEVELOPER_AVATAR_URL,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun AboutSonoraSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(SonoraTheme.colors.primaryAccent, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.settings_about_title),
            style = MaterialTheme.typography.titleLarge.copy(color = SonoraTextPrimary)
        )

        Text(
            text = stringResource(R.string.settings_about_version),
            style = MaterialTheme.typography.bodyMedium.copy(color = SonoraTextSecondary)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.settings_about_description),
            style = MaterialTheme.typography.bodySmall.copy(
                color = SonoraTextSecondary,
                lineHeight = 18.sp
            ),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_about_license),
            style = MaterialTheme.typography.labelSmall.copy(color = SonoraTextTertiary)
        )
    }
}

@Composable
private fun AccentColorSection(
    accentColor: Int,
    onAccentColorChange: (Int) -> Unit
) {
    SettingsGroup(
        header = stringResource(R.string.settings_accent_section_title),
        footer = stringResource(R.string.settings_accent_section_subtitle)
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
        ) {
            items(AccentPalette.options, key = { it.id }) { option ->
                val isSelected = option.color.toArgb() == accentColor
                val colorName = stringResource(option.nameRes)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onAccentColorChange(option.color.toArgb()) }
                        )
                        .semantics { contentDescription = colorName }
                        .border(
                            width = 2.dp,
                            color = if (isSelected) SonoraTextPrimary else Color.Transparent,
                            shape = CircleShape
                        )
                        .padding(5.dp)
                        .background(option.color, CircleShape),
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

@Composable
private fun AppearanceSection(
    appearanceMode: AppearanceMode,
    onAppearanceModeChange: (AppearanceMode) -> Unit,
    reduceMotion: Boolean,
    onReduceMotionChange: (Boolean) -> Unit,
    ambientRotationEnabled: Boolean,
    onAmbientRotationChange: (Boolean) -> Unit,
    liquidGlass: Boolean,
    onLiquidGlassChange: (Boolean) -> Unit
) {
    val glassSupported = isRenderEffectSupported()
    SettingsGroups {
        SettingsGroup(
            header = stringResource(R.string.settings_appearance_section_title),
            footer = stringResource(R.string.settings_appearance_section_subtitle)
        ) {
            AppearanceMode.entries.forEachIndexed { index, mode ->
                if (index > 0) SettingsRowDivider(hasLeading = true)
                val preview = BackgroundSchemeFactory.fallback(mode)
                SettingsChoiceRow(
                    title = stringResource(mode.titleRes),
                    subtitle = stringResource(mode.subtitleRes),
                    selected = mode == appearanceMode,
                    onClick = { onAppearanceModeChange(mode) },
                    leading = {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.verticalGradient(preview.gradient))
                                .border(0.5.dp, preview.glassBorder, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.size(10.dp).background(preview.accent, CircleShape))
                        }
                    }
                )
            }
        }
        SettingsGroup {
            SettingsToggleRow(
                title = stringResource(R.string.settings_liquid_glass_title),
                subtitle = stringResource(
                    if (glassSupported) R.string.settings_liquid_glass_subtitle
                    else R.string.settings_liquid_glass_unavailable
                ),
                checked = liquidGlass && glassSupported,
                onCheckedChange = onLiquidGlassChange,
                enabled = glassSupported,
                icon = Icons.Default.BlurOn,
                tile = SettingsTile.Blue
            )
            SettingsRowDivider(hasLeading = true)
            SettingsToggleRow(
                title = stringResource(R.string.settings_reduce_motion_title),
                subtitle = stringResource(R.string.settings_reduce_motion_subtitle),
                checked = reduceMotion,
                onCheckedChange = onReduceMotionChange,
                icon = Icons.Default.Animation,
                tile = SettingsTile.Graphite
            )
            SettingsRowDivider(hasLeading = true)
            SettingsToggleRow(
                title = stringResource(R.string.ambient_rotation_title),
                subtitle = stringResource(R.string.ambient_rotation_subtitle),
                checked = ambientRotationEnabled,
                onCheckedChange = onAmbientRotationChange,
                icon = Icons.Default.Bedtime,
                tile = SettingsTile.Purple
            )
        }
    }
}
