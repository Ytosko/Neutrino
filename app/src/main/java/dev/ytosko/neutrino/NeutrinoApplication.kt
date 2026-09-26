package dev.ytosko.neutrino

import java.time.ZoneId
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import dev.ytosko.neutrino.data.reminders.DoseReminders
import dev.ytosko.neutrino.data.medicine.MedexClient
import dev.ytosko.neutrino.data.medicine.MedicineRepository
import kotlinx.coroutines.delay
import dev.ytosko.neutrino.data.glucose.MeterSyncOutcome
import dev.ytosko.neutrino.data.glucose.MeterNotifications
import dev.ytosko.neutrino.data.glucose.MeterCompanion
import dev.ytosko.neutrino.data.glucose.MeterScan
import dev.ytosko.neutrino.data.reminders.TestReminders
import dev.ytosko.neutrino.widget.NeutrinoWidget
import dev.ytosko.neutrino.data.export.DataExport
import dev.ytosko.neutrino.domain.GlucoseUnit
import kotlinx.coroutines.flow.MutableStateFlow
import dev.ytosko.neutrino.data.glucose.MeterWake
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.concurrent.ConcurrentHashMap
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

    // Notifications and the widget use the app's context: same language and digits as the screens.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(dev.ytosko.neutrino.data.settings.AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Keep the daily backup scheduled once backups are set up (a no-op if already queued).
        appScope.launch {
            container.chooseGlucoseUnitOnce()
            if (container.backups.state.first().passwordSet) container.backups.schedule()
            if (container.settings.settings.first().remindersEnabled) MealReminders.scheduleAll(this@NeutrinoApplication)
            runCatching { DoseReminders.sync(this@NeutrinoApplication) }
            container.syncHealthConnect()
            runCatching { container.importGlucose() }
            // Keep watching for the meter (e.g. after an app update).
            container.watchMeters()
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

    val medicines = MedicineRepository(
        dao = database.medicines(),
        medex = MedexClient(http, userAgent = "Neutrino/${BuildConfig.VERSION_NAME} (Android; personal medicine list)"),
        onChanged = { dataChanged() },
        onScheduleChanged = { scope.launch { DoseReminders.sync(context) } },
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

    private val scopeForChanges get() = scope

    /** After meals, water or readings change: back up soon and redraw the home screen widget. */
    private fun dataChanged() {
        backups.scheduleSoon()
        scopeForChanges.launch { NeutrinoWidget.refresh(context) }
    }

    val exports = DataExport(application, database, settings)

    /**
     * Picks the glucose unit once and stores it, so it never changes by itself later. Anyone who
     * already has readings or a meter saw mmol/L before units existed and keeps it; otherwise the
     * SIM or network country decides (many phones use English (US) outside the US), then the
     * phone's region.
     */
    suspend fun chooseGlucoseUnitOnce() {
        settings.ensureGlucoseUnit {
            if (glucose.meters.first().isNotEmpty() || glucose.hasReadings.first()) {
                GlucoseUnit.MmolL
            } else {
                val telephony = context.getSystemService(android.telephony.TelephonyManager::class.java)
                val country = listOfNotNull(
                    runCatching { telephony?.simCountryIso }.getOrNull(),
                    runCatching { telephony?.networkCountryIso }.getOrNull(),
                ).firstOrNull { it.isNotBlank() }
                GlucoseUnit.defaultFor(if (country != null) java.util.Locale("", country.uppercase()) else java.util.Locale.getDefault())
            }
        }
    }

    /** A widget or app shortcut asked for something (log a meal, add water); Home handles it. */
    val launchAction = MutableStateFlow<String?>(null)

    val meals = MealRepository(
        database, healthConnect, photos, foods,
        onChanged = ::dataChanged,
        onMealLogged = { at, type -> scope.launch { TestReminders.onMealLogged(application, at, type) } },
    )

    val glucose = GlucoseRepository(application, database, healthConnect, onChanged = ::dataChanged)

    /**
     * Each food's usual glucose change after meals with it, over the last 180 days, for foods
     * eaten at least 3 times with a reading before and after. Keyed by food id.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val usualFoodRises: kotlinx.coroutines.flow.Flow<Map<String, dev.ytosko.neutrino.domain.insights.FoodRise>> =
        kotlinx.coroutines.flow.flowOf(Unit).flatMapLatest {
            val zone = ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            val from = today.minusDays(179)
            combine(meals.observeMealFoods(from, today, zone), glucose.observeBetween(from, today.plusDays(1), zone)) { mealFoods, readings ->
                dev.ytosko.neutrino.domain.insights.FoodGlucoseInsights.rises(
                    mealFoods,
                    readings.map { dev.ytosko.neutrino.domain.insights.TimedReading(java.time.Instant.ofEpochMilli(it.measuredAtEpochMs), it.mmolPerL) },
                ).associateBy { it.key }
            }
        }

    private val context: Context = application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val meterSyncRunning: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lastMeterSuccess = ConcurrentHashMap<String, Long>()

    /**
     * Brings in glucose from other apps if the user turned import on: since the last import (with an
     * hour's overlap for late arrivals), or the last 30 days the first time.
     */
    suspend fun importGlucose() {
        val s = settings.settings.first()
        if (!s.glucoseImport) return
        val now = java.time.Instant.now()
        val since = if (s.glucoseImportedUntil > 0) {
            java.time.Instant.ofEpochMilli(s.glucoseImportedUntil).minusSeconds(3_600)
        } else {
            now.minus(java.time.Duration.ofDays(30))
        }
        glucose.importFromOtherApps(since, now)
        if (runCatching { healthConnect.canReadGlucose() }.getOrDefault(false)) settings.setGlucoseImportedUntil(now.toEpochMilli())
    }

    /** Makes Health Connect match Neutrino: meals, water and glucose readings. */
    suspend fun syncHealthConnect() {
        meals.syncWithHealthConnect()
        glucose.syncWithHealthConnect()
    }

    /**
     * Called when Android sees a paired meter (it wakes up after a test). [wake] says which meter
     * when Android knows; otherwise every paired meter is tried side by side. The meter's Bluetooth
     * window is short, so a failed connection is retried; repeat wake-ups for a meter that is
     * syncing, or synced in the last 30 seconds, are ignored.
     */
    fun syncMeterInBackground(wake: MeterWake = MeterWake()) {
        scope.launch {
            val all = glucose.meters.first()
            val matched = all.filter { wake.matches(it) }
            val targets = matched.ifEmpty { all }
            val attempts = if (matched.isNotEmpty()) 4 else 2
            val added = targets.map { meter -> async { syncOneMeter(meter.id, attempts) } }.awaitAll().sum()
            if (added > 0) MeterNotifications.newReadings(context, added)
        }
    }

    private suspend fun syncOneMeter(id: String, attempts: Int): Int {
        synchronized(meterSyncRunning) {
            if (System.currentTimeMillis() - (lastMeterSuccess[id] ?: 0L) < 30_000) return 0
            if (!meterSyncRunning.add(id)) return 0
        }
        try {
            repeat(attempts) { attempt ->
                when (val outcome = glucose.sync(id)) {
                    is MeterSyncOutcome.Synced -> {
                        lastMeterSuccess[id] = System.currentTimeMillis()
                        return outcome.newReadings
                    }
                    is MeterSyncOutcome.Failed -> delay(3_000L * (attempt + 1))
                    else -> return 0
                }
            }
            return 0
        } finally {
            meterSyncRunning.remove(id)
        }
    }

    /** Keeps the meter wake-ups running while any meter is paired. */
    suspend fun watchMeters() {
        val meters = glucose.meters.first()
        if (meters.isEmpty()) {
            MeterScan.stop(context)
            return
        }
        meters.forEach { MeterCompanion.observe(context, it) }
        MeterScan.start(context)
    }

    val aiClients: Map<AiProvider, AiClient> = mapOf(
        AiProvider.Gemini to GeminiClient(http, json),
        AiProvider.OpenAi to OpenAiClient(http, json),
    )
}

val Context.appContainer: AppContainer
    get() = (applicationContext as NeutrinoApplication).container
