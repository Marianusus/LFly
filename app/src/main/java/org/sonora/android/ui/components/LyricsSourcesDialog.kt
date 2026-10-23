package org.sonora.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.sonora.android.R
import org.sonora.android.api.lyrics.sources.LyricsSource
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun LyricsSourcesDialog(
    sources: Set<LyricsSource>,
    order: List<LyricsSource>,
    prioritizeSyllableSync: Boolean,
    apiKey: String,
    onDismiss: () -> Unit,
    onApply: (Set<LyricsSource>, List<LyricsSource>, Boolean, String) -> Unit,
    onReset: () -> Unit,
) {
    val rows = remember(order, sources) {
        mutableStateListOf<LyricsSource>().apply {
            addAll(LyricsSource.ordered(order).filterNot { it.hidden })
        }
    }
    val enabled = remember(sources) {
        mutableStateMapOf<LyricsSource, Boolean>().apply {
            LyricsSource.offered.forEach { put(it, it in sources) }
        }
    }
    var syllable by remember(prioritizeSyllableSync) { mutableStateOf(prioritizeSyllableSync) }
    var key by remember(apiKey) { mutableStateOf(apiKey) }
    var minimumWarning by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val moving = rows.removeAt(from.index)
        rows.add(to.index, moving)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
                .padding(vertical = 18.dp),
            shape = RoundedCornerShape(24.dp),
            color = SonoraOpaqueSurfaceCard,
            tonalElevation = 10.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.settings_lyrics_sources_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = SonoraTextPrimary,
                    )
                )
                Text(
                    text = stringResource(R.string.settings_lyrics_sources_subtitle),
                    style = MaterialTheme.typography.bodySmall.copy(color = SonoraTextSecondary),
                    modifier = Modifier.padding(top = 3.dp),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.settings_lyrics_sources_priority_hint),
                    style = MaterialTheme.typography.labelSmall.copy(color = SonoraTextTertiary),
                )
                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(rows, key = { _, source -> source.name }) { index, source ->
                        ReorderableItem(reorderState, key = source.name) { _ ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SonoraSurfaceHighlight, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = enabled[source] == true,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            enabled[source] = true
                                            minimumWarning = false
                                        } else if (rows.count { enabled[it] == true } > 1) {
                                            enabled[source] = false
                                        } else {
                                            minimumWarning = true
                                        }
                                    },
                                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                                        checkedColor = SonoraEmerald,
                                        uncheckedColor = SonoraTextTertiary,
                                    ),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = source.label,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = SonoraTextPrimary,
                                        )
                                    )
                                    Text(
                                        text = source.detail,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = SonoraTextSecondary
                                        ),
                                        maxLines = 2,
                                    )
                                    if (source.requiresKey) {
                                        Text(
                                            text = stringResource(R.string.lyrics_source_requires_key),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = Color(0xFFFFC857)
                                            ),
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = (index + 1).toString(),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = SonoraTextTertiary
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = null,
                                    tint = SonoraTextTertiary,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .longPressDraggableHandle(),
                                )
                            }
                        }
                    }
                }

                if (minimumWarning) {
                    Text(
                        text = stringResource(R.string.settings_lyrics_sources_minimum),
                        style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFFF6B6B)),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_lyrics_sources_syllable),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = SonoraTextPrimary,
                            )
                        )
                        Text(
                            text = stringResource(R.string.settings_lyrics_sources_syllable_subtitle),
                            style = MaterialTheme.typography.labelSmall.copy(color = SonoraTextSecondary),
                        )
                    }
                    Switch(
                        checked = syllable,
                        onCheckedChange = { syllable = it },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = SonoraEmerald,
                        ),
                    )
                }

                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    label = { Text(stringResource(R.string.settings_lyrics_sources_key)) },
                    supportingText = {
                        Text(stringResource(R.string.settings_lyrics_sources_key_hint))
                    },
                    singleLine = true,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            onReset()
                            onDismiss()
                        }
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_lyrics_sources_reset))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            val chosen = rows.filter { enabled[it] == true }.toSet()
                            if (chosen.isEmpty()) {
                                minimumWarning = true
                                return@Button
                            }
                            onApply(chosen, rows.toList(), syllable, key)
                            onDismiss()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = SonoraEmerald,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Text(stringResource(R.string.settings_lyrics_sources_done))
                    }
                }
            }
        }
    }
}
