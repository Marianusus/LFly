package org.sonora.android.ui.collection

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.sonora.android.R

/** Joins only the metadata parts a collection actually has, using the shared middle dot. */
fun joinCollectionMeta(vararg parts: String?): String? =
    parts.mapNotNull { it?.takeIf(String::isNotBlank) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

/** A long description gets a Show more control; short copy stays compact. */
fun isCollectionDescriptionExpandable(description: String?): Boolean {
    val text = description?.trim().orEmpty()
    return text.length > 140 || text.count { it == '\n' } >= 2
}

/**
 * Localized collection duration. Keeps the algorithm pure enough to test while the
 * actual words come from resources (Italian, English, Romanian).
 */
fun collectionDurationParts(totalMs: Long): Triple<Long, Long, Long> {
    val totalSeconds = (totalMs.coerceAtLeast(0L)) / 1000
    return Triple(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
}

@Composable
fun formatCollectionDuration(totalMs: Long): String {
    val (hours, minutes, seconds) = collectionDurationParts(totalMs)
    return when {
        hours > 0 -> stringResource(R.string.collection_duration_hours, hours, minutes)
        minutes > 0 -> stringResource(R.string.collection_duration_minutes, minutes)
        seconds > 0 -> stringResource(R.string.collection_duration_seconds, seconds)
        else -> stringResource(R.string.collection_duration_minutes, 0)
    }
}

@Composable
fun formatCollectionUpdated(updatedAt: Long?): String? {
    if (updatedAt == null || updatedAt <= 0L) return null
    val relative = DateUtils.getRelativeTimeSpanString(
        updatedAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    )
    return stringResource(R.string.collection_updated, relative.toString())
}
