package dev.ytosko.neutrino

import kotlinx.coroutines.delay
import dev.ytosko.neutrino.data.glucose.MeterSyncOutcome
import dev.ytosko.neutrino.data.glucose.MeterNotifications
import dev.ytosko.neutrino.data.glucose.MeterCompanion
import dev.ytosko.neutrino.data.glucose.MeterScan
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.reminders.MealReminders
import android.app.Application
import android.content.Context
import dev.ytosko.neutrino.data.ai.AiClient
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.GeminiClient
import dev.ytosko.neutrino.data.ai.OpenAiClient
import dev.ytosko.neutrino.data.backup.BackupRepository
import dev.ytosko.neutrino.data.backup.DriveClient
import dev.ytosko.neutrino.data.backup.GoogleDriveAuth
import dev.ytosko.neutrino.data.backup.LocalBackupFile
import dev.ytosko.neutrino.data.food.FoodCatalog
import dev.ytosko.neutrino.data.food.FoodRepository
import dev.ytosko.neutrino.data.food.OpenFoodFactsClient
import dev.ytosko.neutrino.data.health.HealthConnectManager
import dev.ytosko.neutrino.data.meal.MealDatabase
import dev.ytosko.neutrino.data.meal.MealRepository
import dev.ytosko.neutrino.data.meal.PhotoProcessor
import dev.ytosko.neutrino.data.security.SecretCipher
import dev.ytosko.neutrino.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Application entry point. Holds the [AppContainer] for simple, explicit
 * dependency injection (no DI framework).
 */
class NeutrinoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Keep the daily backup scheduled once backups are set up (a no-op if already queued).
        appScope.launch {
            if (container.backups.state.first().passwordSet) container.backups.schedule()
            if (container.settings.settings.first().remindersEnabled) MealReminders.scheduleAll(this@NeutrinoApplication)
            container.syncHealthConnect()
            // Keep watching for the meter (e.g. after an app update).
            container.watchMeter()
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** App-wide singletons. */
class AppContainer(application: Application) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private val cipher = SecretCipher()

    val settings = SettingsRepository(application, cipher)

    val healthConnect = HealthConnectManager(application)

    val photos = PhotoProcessor(application)

    private val database = MealDatabase.create(application)

    val catalog = FoodCatalog { application.assets.open("foods.json").bufferedReader().use { it.readText() } }

    val foods = FoodRepository(database.foods(), catalog)

    val openFoodFacts = OpenFoodFactsClient(
        http = http,
        json = json,
        userAgent = "Neutrino/${BuildConfig.VERSION_NAME} (Android; privacy@ytosko.dev)",
    )

    val backups = BackupRepository(
        context = application,
        db = database,
        settings = settings,
        cipher = cipher,
        drive = DriveClient(http, json),
        auth = GoogleDriveAuth(application),
        local = LocalBackupFile(application),
        json = json,
    )

    val meals = MealRepository(database, healthConnect, photos, foods, onChanged = backups::scheduleSoon)

    val glucose = GlucoseRepository(application, database, healthConnect, onChanged = backups::scheduleSoon)

    private val context: Context = application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var meterSyncRunning = false
    @Volatile private var lastMeterSuccess = 0L

    /** Makes Health Connect match Neutrino: meals, water and glucose readings. */
    suspend fun syncHealthConnect() {
        meals.syncWithHealthConnect()
        glucose.syncWithHealthConnect()
    }

    /**
     * Called when Android sees the paired meter (it wakes up after a test). The meter's Bluetooth
     * window is short, so a failed connection is retried a few times; repeat wake-ups within a few
     * seconds are ignored.
     */
    @Synchronized
    fun syncMeterInBackground() {
        // One sync per meter wake-up: ignore triggers while syncing or right after a successful sync.
        if (meterSyncRunning || System.currentTimeMillis() - lastMeterSuccess < 30_000) return
        meterSyncRunning = true
        scope.launch {
            try {
                repeat(4) { attempt ->
                    when (val outcome = glucose.sync()) {
                        is MeterSyncOutcome.Synced -> {
                            lastMeterSuccess = System.currentTimeMillis()
                            if (outcome.newReadings > 0) MeterNotifications.newReadings(context, outcome.newReadings)
                            return@launch
                        }
                        is MeterSyncOutcome.Failed -> delay(3_000L * (attempt + 1))
                        else -> return@launch
                    }
                }
            } finally {
                meterSyncRunning = false
            }
        }
    }

    /** Keeps both meter wake-ups running while a meter is paired. */
    suspend fun watchMeter() {
        val meter = glucose.meter.first() ?: return
        MeterCompanion.observe(context, meter)
        MeterScan.start(context)
    }

    val aiClients: Map<AiProvider, AiClient> = mapOf(
        AiProvider.Gemini to GeminiClient(http, json),
        AiProvider.OpenAi to OpenAiClient(http, json),
    )
}

val Context.appContainer: AppContainer
    get() = (applicationContext as NeutrinoApplication).container
