package dev.ytosko.neutrino.ui.navigation

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
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.health.HealthConnectScreen
import dev.ytosko.neutrino.ui.health.HealthConnectViewModel
import dev.ytosko.neutrino.ui.home.HomeScreen
import dev.ytosko.neutrino.ui.home.HomeViewModel
import dev.ytosko.neutrino.ui.review.ReviewScreen
import dev.ytosko.neutrino.ui.review.ReviewViewModel
import dev.ytosko.neutrino.ui.settings.SettingsScreen
import dev.ytosko.neutrino.ui.welcome.WelcomeScreen
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
    @Serializable data class Review(val photoUri: String, val fromCamera: Boolean) : Route
}

private const val KEY_SAVED_RESULT = "meal_saved_synced"


private const val SETUP_STEPS = 2

@Composable
fun NeutrinoNavHost(startDestination: Route, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable<Route.Welcome> {
            WelcomeScreen(onGetStarted = { navController.navigate(Route.SetupHealth) })
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
                onSaved = { navController.finishOnboarding() },
            )
        }
        composable<Route.Home> { entry ->
            val container = LocalContext.current.appContainer
            val homeViewModel: HomeViewModel = viewModel { HomeViewModel(container.meals, container.settings) }
            val savedResult by entry.savedStateHandle.getStateFlow<Boolean?>(KEY_SAVED_RESULT, null).collectAsStateWithLifecycle()
            HomeScreen(
                viewModel = homeViewModel,
                onOpenSettings = { navController.navigate(Route.Settings) },
                onOpenAiSettings = { navController.navigate(Route.SettingsAi) },
                onPhotoSelected = { uri, fromCamera -> navController.navigate(Route.Review(uri.toString(), fromCamera)) },
                savedResult = savedResult,
                onSavedResultShown = { entry.savedStateHandle[KEY_SAVED_RESULT] = null },
            )
        }
        composable<Route.Review> { entry ->
            val route = entry.toRoute<Route.Review>()
            val context = LocalContext.current
            val container = context.appContainer
            val reviewViewModel: ReviewViewModel = viewModel {
                val uri = Uri.parse(route.photoUri)
                ReviewViewModel(
                    photoUri = uri,
                    settings = container.settings,
                    clients = container.aiClients,
                    photos = container.photos,
                    meals = container.meals,
                    // Camera captures are temporary; delete once the photo is prepared.
                    onPhotoConsumed = { if (route.fromCamera) runCatching { context.contentResolver.delete(uri, null, null) } },
                )
            }
            ReviewScreen(
                viewModel = reviewViewModel,
                onBack = navController::popBackStack,
                onSaved = { synced ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(KEY_SAVED_RESULT, synced)
                    navController.popBackStack()
                },
                onOpenAiSettings = { navController.navigate(Route.SettingsAi) },
            )
        }
        composable<Route.Settings> {
            val container = LocalContext.current.appContainer
            SettingsScreen(
                settings = container.settings.settings,
                healthViewModel = healthConnectViewModel(),
                onBack = navController::popBackStack,
                onOpenAi = { navController.navigate(Route.SettingsAi) },
                onOpenHealthConnect = { navController.navigate(Route.SettingsHealth) },
            )
        }
        composable<Route.SettingsAi> {
            AiScreen(onBack = navController::popBackStack, onboarding = false, onSaved = navController::popBackStack)
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
private fun healthConnectViewModel(): HealthConnectViewModel {
    val container = LocalContext.current.appContainer
    return viewModel { HealthConnectViewModel(container.healthConnect) }
}

@Composable
private fun AiScreen(onBack: () -> Unit, onboarding: Boolean, onSaved: () -> Unit) {
    val container = LocalContext.current.appContainer
    val viewModel: AiSetupViewModel = viewModel {
        AiSetupViewModel(
            settings = container.settings,
            clients = container.aiClients,
            onSaved = { if (onboarding) container.settings.setOnboardingComplete() },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.saved.collect { onSaved() } }

    SetupScaffold(
        title = stringResource(R.string.ai_title),
        subtitle = stringResource(R.string.ai_body),
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
                onProviderChange = viewModel::selectProvider,
                onKeyChange = viewModel::onKeyChange,
                onToggleKeyVisibility = viewModel::toggleKeyVisibility,
                onCheckKey = viewModel::checkKey,
                onModelChange = viewModel::selectModel,
                onPhotoDetailChange = viewModel::selectPhotoDetail,
            )
        }
    }
}
