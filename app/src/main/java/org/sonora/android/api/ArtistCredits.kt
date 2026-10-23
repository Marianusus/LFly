package org.sonora.android.api

import com.google.gson.JsonObject
import java.text.Normalizer
import java.util.Locale

/** How an [ArtistCredit] participates in a track. */
enum class ArtistRole {
    PRIMARY,
    FEATURED,
    PRODUCER
}

/**
 * One artist credited on a track.
 *
 * [browseId] is YouTube Music's artist browse id (usually a `UC…` channel id);
 * [channelId] is the canonical channel id when the payload exposes it. Both are
 * nullable because Deezer/Spotify/import rows only carry names.
 */
data class ArtistCredit(
    val name: String,
    val browseId: String? = null,
    val channelId: String? = null,
    val role: ArtistRole = ArtistRole.PRIMARY
) {
    val displayName: String get() = name.trim()

    val normalized: String get() = ArtistCredits.normalize(name)

    /** Stable id usable to open the artist page, preferring the browse id. */
    val artistId: String? get() = browseId?.takeIf { it.isNotBlank() }
        ?: channelId?.takeIf { it.isNotBlank() }
}

/**
 * Pure parsing/merging helpers for artist credits. No network, no Android APIs:
 * every function is unit-testable and shared by the YouTube, Deezer, Spotify and
 * chart clients.
 */
object ArtistCredits {

    private const val ARTIST_PAGE_TYPE = "MUSIC_PAGE_TYPE_ARTIST"

    private val CHANNEL_REGEX = Regex("/channel/([A-Za-z0-9_-]+)")
    private val DURATION_REGEX = Regex("^(\\d{1,2}:)?\\d{1,2}:\\d{2}$")
    private val STREAM_COUNT_REGEX = Regex("([\\d][\\d.,]*)\\s*([KMB])?\\s*(plays|views)", RegexOption.IGNORE_CASE)
    private val KIND_WORDS = setOf(
        "song", "songs", "video", "videos", "album", "albums", "single", "singles",
        "ep", "eps", "playlist", "playlists", "artist", "artists"
    )
    private val FEATURE_WORDS = setOf(
        "feat", "featuring", "ft", "with", "prod", "produced", "producer"
    )

    /**
     * Split markers accepted in free-form artist text.
     *
     * A comma is intentionally **not** a marker: "Tyler, The Creator",
     * "Earth, Wind & Fire" and "R&B" must survive as one artist. `&`, `x`, `/`
     * only split when surrounded by spaces, so "R&B" and "AC/DC" stay intact.
     */
    private val TEXT_MARKER = Regex(
        "(?i)(" +
            "\\s+feat\\.?(?=\\s|$)" +
            "|\\s+ft\\.?(?=\\s|$)" +
            "|\\s+featuring(?=\\s|$)" +

            "|\\s+prod\\.?(?=\\s|$)" +
            "|\\s+&(?:\\s+|$)" +
            "|\\s*[×;]\\s*" +
            "|\\s+/\\s+" +
            "|\\s+[xX](?=\\s)" +
            ")"
    )

    /**
     * Extracts artist credits from an InnerTube text object (`title`, `subtitle`,
     * byline, …). Only runs pointing at an artist page (`pageType` ARTIST, a `UC…`
     * browse id or a `/channel/…` canonical url) become credits; durations, kind
     * labels, play counts and separators are ignored. Input order is preserved.
     */
    fun fromRuns(textObj: JsonObject?): List<ArtistCredit> {
        val runs = textObj?.get("runs")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return emptyList()
        val out = LinkedHashMap<String, ArtistCredit>()
        for (element in runs) {
            if (!element.isJsonObject) continue
            val run = element.asJsonObject
            val text = cleanName(run.stringValue("text")?.trim().orEmpty())
            if (text.isBlank() || isNoise(text)) continue

            val browse = run.get("navigationEndpoint")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("browseEndpoint")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: continue

            val browseId = browse.stringValue("browseId")
            val pageType = browse.get("browseEndpointContextSupportedConfigs")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("browseEndpointContextMusicConfig")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.stringValue("pageType")
            val canonical = browse.stringValue("canonicalBaseUrl")
                ?: browse.stringValue("canonicalUrl")
            val channelFromCanonical = canonical?.let {
                CHANNEL_REGEX.find(it)?.groupValues?.getOrNull(1)
            }
            val channelFromBrowse = browseId?.takeIf { it.startsWith("UC") }
            val isArtist = pageType == ARTIST_PAGE_TYPE ||
                channelFromBrowse != null ||
                channelFromCanonical != null
            if (!isArtist) continue

            val credit = ArtistCredit(
                name = text,
                browseId = browseId,
                channelId = channelFromCanonical ?: channelFromBrowse
            )
            val key = credit.normalized.ifBlank { credit.artistId ?: text }
            val previous = out[key]
            out[key] = if (previous == null) {
                credit
            } else {
                previous.copy(
                    browseId = previous.browseId ?: credit.browseId,
                    channelId = previous.channelId ?: credit.channelId
                )
            }
        }
        return out.values.toList()
    }

