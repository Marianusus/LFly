package org.sonora.android.api

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A European country with its chart storefront code and localized display name. */
data class EuropeanCountry(
    /** ISO 3166-1 alpha-2 code, lowercase (also used for the flag CDN). */
    val code: String,
    /** Apple storefront id used by the RSS chart feed (lowercase; "gb" for the UK). */
    val storefront: String,
    /** Italian display name. */
    val name: String
)

/**
 * One entry of the Home "Top tracks (EU)" carousel: the most-played song of a country,
 * mapped to a playable [Track].
 */
data class CountryTopTrack(
    val countryCode: String,
    val countryName: String,
    val track: Track
)

/** Raw chart entry parsed from the feed (kept separate so the parser is unit-testable). */
internal data class AppleChartEntry(
    val title: String,
    val artist: String,
    val artworkUrl: String?
)

/**
 * Per-country "most played" chart source for the Home "Top tracks (EU)" carousel.
 *
 * The chart is provided by Apple's public marketing RSS feed (keyless, one URL per
 * storefront). Each entry is mapped to a [Track] with `provider = "Charts"` and no stream
 * URL: playback is resolved lazily by [StreamResolver] through the usual YouTube-Music-first
 * chain (title/artist search), keeping the app's streaming architecture untouched.
 *
 * Coverage varies by storefront: unsupported micro-states simply return `null` and are skipped.
 */
object EuropeChartsClient {

    private const val TAG = "EuropeChartsClient"
    private const val BASE_URL = "https://rss.marketingtools.apple.com/api/v2"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    const val PROVIDER = "Charts"

    /** European countries shown in the carousel (unsupported storefronts are skipped at runtime). */
    val COUNTRIES: List<EuropeanCountry> = listOf(
        EuropeanCountry("al", "al", "Albania"),
        EuropeanCountry("ad", "ad", "Andorra"),
        EuropeanCountry("at", "at", "Austria"),
        EuropeanCountry("by", "by", "Bielorussia"),
        EuropeanCountry("be", "be", "Belgio"),
        EuropeanCountry("ba", "ba", "Bosnia ed Erzegovina"),
        EuropeanCountry("bg", "bg", "Bulgaria"),
        EuropeanCountry("cy", "cy", "Cipro"),
        EuropeanCountry("hr", "hr", "Croazia"),
        EuropeanCountry("cz", "cz", "Repubblica Ceca"),
        EuropeanCountry("dk", "dk", "Danimarca"),
        EuropeanCountry("ee", "ee", "Estonia"),
        EuropeanCountry("fi", "fi", "Finlandia"),
        EuropeanCountry("fr", "fr", "Francia"),
        EuropeanCountry("de", "de", "Germania"),
        EuropeanCountry("gr", "gr", "Grecia"),
        EuropeanCountry("hu", "hu", "Ungheria"),
        EuropeanCountry("is", "is", "Islanda"),
        EuropeanCountry("ie", "ie", "Irlanda"),
        EuropeanCountry("it", "it", "Italia"),
        EuropeanCountry("xk", "xk", "Kosovo"),
        EuropeanCountry("lv", "lv", "Lettonia"),
        EuropeanCountry("lt", "lt", "Lituania"),
        EuropeanCountry("lu", "lu", "Lussemburgo"),
        EuropeanCountry("mt", "mt", "Malta"),
        EuropeanCountry("md", "md", "Moldavia"),
        EuropeanCountry("me", "me", "Montenegro"),
        EuropeanCountry("nl", "nl", "Paesi Bassi"),
        EuropeanCountry("mk", "mk", "Macedonia del Nord"),
        EuropeanCountry("no", "no", "Norvegia"),
        EuropeanCountry("pl", "pl", "Polonia"),
        EuropeanCountry("pt", "pt", "Portogallo"),
        EuropeanCountry("ro", "ro", "Romania"),
        EuropeanCountry("ru", "ru", "Russia"),
        EuropeanCountry("rs", "rs", "Serbia"),
        EuropeanCountry("sk", "sk", "Slovacchia"),
        EuropeanCountry("si", "si", "Slovenia"),
        EuropeanCountry("es", "es", "Spagna"),
        EuropeanCountry("se", "se", "Svezia"),
        EuropeanCountry("ch", "ch", "Svizzera"),
        EuropeanCountry("tr", "tr", "Turchia"),
        EuropeanCountry("ua", "ua", "Ucraina"),
        EuropeanCountry("gb", "gb", "Regno Unito")
    )

