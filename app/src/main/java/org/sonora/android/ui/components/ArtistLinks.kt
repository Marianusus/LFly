package org.sonora.android.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import org.sonora.android.api.ArtistCredit
import org.sonora.android.api.ArtistCredits
import org.sonora.android.api.Track

/** Opens one credited artist. Provided once at the app root so every row shares it. */
val LocalArtistClick = staticCompositionLocalOf<((ArtistCredit) -> Unit)?> { null }

fun Track.artistCredits(): List<ArtistCredit> =
    artists?.filter { it.displayName.isNotBlank() }
        ?.takeIf { it.isNotEmpty() }
        ?: ArtistCredits.fromText(artist)

/**
 * Artist credits as one inline line, each name a separate link. Commas are only
 * presentation: the parser already decided where one artist ends and the next begins.
 */
@Composable
fun ArtistLinks(
    track: Track,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    linksEnabled: Boolean = false,
    onArtistClick: ((ArtistCredit) -> Unit)? = if (linksEnabled) LocalArtistClick.current else null,
) {
    val credits = remember(track.id, track.artist, track.artists) { track.artistCredits() }
    if (!linksEnabled || credits.isEmpty() || onArtistClick == null) {
        Text(
            text = track.artist,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    val annotated = remember(credits, color, onArtistClick) {
        val linkStyle = SpanStyle(color = color)
        buildAnnotatedString {
            credits.forEachIndexed { index, credit ->
                if (index > 0) append(", ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "artist:${credit.normalized}:$index",
                        styles = TextLinkStyles(style = linkStyle),
                        linkInteractionListener = { onArtistClick(credit) },
                    )
                ) {
                    append(credit.displayName)
                }
            }
        }
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
