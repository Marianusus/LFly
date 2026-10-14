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
import org.sonora.android.ui.MainScreen
import org.sonora.android.ui.SonoraViewModel
import org.sonora.android.ui.theme.MotionPreferences
import org.sonora.android.ui.theme.SonoraAmoledBlack
import org.sonora.android.ui.theme.SonoraTheme

class MainActivity : ComponentActivity() {

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
            }
        }
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
