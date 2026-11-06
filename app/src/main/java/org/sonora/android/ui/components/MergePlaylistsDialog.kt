package org.sonora.android.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.sonora.android.R
import org.sonora.android.account.SpotifyLibrarySync
import org.sonora.android.account.YouTubeLibrarySync
import org.sonora.android.data.MusicRepository
import org.sonora.android.data.Playlist
import org.sonora.android.ui.theme.LocalBackgroundScheme
import org.sonora.android.ui.theme.SonoraOpaqueSurfaceCard

/** True for a playlist an account import keeps in step with Spotify or YouTube Music: the next import rewrites it. */
private fun isSynced(playlist: Playlist): Boolean =
    playlist.id.startsWith(SpotifyLibrarySync.PLAYLIST_PREFIX) || playlist.id.startsWith(YouTubeLibrarySync.PLAYLIST_PREFIX)

/**
 * Merges other playlists into [target]: their songs are added after its own, each song once. An
 * imported playlist would be rewritten by its next import, so merging into one makes a new playlist
 * of the same name instead. The merged ones can be deleted.
 */
@Composable
fun MergePlaylistsDialog(target: Playlist, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = LocalBackgroundScheme.current
    val repository = remember { MusicRepository.getInstance(context) }
    val all by repository.playlists.collectAsState()
    val others = remember(all, target.id) { all.filter { it.id != target.id } }
    val chosen = remember { mutableStateListOf<String>() }
    var deleteMerged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = SonoraOpaqueSurfaceCard,
        title = {
            Text(
                stringResource(R.string.playlist_merge_title, target.name),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = scheme.onBackground
            )
        },
        text = {
            Column {
                if (others.isEmpty()) {
                    Text(stringResource(R.string.playlist_merge_none), color = scheme.onBackgroundSecondary)
                    return@Column
                }
                Text(
                    stringResource(if (isSynced(target)) R.string.playlist_merge_body_new else R.string.playlist_merge_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onBackgroundSecondary
                )
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp).padding(top = 8.dp)) {
                    items(others, key = { it.id }) { playlist ->
                        val selected = playlist.id in chosen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { if (selected) chosen.remove(playlist.id) else chosen.add(playlist.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = playlist.coverUrl ?: playlist.tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(playlist.name, color = scheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    pluralStringResource(R.plurals.collection_track_count, playlist.tracks.size, playlist.tracks.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onBackgroundSecondary
                                )
                            }
                            Checkbox(checked = selected, onCheckedChange = null)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { deleteMerged = !deleteMerged }.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.playlist_merge_delete),
                        color = scheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = deleteMerged, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.isNotEmpty(),
                onClick = {
                    val sources = others.filter { it.id in chosen }
                    val tracks = (target.tracks + sources.flatMap { it.tracks }).distinctBy { it.id }
                    val added = tracks.size - target.tracks.size
                    if (isSynced(target)) {
                        repository.createPlaylistWithTracks(
                            name = target.name,
                            tracks = tracks,
                            description = target.description,
                            coverUrl = target.coverUrl
                        )
                    } else {
                        repository.savePlaylist(target.copy(tracks = tracks, updatedAt = System.currentTimeMillis()))
                    }
                    if (deleteMerged) sources.forEach { repository.deletePlaylist(it.id) }
                    Toast.makeText(
                        context,
                        context.resources.getQuantityString(R.plurals.playlist_merge_done, added, added),
                        Toast.LENGTH_SHORT
                    ).show()
                    onDismiss()
                }
            ) { Text(stringResource(R.string.playlist_merge_action), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_cancel_action), color = scheme.onBackgroundSecondary) }
        }
    )
}
