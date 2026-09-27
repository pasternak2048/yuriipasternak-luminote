package com.yp.luminote.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yp.luminote.app.ui.about.AboutScreen
import com.yp.luminote.app.ui.access.AccessScreen
import com.yp.luminote.app.ui.adaptive.rememberLuminoteWindowSizeClass
import com.yp.luminote.app.ui.ambient.AmbientHaloScreen
import com.yp.luminote.app.ui.apps.AppsScreen
import com.yp.luminote.app.ui.easteregg.EasterEggScreen
import com.yp.luminote.app.ui.effects.HaloScreen
import com.yp.luminote.app.ui.home.HomeScreen
import com.yp.luminote.app.ui.language.LanguageScreen
import com.yp.luminote.app.ui.settings.AppearanceScreen
import com.yp.luminote.app.ui.settings.SettingsScreen
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel

object LuminoteRoutes {
    const val HOME = "home"
    const val HALO = "halo"
    const val APPS = "apps"
    const val ABOUT = "about"
    const val ACCESS = "access"
    const val AMBIENT = "ambient"
    const val EASTER_EGG = "easter_egg"
    const val LANGUAGE = "language"
    const val SETTINGS = "settings"
    const val APPEARANCE = "appearance"
    const val UPDATES = "updates"
}

@Composable
fun LuminoteNavHost(
    settingsViewModel: LuminoteSettingsViewModel
) {
    val navController =
        rememberNavController()

    val windowSizeClass =
        rememberLuminoteWindowSizeClass()

    NavHost(
        navController = navController,
        startDestination = LuminoteRoutes.HOME,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth ->
                    fullWidth
                },
                animationSpec =
                    tween(
                        NAVIGATION_TRANSITION_DURATION_MS
                    )
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth ->
                    -fullWidth
                },
                animationSpec =
                    tween(
                        NAVIGATION_TRANSITION_DURATION_MS
                    )
            )
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth ->
                    -fullWidth
                },
                animationSpec =
                    tween(
                        NAVIGATION_TRANSITION_DURATION_MS
                    )
            )
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth ->
                    fullWidth
                },
                animationSpec =
                    tween(
                        NAVIGATION_TRANSITION_DURATION_MS
                    )
            )
        }
    ) {
        composable(
            LuminoteRoutes.HOME
        ) {
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
                    navController.navigate(
                        LuminoteRoutes.AMBIENT
                    )
                },
                onAccessClick = {
                    navController.navigate(
                        LuminoteRoutes.ACCESS
                    )
                },
                onSettingsClick = { navController.navigate(LuminoteRoutes.SETTINGS) },
                windowSizeClass = windowSizeClass,
                viewModel =
                    settingsViewModel
            )
        }

        composable(LuminoteRoutes.SETTINGS) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onAppearanceClick = { navController.navigate(LuminoteRoutes.APPEARANCE) },
                onLanguageClick = { navController.navigate(LuminoteRoutes.LANGUAGE) },
                onUpdatesClick = { navController.navigate(LuminoteRoutes.UPDATES) },
                onAboutClick = { navController.navigate(LuminoteRoutes.ABOUT) }
            )
        }

        composable(LuminoteRoutes.APPEARANCE) {
            AppearanceScreen(onBackClick = { navController.popBackStack() }, viewModel = settingsViewModel)
        }

        composable(LuminoteRoutes.UPDATES) {
            AboutScreen(
                onBackClick = { navController.popBackStack() },
                onEasterEggClick = { },
                showUpdatesOnly = true
            )
        }

        composable(
            LuminoteRoutes.LANGUAGE
        ) {
            LanguageScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            LuminoteRoutes.HALO
        ) {
            HaloScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                windowSizeClass =
                    windowSizeClass,
                viewModel =
                    settingsViewModel
            )
        }

        composable(
            LuminoteRoutes.APPS
        ) {
            AppsScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                windowSizeClass =
                    windowSizeClass,
                viewModel =
                    settingsViewModel
            )
        }

        composable(
            LuminoteRoutes.AMBIENT
        ) {
            AmbientHaloScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                viewModel =
                    settingsViewModel
            )
        }

        composable(
            LuminoteRoutes.ABOUT
        ) {
            AboutScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onEasterEggClick = {
                    navController.navigate(
                        LuminoteRoutes.EASTER_EGG
                    )
                }
            )
        }

        composable(
            LuminoteRoutes.EASTER_EGG
        ) {
            val settings by
            settingsViewModel.settings
                .collectAsState()

            EasterEggScreen(
                ambientSettings =
                    settings,
                onClose = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            LuminoteRoutes.ACCESS
        ) {
            AccessScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                viewModel =
                    settingsViewModel
            )
        }
    }
}

private const val NAVIGATION_TRANSITION_DURATION_MS =
    300