    /** Builds the flag CDN URL for a country code (or the EU flag with "eu"). */
    fun flagUrl(code: String, width: Int = 160): String = "https://flagcdn.com/w$width/${code.lowercase()}.png"

    /**
     * Fetches the chart-topping song for [country]. Retries transient failures once.
     * Returns `null` when the storefront is unsupported or the feed is unavailable.
     */
    suspend fun fetchTopTrack(country: EuropeanCountry): Track? = withContext(Dispatchers.IO) {
        val entry = fetchEntries(country, limit = 1).firstOrNull() ?: return@withContext null
        buildTrack(country, entry)
    }

    /**
     * Fetches the current trending songs for [country]. Keeps chart order, so the caller
     * can render the list as a ranked rail.
     */
    suspend fun fetchTopTracks(
        country: EuropeanCountry,
        limit: Int = 20
    ): List<Track> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 50)
        fetchEntries(country, safeLimit).map { entry -> buildTrack(country, entry) }
    }

    private suspend fun fetchEntries(country: EuropeanCountry, limit: Int): List<AppleChartEntry> {
        val url = "$BASE_URL/${country.storefront}/music/most-played/$limit/songs.json"
        repeat(2) { attempt ->
            val result = HttpClientProvider.getJson(
                url = url,
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")
            )
            val body = result.getOrNull()
            if (body != null) {
                val parsed = parseAppleChartEntries(body)
                if (parsed.isNotEmpty()) return parsed
            }
            if (attempt == 0) kotlinx.coroutines.delay(350)
        }
        Log.w(TAG, "No chart entries for '${country.name}' (${country.storefront})")
        return emptyList()
    }

    /**
     * Pure parser for the Apple RSS "most-played songs" payload. Returns every valid entry.
     */
    internal fun parseAppleChartEntries(json: String): List<AppleChartEntry> {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val results = root.getAsJsonObject("feed")?.getAsJsonArray("results") ?: return emptyList()
            buildList {
                for (index in 0 until results.size()) {
                    val item = results.get(index).asJsonObject
                    val title = item.optStringOrNull("name")?.trim().orEmpty()
                    val artist = item.optStringOrNull("artistName")?.trim().orEmpty()
                    if (title.isBlank() || artist.isBlank()) continue
                    val artwork = item.optStringOrNull("artworkUrl100")
                        ?: item.optStringOrNull("artworkUrl60")
                    add(AppleChartEntry(title = title, artist = artist, artworkUrl = artwork))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse chart payload: ${e.message}")
            emptyList()
        }
    }

    /**
     * Pure parser for the Apple RSS "most-played songs" payload. Returns the first entry.
     */
    internal fun parseAppleChartEntry(json: String): AppleChartEntry? =
        parseAppleChartEntries(json).firstOrNull()

    private val ARTWORK_SIZE_REGEX = Regex("/\\d+x\\d+bb\\.")

    /** Upscales an Apple artwork URL (e.g. 100x100bb) to a crisp [size]x[size] variant. */
    internal fun highResArtwork(url: String?, size: Int = 1000): String? {
        if (url.isNullOrBlank()) return null
        return ARTWORK_SIZE_REGEX.replace(url, "/${size}x${size}bb.")
    }

    /**
     * Maps a chart entry to a playable [Track]. The id is stable per country+content so the
     * stream cache and queue survive a reload.
     */
    internal fun buildTrack(country: EuropeanCountry, entry: AppleChartEntry): Track {
        val fingerprint = (entry.title + "\u0000" + entry.artist).hashCode().toUInt().toString(16)
        return Track(
            id = "eu_${country.code}_$fingerprint",
            title = entry.title,
            artist = entry.artist,
            album = null,
            durationMs = 0L,
            coverUrl = highResArtwork(entry.artworkUrl),
            streamUrl = null,
            provider = PROVIDER,
            artists = ArtistCredits.fromText(entry.artist).takeIf { it.isNotEmpty() }
        )
    }

    private fun com.google.gson.JsonObject.optStringOrNull(name: String): String? {
        val element = this.get(name) ?: return null
        if (element.isJsonNull) return null
        return runCatching { element.asString }.getOrNull()
    }
}
