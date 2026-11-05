package org.sonora.android.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import org.sonora.android.wear.ui.Ambient
import org.sonora.android.wear.ui.WearApp

/** The watch app: one activity, listening to the phone while it is on screen. */
class WearActivity : ComponentActivity() {
    private lateinit var link: PhoneLink
    private val ambient = mutableStateOf(Ambient())

    /**
     * Always-on: with this the system keeps the player up when the wrist drops, instead of going
     * back to the watch face, and the ambient player is drawn until it wakes.
     */
    private val ambientObserver by lazy {
        AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    ambient.value = Ambient(
                        active = true,
                        burnInProtection = ambientDetails.burnInProtectionRequired,
                        lowBit = ambientDetails.deviceHasLowBitAmbient
                    )
                }

                override fun onUpdateAmbient() {
                    ambient.value = ambient.value.copy(minute = ambient.value.minute + 1)
                }

                override fun onExitAmbient() {
                    ambient.value = Ambient()
                }
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        link = PhoneLink(applicationContext, lifecycleScope)
        // The phone's Settings show which build this is.
        WatchInstaller.publishVersion(this)
        setContent { WearApp(link, ambient.value) }
        // The LFly icon on the watch face (NowPlayingListener) is a notification: ask once.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        link.start()
    }

    override fun onStop() {
        link.stop()
        super.onStop()
    }
}
