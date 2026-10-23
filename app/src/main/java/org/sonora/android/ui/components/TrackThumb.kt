package org.sonora.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import org.sonora.android.ui.theme.LocalBackgroundScheme

/**
 * Normalizes cover values for Coil: HTTP(S), content:// and local files are valid,
 * while empty/unknown schemes are treated as absent.
 */
fun String?.asImageUrlOrNull(): String? {
    val raw = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("file://") || raw.startsWith("content://") -> raw
        raw.startsWith("/") -> "file://$raw"
        else -> null
    }
}

/**
 * Fixed-size track thumbnail with a deterministic fallback chain:
 * real track cover → album/entity cover → [MusicNote] placeholder.
 *
 * Never leaves an empty hole: covers show an icon while loading or on Coil error.
 */
@Composable
fun TrackThumb(
    url: String?,
    fallbackUrl: String? = null,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    contentDescription: String? = null
) {
    val scheme = LocalBackgroundScheme.current
    val resolved = url.asImageUrlOrNull() ?: fallbackUrl.asImageUrlOrNull()

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(scheme.glassTint),
        contentAlignment = Alignment.Center
    ) {
        if (resolved == null) {
            PlaceholderIcon(size)
        } else {
            SubcomposeAsyncImage(
                model = resolved,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { PlaceholderIcon(size) },
                error = { PlaceholderIcon(size) }
            )
        }
    }
}

@Composable
private fun PlaceholderIcon(size: Dp) {
    Icon(
        imageVector = Icons.Default.MusicNote,
        contentDescription = null,
        tint = LocalBackgroundScheme.current.onBackgroundTertiary,
        modifier = Modifier.size(size * 0.45f)
    )
}
