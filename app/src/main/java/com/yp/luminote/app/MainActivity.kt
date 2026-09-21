package com.yp.luminote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yp.luminote.app.ui.navigation.LuminoteNavHost
import com.yp.luminote.app.ui.theme.LuminoteTheme
import com.yp.luminote.app.update.UpdateScheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateScheduler.schedule(applicationContext)
        enableEdgeToEdge()
        setContent {
            LuminoteTheme {
                LuminoteNavHost()
            }
        }
    }
}
