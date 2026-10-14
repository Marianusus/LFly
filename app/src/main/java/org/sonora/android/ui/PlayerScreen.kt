package org.sonora.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.api.HighlightMode
import org.sonora.android.api.Lyrics
import org.sonora.android.api.Track
import org.sonora.android.playback.PlaybackState
import org.sonora.android.playback.RepeatMode
import org.sonora.android.ui.components.MarqueeText
import org.sonora.android.ui.components.SourceSwitchMenuItems
import org.sonora.android.ui.components.TrackSourceActions
import org.sonora.android.ui.theme.AmoledBlack
import org.sonora.android.ui.theme.AmoledPurple
import org.sonora.android.ui.theme.AppearanceMode
import org.sonora.android.ui.theme.AppleMusicCyan
import org.sonora.android.ui.theme.AppleMusicPink
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.MidnightNeonPrimary
import org.sonora.android.ui.theme.MidnightNeonSecondary
import org.sonora.android.ui.theme.SonoraBackground
import org.sonora.android.ui.theme.SonoraPurple
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.ThemeStyle
import org.sonora.android.ui.theme.liquidGlass

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerScreen(
    playbackState: PlaybackState,
    isFavorite: Boolean,
    isDisliked: Boolean = false,
    lyrics: Lyrics?,
    isLoadingLyrics: Boolean,
    isLyricsOpen: Boolean,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: (Track) -> Unit,
    onToggleDislike: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onPlayNextTrack: (Track) -> Unit = {},
    onShareTrack: (Track) -> Unit = {},
    sourceActions: TrackSourceActions? = null,
    onToggleLyrics: () -> Unit,
    onOpenQueue: () -> Unit = {},
    onOpenEqualizer: () -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    translateLyrics: Boolean = false,
    targetLang: String = "it",
    highlightMode: HighlightMode = HighlightMode.AUTO,
    onToggleTranslate: (Boolean) -> Unit = {},
    onTargetLangChange: (String) -> Unit = {},
    onHighlightModeChange: (HighlightMode) -> Unit = {},
    musixmatchEnabled: Boolean = true,
    onToggleMusixmatch: (Boolean) -> Unit = {},
    lrclibEnabled: Boolean = true,
    onToggleLrclib: (Boolean) -> Unit = {},
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier
) {
    val currentTrack = playbackState.currentTrack ?: return

    var isUserSeeking by remember { mutableStateOf(false) }
    var seekProgress by remember { mutableFloatStateOf(0f) }

    val trackDurationMs = playbackState.durationMs.coerceAtLeast(1L)
    val currentProgress = if (isUserSeeking) {
        seekProgress
    } else {
        (playbackState.positionMs.toFloat() / trackDurationMs.toFloat()).coerceIn(0f, 1f)
    }

    val displayPositionMs = if (isUserSeeking) {
        (seekProgress * trackDurationMs).toLong()
    } else {
        playbackState.positionMs
    }

    val scheme = LocalBackgroundScheme.current
    val strongGlow = scheme.mode != AppearanceMode.AMOLED_BLACK

    // Context glow accent for the artwork ambient glow (matches the dynamic canvas).
    val glowPrimary = scheme.accent
    val glowSecondary = scheme.accentSecondary

    val sharedSurfaceModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(org.sonora.android.ui.components.PlayerSharedKeys.SURFACE),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                resizeMode = androidx.compose.animation.SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    } else {
        Modifier
    }

    SonoraBackground(
        modifier = modifier
            .fillMaxSize()
            .then(sharedSurfaceModifier),
        coverUrl = playbackState.currentTrack?.coverUrl
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // 1. Top Bar: Collapse button, Track/Album title, Equalizer, and 3-dots Menu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Collapse button
                    IconButton(
                        onClick = onCollapse,
                        modifier = Modifier
                            .size(42.dp)
                            .liquidGlass(
                                shape = CircleShape,
                                backgroundColor = Color(0xFFFFFFFF).copy(alpha = 0.08f),
                                borderColor = SonoraTheme.colors.cardBorder
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.player_collapse_desc),
                            tint = SonoraTextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Track / Album Title in Center
                    // Playback origin context in Center (Spotify / Sonora styled)
                    val (originLabel, originTitle) = when {
                        !playbackState.sourceContext.isNullOrBlank() ->
                            stringResource(R.string.player_playing_from) to playbackState.sourceContext
                        !currentTrack.album.isNullOrBlank() &&
                                !currentTrack.album.equals(currentTrack.title, ignoreCase = true) &&
                                !currentTrack.album.equals("YouTube", ignoreCase = true) ->
                            stringResource(R.string.player_playing_from_album) to currentTrack.album
                        currentTrack.provider.equals("YouTube", ignoreCase = true) ->
                            stringResource(R.string.player_playing_from) to stringResource(R.string.player_youtube_music)
                        else ->
                            stringResource(R.string.player_playing_track) to currentTrack.artist
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = originLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            ),
                            color = SonoraTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = originTitle,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = SonoraTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        playbackState.streamProvider?.takeIf { it.isNotBlank() }?.let { api ->
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.player_api, api),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = scheme.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Actions: 3-dots (MoreVert). The equalizer lives in the menu and in the
                    // bottom "EQ" pill, so the top bar keeps a single action.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier
                                    .size(42.dp)
                                    .liquidGlass(
                                        shape = CircleShape,
                                        backgroundColor = Color(0xFFFFFFFF).copy(alpha = 0.08f),
                                        borderColor = SonoraTheme.colors.cardBorder
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.player_more_desc),
                                    tint = SonoraTextPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = SonoraOpaqueSurfaceCard,
                                border = androidx.compose.foundation.BorderStroke(1.dp, SonoraTheme.colors.cardBorder)
                            ) {
                                // Riproduci dopo
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.player_play_next), color = SonoraTextPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = scheme.accent
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onPlayNextTrack(currentTrack)
                                    }
                                )
                                // Aggiungi alla coda
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.player_add_to_queue), color = SonoraTextPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                            contentDescription = null,
                                            tint = scheme.accent
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onAddToQueue(currentTrack)
                                    }
                                )
                                // Aggiungi a playlist
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.player_add_to_playlist), color = SonoraTextPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                            contentDescription = null,
                                            tint = scheme.accent
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onAddToPlaylist(currentTrack)
                                    }
                                )
                                // Condividi
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.player_share), color = SonoraTextPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = null,
                                            tint = scheme.accent
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onShareTrack(currentTrack)
                                    }
                                )
                                // Equalizzatore & DSP
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.player_equalizer_dsp), color = SonoraTextPrimary) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Equalizer,
                                            contentDescription = null,
                                            tint = scheme.accent
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenEqualizer()
                                    }
                                )
                                // Non mi piace
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (isDisliked) stringResource(R.string.player_dislike_remove) else stringResource(R.string.player_dislike),
                                            color = if (isDisliked) Color(0xFFE57373) else SonoraTextPrimary
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isDisliked) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt,
                                            contentDescription = null,
                                            tint = if (isDisliked) Color(0xFFE57373) else SonoraTextSecondary
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onToggleDislike(currentTrack)
                                    }
                                )
                                if (sourceActions != null &&
                                    (currentTrack.id.startsWith("yt_") || currentTrack.id.startsWith("spotify_")) &&
                                    !currentTrack.provider.equals("Local", ignoreCase = true)
                                ) {
                                    SourceSwitchMenuItems(
                                        sourceOverride = sourceActions.overrideFor(currentTrack.id),
                                        onPlayViaSource = { src ->
                                            menuExpanded = false
                                            sourceActions.onSelect(currentTrack, src)
                                        },
                                        onUseGlobalSource = {
                                            menuExpanded = false
                                            sourceActions.onClear(currentTrack)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 2. Artwork Card: Liquid Glass / AMOLED styling with subtle ambient glow
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    // Subtle ambient glow layer behind artwork
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.96f)
                            .graphicsLayer {
                                scaleX = 1.06f
                                scaleY = 1.06f
                                alpha = if (strongGlow) 0.45f else 0.22f
                            }
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        glowPrimary.copy(alpha = 0.45f),
                                        glowSecondary.copy(alpha = 0.20f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(32.dp)
                            )
                    )

                    // Main Artwork Container
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(
                                elevation = if (strongGlow) 18.dp else 8.dp,
                                shape = RoundedCornerShape(24.dp),
                                spotColor = glowPrimary.copy(alpha = 0.35f)
                            )
                            .liquidGlass(
                                shape = RoundedCornerShape(24.dp),
                                backgroundColor = SonoraTheme.colors.surfaceCard,
                                borderColor = SonoraTheme.colors.cardBorder,
                                borderWidth = 1.dp
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!currentTrack.artworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = currentTrack.artworkUrl,
                                contentDescription = currentTrack.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(24.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = SonoraTextTertiary,
                                modifier = Modifier.size(96.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 3. Track Title & Artist Row with Like (Heart) and Dislike buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MarqueeText(
                            text = currentTrack.title,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            ),
                            color = SonoraTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currentTrack.artist,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = 16.sp
                            ),
                            color = SonoraTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onArtistClick(currentTrack.artist) }
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Dislike Button
                        val dislikeScale by animateFloatAsState(
                            targetValue = if (isDisliked) 1.2f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "dislikeScale"
                        )

                        IconButton(
                            onClick = { onToggleDislike(currentTrack) },
                            modifier = Modifier.size(46.dp)
                        ) {
                            Icon(
                                imageVector = if (isDisliked) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt,
                                contentDescription = if (isDisliked) stringResource(R.string.player_dislike_remove_short) else stringResource(R.string.player_dislike),
                                tint = if (isDisliked) Color(0xFFE57373) else SonoraTextSecondary,
                                modifier = Modifier
                                    .size(26.dp)
                                    .graphicsLayer {
                                        scaleX = dislikeScale
                                        scaleY = dislikeScale
                                    }
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Like (Heart) Button
                        val favScale by animateFloatAsState(
                            targetValue = if (isFavorite) 1.2f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "favScale"
                        )
                        val favColor by animateColorAsState(
                            targetValue = if (isFavorite) scheme.accent else SonoraTextSecondary,
                            animationSpec = spring(),
                            label = "favColor"
                        )

                        IconButton(
                            onClick = { onToggleFavorite(currentTrack) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(R.string.player_favorite_desc),
                                tint = favColor,
                                modifier = Modifier
                                    .size(28.dp)
                                    .graphicsLayer {
                                        scaleX = favScale
                                        scaleY = favScale
                                    }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 4. Seeker Progress Slider with current time and duration
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = currentProgress,
                        onValueChange = { newValue ->
                            isUserSeeking = true
                            seekProgress = newValue
                        },
                        onValueChangeFinished = {
                            isUserSeeking = false
                            val targetMs = (seekProgress * trackDurationMs).toLong()
                            onSeekTo(targetMs)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = scheme.accent,
                            activeTrackColor = scheme.accent,
                            inactiveTrackColor = SonoraSurfaceHighlight
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(displayPositionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = SonoraTextSecondary
                        )
                        Text(
                            text = formatTime(playbackState.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = SonoraTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 5. Main Playback Controls: Shuffle, Previous, Play/Pause, Next, Repeat
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle
                    IconButton(
                        onClick = onToggleShuffle,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = stringResource(R.string.player_shuffle),
                            tint = if (playbackState.isShuffle) scheme.accent else SonoraTextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Previous Track
                    IconButton(
                        onClick = onPlayPrevious,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.player_previous),
                            tint = SonoraTextPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Play / Pause prominent Button
                    Box(
                        modifier = Modifier.size(72.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (playbackState.isLoading) {
                            CircularProgressIndicator(
                                color = scheme.accent,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(48.dp)
                            )
                        } else {
                            IconButton(
                                onClick = onPlayPause,
                                modifier = Modifier
                                    .size(68.dp)
                                    .shadow(elevation = 12.dp, shape = CircleShape)
                                    .background(scheme.accent, CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playbackState.isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                                    tint = Color.Black,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }
                    }

                    // Next Track
                    IconButton(
                        onClick = onPlayNext,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.player_next),
                            tint = SonoraTextPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Repeat Mode
                    val repeatIcon = when (playbackState.repeatMode) {
                        RepeatMode.OFF -> Icons.Default.Repeat
                        RepeatMode.ALL -> Icons.Default.Repeat
                        RepeatMode.ONE -> Icons.Default.RepeatOne
                    }
                    val isRepeatActive = playbackState.repeatMode != RepeatMode.OFF

                    IconButton(
                        onClick = onToggleRepeat,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = repeatIcon,
                            contentDescription = stringResource(R.string.player_repeat),
                            tint = if (isRepeatActive) scheme.accent else SonoraTextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 6. Bottom Action Bar: Lyrics pill button, EQ pill button, Queue pill button
                val pillShape = SonoraTheme.buttonShape

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Lyrics pill button
                    Row(
                        modifier = Modifier
                            .liquidGlass(
                                shape = pillShape,
                                backgroundColor = if (isLyricsOpen) scheme.accent.copy(alpha = 0.22f)
                                else Color(0xFFFFFFFF).copy(alpha = 0.08f),
                                borderColor = if (isLyricsOpen) scheme.accent.copy(alpha = 0.40f)
                                else SonoraTheme.colors.cardBorder
                            )
                            .clickable(onClick = onToggleLyrics)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = stringResource(R.string.player_lyrics),
                            tint = if (isLyricsOpen) scheme.accent else SonoraTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.player_lyrics),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (isLyricsOpen) scheme.accent else SonoraTextPrimary
                        )
                    }

                    // EQ pill button
                    Row(
                        modifier = Modifier
                            .liquidGlass(
                                shape = pillShape,
                                backgroundColor = Color(0xFFFFFFFF).copy(alpha = 0.08f),
                                borderColor = SonoraTheme.colors.cardBorder
                            )
                            .clickable(onClick = onOpenEqualizer)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Equalizer,
                            contentDescription = stringResource(R.string.player_equalizer),
                            tint = scheme.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.player_eq),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = SonoraTextPrimary
                        )
                    }

                    // Queue pill button
                    Row(
                        modifier = Modifier
                            .liquidGlass(
                                shape = pillShape,
                                backgroundColor = Color(0xFFFFFFFF).copy(alpha = 0.08f),
                                borderColor = SonoraTheme.colors.cardBorder
                            )
                            .clickable(onClick = onOpenQueue)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = stringResource(R.string.player_queue),
                            tint = scheme.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                R.string.player_queue_count,
                                if (playbackState.userQueueSize > 0) playbackState.userQueueSize else playbackState.queue.size
                            ),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = SonoraTextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            // 7. Lyrics overlay modal when open
            AnimatedVisibility(
                visible = isLyricsOpen,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(animationSpec = tween(280)) + slideInVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ) { it / 3 },
                exit = fadeOut(animationSpec = tween(220)) + slideOutVertically(
                    animationSpec = tween(220)
                ) { it / 3 }
            ) {
                LyricsView(
                    track = currentTrack,
                    lyrics = lyrics,
                    isLoading = isLoadingLyrics,
                    playbackState = playbackState,
                    translateLyrics = translateLyrics,
                    targetLang = targetLang,
                    highlightMode = highlightMode,
                    onToggleTranslate = onToggleTranslate,
                    onTargetLangChange = onTargetLangChange,
                    onHighlightModeChange = onHighlightModeChange,
                    musixmatchEnabled = musixmatchEnabled,
                    onToggleMusixmatch = onToggleMusixmatch,
                    lrclibEnabled = lrclibEnabled,
                    onToggleLrclib = onToggleLrclib,
                    onSeekTo = onSeekTo,
                    onPlayPause = onPlayPause,
                    onPlayPrevious = onPlayPrevious,
                    onPlayNext = onPlayNext,
                    onToggleShuffle = onToggleShuffle,
                    onClose = onToggleLyrics
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
