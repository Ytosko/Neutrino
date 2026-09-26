package dev.ytosko.neutrino

import dev.ytosko.neutrino.ui.glucose.LocalFoodRises
import android.content.Intent
import android.os.Bundle
import dev.ytosko.neutrino.widget.LaunchAction
import dev.ytosko.neutrino.data.settings.AppLanguage
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.ytosko.neutrino.domain.GlucoseUnit
import dev.ytosko.neutrino.ui.glucose.LocalGlucoseUnit
import dev.ytosko.neutrino.ui.lock.AppLock
import dev.ytosko.neutrino.ui.lock.LockScreen
import dev.ytosko.neutrino.ui.navigation.NeutrinoNavHost
import dev.ytosko.neutrino.ui.navigation.Route
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A FragmentActivity so the system fingerprint / screen-lock prompt can be shown for the app lock. */
class MainActivity : FragmentActivity() {

    /** Resolved once from settings; the splash screen stays up until it's known. */
    private val startDestination = MutableStateFlow<Route?>(null)

    /** The app lock is covering the app. */
    private val locked = MutableStateFlow(false)
    @Volatile private var appLockOn = false
    private var prompting = false

    override fun onStart() {
        super.onStart()
        // Lock again after being away for a while (a quick trip to the camera or a permission screen doesn't count).
        if (appLockOn && stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_AFTER_MS) {
            unlockedInProcess = false
            locked.value = true
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        // "All files access" can be switched off in system settings while Neutrino is closed.
        appContainer.backups.refreshAccess()
        // Send anything saved while Health Connect wasn't connected (e.g. permission just granted in its app).
        lifecycleScope.launch {
            appContainer.syncHealthConnect()
            // Glucose from CGM apps, if the user turned import on.
            runCatching { appContainer.importGlucose() }
            // Bluetooth may have been off; restart the background meter scan.
            appContainer.watchMeters()
        }
        // Before Android 12 there's no background wake-up for the meter: try when the app opens.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) appContainer.syncMeterInBackground()
    }

    // Before Android 13, show the language chosen in Neutrino's settings.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchAction(intent)
    }

    /** From the widget or an app shortcut; the Home screen picks it up. */
    private fun handleLaunchAction(intent: Intent?) {
        val action = intent?.action ?: return
        if (action in setOf(LaunchAction.LOG_MEAL, LaunchAction.LOG_MEAL_PHOTO, LaunchAction.ADD_WATER)) {
            appContainer.launchAction.value = action
            intent.action = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleLaunchAction(intent)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { startDestination.value == null }

        lifecycleScope.launch {
            val settings = appContainer.settings.settings.first()
            // Decide the lock before anything is drawn, so the app never flashes before locking.
            appLockOn = settings.appLock && AppLock.available(this@MainActivity)
            // A new process (cold start, or restored after Android freed memory) always starts locked;
            // turning the phone doesn't.
            if (appLockOn && !unlockedInProcess) locked.value = true
            startDestination.value = if (settings.onboardingComplete) Route.Home else Route.Welcome
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                appContainer.settings.settings.collect { settings ->
                    appLockOn = settings.appLock && AppLock.available(this@MainActivity)
                    if (!appLockOn) locked.value = false
                    // Hidden preview in recent apps, and no screenshots.
                    if (settings.hideInRecents) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }

        setContent {
            NeutrinoTheme {
                val settings by appContainer.settings.settings.collectAsStateWithLifecycle(initialValue = null)
                val start by startDestination.collectAsStateWithLifecycle()
                val isLocked by locked.collectAsStateWithLifecycle()
                val foodRises by appContainer.usualFoodRises.collectAsStateWithLifecycle(initialValue = emptyMap())
                CompositionLocalProvider(
                    LocalGlucoseUnit provides (settings?.glucoseUnit ?: GlucoseUnit.defaultFor()),
                    LocalFoodRises provides foodRises,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        // Kept composed underneath so nothing is lost while locked, but hidden from screen readers.
                        Box(if (isLocked) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
                            start?.let { NeutrinoNavHost(startDestination = it) }
                        }
                        if (isLocked) {
                            LaunchedEffect(Unit) { unlock() }
                            LockScreen(onUnlock = ::unlock)
                        }
                    }
                }
            }
        }
    }

    private fun unlock() {
        if (prompting) return
        prompting = true
        AppLock.authenticate(
            this,
            onSuccess = {
                prompting = false
                unlockedInProcess = true
                locked.value = false
            },
            onFailure = { prompting = false },
        )
    }

    private companion object {
        const val RELOCK_AFTER_MS = 2 * 60 * 1000L

        /** Survives rotation (same process), not process death. */
        @Volatile var unlockedInProcess = false

        /** Time the app last went to the background; shared across activity re-creations. */
        var stoppedAt = 0L
    }
}
