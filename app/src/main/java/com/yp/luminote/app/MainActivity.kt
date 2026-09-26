package com.yp.luminote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yp.luminote.app.data.settings.LuminoteSettingsRepository
import com.yp.luminote.app.ui.navigation.LuminoteNavHost
import com.yp.luminote.app.ui.theme.LuminoteTheme
import com.yp.luminote.app.update.UpdateScheduler
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModel
import com.yp.luminote.app.viewmodel.LuminoteSettingsViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateScheduler.schedule(applicationContext)
        enableEdgeToEdge()
        setContent {
            val settingsViewModel: LuminoteSettingsViewModel =
                viewModel(
                    factory = LuminoteSettingsViewModelFactory(
                        LuminoteSettingsRepository(applicationContext)
                    )
                )
            val settingsLoaded by settingsViewModel.settingsLoaded.collectAsState()
            val settings by settingsViewModel.settings.collectAsState()

            if (settingsLoaded) {
                LuminoteTheme(themeMode = settings.themeMode) {
                    LuminoteNavHost(settingsViewModel)
                }
            } else {
                // Do not render normal UI with the temporary default before DataStore loads.
                // This temporary system theme also keeps edge-to-edge bar icons readable.
                LuminoteTheme {
                    Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background))
                }
            }
        }
    }
}
