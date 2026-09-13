package com.yp.luminote.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yp.luminote.app.ui.about.AboutScreen
import com.yp.luminote.app.ui.access.AccessScreen
import com.yp.luminote.app.ui.adaptive.rememberLuminoteWindowSizeClass
import com.yp.luminote.app.ui.ambient.AmbientHaloScreen
import com.yp.luminote.app.ui.apps.AppsScreen
import com.yp.luminote.app.ui.behavior.NotificationBehaviorScreen
import com.yp.luminote.app.ui.effects.HaloScreen
import com.yp.luminote.app.ui.easteregg.EasterEggScreen
import com.yp.luminote.app.ui.home.HomeScreen
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModelFactory

object LuminoteRoutes {
    const val HOME = "home"
    const val HALO = "halo"
    const val APPS = "apps"
    const val NOTIFICATION_BEHAVIOR = "notification_behavior"
    const val ABOUT = "about"
    const val ACCESS = "access"
    const val AMBIENT = "ambient"
    const val EASTER_EGG = "easter_egg"
}

@Composable
fun LuminoteNavHost() {
    val navController = rememberNavController()
    val applicationContext = LocalContext.current.applicationContext
    val settingsRepository = remember(applicationContext) {
        LuminoteSettingsRepository(applicationContext)
    }
    val settingsViewModel: LuminoteSettingsViewModel = viewModel(
        factory = LuminoteSettingsViewModelFactory(settingsRepository)
    )

    val windowSizeClass =
        rememberLuminoteWindowSizeClass()

    NavHost(
        navController = navController,
        startDestination = LuminoteRoutes.HOME,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS)
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS)
            )
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS)
            )
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS)
            )
        }
    ) {
        composable(LuminoteRoutes.HOME) {
            HomeScreen(
                onHaloClick = {
                    navController.navigate(
                        LuminoteRoutes.HALO
                    )
                },
                onAppsClick = {
                    navController.navigate(
                        LuminoteRoutes.APPS
                    )
                },
                onAmbientClick = {
                    navController.navigate(LuminoteRoutes.AMBIENT)
                },
                onNotificationBehaviorClick = {
                    navController.navigate(LuminoteRoutes.NOTIFICATION_BEHAVIOR)
                },
                onAboutClick = {
                    navController.navigate(LuminoteRoutes.ABOUT)
                },
                onAccessClick = {
                    navController.navigate(LuminoteRoutes.ACCESS)
                },
                windowSizeClass = windowSizeClass,
                viewModel = settingsViewModel
            )
        }

        composable(LuminoteRoutes.HALO) {
            HaloScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                windowSizeClass = windowSizeClass,
                viewModel = settingsViewModel
            )
        }

        composable(LuminoteRoutes.APPS) {
            AppsScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                windowSizeClass = windowSizeClass,
                viewModel = settingsViewModel
            )
        }

        composable(LuminoteRoutes.AMBIENT) {
            AmbientHaloScreen(
                onBackClick = { navController.popBackStack() },
                viewModel = settingsViewModel
            )
        }

        composable(LuminoteRoutes.NOTIFICATION_BEHAVIOR) {
            NotificationBehaviorScreen(
                onBackClick = { navController.popBackStack() },
                viewModel = settingsViewModel
            )
        }

        composable(LuminoteRoutes.ABOUT) {
            AboutScreen(
                onBackClick = { navController.popBackStack() },
                onEasterEggClick = { navController.navigate(LuminoteRoutes.EASTER_EGG) }
            )
        }

        composable(LuminoteRoutes.EASTER_EGG) {
            val settings by settingsViewModel.settings.collectAsState()
            EasterEggScreen(
                ambientSettings = settings,
                onClose = { navController.popBackStack() }
            )
        }

        composable(LuminoteRoutes.ACCESS) {
            AccessScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

private const val NAVIGATION_TRANSITION_DURATION_MS = 300
