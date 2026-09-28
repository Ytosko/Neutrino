package dev.ytosko.neutrino.ui.navigation

import dev.ytosko.neutrino.ui.settings.ConditionsScreen
import dev.ytosko.neutrino.ui.settings.WorkoutsScreen
import dev.ytosko.neutrino.ui.settings.WeightScreen
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.ytosko.neutrino.ui.settings.GlucoseSettingsScreen
import dev.ytosko.neutrino.ui.ai.OpenRouterGoneDialog
import dev.ytosko.neutrino.ui.ai.OpenRouterCheckViewModel
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import dev.ytosko.neutrino.ui.voice.voiceController
import dev.ytosko.neutrino.ui.voice.VoiceDialogs
import dev.ytosko.neutrino.ui.voice.LogByVoiceSheet
import dev.ytosko.neutrino.ui.voice.VoiceLogViewModel
import dev.ytosko.neutrino.ui.ai.AiModelsViewModel
import dev.ytosko.neutrino.ui.ai.AiModelsScreen
import dev.ytosko.neutrino.ui.medicine.MedicinesViewModel
import dev.ytosko.neutrino.ui.medicine.MedicinesScreen
import dev.ytosko.neutrino.ui.glucose.MeterViewModel
import dev.ytosko.neutrino.ui.glucose.MeterScreen
import dev.ytosko.neutrino.ui.settings.GoalsScreen
import dev.ytosko.neutrino.ui.glucose.GlucoseDayScreen
import dev.ytosko.neutrino.ui.glucose.GlucoseDayViewModel
import dev.ytosko.neutrino.ui.export.ExportScreen
import dev.ytosko.neutrino.data.glucose.MeterModel
import dev.ytosko.neutrino.ui.glucose.PairMeterViewModel
import dev.ytosko.neutrino.ui.glucose.PairMeterScreen
import dev.ytosko.neutrino.ui.glucose.AddMeterScreen
import dev.ytosko.neutrino.ui.glucose.MetersViewModel
import dev.ytosko.neutrino.ui.glucose.MetersScreen
import dev.ytosko.neutrino.ui.theme.Spacing
import androidx.compose.foundation.layout.padding
import dev.ytosko.neutrino.ui.settings.MealsScreen
import dev.ytosko.neutrino.ui.ai.AiPreferences
import dev.ytosko.neutrino.data.reminders.MealReminders
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import android.net.Uri
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.appContainer
import dev.ytosko.neutrino.ui.ai.AiSetupForm
import dev.ytosko.neutrino.ui.ai.AiSetupViewModel
import dev.ytosko.neutrino.ui.backup.BackupSettingsScreen
import dev.ytosko.neutrino.ui.backup.BackupSetupScreen
import dev.ytosko.neutrino.ui.backup.BackupViewModel
import dev.ytosko.neutrino.ui.backup.RestoreScreen
import dev.ytosko.neutrino.ui.backup.RestoreViewModel
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.health.HealthConnectScreen
import dev.ytosko.neutrino.ui.health.HealthConnectViewModel
import dev.ytosko.neutrino.ui.food.FoodSearchViewModel
import dev.ytosko.neutrino.ui.home.HomeScreen
import dev.ytosko.neutrino.ui.home.HomeViewModel
import dev.ytosko.neutrino.ui.insights.HealthViewModel
import dev.ytosko.neutrino.ui.review.ReviewScreen
import dev.ytosko.neutrino.ui.review.ReviewViewModel
import dev.ytosko.neutrino.ui.settings.SettingsScreen
import dev.ytosko.neutrino.ui.welcome.WelcomeScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
sealed interface Route {
    @Serializable data object Welcome : Route
    @Serializable data object SetupHealth : Route
    @Serializable data object SetupAi : Route
    @Serializable data object Home : Route
    @Serializable data object Settings : Route
    @Serializable data object SettingsAi : Route
    @Serializable data object SettingsHealth : Route
    /** [logEpochDay]: the day shown on Today when logging started, if not today. */
    @Serializable data class Review(
        /** Photos to read, joined by new lines (up to 5). */
        val photoUris: String? = null,
        val fromCamera: Boolean = false,
        val logEpochDay: Long? = null,
        val editMealId: String? = null,
        /** Opens the meal just said with Log by voice. */
        val fromVoice: Boolean = false,
    ) : Route
    @Serializable data object SetupBackup : Route
    @Serializable data object SettingsBackup : Route
    @Serializable data object SettingsMeals : Route
    @Serializable data object SettingsMeter : Route
    @Serializable data object AddMeter : Route
    @Serializable data object SettingsGoals : Route
    @Serializable data class GlucoseDay(val epochDay: Long) : Route
    @Serializable data object SettingsExport : Route
    @Serializable data class PairMeter(val model: String) : Route
    @Serializable data class MeterDetail(val id: String) : Route
    @Serializable data object Restore : Route
    @Serializable data object RestoreHealth : Route
    @Serializable data object Medicines : Route
    @Serializable data object SettingsGlucose : Route
    @Serializable data object SettingsWorkouts : Route
    @Serializable data object SettingsConditions : Route
    @Serializable data object SettingsWeight : Route
    @Serializable data object SettingsLiver : Route
    /** One AI model's setup: an existing one ([configId]) or a new one of [provider]. */
    @Serializable data class AiConfigEdit(val configId: String? = null, val provider: String? = null, val voice: Boolean = false) : Route
}

