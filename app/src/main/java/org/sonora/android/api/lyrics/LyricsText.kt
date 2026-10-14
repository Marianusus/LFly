package org.sonora.android.api.lyrics

/**
 * Normalizes messy track metadata before a provider lookup, so a video-style title
 * ("Blinding Lights (Official Music Video)", "The Weeknd - Topic") does not make the
 * provider match the wrong version (and therefore the wrong word timings).
 *
 * Mirrors the cleaning already used by the legacy [org.sonora.android.api.LrclibClient].
 */
internal object LyricsText {

    fun cleanTitle(title: String): String = title
        // Remove pipe and anything after (e.g. "| Official Video", "| Napalm Records")
        .replace(Regex("\\|.*$"), "")
        // Remove brackets containing video/audio tags
        .replace(Regex("\\[.*?(Official|Video|Audio|Lyric|Visualizer|Clip|HD|4K|Remaster|Music).*?\\]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\(.*?(Official|Video|Audio|Lyric|Visualizer|Clip|HD|4K|Remaster|Music|Live).*?\\)", RegexOption.IGNORE_CASE), "")
        // Remove features and with tags
        .replace(Regex("\\s*\\(feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\[feat\\..*\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\(with.*?\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*feat\\..*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*ft\\..*$", RegexOption.IGNORE_CASE), "")
        // Remove dash suffixes like " - Official Video"
        .replace(Regex("\\s*-\\s*(Official|Lyric|Visualizer|Audio|Clip|Remaster).*", RegexOption.IGNORE_CASE), "")
        // Remove enclosing quotes
        .replace(Regex("^[\"']+|[\"']+$"), "")
        .trim()

    fun cleanArtist(artist: String): String = artist
        .replace(Regex("\\s*-\\s*Topic", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*VEVO", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*Official", RegexOption.IGNORE_CASE), "")
        .split(",", "&", "feat.", "ft.", "x ")[0]
        .trim()

    /**
     * Splits a video-style "Artist - Title" title into (title, artist) when the store's artist
     * field is a channel (its name never appears in the title). Leaves proper metadata untouched.
     */
    fun splitArtistTitle(rawTitle: String, rawArtist: String): Pair<String, String> {
        val title = cleanTitle(rawTitle)
        val artist = cleanArtist(rawArtist)
        if (title.contains(" - ")) {
            val before = title.substringBefore(" - ").trim()
            val after = title.substringAfter(" - ").trim()
            val artistInTitle = artist.isNotEmpty() && title.contains(artist, ignoreCase = true)
            if (!artistInTitle && before.isNotEmpty() && after.isNotEmpty()) return after to before
        }
        return title to artist
    }
}
