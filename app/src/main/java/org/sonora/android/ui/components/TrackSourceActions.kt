package org.sonora.android.ui.components

import org.sonora.android.api.PlaybackSource
import org.sonora.android.api.Track

/**
 * Groups the callbacks needed by the 3-dots source switch (YouTube Music / Monochrome / Auto)
 * so they can be threaded through screens as a single parameter.
 *
 * @property onSelect pins a track to a specific source.
 * @property onClear removes a track's override so it follows the global preference again.
 * @property overrideFor returns the track's current override, or null when it follows the global default.
 */
data class TrackSourceActions(
    val onSelect: (Track, PlaybackSource) -> Unit,
    val onClear: (Track) -> Unit,
    val overrideFor: (String) -> PlaybackSource?
)
