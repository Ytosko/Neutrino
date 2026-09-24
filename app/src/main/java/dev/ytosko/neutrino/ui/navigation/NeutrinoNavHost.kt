package dev.ytosko.neutrino.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.ytosko.neutrino.ui.home.HomeScreen
import dev.ytosko.neutrino.ui.welcome.WelcomeScreen
import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
sealed interface Route {
    @Serializable data object Welcome : Route
    @Serializable data object Home : Route
}

@Composable
fun NeutrinoNavHost(
    modifier: Modifier = Modifier,
    // TODO(onboarding): start at Home once setup is complete (persisted in DataStore).
    startDestination: Route = Route.Welcome,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<Route.Welcome> {
            WelcomeScreen(
                onGetStarted = {
                    navController.navigate(Route.Home) {
                        popUpTo<Route.Welcome> { inclusive = true }
                    }
                },
            )
        }
        composable<Route.Home> { HomeScreen() }
    }
}
