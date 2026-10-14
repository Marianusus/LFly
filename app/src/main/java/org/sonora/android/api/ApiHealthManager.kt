package org.sonora.android.api

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * High-resilience API Health Manager.
 * Maintains an endpoint registry with live ping verification, latency tracking,
 * circuit breaker status, automatic failover, dynamic instance updating,
 * active mirror auto-discovery and rotation, and a reactive [StateFlow] for real-time UI diagnostics.
 */
class ApiHealthManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val failureThreshold: Int = 3,
    private val cooldownMs: Long = 60_000L // 1 minute circuit breaker trip duration
) {

    companion object {
        private const val TAG = "ApiHealthManager"

        @Volatile
        private var instance: ApiHealthManager? = null

        fun getInstance(): ApiHealthManager {
            return instance ?: synchronized(this) {
                instance ?: ApiHealthManager().also { instance = it }
            }
        }

        val DEFAULT_ENDPOINTS = listOf(
            ApiEndpoint("Deezer Official", DeezerClient.DEFAULT_BASE_URL, EndpointType.DEEZER),
            ApiEndpoint("LRCLIB Synced Lyrics", LrclibClient.DEFAULT_BASE_URL, EndpointType.LRCLIB),

            // Monochrome HiFi ecosystem (Lossless FLAC)
            ApiEndpoint("Monochrome Mirror (TF)", "https://api.monochrome.tf", EndpointType.MONOCHROME),
            ApiEndpoint("Monochrome HiFi (Tracks)", "https://tracks.monochrome.st", EndpointType.MONOCHROME),
            ApiEndpoint("Monochrome Root (TF)", "https://monochrome.tf", EndpointType.MONOCHROME),

            // JioSaavn high-speed REST mirrors
            ApiEndpoint("JioSaavn API (Black /api)", "https://jiosaavn-api-black.vercel.app/api", EndpointType.JIOSAAVN),
            ApiEndpoint("JioSaavn API (Sage)", "https://jiosaavn-api-sage.vercel.app/api", EndpointType.JIOSAAVN),
            ApiEndpoint("JioSaavn API (Self)", "https://jiosaavn-api-self.vercel.app/api", EndpointType.JIOSAAVN),
            ApiEndpoint("JioSaavn API (Saavn.me)", "https://saavn.me/api", EndpointType.JIOSAAVN),

            // Invidious instances
            ApiEndpoint("Invidious (f5.si)", "https://invidious.f5.si", EndpointType.INVIDIOUS),

            // Native YouTube Music InnerTube (/youtubei/v1/player)
            ApiEndpoint("YouTube Music InnerTube", YouTubeMusicClient.BASE_URL, EndpointType.YOUTUBE_MUSIC)
        )

        private val CANDIDATE_SAAVN_MIRRORS = listOf(
            "https://jiosaavn-api-black.vercel.app/api",
            "https://jiosaavn-api-sage.vercel.app/api",
            "https://jiosaavn-api-self.vercel.app/api",
            "https://saavn.me/api"
        )

        private val CANDIDATE_PIPED_INSTANCES = emptyList<String>()

        private val CANDIDATE_MONOCHROME_MIRRORS = listOf(
            "https://tracks.monochrome.st",
            "https://api.monochrome.tf"
        )
    }

    private val endpoints = ConcurrentHashMap<String, ApiEndpoint>()
    private val healthMap = ConcurrentHashMap<String, ProviderHealth>()
    private val circuitBreakCooldowns = ConcurrentHashMap<String, Long>()

    private val _healthState = MutableStateFlow<List<ProviderHealth>>(emptyList())
    val healthState: StateFlow<List<ProviderHealth>> = _healthState.asStateFlow()

    init {
        // Register default endpoints
        DEFAULT_ENDPOINTS.forEach { endpoint ->
            registerEndpoint(endpoint)
        }
        publishState()

        // Kick off initial health sweep asynchronously
        scope.launch {
            checkAllHealth()
            discoverAndRotateHealthyMirrors()

            // Periodic background health & auto-discovery cycle (every 3 minutes)
            while (isActive) {
                delay(180_000L)
                try {
                    checkAllHealth()
                    discoverAndRotateHealthyMirrors()
                } catch (e: Exception) {
                    Log.w(TAG, "Background health check error: ${e.message}")
                }
            }
        }
    }

    /**
     * Registers an endpoint into the registry.
     */
    fun registerEndpoint(endpoint: ApiEndpoint) {
        val normalized = normalizeUrl(endpoint.baseUrl)
        val cleanEndpoint = endpoint.copy(baseUrl = normalized)
        endpoints[normalized] = cleanEndpoint
        healthMap.putIfAbsent(
            normalized,
            ProviderHealth(
                providerName = cleanEndpoint.name,
                endpointUrl = normalized,
                latencyMs = 9999L,
                status = HealthStatus.ONLINE,
                lastChecked = System.currentTimeMillis()
            )
        )
        publishState()
    }

    /**
     * Removes an endpoint from registry.
     */
    fun unregisterEndpoint(baseUrl: String) {
        val normalized = normalizeUrl(baseUrl)
        endpoints.remove(normalized)
        healthMap.remove(normalized)
        circuitBreakCooldowns.remove(normalized)
        publishState()
    }

    /**
     * Returns all registered endpoints.
     */
    fun getAllEndpoints(): List<ApiEndpoint> = endpoints.values.toList()

    /**
     * Gets registered endpoints for a specific type, sorted by operational health and latency.
     */
    fun getHealthyEndpoints(type: EndpointType): List<ApiEndpoint> {
        val now = System.currentTimeMillis()
        val list = endpoints.values
            .filter { it.type == type && it.enabled }
            .sortedWith(
                compareBy<ApiEndpoint> { endpoint ->
                    val health = healthMap[endpoint.baseUrl]
                    val openUntil = circuitBreakCooldowns[endpoint.baseUrl] ?: 0L

                    // Check if circuit breaker is open (tripped)
                    val isCircuitOpen = openUntil > now

                    when {
                        isCircuitOpen -> 3 // Deprioritize tripped circuit breaker
                        health == null -> 1
                        health.status == HealthStatus.ONLINE -> 0
                        health.status == HealthStatus.DEGRADED -> 1
                        health.status == HealthStatus.OFFLINE -> 2
                        else -> 2
                    }
                }.thenBy { endpoint ->
                    healthMap[endpoint.baseUrl]?.latencyMs ?: Long.MAX_VALUE
                }
            )

        return list.ifEmpty {
            // Fallback to all enabled of this type even if degraded
            endpoints.values.filter { it.type == type && it.enabled }
        }
    }

    /**
     * Returns the primary (healthiest & lowest latency) active endpoint for a given type.
     */
    fun getActiveEndpoint(type: EndpointType): ApiEndpoint? {
        return getHealthyEndpoints(type).firstOrNull()
    }

    /**
     * Returns a prioritized list of active mirror URLs for a given endpoint type.
     */
    fun getActiveMirrors(type: EndpointType): List<String> {
        return getHealthyEndpoints(type).map { it.baseUrl }
    }

    /**
     * Returns current health record for an endpoint URL.
     */
    fun getEndpointHealth(endpointUrl: String): ProviderHealth? {
        return healthMap[normalizeUrl(endpointUrl)]
    }

    /**
     * Checks if an endpoint URL is currently considered healthy and responsive.
     */
    fun isEndpointHealthy(endpointUrl: String): Boolean {
        val normalized = normalizeUrl(endpointUrl)
        val openUntil = circuitBreakCooldowns[normalized] ?: 0L
        if (openUntil > System.currentTimeMillis()) return false

        val health = healthMap[normalized] ?: return false
        return health.status != HealthStatus.OFFLINE
    }

    /**
     * Rotates away from a failing endpoint and returns the next best healthy candidate.
     */
    fun rotateEndpoint(type: EndpointType, failedUrl: String): ApiEndpoint? {
        val normalized = normalizeUrl(failedUrl)
        recordFailure(normalized, "Rotated away due to failure")

        val candidates = getHealthyEndpoints(type)
        val nextBest = candidates.firstOrNull { it.baseUrl != normalized } ?: candidates.firstOrNull()
        Log.i(TAG, "Rotated $type from $normalized -> ${nextBest?.baseUrl}")
        return nextBest
    }

    /**
     * Record a successful request to an endpoint.
     * Resets failure counter and resets circuit breaker.
     */
    fun recordSuccess(endpointUrl: String, latencyMs: Long) {
        val normalizedUrl = normalizeUrl(endpointUrl)
        circuitBreakCooldowns.remove(normalizedUrl)

        val endpoint = endpoints[normalizedUrl]
        val providerName = endpoint?.name ?: extractHost(normalizedUrl)

        val status = if (latencyMs > 1500L) HealthStatus.DEGRADED else HealthStatus.ONLINE

        healthMap[normalizedUrl] = ProviderHealth(
            providerName = providerName,
            endpointUrl = normalizedUrl,
            latencyMs = latencyMs,
            status = status,
            lastChecked = System.currentTimeMillis(),
            consecutiveFailures = 0,
            message = "Latency: ${latencyMs}ms"
        )
        publishState()
    }

    /**
     * Record a failure (timeout, 5xx, 429) to an endpoint.
     * Only marks offline/degraded and trips circuit breaker after [failureThreshold] (at least 3) consecutive failures.
     */
    fun recordFailure(endpointUrl: String, error: String? = null) {
        val normalizedUrl = normalizeUrl(endpointUrl)
        val endpoint = endpoints[normalizedUrl]
        val providerName = endpoint?.name ?: extractHost(normalizedUrl)

        val current = healthMap[normalizedUrl]
        val newFailures = (current?.consecutiveFailures ?: 0) + 1

        val (status, cooldown) = if (newFailures >= failureThreshold) {
            Pair(HealthStatus.OFFLINE, System.currentTimeMillis() + cooldownMs)
        } else {
            Pair(HealthStatus.ONLINE, 0L)
        }

        if (cooldown > 0L) {
            circuitBreakCooldowns[normalizedUrl] = cooldown
        }

        healthMap[normalizedUrl] = ProviderHealth(
            providerName = providerName,
            endpointUrl = normalizedUrl,
            latencyMs = current?.latencyMs ?: 9999L,
            status = status,
            lastChecked = System.currentTimeMillis(),
            consecutiveFailures = newFailures,
            message = error ?: "Consecutive failures: $newFailures"
        )
        publishState()
    }

    /**
     * Records an active blackout (timeout >3.5s or 5xx/429 response).
     * Only marks offline/degraded and trips circuit breaker after [failureThreshold] (at least 3) consecutive failures.
     */
    fun recordBlackout(endpointUrl: String, error: String? = null) {
        val normalizedUrl = normalizeUrl(endpointUrl)
        val endpoint = endpoints[normalizedUrl]
        val providerName = endpoint?.name ?: extractHost(normalizedUrl)

        val current = healthMap[normalizedUrl]
        val newFailures = (current?.consecutiveFailures ?: 0) + 1

        val (status, cooldown) = if (newFailures >= failureThreshold) {
            Pair(HealthStatus.OFFLINE, System.currentTimeMillis() + cooldownMs)
        } else {
            Pair(HealthStatus.ONLINE, 0L)
        }

        if (cooldown > 0L) {
            circuitBreakCooldowns[normalizedUrl] = cooldown
            Log.w(TAG, "Blackout tripped circuit breaker for $providerName ($normalizedUrl) after $newFailures consecutive failures for ${cooldownMs / 1000}s.")
        }

        healthMap[normalizedUrl] = ProviderHealth(
            providerName = providerName,
            endpointUrl = normalizedUrl,
            latencyMs = if (newFailures >= failureThreshold) 9999L else (current?.latencyMs ?: 9999L),
            status = status,
            lastChecked = System.currentTimeMillis(),
            consecutiveFailures = newFailures,
            message = error ?: "Consecutive failures: $newFailures"
        )
        publishState()
    }

    /**
     * Check health of all registered endpoints concurrently.
     */
    suspend fun checkAllHealth(): List<ProviderHealth> = withContext(Dispatchers.IO) {
        val activeEndpoints = endpoints.values.toList()
        val deferredList = activeEndpoints.map { ep ->
            async {
                pingEndpoint(ep)
            }
        }

        deferredList.awaitAll()
        publishState()
        healthState.value
    }

    /**
     * UI alias for re-checking health of all endpoints.
     */
    suspend fun recheckAll(): List<ProviderHealth> = checkAllHealth()

    /**
     * Pings a single endpoint and updates its status in the registry.
     */
    suspend fun pingEndpoint(endpoint: ApiEndpoint): ProviderHealth = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(endpoint.baseUrl)
        val testUrl = when (endpoint.type) {
            EndpointType.DEEZER -> "$normalized/chart/0/tracks?limit=1"
            EndpointType.LRCLIB -> "$normalized/api/get?track_name=test&artist_name=test"
            EndpointType.JIOSAAVN -> buildSaavnTestUrl(normalized)
            EndpointType.INVIDIOUS -> "$normalized/api/v1/stats"
            EndpointType.PIPED -> "$normalized/search?q=test&filter=all"
            EndpointType.COBALT -> "$normalized/"
            EndpointType.MONOCHROME -> "$normalized/search/tracks?q=test&limit=1"
            EndpointType.YOUTUBE_MUSIC -> "$normalized/"
        }

        val (isReachable, latency) = HttpClientProvider.ping(testUrl)

        val health = if (isReachable) {
            recordSuccess(normalized, latency)
            healthMap[normalized]!!
        } else {
            recordFailure(normalized, "Unreachable / timed out")
            healthMap[normalized]!!
        }

        health
    }

    /**
     * Ping test for an arbitrary URL.
     */
    suspend fun testEndpoint(endpointUrl: String): ProviderHealth = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(endpointUrl)
        val existing = endpoints[normalized]
        if (existing != null) {
            return@withContext pingEndpoint(existing)
        }

        val (isReachable, latency) = HttpClientProvider.ping(normalized)
        val status = when {
            !isReachable -> HealthStatus.OFFLINE
            latency > 1500L -> HealthStatus.DEGRADED
            else -> HealthStatus.ONLINE
        }

        ProviderHealth(
            providerName = extractHost(normalized),
            endpointUrl = normalized,
            latencyMs = latency,
            status = status,
            lastChecked = System.currentTimeMillis(),
            message = if (isReachable) "OK ($latency ms)" else "Ping failed"
        )
    }

    /**
     * Transparent failover execution helper.
     * Iterates through healthy endpoints of the specified [type], executing [block].
     * If an endpoint throws or fails, transparently queries the next available instance.
     */
    suspend fun <T> executeWithFailover(
        type: EndpointType,
        block: suspend (ApiEndpoint) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        val candidates = getHealthyEndpoints(type)
        if (candidates.isEmpty()) {
            return@withContext Result.failure(IOException("No endpoints configured for type $type"))
        }

        val errors = mutableListOf<String>()
        for (endpoint in candidates) {
            val start = System.currentTimeMillis()
            try {
                val result = block(endpoint)
                val latency = System.currentTimeMillis() - start
                recordSuccess(endpoint.baseUrl, latency)
                return@withContext Result.success(result)
            } catch (e: Exception) {
                recordFailure(endpoint.baseUrl, e.message)
                errors.add("${endpoint.name}: ${e.message}")
            }
        }

        Result.failure(IOException("All endpoints for $type failed: ${errors.joinToString("; ")}"))
    }

    /**
     * Discovers fresh, healthy mirror instances across JioSaavn, Invidious, and Piped.
     * Tests candidates and registers newly discovered healthy endpoints.
     */
    suspend fun discoverAndRotateHealthyMirrors() = withContext(Dispatchers.IO) {
        // 0. Discover Monochrome mirrors
        for (candidate in CANDIDATE_MONOCHROME_MIRRORS) {
            val normalized = normalizeUrl(candidate)
            if (!endpoints.containsKey(normalized)) {
                val pingResult = HttpClientProvider.ping("$normalized/search/tracks?q=test&limit=1")
                val isReachable = if (pingResult.first) true else HttpClientProvider.ping(normalized).first
                if (isReachable) {
                    val hostName = extractHost(normalized)
                    val ep = ApiEndpoint(
                        name = "Monochrome ($hostName)",
                        baseUrl = normalized,
                        type = EndpointType.MONOCHROME
                    )
                    registerEndpoint(ep)
                    recordSuccess(normalized, pingResult.second)
                    Log.i(TAG, "Discovered healthy Monochrome mirror: $normalized (${pingResult.second}ms)")
                }
            }
        }

        // 1. Discover Saavn REST mirrors
        for (candidate in CANDIDATE_SAAVN_MIRRORS) {
            val normalized = normalizeUrl(candidate)
            if (!endpoints.containsKey(normalized)) {
                val pingUrl = buildSaavnTestUrl(normalized)
                val pingResult = HttpClientProvider.ping(pingUrl)
                if (pingResult.first) {
                    val hostName = extractHost(normalized).removeSuffix(".vercel.app")
                    val ep = ApiEndpoint(
                        name = "JioSaavn ($hostName)",
                        baseUrl = normalized,
                        type = EndpointType.JIOSAAVN
                    )
                    registerEndpoint(ep)
                    recordSuccess(normalized, pingResult.second)
                    Log.i(TAG, "Discovered healthy JioSaavn mirror: $normalized (${pingResult.second}ms)")
                }
            }
        }

        // 2. Discover Piped instances
        for (candidate in CANDIDATE_PIPED_INSTANCES) {
            val normalized = normalizeUrl(candidate)
            if (!endpoints.containsKey(normalized)) {
                val pingResult = HttpClientProvider.ping("$normalized/search?q=test&filter=all")
                if (pingResult.first) {
                    val hostName = extractHost(normalized)
                    val ep = ApiEndpoint(
                        name = "Piped ($hostName)",
                        baseUrl = normalized,
                        type = EndpointType.PIPED
                    )
                    registerEndpoint(ep)
                    recordSuccess(normalized, pingResult.second)
                    Log.i(TAG, "Discovered healthy Piped instance: $normalized (${pingResult.second}ms)")
                }
            }
        }

        // 3. Discover Invidious instances
        fetchFreshInvidiousInstances()
    }

    /**
     * UI backward-compatible discovery trigger.
     */
    suspend fun fetchFreshInstances() = discoverAndRotateHealthyMirrors()

    private suspend fun fetchFreshInvidiousInstances() = withContext(Dispatchers.IO) {
        try {
            val jsonResult = HttpClientProvider.getJson("https://api.invidious.io/instances.json?sort_by=health")
            jsonResult.onSuccess { rawJson ->
                val root = JsonParser.parseString(rawJson).asJsonArray
                var added = 0
                for (elem in root) {
                    if (added >= 5) break
                    val array = elem.asJsonArray
                    if (array.size() >= 2) {
                        val domain = array.get(0).asString
                        val info = array.get(1).asJsonObject
                        val scheme = if (info.has("type")) info.get("type").asString else "https"
                        val apiEnabled = info.has("api") && info.get("api").asBoolean
                        val isForbiddenDomain = domain.contains(".onion") || domain.contains(".i2p") || domain.contains(".ygg")

                        if (scheme.equals("https", ignoreCase = true) && apiEnabled && !isForbiddenDomain) {
                            val fullUrl = normalizeUrl("$scheme://$domain")
                            if (!endpoints.containsKey(fullUrl)) {
                                val ep = ApiEndpoint(
                                    name = "Invidious ($domain)",
                                    baseUrl = fullUrl,
                                    type = EndpointType.INVIDIOUS
                                )
                                registerEndpoint(ep)
                                added++
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Invidious instance directory fetch error: ${e.message}")
        }
    }

    private fun buildSaavnTestUrl(baseUrl: String): String {
        val clean = normalizeUrl(baseUrl)
        return when {
            clean.contains("jiosaavn.com") -> "$clean?__call=autocomplete.get&_format=json&query=a"
            clean.endsWith("/api") -> "$clean/search/songs?query=test&limit=1"
            else -> "$clean/api/search/songs?query=test&limit=1"
        }
    }

    private fun publishState() {
        _healthState.value = healthMap.values.toList()
            .sortedWith(compareBy({ it.status.ordinal }, { it.latencyMs }))
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().removeSuffix("/")
    }

    private fun extractHost(url: String): String {
        return try {
            java.net.URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
    }
}
