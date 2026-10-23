package org.sonora.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.sonora.android.R
import org.sonora.android.api.lyrics.sources.LyricsProviderState
import org.sonora.android.api.lyrics.sources.LyricsSource
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsProviderSheet(
    states: Map<LyricsSource, LyricsProviderState>,
    active: LyricsSource?,
    onSelect: (LyricsSource?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SonoraOpaqueSurfaceCard,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = stringResource(R.string.lyrics_sources_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = SonoraTextPrimary,
                )
            )
            Text(
                text = stringResource(R.string.lyrics_sources_subtitle),
                style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary),
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.height(14.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    ProviderRow(
                        title = stringResource(R.string.lyrics_source_automatic),
                        detail = stringResource(R.string.lyrics_source_automatic_detail),
                        state = LyricsProviderState.NOT_FETCHED,
                        selected = active == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(LyricsSource.offered, key = { it.name }) { source ->
                    ProviderRow(
                        title = source.label,
                        detail = source.detail,
                        state = states[source] ?: LyricsProviderState.NOT_FETCHED,
                        selected = active == source,
                        onClick = { onSelect(source) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    title: String,
    detail: String,
    state: LyricsProviderState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val stateLabel = when (state) {
        LyricsProviderState.FETCHING -> stringResource(R.string.lyrics_source_fetching)
        LyricsProviderState.FOUND -> stringResource(R.string.lyrics_source_found)
        LyricsProviderState.NOT_FOUND -> stringResource(R.string.lyrics_source_not_found)
        LyricsProviderState.REQUIRES_KEY -> stringResource(R.string.lyrics_source_requires_key)
        LyricsProviderState.SKIPPED -> stringResource(R.string.lyrics_source_skipped)
        LyricsProviderState.NOT_FETCHED -> ""
    }
    val stateColor = when (state) {
        LyricsProviderState.FOUND -> SonoraEmerald
        LyricsProviderState.REQUIRES_KEY -> Color(0xFFFFC857)
        LyricsProviderState.NOT_FOUND -> SonoraTextTertiary
        LyricsProviderState.FETCHING -> SonoraTextSecondary
        else -> SonoraTextTertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) SonoraEmerald.copy(alpha = 0.12f) else SonoraSurfaceHighlight,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = SonoraTextPrimary,
                )
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary),
                maxLines = 2,
            )
            if (stateLabel.isNotBlank()) {
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelSmall.copy(color = stateColor),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        when {
            selected -> Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = SonoraEmerald,
                modifier = Modifier.size(20.dp),
            )
            state == LyricsProviderState.REQUIRES_KEY -> Icon(
                Icons.Default.Key,
                contentDescription = null,
                tint = stateColor,
                modifier = Modifier.size(18.dp),
            )
            state == LyricsProviderState.FETCHING -> Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = stateColor,
                modifier = Modifier.size(18.dp),
            )
            else -> Spacer(modifier = Modifier.size(20.dp))
        }
    }
}
