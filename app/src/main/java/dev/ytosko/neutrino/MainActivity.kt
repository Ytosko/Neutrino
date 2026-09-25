package dev.ytosko.neutrino

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.ytosko.neutrino.ui.navigation.NeutrinoNavHost
import dev.ytosko.neutrino.ui.navigation.Route
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Resolved once from settings; the splash screen stays up until it's known. */
    private val startDestination = MutableStateFlow<Route?>(null)

    override fun onResume() {
        super.onResume()
        // "All files access" can be switched off in system settings while Neutrino is closed.
        appContainer.backups.refreshAccess()
        // Send anything saved while Health Connect wasn't connected (e.g. permission just granted in its app).
        lifecycleScope.launch {
            appContainer.syncHealthConnect()
            // Bluetooth may have been off; restart the background meter scan.
            appContainer.watchMeters()
        }
        // Before Android 12 there's no background wake-up for the meter: try when the app opens.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) appContainer.syncMeterInBackground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { startDestination.value == null }

        lifecycleScope.launch {
            val settings = appContainer.settings.settings.first()
            startDestination.value = if (settings.onboardingComplete) Route.Home else Route.Welcome
        }

        setContent {
            NeutrinoTheme {
                val start by startDestination.collectAsStateWithLifecycle()
                start?.let { NeutrinoNavHost(startDestination = it) }
            }
        }
    }
}
