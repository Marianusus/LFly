package org.sonora.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.sonora.android.R
import org.sonora.android.playback.DownloadProgress
import org.sonora.android.ui.components.DownloadIconButton
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.Spacing
import org.sonora.android.ui.theme.onColorFor

/**
 * A playlist's header laid out like Spotify's: the artwork, then the title, description, owner and
 * facts aligned to the start, then one row of actions with the library actions on the left
 * (download, Mix) and shuffle with the big round Play on the right. [status] sits under the
 * actions: the analysis of a mixed playlist, or a generated mix's state.
 */
@Composable
fun PlaylistHero(
    title: String,
    description: String?,
    owner: String?,
    metadata: String?,
    descriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    hasTracks: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    downloadProgress: DownloadProgress,
    onToggleDownload: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    artwork: @Composable BoxScope.() -> Unit,
) {
    val scheme = LocalBackgroundScheme.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val artworkSize = if (maxWidth >= 600.dp) 260.dp else 240.dp
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(artworkSize)
                        .shadow(elevation = 24.dp, shape = RoundedCornerShape(4.dp), ambientColor = Color.Black, spotColor = Color.Black)
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.glassTint),
                    content = artwork,
                )
            }
            Spacer(Modifier.height(Spacing.xl))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = scheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onBackgroundSecondary,
                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onToggleDescription),
                )
            }
            if (!owner.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(24.dp).clip(CircleShape).background(scheme.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = onColorFor(scheme.accent), modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        owner,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onBackground,
                    )
                }
            }
            if (!metadata.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(metadata, style = MaterialTheme.typography.bodySmall, color = scheme.onBackgroundSecondary, maxLines = 2)
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onToggleDownload != null) {
                    DownloadIconButton(
                        progress = downloadProgress,
                        onClick = { if (hasTracks) onToggleDownload() },
                        buttonSize = 48.dp,
                        iconSize = 26.dp,
                        tint = scheme.onBackgroundSecondary,
                        accent = scheme.accent,
                    )
                }
                actions?.invoke(this)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onShuffle, enabled = hasTracks, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = stringResource(R.string.collection_shuffle_action),
                        tint = if (hasTracks) scheme.onBackgroundSecondary else scheme.onBackgroundTertiary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (hasTracks) scheme.accent else scheme.glassTint)
                        .clickable(enabled = hasTracks, role = Role.Button, onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.collection_play_action),
                        tint = onColorFor(scheme.accent),
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            status?.let {
                Spacer(Modifier.height(Spacing.sm))
                it()
            }
        }
    }
}

/** The facts under the last song: "6 songs · 24 min". */
@Composable
fun PlaylistFooter(text: String, modifier: Modifier = Modifier) {
    val scheme = LocalBackgroundScheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onBackgroundSecondary,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
    )
}
