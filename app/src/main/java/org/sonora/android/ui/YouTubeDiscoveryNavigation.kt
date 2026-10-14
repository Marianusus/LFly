package org.sonora.android.ui

import org.sonora.android.api.YouTubeAlbum
import org.sonora.android.api.YouTubeArtist
import org.sonora.android.api.YouTubePlaylist

/**
 * Returns the detail navigation id for a discovery entity, or null when unavailable.
 * A null result means the UI must show a visible "detail not available" feedback instead
 * of navigating (never a silent tap).
 */
internal fun YouTubeArtist.discoveryDetailId(): String? = browseId ?: channelId

internal fun YouTubeAlbum.discoveryDetailId(): String? = detailId

internal fun YouTubePlaylist.discoveryDetailId(): String? = detailId
