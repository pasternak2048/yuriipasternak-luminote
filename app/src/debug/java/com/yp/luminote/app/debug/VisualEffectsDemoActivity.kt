package com.yp.luminote.app.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.yp.luminote.app.R
import com.yp.luminote.app.animation.definitions.LuminoteHaloAnimations
import com.yp.luminote.app.ui.components.LuminoteSelectionControl
import com.yp.luminote.app.ui.components.LuminoteSelectionRow
import com.yp.luminote.app.ui.components.LuminoteSettingsCard
import com.yp.luminote.app.ui.theme.LuminoteTheme
import kotlinx.coroutines.delay

/** DEBUG presentation only; the controller owns every execution and validation decision. */
internal class VisualEffectsDemoActivity : ComponentActivity() {
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
        demoHost.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { LuminoteTheme { VisualEffectsDemoControls(controller, lifecycle) } }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onStop() {
        controller.onHostStopped()
        super.onStop()
    }

    override fun onDestroy() {
        controller.dispose()
        super.onDestroy()
    }
}

@Composable
private fun VisualEffectsDemoControls(controller: VisualEffectsDemoController, lifecycle: Lifecycle) {
    var family by remember { mutableStateOf(controller.selectedFamily) }
    var mode by remember { mutableStateOf(controller.selectedMode) }
    var engine by remember { mutableStateOf(VisualEffectsDemoEngine.CANVAS) }
    var selectionPending by remember { mutableStateOf(true) }
    var shaderTest by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var unavailableExpanded by remember { mutableStateOf(false) }
    var proofExpanded by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(controller.diagnosticsSnapshot()) }
    var validation by remember { mutableStateOf(controller.gpuValidationSnapshot()) }
    var selectionFailed by remember { mutableStateOf(false) }
    val capability = demoCapability(engine, family, mode)
    val testing = validation.phase == GpuValidationPhase.RUNNING

    // Poll immutable renderer evidence only while visible. Selection never creates GPU success.
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                validation = controller.gpuValidationSnapshot()
                diagnostics = controller.diagnosticsSnapshot()
                delay(250)
            }
        }
    }

    fun selectionChanged() {
        selectionPending = true
        selectionFailed = false
    }

    Box(Modifier.fillMaxSize()) {
        if (controlsVisible) {
            // A transparent host preserves the physical-edge preview. Every control has its own
            // opaque theme surface; insets are applied once to the single scrolling container.
            Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        LabSection(stringResource(R.string.debug_lab_title)) {
                            Text(stringResource(R.string.debug_lab_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        LabSection(stringResource(R.string.debug_lab_rendering_engine)) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                VisualEffectsDemoEngine.entries.forEachIndexed { index, entry ->
                                    SegmentedButton(
                                        selected = engine == entry,
                                        onClick = { engine = entry; selectionChanged() },
                                        enabled = !testing,
                                        shape = SegmentedButtonDefaults.itemShape(index, VisualEffectsDemoEngine.entries.size)
                                    ) { Text(stringResource(if (entry == VisualEffectsDemoEngine.CANVAS) R.string.debug_lab_canvas else R.string.debug_lab_gpu)) }
                                }
                            }
                            Text(capabilityLabel(capability, engine), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        LabSection(stringResource(R.string.animation)) {
                            Column(Modifier.selectableGroup()) {
                                visualEffectsDemoFamilies.forEach { entry ->
                                    // Animation support is independent of the currently chosen
                                    // effect. Keep its identity selectable, then require an admitted
                                    // effect before Preview rather than silently resetting effects.
                                    val admission = demoCapability(engine, entry, VisualEffectsDemoMode.BASELINE)
                                    LuminoteSelectionRow(
                                        title = entry.animationLabel(),
                                        selected = family == entry,
                                        onClick = { family = entry; selectionChanged() },
                                        control = LuminoteSelectionControl.Radio,
                                        enabled = admission.admitted && !testing,
                                        description = if (engine == VisualEffectsDemoEngine.GPU || !admission.admitted) capabilityLabel(admission, engine) else null
                                    )
                                }
                            }
                        }
                    }
                    item {
                        LabSection(stringResource(R.string.debug_lab_visual_effects)) {
                            val available = VisualEffectsDemoMode.entries.filter { demoCapability(engine, family, it).admitted }
                            Column(Modifier.selectableGroup()) {
                                available.forEach { entry ->
                                    LuminoteSelectionRow(
                                        title = entry.label(), selected = mode == entry,
                                        onClick = { mode = entry; selectionChanged() },
                                        control = LuminoteSelectionControl.Radio, enabled = !testing
                                    )
                                }
                            }
                            val unavailable = VisualEffectsDemoMode.entries.filterNot { it in available }
                            if (unavailable.isNotEmpty()) {
                                Text(stringResource(if (engine == VisualEffectsDemoEngine.GPU) R.string.debug_lab_gpu_effects_pending else R.string.debug_lab_effects_operation_limited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { unavailableExpanded = !unavailableExpanded }) {
                                    Text(stringResource(if (unavailableExpanded) R.string.debug_lab_hide_unavailable_effects else R.string.debug_lab_unavailable_effects))
                                }
                                if (unavailableExpanded) unavailable.forEach { entry ->
                                    Text(entry.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item {
                        LabSection(stringResource(R.string.debug_lab_preview)) {
                            Button(
                                onClick = {
                                    val result = controller.previewAnimation(engine, family, mode)
                                    selectionFailed = result is VisualEffectsDemoSelection.Unsupported
                                    selectionPending = selectionFailed
                                    shaderTest = false
                                    diagnostics = controller.diagnosticsSnapshot()
                                    validation = controller.gpuValidationSnapshot()
                                },
                                enabled = capability.admitted && !testing,
                                modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)
                            ) { Text(stringResource(R.string.debug_lab_preview_animation)) }
                            ExecutionStatus(diagnostics, selectionPending, shaderTest, capability.admitted, selectionFailed)
                            TextButton(onClick = { controlsVisible = false }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.debug_lab_inspect_preview))
                            }
                        }
                    }
                    item {
                        LabSection(stringResource(R.string.debug_lab_gpu_diagnostics)) {
                            Text(stringResource(R.string.debug_lab_shader_test_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = {
                                val started = controller.runShaderTest()
                                selectionFailed = !started
                                selectionPending = !started
                                shaderTest = started
                                diagnostics = controller.diagnosticsSnapshot()
                            }, enabled = !testing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.debug_lab_run_shader_test)) }
                            Text(
                                if (shaderTest && !selectionPending) stringResource(R.string.debug_lab_validation_operation, stringResource(R.string.debug_lab_shader_test))
                                else stringResource(R.string.debug_lab_self_test_description),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = {
                                validation = controller.runGpuSelfTest()
                                diagnostics = controller.diagnosticsSnapshot()
                            }, enabled = !testing && !selectionPending && (shaderTest || engine == VisualEffectsDemoEngine.GPU && capability.admitted), modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.debug_lab_run_gpu_self_test))
                            }
                            if (selectionPending || !shaderTest && engine != VisualEffectsDemoEngine.GPU) {
                                Text(stringResource(R.string.debug_lab_self_test_preview_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            ValidationSummary(validation)
                            CopyReportButton(controller::gpuValidationReport)
                            TextButton(onClick = { proofExpanded = !proofExpanded }) {
                                Text(stringResource(if (proofExpanded) R.string.debug_lab_hide_technical_details else R.string.debug_lab_execution_proof))
                            }
                            if (proofExpanded) {
                                RuntimeProof(diagnostics)
                                Text(stringResource(R.string.debug_lab_self_test_evidence), style = MaterialTheme.typography.labelLarge)
                                Text(stringResource(R.string.debug_lab_validation_session, validation.sessionId, phaseLabel(validation.phase)), style = MaterialTheme.typography.bodySmall)
                                ValidationChecks(validation.checks)
                                ValidationTechnicalDetails(validation)
                            }
                        }
                    }
                }
            }
        } else {
            Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp), shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
                TextButton(onClick = { controlsVisible = true }) { Text(stringResource(R.string.debug_lab_show)) }
            }
        }
    }
}

