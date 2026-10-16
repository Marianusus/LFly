package org.sonora.android.party

import kotlin.random.Random

/**
 * Short, human-friendly session codes used to pair nearby devices for a Party.
 *
 * The alphabet omits look-alike characters (0/O, 1/I/L) so a code can be read
 * aloud or typed without ambiguity.
 */
object PartyCode {

    const val LENGTH = 6
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Endpoint-name prefix advertised through Nearby Connections. */
    const val ENDPOINT_PREFIX = "LFly-Party-"

    private const val URI_SCHEME = "lfly"
    private const val URI_HOST = "party"

    fun generate(random: Random = Random.Default): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** Uppercases and strips anything that is not a valid code character. */
    fun normalize(raw: String?): String? {
        val cleaned = raw?.trim()?.uppercase()?.filter { it in ALPHABET } ?: return null
        return cleaned.takeIf { it.length == LENGTH }
    }

    fun isValid(raw: String?): Boolean = normalize(raw) != null

    /** Name advertised to nearby devices, e.g. `LFly-Party-K7M2QX`. */
    fun endpointName(code: String): String = ENDPOINT_PREFIX + code

    /** Extracts the session code from an advertised endpoint name, or null. */
    fun codeFromEndpointName(endpointName: String?): String? {
        val name = endpointName ?: return null
        if (!name.startsWith(ENDPOINT_PREFIX)) return null
        return normalize(name.removePrefix(ENDPOINT_PREFIX))
    }

    /** Deep link shown in the QR code, e.g. `lfly://party?code=K7M2QX&name=Nick`. */
    fun toUri(code: String, hostNick: String? = null): String {
        val nick = hostNick
            ?.takeIf { it.isNotBlank() }
            ?.let { "&name=" + java.net.URLEncoder.encode(it, "UTF-8") }
            ?: ""
        return "$URI_SCHEME://$URI_HOST?code=$code$nick"
    }

    /** Parses the QR deep link; returns the code plus the optional host nickname. */
    fun parseUri(raw: String?): Pair<String, String?>? {
        val value = raw?.trim() ?: return null
        if (!value.startsWith("$URI_SCHEME://", ignoreCase = true)) return null
        val code = Regex("[?&]code=([^&]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
            ?.let { normalize(it) }
            ?: return null
        val name = Regex("[?&]name=([^&]+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        return code to name
    }
}
