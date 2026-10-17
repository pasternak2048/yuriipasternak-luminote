package com.yp.luminote.app.debug

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.ui.theme.LuminoteTheme

/** Debug-only launch point. Tanya owns the Material 3 controls layered over this host. */
internal class VisualEffectsDemoActivity : ComponentActivity() {
    /** Tanya layers the debug-only Material 3 controls above this full-screen host. */
    internal lateinit var demoHost: FrameLayout
        private set

    internal lateinit var controller: VisualEffectsDemoController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        demoHost = FrameLayout(this)
        setContentView(demoHost)
        controller = VisualEffectsDemoController(this, demoHost)
        demoHost.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    LuminoteTheme {
                        VisualEffectsDemoControls(
                            onFamilySelected = controller::selectFamily,
                            onModeSelected = controller::select,
                            onRestart = controller::restart
                        )
                    }
                }
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onDestroy() {
        controller.dispose()
        super.onDestroy()
    }
}
@Composable
private fun VisualEffectsDemoControls(
    onFamilySelected: (VisualEffectsDemoFamily) -> Unit,
    onModeSelected: (VisualEffectsDemoMode) -> VisualEffectsDemoSelection,
    onRestart: () -> Unit
) {
    var selectedFamily by remember { mutableStateOf(VisualEffectsDemoFamily.FULL_CONTOUR) }
    var selectedMode by remember { mutableStateOf(VisualEffectsDemoMode.BASELINE) }
    var controlsVisible by remember { mutableStateOf(true) }
    var selectionStatus by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (controlsVisible) {
            Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        ElevatedCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Visual effects demo",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Animation family",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VisualEffectsDemoFamily.entries.forEach { family ->
                        FilterChip(
                            selected = selectedFamily == family,
                            onClick = {
                                selectedFamily = family
                                selectedMode = VisualEffectsDemoMode.BASELINE
                                selectionStatus = null
                                onFamilySelected(family)
                            },
                            label = { Text(family.label) }
                        )
                    }
                }
                Text(
                    text = "Modifier stack",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VisualEffectsDemoMode.entries.forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = {
                                when (val selection = onModeSelected(mode)) {
                                    VisualEffectsDemoSelection.Applied -> {
                                        selectedMode = mode
                                        selectionStatus = null
                                    }
                                    is VisualEffectsDemoSelection.Unsupported -> {
                                        selectionStatus = selection.reason
                                    }
                                }
                            },
                            label = { Text(mode.label) }
                        )
                    }
                }
                selectionStatus?.let { status ->
                    Text(text = status, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = onRestart,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
                ) {
                    Text("Restart animation")
                }
                TextButton(
                    onClick = { controlsVisible = false },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Hide controls")
                }
            }
        }
            }
        } else {
            TextButton(
                onClick = { controlsVisible = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                Text("Show")
            }
        }
    }
}
