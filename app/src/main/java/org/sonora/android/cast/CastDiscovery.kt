package org.sonora.android.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app Cast receiver discovery. The receiver list is published directly to the
 * output sheet, so choosing a device never leaves Sonora.
 */
object CastDiscovery {
    data class Device(
        val id: String,
        val name: String,
        val connecting: Boolean = false,
        val connected: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _supported = MutableStateFlow(false)
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    private val _activeName = MutableStateFlow<String?>(null)
    val activeName: StateFlow<String?> = _activeName.asStateFlow()

    private var started = false
    private var discovering = false
    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var callback: MediaRouter.Callback? = null

    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        if (GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS
        ) return
        runCatching {
            CastContext.getSharedInstance(app, ContextCompat.getMainExecutor(app))
                .addOnSuccessListener { ctx ->
                    castContext = ctx
                    router = MediaRouter.getInstance(app)
                    selector = ctx.mergedSelector
                    _supported.value = true
                    if (discovering) attach()
                }
        }
    }

    fun discover(context: Context) {
        ensureStarted(context)
        discovering = true
        if (castContext != null) attach()
    }

    fun stopDiscovery() {
        discovering = false
        val current = callback ?: return
        runCatching { router?.removeCallback(current) }
        callback = null
        _devices.value = emptyList()
    }

    fun connect(id: String) {
        val route = router?.routes?.firstOrNull { it.id == id } ?: return
        router?.selectRoute(route)
        publish()
    }

    fun disconnect() {
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
        publish()
    }

    private fun attach() {
        if (!discovering || callback != null) return
        val router = router ?: return
        val selector = selector ?: return
        val watcher = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publish()
            override fun onRouteSelected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) = publish()

            override fun onRouteUnselected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) = publish()
        }
        callback = watcher
        main.post {
            runCatching {
                router.addCallback(
                    selector,
                    watcher,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or
                        MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
                )
            }
            publish()
        }
    }

    private fun publish() {
        main.post {
            val router = router ?: return@post
            val selector = selector ?: return@post
            val session = castContext?.sessionManager?.currentCastSession
            val casting = session?.isConnected == true
            _activeName.value = if (casting) {
                router.selectedRoute?.name?.takeIf { it.isNotBlank() }
            } else null
            _devices.value = router.routes
                .asSequence()
                .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
                .map { route ->
                    Device(
                        id = route.id,
                        name = route.name,
                        connecting = route.connectionState ==
                            MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                        connected = casting && route.isSelected,
                    )
                }
                .toList()
        }
    }
}