    /**
     * Splits free-form artist text ("A feat. B & C", "A x B", "A; B") into
     * credits. Commas never split, and `&` does not split a comma-containing band
     * name such as "Earth, Wind & Fire".
     */
    fun fromText(raw: String?): List<ArtistCredit> {
        val original = raw?.trim().orEmpty()
        if (original.isBlank()) return emptyList()

        // Parentheses/brackets around feature markers are presentation only.
        val text = original
            .replace('(', ' ')
            .replace(')', ' ')
            .replace('[', ' ')
            .replace(']', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val out = LinkedHashMap<String, ArtistCredit>()
        var role = ArtistRole.PRIMARY
        var lastIndex = 0
        val current = StringBuilder()

        fun emit(value: CharSequence, creditRole: ArtistRole) {
            val name = cleanName(value.toString().trim().trim(',', ';', '/', '&', '×', ' '))
            if (name.isBlank()) return
            if (name.lowercase(Locale.ROOT) in FEATURE_WORDS) return
            if (isNoise(name)) return
            val credit = ArtistCredit(name = name, role = creditRole)
            val key = credit.normalized.ifBlank { name.lowercase(Locale.ROOT) }
            out.putIfAbsent(key, credit)
        }

        for (match in TEXT_MARKER.findAll(text)) {
            val before = text.substring(lastIndex, match.range.first)
            current.append(before)
            val marker = match.value
            val markerKind = markerKind(marker)

            // Conservative ampersand: keep band/duo names together ("Earth, Wind & Fire",
            // "Simon & Garfunkel", "Drake & Future") unless both sides are clearly full
            // names, or the right-hand side follows a featured credit ("A feat. B & C").
            if (markerKind == MarkerKind.AMPERSAND) {
                val beforeText = before.trim()
                val afterStart = match.range.last + 1
                val nextMarker = TEXT_MARKER.find(text, afterStart)
                val afterText = text.substring(afterStart, nextMarker?.range?.first ?: text.length).trim()
                val singleWordPair = role == ArtistRole.PRIMARY &&
                    beforeText.isNotBlank() && !beforeText.contains(' ') &&
                    afterText.isNotBlank() && !afterText.contains(' ')
                val commaBand = role == ArtistRole.PRIMARY &&
                    before.contains(',') && afterText.isNotBlank() && !afterText.contains(' ')
                if (afterText.isBlank() || singleWordPair || commaBand) {
                    current.append(marker)
                    lastIndex = afterStart
                    continue
                }
            }

            emit(current, role)
            current.setLength(0)
            role = when (markerKind) {
                MarkerKind.FEATURED, MarkerKind.WITH -> ArtistRole.FEATURED
                MarkerKind.PRODUCER -> ArtistRole.PRODUCER
                MarkerKind.AMPERSAND, MarkerKind.X, MarkerKind.SEMICOLON, MarkerKind.SLASH -> role
                MarkerKind.UNKNOWN -> role
            }
            lastIndex = match.range.last + 1
        }
        current.append(text.substring(lastIndex))
        emit(current, role)
        return out.values.toList()
    }

    /** First credit, preferring an explicit PRIMARY when roles are mixed. */
    fun primary(credits: List<ArtistCredit>?): ArtistCredit? {
        val safe = credits.orEmpty().filter { it.displayName.isNotBlank() }
        return safe.firstOrNull { it.role == ArtistRole.PRIMARY } ?: safe.firstOrNull()
    }

    fun primaryName(credits: List<ArtistCredit>?): String = primary(credits)?.displayName.orEmpty()

    /** Human-readable join of every credit, preserving order. */
    fun displayName(credits: List<ArtistCredit>?): String =
        credits.orEmpty()
            .mapNotNull { credit -> credit.displayName.takeIf { it.isNotBlank() } }
            .joinToString(", ")

    /** First credit that actually carries a navigable id. */
    fun firstArtistId(credits: List<ArtistCredit>?): String? =
        credits.orEmpty().firstNotNullOfOrNull { it.artistId }

    /**
     * Merges credit lists without losing the legacy `artist`/`artistId` fields.
     * Existing order wins; incoming credits only fill ids/roles. When the list is
     * empty the legacy name becomes a single PRIMARY credit.
     */
    fun merge(
        existing: List<ArtistCredit>?,
        incoming: List<ArtistCredit>?,
        legacyName: String? = null,
        legacyArtistId: String? = null
    ): List<ArtistCredit>? {
        if (existing.isNullOrEmpty() && incoming.isNullOrEmpty() && legacyName.isNullOrBlank()) {
            return null
        }
        val out = LinkedHashMap<String, ArtistCredit>()

        fun put(credit: ArtistCredit) {
            if (credit.displayName.isBlank()) return
            val key = credit.normalized.ifBlank { credit.artistId ?: credit.displayName }
            val previous = out[key]
            out[key] = if (previous == null) {
                credit
            } else {
                previous.copy(
                    browseId = previous.browseId ?: credit.browseId,
                    channelId = previous.channelId ?: credit.channelId,
                    role = if (previous.role == ArtistRole.PRIMARY) previous.role else credit.role
                )
            }
        }

        existing.orEmpty().forEach(::put)
        incoming.orEmpty().forEach(::put)

        val name = legacyName?.let(::cleanName)
        if (out.isEmpty() && !name.isNullOrBlank()) {
            // No structured list at all: legacy text is the only source. Split it with the
            // same conservative parser, then attach the legacy id to the first credit.
            val legacyCredits = fromText(name).ifEmpty { listOf(ArtistCredit(name)) }
            legacyCredits.forEach { credit ->
                val key = credit.normalized.ifBlank { credit.displayName }
                out[key] = credit
            }
        }

        // Structured existing/incoming credits are authoritative. The legacy display string
        // must never be prepended when a list already exists, or "A, B" would become
        // "A, B" + "A" + "B". It only fills a missing id below.
        if (!legacyArtistId.isNullOrBlank()) {
            val first = out.values.firstOrNull()
            if (first != null && first.artistId == null) {
                val key = first.normalized.ifBlank { first.displayName }
                out[key] = first.copy(
                    browseId = legacyArtistId.takeIf { it.startsWith("UC") },
                    channelId = legacyArtistId
                )
            }
        }
        return out.values.toList().takeIf { it.isNotEmpty() }
    }

    /** Lowercase, accent-free, punctuation-free key used for dedup. */
    fun normalize(value: String?): String {
        val decomposed = Normalizer.normalize(value.orEmpty().trim(), Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private enum class MarkerKind { FEATURED, WITH, PRODUCER, AMPERSAND, X, SEMICOLON, SLASH, UNKNOWN }

    private fun markerKind(marker: String): MarkerKind {
        val value = marker.trim().lowercase(Locale.ROOT)
        return when {
            value.startsWith("prod") -> MarkerKind.PRODUCER
            value == "with" -> MarkerKind.WITH
            value.startsWith("feat") || value.startsWith("ft") || value == "featuring" -> MarkerKind.FEATURED
            value == "&" || value == "and" -> MarkerKind.AMPERSAND
            value == "x" -> MarkerKind.X
            value == ";" -> MarkerKind.SEMICOLON
            value == "/" -> MarkerKind.SLASH
            else -> MarkerKind.UNKNOWN
        }
    }

    /** Drops YouTube's auto-generated "Artist - Topic" channel suffix. */
    private fun cleanName(value: String): String =
        value.replace(Regex("(?i)^(.+?)\\s+-\\s+topic$"), "$1").trim()

    private fun isNoise(value: String): Boolean {
        val text = value.trim()
        if (text.isBlank() || text == "•" || text == "·" || text == "," || text == "&") return true
        if (DURATION_REGEX.matches(text)) return true
        if (STREAM_COUNT_REGEX.containsMatchIn(text)) return true
        return KIND_WORDS.contains(text.lowercase(Locale.ROOT))
    }

    private fun JsonObject.stringValue(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asString }.getOrNull()
    }
}
