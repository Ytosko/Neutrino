package dev.ytosko.neutrino

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
            container.meals.syncWithHealthConnect()
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

    val aiClients: Map<AiProvider, AiClient> = mapOf(
        AiProvider.Gemini to GeminiClient(http, json),
        AiProvider.OpenAi to OpenAiClient(http, json),
    )
}

val Context.appContainer: AppContainer
    get() = (applicationContext as NeutrinoApplication).container
