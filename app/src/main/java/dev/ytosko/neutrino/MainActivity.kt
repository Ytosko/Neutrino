package dev.ytosko.neutrino

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.ytosko.neutrino.ui.navigation.NeutrinoNavHost
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeutrinoTheme {
                NeutrinoNavHost()
            }
        }
    }
}