private const val KEY_SAVED_RESULT = "meal_saved_synced"



private const val SETUP_STEPS = 3

@Composable
fun NeutrinoNavHost(startDestination: Route, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    // The weekly weigh-in reminder: open Daily goals (it then shows the weight editor).
    val openWeight = LocalContext.current.appContainer.openWeight
    val wantsWeight by openWeight.collectAsStateWithLifecycle()
    LaunchedEffect(wantsWeight) {
        if (wantsWeight && navController.currentDestination?.hasRoute(Route.SettingsWeight::class) != true) {
            runCatching { navController.navigate(Route.SettingsWeight) }
        }
    }
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable<Route.Welcome> {
            WelcomeScreen(
                onGetStarted = { navController.navigate(Route.SetupHealth) },
                onRestore = { navController.navigate(Route.Restore) },
            )
        }
        composable<Route.SetupHealth> {
            HealthConnectScreen(
                viewModel = healthConnectViewModel(),
                onBack = navController::popBackStack,
                onContinue = { navController.navigate(Route.SetupAi) },
                step = 1 to SETUP_STEPS,
            )
        }
        composable<Route.SetupAi> {
            AiScreen(
                onBack = navController::popBackStack,
                onboarding = true,
                onSaved = { navController.navigate(Route.SetupBackup) },
            )
        }
        composable<Route.SetupBackup> {
            val container = LocalContext.current.appContainer
            val scope = rememberCoroutineScope()
            BackupSetupScreen(
                viewModel = backupViewModel(),
                step = 3 to SETUP_STEPS,
                onBack = navController::popBackStack,
                onFinish = {
                    scope.launch {
                        container.settings.setOnboardingComplete()
                        navController.finishOnboarding()
                    }
                },
            )
        }
        composable<Route.Restore> {
            val container = LocalContext.current.appContainer
            RestoreScreen(
                viewModel = viewModel { RestoreViewModel(container.backups) },
                onBack = navController::popBackStack,
                onStartFresh = { navController.navigate(Route.SetupHealth) { popUpTo<Route.Welcome>() } },
                onRestored = {
                    navController.navigate(Route.RestoreHealth) { popUpTo(navController.graph.id) { inclusive = true } }
                },
            )
        }
        composable<Route.RestoreHealth> {
            // Health Connect permission doesn't survive a reinstall, so ask again after a restore.
            HealthConnectScreen(
                viewModel = healthConnectViewModel(),
                onBack = null,
                onContinue = { navController.finishOnboarding() },
            )
        }
        composable<Route.Home> { entry ->
            val container = LocalContext.current.appContainer
            val homeViewModel: HomeViewModel = viewModel { HomeViewModel(container.meals, container.settings, container.glucose, container.medicines) }
            val savedResult by entry.savedStateHandle.getStateFlow<Boolean?>(KEY_SAVED_RESULT, null).collectAsStateWithLifecycle()
            val backup by container.backups.state.collectAsStateWithLifecycle(initialValue = null)
            val healthViewModel: HealthViewModel = viewModel { HealthViewModel(container.meals, container.glucose, container.settings, container.medicines) }
            val context = LocalContext.current
            val voiceViewModel: VoiceLogViewModel = viewModel { VoiceLogViewModel(container, context) }
            val appSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            var voiceOpen by rememberSaveable { mutableStateOf(false) }
            val voiceDone by voiceViewModel.controller.finished.collectAsStateWithLifecycle()
            LaunchedEffect(voiceDone) {
                val command = voiceDone ?: return@LaunchedEffect
                voiceViewModel.controller.consumeFinished()
                voiceOpen = false
                if (command.hasMeal) navController.navigate(Route.Review(logEpochDay = homeViewModel.pastDayEpoch(), fromVoice = true))
            }
            HomeScreen(
                viewModel = homeViewModel,
                healthViewModel = healthViewModel,
                backup = backup,
                onOpenSettings = { navController.navigate(Route.Settings) },
                onOpenAiSettings = { navController.navigate(Route.SettingsAi) },
                onOpenBackup = { navController.navigate(Route.SettingsBackup) },
                onPhotoSelected = { uris, fromCamera ->
                    navController.navigate(Route.Review(uris.joinToString("\n"), fromCamera, homeViewModel.pastDayEpoch()))
                },
                onAddManually = { navController.navigate(Route.Review(logEpochDay = homeViewModel.pastDayEpoch())) },
                // Voice is on but not ready (no voice model, or no Primary): finish setting it up first.
                onLogByVoice = { if (appSettings?.voiceReady == true) voiceOpen = true else navController.navigate(Route.SettingsAi) },
                onOpenMeal = { id -> navController.navigate(Route.Review(editMealId = id)) },
                onOpenGlucoseDay = { date -> navController.navigate(Route.GlucoseDay(date.toEpochDay())) },
                onOpenHealthConnect = { navController.navigate(Route.SettingsHealth) },
                onOpenMedicines = { navController.navigate(Route.Medicines) },
                savedResult = savedResult,
                onSavedResultShown = { entry.savedStateHandle[KEY_SAVED_RESULT] = null },
            )
            // An OpenRouter model that's gone: offer its replacement, or open its setup page.
            val openRouterCheck: OpenRouterCheckViewModel = viewModel { OpenRouterCheckViewModel(container.settings, container.openRouter) }
            OpenRouterGoneDialog(openRouterCheck, onChoose = { id -> navController.navigate(Route.AiConfigEdit(configId = id)) })
            if (voiceOpen) LogByVoiceSheet(voiceViewModel.controller, onDismiss = { voiceOpen = false })
            VoiceDialogs(voiceViewModel.controller, (appSettings?.glucoseLow ?: 4.0)..(appSettings?.glucoseHigh ?: 10.0))
        }
        composable<Route.Review> { entry ->
            val route = entry.toRoute<Route.Review>()
            val context = LocalContext.current
            val container = context.appContainer
            val reviewViewModel: ReviewViewModel = viewModel {
                val uris = route.photoUris?.split('\n')?.filter { it.isNotBlank() }?.map(Uri::parse).orEmpty()
                ReviewViewModel(
                    photoUris = uris,
                    settings = container.settings,
                    clients = container.aiClients,
                    photos = container.photos,
                    meals = container.meals,
                    foods = container.foods,
                    // Camera captures are temporary; delete once the photo is prepared.
                    // Camera photos are temporary files (the gallery's aren't ours): deleted once read.
                    onPhotoConsumed = { uri ->
                        if (uri.authority == "${context.packageName}.files") runCatching { context.contentResolver.delete(uri, null, null) }
                    },
                    logDate = route.logEpochDay?.let(java.time.LocalDate::ofEpochDay),
                    editMealId = route.editMealId,
                    voiceMeal = if (route.fromVoice) container.voiceMeal.also { container.voiceMeal = null } else null,
                    voiceFactory = { scope, contextFor, session, onMeal -> container.voiceController(scope, context, contextFor, session, onMeal) },
                )
            }
            ReviewScreen(
                viewModel = reviewViewModel,
                searchViewModel = { key, mealType ->
                    viewModel(key = key) {
                        FoodSearchViewModel(container.foods, container.openFoodFacts, container.settings, container.aiClients, mealType, container.network.online)
                    }
                },
                onSaved = { synced ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(KEY_SAVED_RESULT, synced)
                    navController.popBackStack()
                },
                onOpenAiSettings = { navController.navigate(Route.SettingsAi) },
                onDiscard = navController::popBackStack,
            )
        }
        composable<Route.Settings> {
            val context = LocalContext.current
            val container = context.appContainer
            val scope = rememberCoroutineScope()
            SettingsScreen(
                settings = container.settings.settings,
                backup = container.backups.state,
                onOpenBackup = { navController.navigate(Route.SettingsBackup) },
                onOpenMeals = { navController.navigate(Route.SettingsMeals) },
                meters = container.glucose.meters,
                onOpenMeter = { navController.navigate(Route.SettingsMeter) },
                healthViewModel = healthConnectViewModel(),
                onBack = navController::popBackStack,
                onOpenAi = { navController.navigate(Route.SettingsAi) },
                onOpenHealthConnect = { navController.navigate(Route.SettingsHealth) },
                onOpenGoals = { navController.navigate(Route.SettingsGoals) },
                onOpenExport = { navController.navigate(Route.SettingsExport) },
                onOpenMedicines = { navController.navigate(Route.Medicines) },
                medicines = container.medicines.medicines,
                onOpenGlucose = { navController.navigate(Route.SettingsGlucose) },
                repository = container.settings,
            )
        }
        composable<Route.SettingsGlucose> {
            val container = LocalContext.current.appContainer
            GlucoseSettingsScreen(
                settings = container.settings.settings,
                healthViewModel = healthConnectViewModel(),
                repository = container.settings,
                onOpenHealthConnect = { navController.navigate(Route.SettingsHealth) },
                onBack = navController::popBackStack,
            )
        }
        composable<Route.Medicines> {
            val container = LocalContext.current.appContainer
            val medicinesViewModel: MedicinesViewModel = viewModel { MedicinesViewModel(container.medicines, container.settings) }
            MedicinesScreen(viewModel = medicinesViewModel, onBack = navController::popBackStack)
        }
        composable<Route.SettingsMeter> {
            val container = LocalContext.current.appContainer
            val metersViewModel: MetersViewModel = viewModel { MetersViewModel(container.glucose, container.healthConnect) }
            MetersScreen(
                viewModel = metersViewModel,
                onBack = navController::popBackStack,
                onAdd = { navController.navigate(Route.AddMeter) },
                onOpen = { navController.navigate(Route.MeterDetail(it)) },
            )
        }
        composable<Route.SettingsExport> {
            ExportScreen(export = LocalContext.current.appContainer.exports, onBack = navController::popBackStack)
        }
        composable<Route.GlucoseDay> { entry ->
            val container = LocalContext.current.appContainer
            val date = java.time.LocalDate.ofEpochDay(entry.toRoute<Route.GlucoseDay>().epochDay)
            val dayViewModel: GlucoseDayViewModel = viewModel { GlucoseDayViewModel(container.glucose, container.settings, date, container.meals) }
            GlucoseDayScreen(viewModel = dayViewModel, onBack = navController::popBackStack)
        }
        composable<Route.SettingsGoals> {
            val container = LocalContext.current.appContainer
            GoalsScreen(
                settings = container.settings,
                viewModel = goalsViewModel(),
                onOpenWeight = { navController.navigate(Route.SettingsWeight) },
                onOpenWorkouts = { navController.navigate(Route.SettingsWorkouts) },
                onOpenConditions = { navController.navigate(Route.SettingsConditions) },
                onOpenAi = { navController.navigate(Route.SettingsAi) },
                onBack = navController::popBackStack,
            )
        }
        composable<Route.SettingsWorkouts> {
            WorkoutsScreen(viewModel = goalsViewModel(), onBack = navController::popBackStack)
        }
        composable<Route.SettingsWeight> {
            WeightScreen(
                viewModel = goalsViewModel(),
                openWeight = LocalContext.current.appContainer.openWeight,
                onBack = navController::popBackStack,
            )
        }
        composable<Route.SettingsConditions> {
            ConditionsScreen(
                viewModel = goalsViewModel(),
                onOpenLiver = { navController.navigate(Route.SettingsLiver) },
                onBack = navController::popBackStack,
            )
        }
        composable<Route.SettingsLiver> {
            dev.ytosko.neutrino.ui.settings.LiverScreen(viewModel = goalsViewModel(), onBack = navController::popBackStack)
        }
        composable<Route.AddMeter> {
            AddMeterScreen(
                onBack = navController::popBackStack,
                onPick = { navController.navigate(Route.PairMeter(it.name)) },
            )
        }
        composable<Route.PairMeter> { entry ->
            val context = LocalContext.current
            val container = context.appContainer
            val model = MeterModel.fromKey(entry.toRoute<Route.PairMeter>().model)
            val pairViewModel: PairMeterViewModel = viewModel {
                PairMeterViewModel(
                    context.applicationContext, container.glucose, model,
                    syncInBackground = container::syncMeterInBackground,
                    watchMeters = container::watchMeters,
                )
            }
            PairMeterScreen(
                viewModel = pairViewModel,
                onBack = navController::popBackStack,
                // Back to the meter list, which now shows the new meter.
                onDone = { navController.popBackStack(Route.SettingsMeter, inclusive = false) },
            )
        }
        composable<Route.MeterDetail> { entry ->
            val context = LocalContext.current
            val container = context.appContainer
            val id = entry.toRoute<Route.MeterDetail>().id
            val meterViewModel: MeterViewModel = viewModel {
                MeterViewModel(context.applicationContext, container.glucose, container.settings, id, container::watchMeters)
            }
            MeterScreen(
                viewModel = meterViewModel,
                onBack = { navController.popBackStack(Route.SettingsMeter, inclusive = false) },
                onPairAgain = { navController.navigate(Route.PairMeter(it.name)) },
            )
        }
        composable<Route.SettingsMeals> {
            MealsScreen(settings = LocalContext.current.appContainer.settings, onBack = navController::popBackStack)
        }
        composable<Route.SettingsBackup> {
            BackupSettingsScreen(viewModel = backupViewModel(), onBack = navController::popBackStack)
        }
        composable<Route.SettingsAi> {
            val container = LocalContext.current.appContainer
            val modelsViewModel: AiModelsViewModel = viewModel { AiModelsViewModel(container.settings) }
            AiModelsScreen(
                viewModel = modelsViewModel,
                onBack = navController::popBackStack,
                onAdd = { provider -> navController.navigate(Route.AiConfigEdit(provider = provider.id)) },
                onOpen = { id -> navController.navigate(Route.AiConfigEdit(configId = id)) },
                onAddVoice = { provider -> navController.navigate(Route.AiConfigEdit(provider = provider.id, voice = true)) },
                onOpenVoice = { navController.navigate(Route.AiConfigEdit(voice = true)) },
            )
        }
        composable<Route.AiConfigEdit> { entry ->
            val route = entry.toRoute<Route.AiConfigEdit>()
            AiScreen(
                onBack = navController::popBackStack,
                onboarding = false,
                configId = route.configId,
                provider = dev.ytosko.neutrino.data.ai.AiProvider.fromId(route.provider),
                voice = route.voice,
                onSaved = navController::popBackStack,
            )
        }
        composable<Route.SettingsHealth> {
            HealthConnectScreen(
                viewModel = healthConnectViewModel(),
                onBack = navController::popBackStack,
                onContinue = navController::popBackStack,
            )
        }
    }
}

