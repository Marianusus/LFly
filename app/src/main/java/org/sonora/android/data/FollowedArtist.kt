package org.sonora.android.data

/**
 * A locally followed YouTube Music artist. This is a client-side-only concept:
 * no follow action is ever sent to YouTube.
 *
 * [artistKey] is the stable local key (`yt_browse_<browseId>`, `yt_channel_<channelId>`,
 * or `yt_name_<normalized>`), while [browseId]/[channelId] are kept separately as aliases
 * so the same artist arriving first by name and later by id never duplicates.
 */
data class FollowedArtist(
    val artistKey: String,
    val browseId: String? = null,
    val channelId: String? = null,
    val name: String,
    val coverUrl: String? = null,
    val provider: String = "YouTube",
    val followedAtEpochMs: Long = 0L
)
