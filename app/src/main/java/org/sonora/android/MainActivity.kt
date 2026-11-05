package org.sonora.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.sonora.android.ui.MainScreen
import org.sonora.android.ui.SonoraViewModel
import org.sonora.android.ui.theme.MotionPreferences
import org.sonora.android.ui.theme.SonoraAmoledBlack
import org.sonora.android.ui.theme.SonoraTheme

class MainActivity : ComponentActivity() {

    private companion object {
        /** Lives as long as the app's process: the opening animation plays once per launch. */
        var splashShown = false
    }

    private val viewModel: SonoraViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission granted or denied for Media3 playback notification
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system-bar scrims: the app manages its own (always themed) canvas, so the
        // default "auto" scrim (white when the system is in light mode) must not tint the bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        // Request notification permission for Android 13+ media notifications
        checkAndRequestNotificationPermission()

        // Once per launch of the app: not when it comes back from the background or the screen is
        // reopened while the app still runs (from the music notification, say).
        val freshStart = savedInstanceState == null && !splashShown
        splashShown = true
        setContent {
            val themeStyle by viewModel.themeStyle.collectAsState()
            val appearanceMode by viewModel.appearanceMode.collectAsState()
            val accentArgb by viewModel.accentColor.collectAsState()
            val reduceMotion by viewModel.reduceMotion.collectAsState()
            SonoraTheme(
                themeStyle = themeStyle,
                appearanceMode = appearanceMode,
                accentColor = androidx.compose.ui.graphics.Color(accentArgb),
                motionPreferences = MotionPreferences(reduceMotion = reduceMotion)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SonoraTheme.colors.background
                ) {
                    MainScreen(viewModel = viewModel)
                }
                // Another LFly asking to control this phone (LFly Connect).
                org.sonora.android.ui.components.ConnectRequestDialog()
                // Only on a fresh start, not when the screen is recreated (rotation, language).
                var splashing by rememberSaveable { mutableStateOf(freshStart) }
                if (splashing) {
                    org.sonora.android.ui.LaunchSplash(reduceMotion = reduceMotion) { splashing = false }
                }
            }
        }
    }

    /** While the music plays on another LFly, the volume keys turn that device up or down (Connect). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val up = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
        val down = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if ((up || down) && org.sonora.android.connect.ConnectManager.following.value != null) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) org.sonora.android.connect.ConnectManager.nudgeRemoteVolume(up)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!isGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
