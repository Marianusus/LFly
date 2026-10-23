package org.sonora.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.sonora.android.ui.theme.LocalBackgroundScheme

@Composable
internal fun HomeSectionHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier
) {
    val scheme = LocalBackgroundScheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDimens.Gutter)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = (-0.3).sp
            ),
            color = scheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() }
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                color = scheme.onBackgroundSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun HomeArtwork(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(HomeDimens.CardRadius)
) {
    val scheme = LocalBackgroundScheme.current
    Box(
        modifier = modifier
            .clip(shape)
            .background(scheme.glassTint),
        contentAlignment = Alignment.Center
    ) {
        if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = scheme.onBackgroundTertiary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
internal fun HomeBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.7.sp
        ),
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.52f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
