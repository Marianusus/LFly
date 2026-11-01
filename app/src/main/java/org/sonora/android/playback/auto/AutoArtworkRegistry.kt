package org.sonora.android.playback.auto

import java.security.MessageDigest

/**
 * Covers the app chose to show on Android Auto, by opaque key.
 *
 * The exported artwork provider only serves keys registered here, so another app cannot use it
 * to read arbitrary files or to make LFly fetch arbitrary URLs.
 */
internal object AutoArtworkRegistry {

    private const val MAX_ENTRIES = 2_000
    private val LOCAL_IMAGE = Regex(".*\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE)

    private val sources = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > MAX_ENTRIES
    }

    /** Registers [coverUrl] and returns its key, or null when it is not a usable image source. */
    fun register(coverUrl: String?): String? {
        val source = normalize(coverUrl) ?: return null
        val key = sha256(source)
        synchronized(sources) { sources[key] = source }
        return key
    }

    fun sourceFor(key: String): String? = synchronized(sources) { sources[key] }

    internal fun clear() = synchronized(sources) { sources.clear() }

    private fun normalize(coverUrl: String?): String? {
        val url = coverUrl?.trim().orEmpty()
        return when {
            url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("http://", ignoreCase = true) -> url
            url.startsWith("file://") -> url.removePrefix("file://").takeIf(LOCAL_IMAGE::matches)
            url.startsWith("/") -> url.takeIf(LOCAL_IMAGE::matches)
            else -> null
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