@Composable
private fun LabSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    LuminoteSettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun capabilityLabel(capability: DemoCapability, engine: VisualEffectsDemoEngine): String = stringResource(
    when {
        !capability.admitted && engine == VisualEffectsDemoEngine.CANVAS -> R.string.debug_lab_effects_operation_limited
        !capability.admitted -> R.string.debug_lab_selection_unsupported
        engine == VisualEffectsDemoEngine.GPU -> R.string.debug_lab_gpu_under_validation
        else -> R.string.debug_lab_canvas_capability
    }
)

@Composable
private fun ExecutionStatus(diagnostics: VisualEffectsDemoDiagnosticsSnapshot, pending: Boolean, shaderTest: Boolean, admitted: Boolean, rejected: Boolean) {
    val fallbackActive = diagnostics.actualBackend == VisualEffectsDemoActualBackend.CANVAS && diagnostics.fallbackReason != null
    val status = when {
        rejected -> R.string.debug_lab_execution_rejected
        !admitted && !shaderTest -> R.string.debug_lab_selection_unsupported
        pending -> R.string.debug_lab_not_executed
        fallbackActive -> R.string.debug_lab_gpu_failed_fallback
        diagnostics.actualBackend == VisualEffectsDemoActualBackend.GPU && diagnostics.gpuSubmitted -> R.string.debug_lab_gpu_rendering
        diagnostics.actualBackend == VisualEffectsDemoActualBackend.CANVAS -> R.string.debug_lab_canvas_rendering
        else -> R.string.debug_lab_waiting_execution
    }
    Text(stringResource(status), style = MaterialTheme.typography.titleSmall, color = if (!pending && fallbackActive || rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if (shaderTest && !pending) Text(stringResource(R.string.debug_lab_shader_test_active), style = MaterialTheme.typography.bodySmall)
    if (!pending) {
        Text(stringResource(R.string.debug_lab_requested, diagnostics.requestedBackend.label()), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.debug_lab_actual, diagnostics.actualBackend.label()), style = MaterialTheme.typography.bodySmall)
        diagnostics.failureMessage?.let { Text(stringResource(R.string.debug_lab_failure_message, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    } else Text(stringResource(R.string.debug_lab_preview_selection_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RuntimeProof(diagnostics: VisualEffectsDemoDiagnosticsSnapshot) {
    val unknown = stringResource(R.string.debug_lab_unknown)
    Text(stringResource(R.string.debug_lab_current_execution), style = MaterialTheme.typography.labelLarge)
    Text(
        stringResource(R.string.debug_lab_requested, diagnostics.requestedBackend.label()) + "\n" +
            stringResource(R.string.debug_lab_actual, diagnostics.actualBackend.label()) + "\n" +
            stringResource(R.string.debug_lab_selected_operation, diagnostics.selectedOperation) + "\n" +
            stringResource(R.string.debug_lab_admitted_operation, diagnostics.admittedOperation ?: unknown) + "\n" +
            stringResource(R.string.debug_lab_capability, diagnostics.capabilitySummary) + "\n" +
            stringResource(R.string.debug_lab_validation_generation, diagnostics.generation?.toString() ?: unknown) + "\n" +
            stringResource(R.string.debug_lab_gpu_initialized, diagnostics.gpuInitialized.label()) + "\n" +
            stringResource(R.string.debug_lab_shader_initialized, diagnostics.shaderInitialized.label()) + "\n" +
            stringResource(R.string.debug_lab_gpu_submitted, diagnostics.gpuSubmitted.label()) + "\n" +
            stringResource(R.string.debug_lab_validation_init_attempts, diagnostics.initializationAttempts) + "\n" +
            stringResource(R.string.debug_lab_validation_gpu_attempts, diagnostics.gpuAttempts) + "\n" +
            stringResource(R.string.debug_lab_validation_submitted_frames, diagnostics.gpuSubmittedFrames) + "\n" +
            stringResource(R.string.debug_lab_canvas_fallback, diagnostics.fallbackCount) + (diagnostics.fallbackReason?.let { " — $it" } ?: "") + "\n" +
            stringResource(R.string.debug_lab_validation_check, stringResource(R.string.debug_lab_check_pixel_verification), validationStateLabel(diagnostics.pixelVerification)) +
            diagnostics.failureStage?.let { "\n" + stringResource(R.string.debug_lab_failure_stage, it) }.orEmpty() +
            diagnostics.failureType?.let { "\n" + stringResource(R.string.debug_lab_failure_type, it) }.orEmpty() +
            diagnostics.failureMessage?.let { "\n" + stringResource(R.string.debug_lab_failure_message, it) }.orEmpty(),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ValidationSummary(validation: GpuValidationSessionSnapshot) {
    Text(phaseLabel(validation.phase), style = MaterialTheme.typography.labelLarge)
    if (validation.phase != GpuValidationPhase.NOT_RUN) {
        Text(stringResource(R.string.debug_lab_validation_summary,
            validation.checks.values.count { it.state == GpuValidationState.PASS },
            validation.checks.values.count { it.state == GpuValidationState.FAIL },
            validation.checks.values.count { it.state == GpuValidationState.NOT_TESTABLE },
            validation.checks.values.count { it.state == GpuValidationState.NOT_RUN }
        ), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun phaseLabel(phase: GpuValidationPhase): String = stringResource(when (phase) {
    GpuValidationPhase.NOT_RUN -> R.string.debug_lab_self_test_not_run
    GpuValidationPhase.RUNNING -> R.string.debug_lab_self_test_running
    GpuValidationPhase.COMPLETED -> R.string.debug_lab_self_test_completed
    GpuValidationPhase.CANCELLED -> R.string.debug_lab_self_test_cancelled
})

@Composable
private fun CopyReportButton(report: () -> String) {
    val context = LocalContext.current
    val label = stringResource(R.string.debug_lab_gpu_validation_report)
    val unavailable = stringResource(R.string.debug_lab_clipboard_unavailable)
    val copied = stringResource(R.string.debug_lab_validation_report_copied)
    TextButton(onClick = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val message = if (clipboard == null) unavailable else {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, report()))
            copied
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.debug_lab_copy_diagnostic_report)) }
}

@Composable
private fun ValidationChecks(checks: Map<String, GpuValidationCheck>) {
    checks.forEach { (name, check) ->
        Text(stringResource(R.string.debug_lab_validation_check, validationCheckLabel(name), validationStateLabel(check.state)), style = MaterialTheme.typography.bodySmall, color = validationStateColor(check.state))
        // Technical explanations retain their original diagnostic wording and remain expandable.
        Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ValidationTechnicalDetails(validation: GpuValidationSessionSnapshot) {
    val unknown = stringResource(R.string.debug_lab_unknown)
    Text(
        stringResource(R.string.debug_lab_validation_operation, validation.selectedOperation) + "\n" +
            stringResource(R.string.debug_lab_validation_mode, validation.selectedMode) + "\n" +
            stringResource(R.string.debug_lab_validation_capability, validation.capabilityDecision) + "\n" +
            stringResource(R.string.debug_lab_validation_generation, validation.generation?.toString() ?: unknown) + "\n" +
            stringResource(R.string.debug_lab_validation_field_key, validation.fieldKey?.toString() ?: unknown) + "\n" +
            stringResource(R.string.debug_lab_validation_surface_epoch, validation.surfaceEpoch?.toString() ?: unknown) + "\n" +
            stringResource(R.string.debug_lab_validation_gpu_attempts, validation.gpuAttempts) + "\n" +
            stringResource(R.string.debug_lab_validation_latched_frames, validation.latchedFailureFrames) + "\n" +
            stringResource(R.string.debug_lab_validation_canvas_after_latch, validation.canvasAfterLatchFrames) + "\n" +
            stringResource(R.string.debug_lab_canvas_fallback, validation.fallbackCount),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    validation.firstFailure?.let { failure ->
        Text(stringResource(R.string.debug_lab_validation_first_failure), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.debug_lab_validation_failure_classification, failure.classification) + "\n" +
                stringResource(R.string.debug_lab_failure_stage, failure.stage) + "\n" +
                stringResource(R.string.debug_lab_failure_type, failure.type) + "\n" +
                stringResource(R.string.debug_lab_failure_message, failure.message ?: unknown),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (failure.stack.isNotEmpty()) Text(failure.stack.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } ?: Text(stringResource(R.string.debug_lab_validation_no_failure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun validationCheckLabel(name: String): String {
    val resource = when (name) {
        "GPU initialization" -> R.string.debug_lab_check_gpu_initialization
        "Shader compilation" -> R.string.debug_lab_check_shader_compilation
        "Frame submission" -> R.string.debug_lab_check_frame_submission
        "Actual backend" -> R.string.debug_lab_check_actual_backend
        "Pixel verification" -> R.string.debug_lab_check_pixel_verification
        "Alpha/transparency" -> R.string.debug_lab_check_alpha_transparency
        "Clipping" -> R.string.debug_lab_check_clipping
        "Resource recreation" -> R.string.debug_lab_check_resource_recreation
        "Canvas fallback" -> R.string.debug_lab_check_canvas_fallback
        else -> null
    }
    return if (resource == null) name else stringResource(resource)
}

@Composable
private fun validationStateLabel(state: GpuValidationState): String = stringResource(when (state) {
    GpuValidationState.PASS -> R.string.debug_lab_validation_pass
    GpuValidationState.FAIL -> R.string.debug_lab_validation_fail
    GpuValidationState.NOT_TESTABLE -> R.string.debug_lab_validation_not_testable
    GpuValidationState.NOT_RUN -> R.string.debug_lab_validation_not_run
})

@Composable
private fun validationStateColor(state: GpuValidationState) = when (state) {
    GpuValidationState.PASS -> MaterialTheme.colorScheme.primary
    GpuValidationState.FAIL -> MaterialTheme.colorScheme.error
    GpuValidationState.NOT_TESTABLE, GpuValidationState.NOT_RUN -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun VisualEffectsDemoFamily.animationLabel(): String = stringResource(LuminoteHaloAnimations.registry.definition(config().motion).titleRes)

@Composable
private fun VisualEffectsDemoMode.label(): String = stringResource(when (this) {
    VisualEffectsDemoMode.BASELINE -> R.string.debug_lab_baseline
    VisualEffectsDemoMode.GLOW -> R.string.debug_lab_glow
    VisualEffectsDemoMode.BLUR -> R.string.debug_lab_blur
    VisualEffectsDemoMode.INTENSITY_PULSE -> R.string.debug_lab_intensity_pulse
    VisualEffectsDemoMode.COMBINED -> R.string.debug_lab_full_stack
    VisualEffectsDemoMode.DIAGNOSTIC_GLOW -> R.string.debug_lab_diagnostic_glow
    VisualEffectsDemoMode.DIAGNOSTIC_BLUR -> R.string.debug_lab_diagnostic_blur
    VisualEffectsDemoMode.DIAGNOSTIC_INTENSITY_PULSE -> R.string.debug_lab_diagnostic_pulse
})

@Composable
private fun VisualEffectsDemoRequestedBackend.label(): String = stringResource(when (this) {
    VisualEffectsDemoRequestedBackend.CANVAS -> R.string.debug_lab_canvas
    VisualEffectsDemoRequestedBackend.GPU_WITNESS -> R.string.debug_lab_shader_test
    VisualEffectsDemoRequestedBackend.GPU_PULSE -> R.string.debug_lab_gpu
})

@Composable
private fun VisualEffectsDemoActualBackend.label(): String = stringResource(when (this) {
    VisualEffectsDemoActualBackend.AWAITING -> R.string.debug_lab_awaiting_runtime_evidence
    VisualEffectsDemoActualBackend.CANVAS -> R.string.debug_lab_canvas
    VisualEffectsDemoActualBackend.GPU -> R.string.debug_lab_gpu
})

@Composable
private fun Boolean.label(): String = stringResource(if (this) R.string.debug_lab_yes else R.string.debug_lab_no)