private fun NavHostController.finishOnboarding() {
    navigate(Route.Home) {
        popUpTo(graph.id) { inclusive = true }
    }
}

@Composable
private fun backupViewModel(): BackupViewModel {
    val container = LocalContext.current.appContainer
    return viewModel { BackupViewModel(container.backups) }
}

@Composable
private fun healthConnectViewModel(): HealthConnectViewModel {
    val container = LocalContext.current.appContainer
    return viewModel {
        HealthConnectViewModel(
            container.healthConnect,
            onConnected = { container.syncHealthConnect() },
            onGlucoseImport = { container.importGlucose() },
        )
    }
}

@Composable
private fun AiScreen(
    onBack: () -> Unit,
    onboarding: Boolean,
    onSaved: () -> Unit,
    configId: String? = null,
    provider: dev.ytosko.neutrino.data.ai.AiProvider? = null,
    voice: Boolean = false,
) {
    val container = LocalContext.current.appContainer
    val viewModel: AiSetupViewModel = viewModel {
        AiSetupViewModel(
            settings = container.settings,
            clients = container.aiClients,
            configId = configId,
            provider = provider,
            voice = voice,
            speech = container.speechClients,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.saved.collect { onSaved() } }

    SetupScaffold(
        title = when {
            onboarding -> stringResource(R.string.ai_title)
            voice && provider == null -> stringResource(R.string.voice_edit_title)
            voice -> stringResource(R.string.voice_add_title, state.provider.displayName)
            configId != null -> stringResource(R.string.ai_config_edit_title)
            else -> stringResource(R.string.ai_config_add_title, state.provider.displayName)
        },
        subtitle = if (onboarding) stringResource(R.string.ai_body) else null,
        onBack = onBack,
        step = if (onboarding) 2 to SETUP_STEPS else null,
        bottomBar = {
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(if (onboarding) R.string.ai_save_continue else R.string.ai_save))
                }
            }
        },
    ) {
        if (state.loaded) {
            AiSetupForm(
                state = state,
                showProviderSwitch = onboarding,
                onProviderChange = viewModel::selectProvider,
                onNameChange = viewModel::onNameChange,
                onUseExistingKey = viewModel::useExistingKey,
                onKeyChange = viewModel::onKeyChange,
                onToggleKeyVisibility = viewModel::toggleKeyVisibility,
                onCheckKey = viewModel::checkKey,
                onModelChange = viewModel::selectModel,
                onPhotoDetailChange = viewModel::selectPhotoDetail,
                voice = voice,
                onFreePlanChange = viewModel::setFreePlan,
            )
        }
    }
}

@Composable
private fun goalsViewModel(): dev.ytosko.neutrino.ui.settings.GoalsViewModel {
    val context = LocalContext.current
    val container = context.appContainer
    return viewModel {
        dev.ytosko.neutrino.ui.settings.GoalsViewModel(
            context.applicationContext,
            container.goalProfile,
            container.settings,
            container.medicines,
            container.glucose,
            container.healthConnect,
            container.aiClients,
        )
    }
}
